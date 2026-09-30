package com.itantra.codec

import com.itantra.core.PacketPriority
import com.itantra.core.RecognizedUtterance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Utf8BaselineCodecTest {

    private val codec = Utf8BaselineCodec()

    @Test
    fun `round-trips ASCII text exactly`() {
        val u = RecognizedUtterance("evacuate now", "en", true, 0.95f, 0, 900)
        val packet = codec.encode(u, PacketPriority.ALERT_BASE, ttlMs = 2000, sentenceId = 1)
        val received = codec.decode(packet)
        assertEquals("evacuate now", received.text)
        assertEquals(1f, received.confidence)
        assertTrue(!received.reconstructedFromResidual)
    }

    @Test
    fun `round-trips multi-byte Indic UTF-8 text exactly`() {
        val text = "\u092E\u0926\u0926" // Hindi "madad" (help)
        val u = RecognizedUtterance(text, "hi", true, 0.9f, 0, 500)
        val packet = codec.encode(u, PacketPriority.ALERT_BASE, ttlMs = 2000, sentenceId = 2)
        assertEquals(text, codec.decode(packet).text)
    }

    @Test
    fun `token count equals utf8 byte count, not character count`() {
        val text = "\u0BA4\u0BAE\u0BBF\u0BB4\u0BCD" // Tamil "Tamil"
        val u = RecognizedUtterance(text, "ta", true, 0.9f, 0, 500)
        val packet = codec.encode(u, PacketPriority.NORMAL_BASE, ttlMs = 2000, sentenceId = 3)
        assertEquals(text.toByteArray(Charsets.UTF_8).size, packet.tokenIds.size)
    }

    @Test
    fun `bits per second formula matches the §16 definition`() {
        assertEquals(400.0, codec.payloadBitsPerSecond(payloadBytes = 100, durationSeconds = 2.0), 1e-9)
    }
}

class BpeSemanticCodecTest {

    private fun trainedCodec(corpus: List<String>): BpeSemanticCodec {
        val model = BpeTokenizerTrainer().train(corpus, numMerges = 40)
        return BpeSemanticCodec(BpeTokenizer(model))
    }

    @Test
    fun `round-trips a sentence from its own training corpus`() {
        val corpus = listOf("please send help now", "evacuate the building now", "fire near the market")
        val codec = trainedCodec(corpus)
        val u = RecognizedUtterance("please send help now", "en", true, 0.9f, 0, 900)
        val packet = codec.encode(u, PacketPriority.ALERT_BASE, ttlMs = 2000, sentenceId = 1)
        val received = codec.decode(packet)
        assertEquals("please send help now", received.text)
        assertEquals(1f, received.confidence, "no <unk> tokens expected for an in-corpus sentence")
    }

    @Test
    fun `unseen vocabulary lowers confidence rather than silently fabricating text`() {
        val corpus = listOf("please send help now")
        val codec = trainedCodec(corpus)
        // "zzz" contains a character never seen in training and not decomposable by the
        // learned merges, so it must map through <unk> rather than being invented.
        val u = RecognizedUtterance("zzz", "en", true, 0.9f, 0, 200)
        val packet = codec.encode(u, PacketPriority.NORMAL_BASE, ttlMs = 1000, sentenceId = 2)
        val received = codec.decode(packet)
        assertTrue(received.confidence < 1f, "unseen characters should reduce confidence, not disappear silently")
    }

    @Test
    fun `rejects encoding a non-final utterance`() {
        val codec = trainedCodec(listOf("test corpus"))
        val u = RecognizedUtterance("test", "en", isFinal = false, confidence = 0.5f, startTsMs = 0, endTsMs = 100)
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            codec.encode(u, PacketPriority.NORMAL_BASE, ttlMs = 1000, sentenceId = 1)
        }
    }
}

class EntropyCoderTest {

    @Test
    fun `varint round-trips arbitrary non-negative ids`() {
        val ids = intArrayOf(0, 1, 127, 128, 300, 16384, 2_097_151)
        val encoded = VarintCodec.encode(ids)
        val decoded = VarintCodec.decode(encoded)
        assertTrue(ids.contentEquals(decoded))
    }

    @Test
    fun `huffman code lengths are shorter for more frequent symbols`() {
        val ids = intArrayOf(1, 1, 1, 1, 1, 1, 1, 2, 3)
        val table = HuffmanCoder.buildTable(ids)
        val lenFor1 = table.codeLengthBits.getValue(1)
        val lenFor2 = table.codeLengthBits.getValue(2)
        assertTrue(lenFor1 <= lenFor2, "the far more frequent symbol should not get a longer code")
    }
}

class AdaptiveChannelControllerTest {

    private fun stats(lossPct: Float, rttMs: Float, goodputBps: Float) = com.itantra.core.ChannelStats(
        rttMs = rttMs, lossPct = lossPct, goodputBps = goodputBps,
        rssiDbm = -55, queueDepth = 0, thermalState = com.itantra.core.ThermalState.NOMINAL, batteryPct = 80
    )

    @Test
    fun `starts SEMANTIC_SAFE and upgrades only after sustained good link`() {
        val controller = AdaptiveChannelController()
        assertEquals(com.itantra.core.ChannelMode.SEMANTIC_SAFE, controller.mode())
        var mode = com.itantra.core.ChannelMode.SEMANTIC_SAFE
        repeat(10) { mode = controller.update(stats(lossPct = 0.5f, rttMs = 40f, goodputBps = 20_000f)) }
        assertEquals(com.itantra.core.ChannelMode.HIGH_FIDELITY_RESIDUAL, mode)
    }

    @Test
    fun `severe thermal state forces SEMANTIC_SAFE regardless of link quality`() {
        val controller = AdaptiveChannelController()
        repeat(10) { controller.update(stats(lossPct = 0.5f, rttMs = 40f, goodputBps = 20_000f)) }
        val hot = com.itantra.core.ChannelStats(
            rttMs = 40f, lossPct = 0.5f, goodputBps = 20_000f, rssiDbm = -55,
            queueDepth = 0, thermalState = com.itantra.core.ThermalState.CRITICAL, batteryPct = 80
        )
        assertEquals(com.itantra.core.ChannelMode.SEMANTIC_SAFE, controller.update(hot))
    }

    @Test
    fun `top mode steps down one level at a time, not straight to SEMANTIC_SAFE`() {
        val controller = AdaptiveChannelController()
        repeat(10) { controller.update(stats(lossPct = 0.5f, rttMs = 40f, goodputBps = 20_000f)) }
        assertEquals(com.itantra.core.ChannelMode.HIGH_FIDELITY_RESIDUAL, controller.mode())
        // One mediocre-but-not-bad sample should step down exactly one level, not collapse.
        val afterOneMediocre = controller.update(stats(lossPct = 5f, rttMs = 200f, goodputBps = 20_000f))
        assertEquals(com.itantra.core.ChannelMode.SEMANTIC_PLUS_RESIDUAL, afterOneMediocre)
    }
}

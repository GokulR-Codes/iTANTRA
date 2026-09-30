// Standalone runtime smoke test mirroring protocol/src/test/kotlin/com/itantra/protocol/
// PacketFragmenterTest.kt and SemanticPacketWireCodecTest.kt — actually compiled and run
// against the real classes in this sandbox (JUnit itself is unreachable — see
// docs/KNOWN_ISSUES.md). Companion to protocol_reference.py / wifi_loopback_reference.py in
// this same directory, which validate the pre-existing Packet/CRC/fragmentation logic;
// this file validates the two pieces added this session.

import com.itantra.core.PacketPriority
import com.itantra.core.SemanticPacket
import com.itantra.protocol.MessageType
import com.itantra.protocol.Packet
import com.itantra.protocol.PacketFormatException
import com.itantra.protocol.PacketFragmenter
import com.itantra.protocol.SemanticPacketWireCodec

private var checks = 0
private var failures = 0
private fun check(name: String, condition: Boolean) {
    checks++
    if (condition) println("PASS: $name") else { failures++; println("FAIL: $name") }
}

fun main() {
    println("=== PacketFragmenter + SemanticPacketWireCodec runtime smoke test ===")

    run {
        val result = PacketFragmenter.buildFragmentPackets(
            payload = ByteArray(0), sessionId = 1, senderShortId = 1, messageId = 1,
            startSequenceNumber = 0, langCode = 0, messageType = MessageType.FINAL,
            isAlert = false, maxFragmentPayload = 100
        )
        check("empty payload -> exactly one empty fragment", result.packets.size == 1 && result.packets[0].payload.isEmpty())
        check("empty payload fragment is marked final", result.packets[0].isFinal)
    }

    run {
        val payload = ByteArray(250) { it.toByte() }
        val result = PacketFragmenter.buildFragmentPackets(
            payload = payload, sessionId = 1, senderShortId = 1, messageId = 1,
            startSequenceNumber = 0, langCode = 0, messageType = MessageType.FINAL,
            isAlert = false, maxFragmentPayload = 100
        )
        check("250-byte payload with 100-byte budget -> 3 fragments", result.packets.size == 3)
        check("fragments flagged correctly (fragmented, correct index/count, only last is final)",
            result.packets.withIndex().all { (idx, p) -> p.isFragmented && p.fragmentIndex == idx && p.fragmentCount == 3 && p.isFinal == (idx == 2) })
        val reassembled = result.packets.flatMap { it.payload.toList() }.toByteArray()
        check("fragments concatenate back to the exact original payload", reassembled.toList() == payload.toList())
    }

    run {
        val result = PacketFragmenter.buildFragmentPackets(
            payload = ByteArray(250) { it.toByte() }, sessionId = 1, senderShortId = 1, messageId = 1,
            startSequenceNumber = 0xFFFE, langCode = 0, messageType = MessageType.FINAL,
            isAlert = false, maxFragmentPayload = 100
        )
        check("sequence numbers wrap 0xFFFE -> 0xFFFF -> 0x0000", result.packets.map { it.sequenceNumber } == listOf(0xFFFE, 0xFFFF, 0))
        check("nextSequenceNumber reported correctly after wraparound", result.nextSequenceNumber == 1)
    }

    run {
        var threw = false
        try {
            PacketFragmenter.buildFragmentPackets(
                payload = ByteArray(300), sessionId = 1, senderShortId = 1, messageId = 1,
                startSequenceNumber = 0, langCode = 0, messageType = MessageType.FINAL,
                isAlert = false, maxFragmentPayload = 1
            )
        } catch (e: IllegalArgumentException) { threw = true }
        check("more than 255 fragments is rejected", threw)
    }

    run {
        val original = SemanticPacket(
            tokenIds = intArrayOf(104, 101, 108, 108, 111), lang = "en",
            priority = PacketPriority.NORMAL_BASE, residualPayload = null, ttlMs = 30_000, sentenceId = 42L
        )
        val bytes = SemanticPacketWireCodec.encodePayload(original)
        val decoded = SemanticPacketWireCodec.decodePayload(bytes, "en", PacketPriority.NORMAL_BASE)
        check("SemanticPacket round-trip: tokenIds match", decoded.tokenIds.toList() == original.tokenIds.toList())
        check("SemanticPacket round-trip: ttlMs/sentenceId match", decoded.ttlMs == original.ttlMs && decoded.sentenceId == original.sentenceId)
    }

    run {
        val original = SemanticPacket(
            tokenIds = intArrayOf(1, 2, 3), lang = "hi", priority = PacketPriority.ALERT_BASE,
            residualPayload = byteArrayOf(9, 8, 7, 6), ttlMs = 5_000, sentenceId = 7L
        )
        val decoded = SemanticPacketWireCodec.decodePayload(SemanticPacketWireCodec.encodePayload(original), "hi", PacketPriority.ALERT_BASE)
        check("SemanticPacket round-trip with residual payload", decoded.residualPayload?.toList() == original.residualPayload?.toList())
    }

    run {
        var threw = false
        try { SemanticPacketWireCodec.decodePayload(ByteArray(5), "en", PacketPriority.NORMAL_BASE) } catch (e: PacketFormatException) { threw = true }
        check("truncated payload throws PacketFormatException, not a crash or a guess", threw)
    }

    run {
        val code = SemanticPacketWireCodec.langCodeFor("xx-not-a-real-language")
        check("unmapped language -> UNKNOWN_LANG_CODE, not a guess", code == SemanticPacketWireCodec.UNKNOWN_LANG_CODE)
        check("UNKNOWN_LANG_CODE decodes back to 'und'", SemanticPacketWireCodec.langFor(code) == "und")
    }

    run {
        val enCode = SemanticPacketWireCodec.langCodeFor("en")
        check("known language round-trips through langCodeFor/langFor", SemanticPacketWireCodec.langFor(enCode) == "en")
    }

    println()
    println("=== $checks checks run, ${checks - failures} passed, $failures failed ===")
    if (failures > 0) kotlin.system.exitProcess(1)
}

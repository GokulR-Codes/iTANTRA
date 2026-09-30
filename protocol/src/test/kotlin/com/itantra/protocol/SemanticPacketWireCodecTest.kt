package com.itantra.protocol

import com.itantra.core.PacketPriority
import com.itantra.core.SemanticPacket
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SemanticPacketWireCodecTest {

    @Test
    fun `round-trips a token-only packet exactly`() {
        val original = SemanticPacket(
            tokenIds = intArrayOf(104, 101, 108, 108, 111), // "hello" as UTF-8 baseline bytes
            lang = "en", priority = PacketPriority.NORMAL_BASE, residualPayload = null,
            ttlMs = 30_000, sentenceId = 42L
        )
        val bytes = SemanticPacketWireCodec.encodePayload(original)
        val decoded = SemanticPacketWireCodec.decodePayload(bytes, "en", PacketPriority.NORMAL_BASE)

        assertArrayEquals(original.tokenIds, decoded.tokenIds)
        assertEquals(original.ttlMs, decoded.ttlMs)
        assertEquals(original.sentenceId, decoded.sentenceId)
        assertEquals("en", decoded.lang)
    }

    @Test
    fun `round-trips a packet with a non-null residual payload`() {
        val original = SemanticPacket(
            tokenIds = intArrayOf(1, 2, 3), lang = "hi", priority = PacketPriority.ALERT_BASE,
            residualPayload = byteArrayOf(9, 8, 7, 6), ttlMs = 5_000, sentenceId = 7L
        )
        val bytes = SemanticPacketWireCodec.encodePayload(original)
        val decoded = SemanticPacketWireCodec.decodePayload(bytes, "hi", PacketPriority.ALERT_BASE)
        assertArrayEquals(original.residualPayload, decoded.residualPayload)
    }

    @Test
    fun `round-trips an empty token array`() {
        val original = SemanticPacket(
            tokenIds = intArrayOf(), lang = "en", priority = PacketPriority.NORMAL_BASE,
            residualPayload = null, ttlMs = 1_000, sentenceId = 1L
        )
        val bytes = SemanticPacketWireCodec.encodePayload(original)
        val decoded = SemanticPacketWireCodec.decodePayload(bytes, "en", PacketPriority.NORMAL_BASE)
        assertEquals(0, decoded.tokenIds.size)
    }

    @Test
    fun `truncated bytes are rejected with PacketFormatException, not silently guessed at`() {
        assertThrows(PacketFormatException::class.java) {
            SemanticPacketWireCodec.decodePayload(ByteArray(5), "en", PacketPriority.NORMAL_BASE)
        }
    }

    @Test
    fun `a tokenCount field larger than the actual data is rejected`() {
        val original = SemanticPacket(
            tokenIds = intArrayOf(1), lang = "en", priority = PacketPriority.NORMAL_BASE,
            residualPayload = null, ttlMs = 1_000, sentenceId = 1L
        )
        val bytes = SemanticPacketWireCodec.encodePayload(original).copyOf(12) // truncate away the tokenIds/residual
        assertThrows(PacketFormatException::class.java) {
            SemanticPacketWireCodec.decodePayload(bytes, "en", PacketPriority.NORMAL_BASE)
        }
    }

    @Test
    fun `langCodeFor and langFor round-trip known language codes`() {
        val code = SemanticPacketWireCodec.langCodeFor("en")
        assertEquals("en", SemanticPacketWireCodec.langFor(code))
    }

    @Test
    fun `an unmapped language falls back to UNKNOWN_LANG_CODE, not a guess`() {
        val code = SemanticPacketWireCodec.langCodeFor("xx-not-a-real-language")
        assertEquals(SemanticPacketWireCodec.UNKNOWN_LANG_CODE, code)
        assertEquals("und", SemanticPacketWireCodec.langFor(code))
    }
}

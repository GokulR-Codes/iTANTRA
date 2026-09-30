package com.itantra.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContractsTest {

    @Test
    fun `AudioFrame rejects non-16kHz sample rate`() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioFrame(pcm16 = shortArrayOf(1, 2, 3), sampleRateHz = 8000, captureTsNanos = 0)
        }
    }

    @Test
    fun `AudioFrame rejects non-mono channel count`() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioFrame(pcm16 = shortArrayOf(1, 2, 3), channels = 2, captureTsNanos = 0)
        }
    }

    @Test
    fun `AudioFrame value equality works despite ShortArray field`() {
        val a = AudioFrame(pcm16 = shortArrayOf(1, 2, 3), captureTsNanos = 100)
        val b = AudioFrame(pcm16 = shortArrayOf(1, 2, 3), captureTsNanos = 100)
        val c = AudioFrame(pcm16 = shortArrayOf(1, 2, 4), captureTsNanos = 100)
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `RecognizedUtterance rejects out-of-range confidence`() {
        assertThrows(IllegalArgumentException::class.java) {
            RecognizedUtterance(
                text = "test", lang = "en", isFinal = true,
                confidence = 1.5f, startTsMs = 0, endTsMs = 100
            )
        }
    }

    @Test
    fun `RecognizedUtterance rejects end before start`() {
        assertThrows(IllegalArgumentException::class.java) {
            RecognizedUtterance(
                text = "test", lang = "en", isFinal = true,
                confidence = 0.9f, startTsMs = 200, endTsMs = 100
            )
        }
    }

    @Test
    fun `SemanticPacket accepts empty tokenIds with no residual (e.g. empty-text edge case)`() {
        // NOTE: the Gate1 git archive's contract required tokenIds.isNotEmpty() || residual !=
        // null. That requirement is deliberately dropped in the merge (see Contracts.kt /
        // FILE_CONFLICT_MATRIX.md) because codec.SemanticCodec.decode() treats an empty
        // tokenIds array as a legitimate, handleable state (confidence 0), not a construction
        // error.
        val p = SemanticPacket(
            tokenIds = intArrayOf(), lang = "hi", priority = PacketPriority.NORMAL_BASE,
            residualPayload = null, ttlMs = 1000, sentenceId = 1
        )
        assertEquals(0, p.tokenIds.size)
    }

    @Test
    fun `SemanticPacket accepts residual-only payload`() {
        val p = SemanticPacket(
            tokenIds = intArrayOf(), lang = "hi", priority = PacketPriority.RESIDUAL,
            residualPayload = byteArrayOf(1, 2, 3), ttlMs = 500, sentenceId = 42
        )
        assertTrue(p.residualPayload!!.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `SemanticPacket rejects non-positive ttl`() {
        assertThrows(IllegalArgumentException::class.java) {
            SemanticPacket(
                tokenIds = intArrayOf(1), lang = "hi", priority = PacketPriority.ALERT_BASE,
                residualPayload = null, ttlMs = 0, sentenceId = 1
            )
        }
    }

    @Test
    fun `SynthesizedAudio value equality works despite ShortArray field`() {
        val a = SynthesizedAudio(pcm16 = shortArrayOf(1, 2, 3), sampleRateHz = 22050, isAlert = false, firstAudioReadyTsMs = 10)
        val b = SynthesizedAudio(pcm16 = shortArrayOf(1, 2, 3), sampleRateHz = 22050, isAlert = false, firstAudioReadyTsMs = 10)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `ChannelStats rejects out-of-range loss percentage`() {
        assertThrows(IllegalArgumentException::class.java) {
            ChannelStats(
                rttMs = 20f, lossPct = 120f, goodputBps = 1000f,
                rssiDbm = -60, queueDepth = 0, thermalState = ThermalState.NOMINAL, batteryPct = 80
            )
        }
    }

    @Test
    fun `Language fromCode resolves all ten required languages`() {
        val required = listOf("hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn", "en")
        required.forEach { code ->
            assertTrue(Language.fromCode(code) != null, "Missing Language mapping for code '$code'")
        }
        assertEquals(10, Language.entries.size)
        assertEquals(required.toSet(), Language.REQUIRED_CODES)
    }

    @Test
    fun `Language fromCode returns null for unknown code`() {
        assertNull(Language.fromCode("xx"))
    }
}

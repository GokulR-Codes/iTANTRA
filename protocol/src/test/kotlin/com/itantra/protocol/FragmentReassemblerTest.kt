package com.itantra.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FragmentReassemblerTest {

    private fun fragment(
        sessionId: Int, messageId: Int, index: Int, count: Int, payload: ByteArray
    ) = Packet(
        flags = if (count > 1) PacketFlags.FRAGMENTED else 0,
        sessionId = sessionId, senderShortId = 1, messageId = messageId, sequenceNumber = index,
        timestampMs = 0, langCode = 0, messageType = MessageType.FINAL,
        fragmentIndex = index, fragmentCount = count, payload = payload
    )

    @Test
    fun `single-fragment message completes immediately`() {
        val r = FragmentReassembler()
        val result = r.onFragment(fragment(1, 1, 0, 1, "hi".toByteArray()), nowMs = 0)
        assertNotNull(result)
        assertArrayEquals("hi".toByteArray(), result!!.payload)
    }

    @Test
    fun `fragments arriving in order reassemble correctly`() {
        val r = FragmentReassembler()
        assertNull(r.onFragment(fragment(1, 1, 0, 3, byteArrayOf(1, 2)), 0))
        assertNull(r.onFragment(fragment(1, 1, 1, 3, byteArrayOf(3, 4)), 1))
        val result = r.onFragment(fragment(1, 1, 2, 3, byteArrayOf(5)), 2)
        assertNotNull(result)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), result!!.payload)
    }

    @Test
    fun `fragments arriving out of order still reassemble correctly and in the right byte order`() {
        val r = FragmentReassembler()
        assertNull(r.onFragment(fragment(1, 1, 2, 3, byteArrayOf(5)), 0))
        assertNull(r.onFragment(fragment(1, 1, 0, 3, byteArrayOf(1, 2)), 1))
        val result = r.onFragment(fragment(1, 1, 1, 3, byteArrayOf(3, 4)), 2)
        assertNotNull(result)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), result!!.payload)
    }

    @Test
    fun `duplicate fragment is ignored, not double-counted`() {
        val r = FragmentReassembler()
        assertNull(r.onFragment(fragment(1, 1, 0, 2, byteArrayOf(1)), 0))
        assertNull(r.onFragment(fragment(1, 1, 0, 2, byteArrayOf(1)), 1)) // exact duplicate of index 0
        assertEquals(1, r.activeMessageCount())
        val result = r.onFragment(fragment(1, 1, 1, 2, byteArrayOf(2)), 2)
        assertNotNull(result)
        assertArrayEquals(byteArrayOf(1, 2), result!!.payload)
    }

    @Test
    fun `missing fragment leaves the message incomplete indefinitely until it arrives`() {
        val r = FragmentReassembler()
        r.onFragment(fragment(1, 1, 0, 3, byteArrayOf(1)), 0)
        r.onFragment(fragment(1, 1, 2, 3, byteArrayOf(3)), 1) // index 1 never arrives yet
        assertEquals(1, r.activeMessageCount())
    }

    @Test
    fun `two simultaneous messages in the same session do not interfere`() {
        val r = FragmentReassembler()
        r.onFragment(fragment(1, 100, 0, 2, byteArrayOf(1)), 0)
        r.onFragment(fragment(1, 200, 0, 2, byteArrayOf(9)), 0)
        assertEquals(2, r.activeMessageCount())
        val m100 = r.onFragment(fragment(1, 100, 1, 2, byteArrayOf(2)), 1)
        val m200 = r.onFragment(fragment(1, 200, 1, 2, byteArrayOf(10)), 1)
        assertArrayEquals(byteArrayOf(1, 2), m100!!.payload)
        assertArrayEquals(byteArrayOf(9, 10), m200!!.payload)
    }

    @Test
    fun `two simultaneous sessions with the same messageId do not collide`() {
        val r = FragmentReassembler()
        val a = r.onFragment(fragment(sessionId = 1, messageId = 5, index = 0, count = 1, payload = byteArrayOf(1)), 0)
        val b = r.onFragment(fragment(sessionId = 2, messageId = 5, index = 0, count = 1, payload = byteArrayOf(2)), 0)
        assertArrayEquals(byteArrayOf(1), a!!.payload)
        assertArrayEquals(byteArrayOf(2), b!!.payload)
    }

    @Test
    fun `sweepExpired drops and reports messages past the timeout, and stops tracking them`() {
        val r = FragmentReassembler(fragmentTimeoutMs = 100)
        r.onFragment(fragment(1, 1, 0, 2, byteArrayOf(1)), 0) // index 1 never arrives
        val expired = r.sweepExpired(nowMs = 500)
        assertEquals(listOf(1 to 1), expired)
        assertEquals(0, r.activeMessageCount())
    }

    @Test
    fun `repeated cleanup is safe and idempotent`() {
        val r = FragmentReassembler(fragmentTimeoutMs = 100)
        r.onFragment(fragment(1, 1, 0, 2, byteArrayOf(1)), 0)
        r.sweepExpired(500)
        val secondSweep = r.sweepExpired(1000) // nothing left to expire
        assertTrue(secondSweep.isEmpty())
    }

    @Test
    fun `oldest in-flight message is evicted once maxConcurrentMessages is exceeded`() {
        val r = FragmentReassembler(maxConcurrentMessages = 2)
        r.onFragment(fragment(1, 1, 0, 2, byteArrayOf(1)), 0) // oldest, incomplete
        r.onFragment(fragment(1, 2, 0, 2, byteArrayOf(2)), 1) // incomplete
        assertEquals(2, r.activeMessageCount())
        r.onFragment(fragment(1, 3, 0, 2, byteArrayOf(3)), 2) // forces eviction of messageId=1
        assertEquals(2, r.activeMessageCount(), "must stay bounded, not grow past maxConcurrentMessages")
    }

    @Test
    fun `message exceeding maxMessageBytes is dropped, not silently truncated`() {
        val r = FragmentReassembler(maxMessageBytes = 4)
        assertThrows(IllegalStateException::class.java) {
            r.onFragment(fragment(1, 1, 0, 2, ByteArray(10)), 0)
        }
        assertEquals(0, r.activeMessageCount(), "the oversized in-flight message must be dropped, not left dangling")
    }

    @Test
    fun `invalid fragment count is rejected`() {
        val r = FragmentReassembler()
        assertThrows(IllegalArgumentException::class.java) {
            r.onFragment(fragment(1, 1, 0, 0, byteArrayOf(1)), 0)
        }
    }

    @Test
    fun `invalid fragment index is rejected`() {
        val r = FragmentReassembler()
        assertThrows(IllegalArgumentException::class.java) {
            r.onFragment(fragment(1, 1, 5, 3, byteArrayOf(1)), 0)
        }
    }
}

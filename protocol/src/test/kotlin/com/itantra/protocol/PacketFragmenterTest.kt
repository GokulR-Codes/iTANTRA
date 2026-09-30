package com.itantra.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PacketFragmenterTest {

    @Test
    fun `empty payload produces exactly one empty fragment`() {
        val result = PacketFragmenter.buildFragmentPackets(
            payload = ByteArray(0), sessionId = 1, senderShortId = 1, messageId = 1,
            startSequenceNumber = 0, langCode = 0, messageType = MessageType.FINAL,
            isAlert = false, maxFragmentPayload = 100
        )
        assertEquals(1, result.packets.size)
        assertEquals(0, result.packets[0].payload.size)
        assertTrue(result.packets[0].isFinal)
        assertEquals(1, result.packets[0].fragmentCount)
    }

    @Test
    fun `payload smaller than maxFragmentPayload is a single non-fragmented packet`() {
        val payload = ByteArray(10) { it.toByte() }
        val result = PacketFragmenter.buildFragmentPackets(
            payload = payload, sessionId = 1, senderShortId = 1, messageId = 1,
            startSequenceNumber = 0, langCode = 0, messageType = MessageType.FINAL,
            isAlert = false, maxFragmentPayload = 100
        )
        assertEquals(1, result.packets.size)
        assertEquals(false, result.packets[0].isFragmented)
        assertTrue(result.packets[0].isFinal)
    }

    @Test
    fun `payload larger than maxFragmentPayload splits into multiple correctly-flagged, reassemblable fragments`() {
        val payload = ByteArray(250) { it.toByte() }
        val result = PacketFragmenter.buildFragmentPackets(
            payload = payload, sessionId = 1, senderShortId = 1, messageId = 1,
            startSequenceNumber = 0, langCode = 0, messageType = MessageType.FINAL,
            isAlert = false, maxFragmentPayload = 100
        )
        assertEquals(3, result.packets.size)
        result.packets.forEachIndexed { idx, p ->
            assertTrue(p.isFragmented)
            assertEquals(idx == result.packets.size - 1, p.isFinal)
            assertEquals(idx, p.fragmentIndex)
            assertEquals(3, p.fragmentCount)
        }
        val reassembled = result.packets.flatMap { it.payload.toList() }.toByteArray()
        assertEquals(payload.toList(), reassembled.toList())
    }

    @Test
    fun `isAlert sets the IS_ALERT flag on every fragment`() {
        val result = PacketFragmenter.buildFragmentPackets(
            payload = ByteArray(250) { it.toByte() }, sessionId = 1, senderShortId = 1, messageId = 1,
            startSequenceNumber = 0, langCode = 0, messageType = MessageType.ALERT,
            isAlert = true, maxFragmentPayload = 100
        )
        assertTrue(result.packets.all { it.isAlert })
    }

    @Test
    fun `sequence numbers increment and wrap correctly across fragments`() {
        val result = PacketFragmenter.buildFragmentPackets(
            payload = ByteArray(250) { it.toByte() }, sessionId = 1, senderShortId = 1, messageId = 1,
            startSequenceNumber = 0xFFFE, langCode = 0, messageType = MessageType.FINAL,
            isAlert = false, maxFragmentPayload = 100
        )
        assertEquals(listOf(0xFFFE, 0xFFFF, 0), result.packets.map { it.sequenceNumber })
        assertEquals(1, result.nextSequenceNumber)
    }

    @Test
    fun `more than 255 fragments is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PacketFragmenter.buildFragmentPackets(
                payload = ByteArray(300), sessionId = 1, senderShortId = 1, messageId = 1,
                startSequenceNumber = 0, langCode = 0, messageType = MessageType.FINAL,
                isAlert = false, maxFragmentPayload = 1
            )
        }
    }

    @Test
    fun `maxFragmentPayload accounts for header, trailer, and extra overhead`() {
        val mtu = 200
        val withoutExtra = PacketFragmenter.maxFragmentPayload(mtu)
        val withBleOverhead = PacketFragmenter.maxFragmentPayload(mtu, extraOverhead = 3)
        assertEquals(mtu - Packet.HEADER_SIZE - Packet.TRAILER_SIZE, withoutExtra)
        assertEquals(withoutExtra - 3, withBleOverhead)
    }
}

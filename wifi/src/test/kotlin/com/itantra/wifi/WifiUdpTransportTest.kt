package com.itantra.wifi

import com.itantra.protocol.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetAddress

/**
 * A real Kotlin/JVM loopback test on 127.0.0.1 — the JVM/kotlinc equivalent of
 * protocol/validation/wifi_loopback_reference.py's Python loopback (which was executed and
 * passed 3/3 checks). This is new: no source archive had a Kotlin-native version of that
 * loopback test, only the Python one. It also exercises the FragmentReassembler integration
 * fix (see WifiUdpTransport.kt's KDoc) that the Python reference predates.
 */
class WifiUdpTransportTest {

    @Test
    fun `single-fragment message round-trips over a real UDP socket on loopback`() = runBlocking {
        val receiver = WifiUdpTransport(WifiTransportConfig(), sessionId = 1, senderShortId = 2)
        receiver.open(localPort = 0)
        val receiverPort = receiverLocalPort(receiver)

        val sender = WifiUdpTransport(WifiTransportConfig(), sessionId = 1, senderShortId = 1)
        sender.open(localPort = 0)
        sender.connectTo(InetAddress.getByName("127.0.0.1"), receiverPort)

        val job = launch(Dispatchers.IO) { receiver.receiveLoop() }
        try {
            sender.send("hello wifi".toByteArray(), langCode = 9, messageType = MessageType.FINAL, isAlert = false)

            val received = withTimeout(5_000) { receiver.incomingMessages.first() }
            assertArrayEquals("hello wifi".toByteArray(), received.payload)
            assertEquals(9, received.langCode)
        } finally {
            job.cancel()
            sender.close()
            receiver.close()
        }
    }

    @Test
    fun `multi-fragment message reassembles byte-exact across a real UDP socket`() = runBlocking {
        val receiver = WifiUdpTransport(WifiTransportConfig(mtu = 60), sessionId = 5, senderShortId = 2)
        receiver.open(localPort = 0)
        val receiverPort = receiverLocalPort(receiver)

        val sender = WifiUdpTransport(WifiTransportConfig(mtu = 60), sessionId = 5, senderShortId = 1)
        sender.open(localPort = 0)
        sender.connectTo(InetAddress.getByName("127.0.0.1"), receiverPort)

        val job = launch(Dispatchers.IO) { receiver.receiveLoop() }
        try {
            val longPayload = "iTantra ".repeat(30).toByteArray() // forces multiple ~60-byte-MTU fragments
            sender.send(longPayload, langCode = 1, messageType = MessageType.FINAL, isAlert = true)

            val received = withTimeout(5_000) { receiver.incomingMessages.first() }
            assertArrayEquals(longPayload, received.payload)
            assertEquals(true, received.isAlert)
        } finally {
            job.cancel()
            sender.close()
            receiver.close()
        }
    }

    // WifiUdpTransport doesn't expose the bound ephemeral port directly (open() takes a
    // requested port, 0 meaning "OS picks one"), so tests need a small reflective peek to
    // find out which port the OS actually assigned before the sender can connect to it.
    private fun receiverLocalPort(transport: WifiUdpTransport): Int {
        val socketField = WifiUdpTransport::class.java.getDeclaredField("socket")
        socketField.isAccessible = true
        val socket = socketField.get(transport) as java.net.DatagramSocket
        return socket.localPort
    }
}

package com.nblaisot.voxcrew.lanlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class StunClientTest {

    @Test
    fun `discover parses XOR-MAPPED-ADDRESS from a binding success response`() {
        val serverSocket = DatagramSocket(0)
        val serverPort = serverSocket.localPort
        val expectedHost = "203.0.113.42"
        val expectedPort = 54321

        val serverThread = thread {
            val buffer = ByteArray(512)
            val packet = DatagramPacket(buffer, buffer.size)
            serverSocket.receive(packet)
            val transactionId = packet.data.copyOfRange(8, 20)
            val response = buildBindingSuccessResponse(transactionId, expectedHost, expectedPort)
            serverSocket.send(DatagramPacket(response, response.size, packet.address, packet.port))
        }

        val clientSocket = DatagramSocket(0)
        val endpoint = StunClient.discover(clientSocket, "127.0.0.1", serverPort, timeoutMs = 2_000)
        serverThread.join(2_000)
        clientSocket.close()
        serverSocket.close()

        assertEquals(expectedHost, endpoint?.host)
        assertEquals(expectedPort, endpoint?.port)
    }

    @Test
    fun `discover returns null when nothing responds before the timeout`() {
        val clientSocket = DatagramSocket(0)
        val unusedPort = DatagramSocket(0).use { it.localPort } // bound then immediately closed: nobody listens there
        val endpoint = StunClient.discover(clientSocket, "127.0.0.1", unusedPort, timeoutMs = 300)
        clientSocket.close()
        assertNull(endpoint)
    }

    private fun buildBindingSuccessResponse(transactionId: ByteArray, host: String, port: Int): ByteArray {
        val magicCookie = 0x2112A442.toInt()
        val cookieBytes = ByteBuffer.allocate(4).putInt(magicCookie).array()
        val addrParts = host.split(".").map { it.toInt() }
        val xPort = port xor (magicCookie ushr 16)
        val xAddr = ByteArray(4) { i -> (addrParts[i] xor (cookieBytes[i].toInt() and 0xFF)).toByte() }

        val attrValue = ByteBuffer.allocate(8)
        attrValue.put(0)
        attrValue.put(0x01) // IPv4
        attrValue.putShort(xPort.toShort())
        attrValue.put(xAddr)

        val attrHeader = ByteBuffer.allocate(4)
        attrHeader.putShort(0x0020.toShort()) // XOR-MAPPED-ADDRESS
        attrHeader.putShort(8.toShort())

        val body = attrHeader.array() + attrValue.array()

        val header = ByteBuffer.allocate(20)
        header.putShort(0x0101.toShort()) // Binding Success Response
        header.putShort(body.size.toShort())
        header.putInt(magicCookie)
        header.put(transactionId)

        return header.array() + body
    }
}

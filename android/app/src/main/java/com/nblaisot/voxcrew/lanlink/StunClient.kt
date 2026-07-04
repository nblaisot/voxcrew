package com.nblaisot.voxcrew.lanlink

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Minimal STUN (RFC 5389) binding client: sends one Binding Request over an
 * already-bound [DatagramSocket] and parses the XOR-MAPPED-ADDRESS (falling back to
 * the legacy MAPPED-ADDRESS) from the response — just enough to discover this
 * device's public IP:port as seen from the internet, for UDP hole punching.
 *
 * Must run on the exact socket that will later carry the P2P traffic: a NAT mapping
 * is keyed by (local ip, local port, protocol[, sometimes destination]), so
 * discovering it from a different socket would be useless.
 */
object StunClient {
    private const val BINDING_REQUEST = 0x0001
    private const val BINDING_SUCCESS_RESPONSE = 0x0101
    private const val MAGIC_COOKIE = 0x2112A442.toInt()
    private const val ATTR_MAPPED_ADDRESS = 0x0001
    private const val ATTR_XOR_MAPPED_ADDRESS = 0x0020
    private const val FAMILY_IPV4 = 0x01

    data class Endpoint(val host: String, val port: Int)

    /** Blocks on [socket] until a response arrives or [timeoutMs] elapses. Returns null on failure. */
    fun discover(socket: DatagramSocket, stunHost: String, stunPort: Int, timeoutMs: Int = 3_000): Endpoint? {
        return try {
            val transactionId = ByteArray(12).also { Random.nextBytes(it) }
            val request = buildRequest(transactionId)
            val stunAddress = InetAddress.getByName(stunHost)
            socket.send(DatagramPacket(request, request.size, stunAddress, stunPort))

            val originalTimeout = socket.soTimeout
            socket.soTimeout = timeoutMs
            try {
                val buffer = ByteArray(512)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                parseResponse(response.data.copyOf(response.length), transactionId)
            } finally {
                socket.soTimeout = originalTimeout
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildRequest(transactionId: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(20)
        buffer.putShort(BINDING_REQUEST.toShort())
        buffer.putShort(0) // no attributes
        buffer.putInt(MAGIC_COOKIE)
        buffer.put(transactionId)
        return buffer.array()
    }

    private fun parseResponse(data: ByteArray, expectedTransactionId: ByteArray): Endpoint? {
        if (data.size < 20) return null
        val buffer = ByteBuffer.wrap(data)
        val type = buffer.short.toInt() and 0xFFFF
        val length = buffer.short.toInt() and 0xFFFF
        val cookie = buffer.int
        val transactionId = ByteArray(12).also { buffer.get(it) }
        if (type != BINDING_SUCCESS_RESPONSE || cookie != MAGIC_COOKIE) return null
        if (!transactionId.contentEquals(expectedTransactionId)) return null

        var offset = 20
        var xorResult: Endpoint? = null
        var mappedResult: Endpoint? = null
        while (offset + 4 <= data.size && offset < 20 + length) {
            val attrType = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            val attrLen = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            val valueStart = offset + 4
            if (valueStart + attrLen > data.size) break
            when (attrType) {
                ATTR_XOR_MAPPED_ADDRESS -> xorResult = parseXorMappedAddress(data, valueStart, attrLen)
                ATTR_MAPPED_ADDRESS -> mappedResult = parseMappedAddress(data, valueStart, attrLen)
            }
            val padded = (attrLen + 3) / 4 * 4
            offset = valueStart + padded
        }
        return xorResult ?: mappedResult
    }

    private fun parseXorMappedAddress(data: ByteArray, start: Int, length: Int): Endpoint? {
        if (length < 8) return null
        val family = data[start + 1].toInt() and 0xFF
        if (family != FAMILY_IPV4) return null
        val xPort = ((data[start + 2].toInt() and 0xFF) shl 8) or (data[start + 3].toInt() and 0xFF)
        val port = xPort xor (MAGIC_COOKIE ushr 16)
        val cookieBytes = ByteBuffer.allocate(4).putInt(MAGIC_COOKIE).array()
        val addrBytes = ByteArray(4) { i -> (data[start + 4 + i].toInt() xor cookieBytes[i].toInt()).toByte() }
        return Endpoint(ipv4ToString(addrBytes), port and 0xFFFF)
    }

    private fun parseMappedAddress(data: ByteArray, start: Int, length: Int): Endpoint? {
        if (length < 8) return null
        val family = data[start + 1].toInt() and 0xFF
        if (family != FAMILY_IPV4) return null
        val port = ((data[start + 2].toInt() and 0xFF) shl 8) or (data[start + 3].toInt() and 0xFF)
        val addrBytes = ByteArray(4) { i -> data[start + 4 + i] }
        return Endpoint(ipv4ToString(addrBytes), port)
    }

    private fun ipv4ToString(bytes: ByteArray): String = bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
}

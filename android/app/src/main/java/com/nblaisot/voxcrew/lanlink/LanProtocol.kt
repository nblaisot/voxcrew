package com.nblaisot.voxcrew.lanlink

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Wire format for the LAN intercom link. Deliberately minimal (no external
 * serialization dependency): every frame is [type:1 byte][length:4 bytes][payload].
 * TCP already guarantees order and delivery within a connection; the `seq` carried
 * by [LanFrame.Audio] only matters across reconnects, to resume without gaps or
 * duplicates (see [SendBuffer]).
 */
sealed class LanFrame {
    data class Hello(val uid: String, val lastContiguousSeq: Long) : LanFrame()

    /** [payload] is codec-encoded audio (Opus), not raw PCM — see [AudioCapture]/[AudioPlayback]. */
    data class Audio(val seq: Long, val payload: ByteArray) : LanFrame()
    /** Reliable, sequenced talk-spurt boundary used to drive the remote Telecom lifecycle. */
    data class MediaActivity(val seq: Long, val active: Boolean) : LanFrame()
    data class Ack(val lastContiguousSeq: Long) : LanFrame()
    data class Ping(val timestampMs: Long) : LanFrame()
    data class Pong(val timestampMs: Long) : LanFrame()

    /**
     * Sender-declared sequence gap: frames up to and including [untilSeq] no longer
     * exist (expired from the [SendBuffer] or evicted by its byte cap). The receiver
     * fast-forwards its contiguity cursor instead of waiting forever — audio is
     * delayed up to the buffer age cap, then dropped cleanly, never wedged.
     */
    data class Skip(val untilSeq: Long) : LanFrame()
}

object LanProtocol {
    private const val TYPE_HELLO = 1
    private const val TYPE_AUDIO = 2
    private const val TYPE_ACK = 3
    private const val TYPE_PING = 4
    private const val TYPE_PONG = 5
    private const val TYPE_MEDIA_ACTIVITY = 6
    private const val TYPE_SKIP = 7

    /** Guards against a corrupt/malicious length prefix causing an OOM allocation. */
    const val MAX_PAYLOAD_BYTES = 64 * 1024

    @Throws(IOException::class)
    fun writeFrame(out: DataOutputStream, frame: LanFrame) {
        val payload = encodePayload(frame)
        out.writeByte(typeOf(frame))
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    /** Datagram-oriented encoding (one frame = one UDP packet / WS binary message payload). */
    fun encodeFrame(frame: LanFrame): ByteArray {
        val payload = encodePayload(frame)
        val buffer = java.io.ByteArrayOutputStream(1 + payload.size)
        buffer.write(typeOf(frame))
        buffer.write(payload)
        return buffer.toByteArray()
    }

    /** Counterpart to [encodeFrame]. Returns null if [bytes] is too short to be a valid frame. */
    fun decodeFrame(bytes: ByteArray): LanFrame? {
        if (bytes.isEmpty()) return null
        val type = bytes[0].toInt() and 0xFF
        return runCatching { decodePayload(type, bytes.copyOfRange(1, bytes.size)) }.getOrNull()
    }

    /** Returns null on clean stream end (EOF before any byte of the next frame). */
    @Throws(IOException::class)
    fun readFrame(input: DataInputStream): LanFrame? {
        val type = try {
            input.readUnsignedByte()
        } catch (e: java.io.EOFException) {
            return null
        }
        val length = input.readInt()
        if (length < 0 || length > MAX_PAYLOAD_BYTES) {
            throw IOException("Invalid LAN frame length: $length")
        }
        val payload = ByteArray(length)
        input.readFully(payload)
        return decodePayload(type, payload)
    }

    private fun typeOf(frame: LanFrame): Int = when (frame) {
        is LanFrame.Hello -> TYPE_HELLO
        is LanFrame.Audio -> TYPE_AUDIO
        is LanFrame.Ack -> TYPE_ACK
        is LanFrame.Ping -> TYPE_PING
        is LanFrame.Pong -> TYPE_PONG
        is LanFrame.MediaActivity -> TYPE_MEDIA_ACTIVITY
        is LanFrame.Skip -> TYPE_SKIP
    }

    private fun encodePayload(frame: LanFrame): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val data = DataOutputStream(buffer)
        when (frame) {
            is LanFrame.Hello -> {
                data.writeUTF(frame.uid)
                data.writeLong(frame.lastContiguousSeq)
            }
            is LanFrame.Audio -> {
                data.writeLong(frame.seq)
                data.write(frame.payload)
            }
            is LanFrame.Ack -> data.writeLong(frame.lastContiguousSeq)
            is LanFrame.Ping -> data.writeLong(frame.timestampMs)
            is LanFrame.Pong -> data.writeLong(frame.timestampMs)
            is LanFrame.MediaActivity -> {
                data.writeLong(frame.seq)
                data.writeBoolean(frame.active)
            }
            is LanFrame.Skip -> data.writeLong(frame.untilSeq)
        }
        data.flush()
        return buffer.toByteArray()
    }

    private fun decodePayload(type: Int, payload: ByteArray): LanFrame {
        val data = DataInputStream(payload.inputStream())
        return when (type) {
            TYPE_HELLO -> LanFrame.Hello(data.readUTF(), data.readLong())
            TYPE_AUDIO -> {
                val seq = data.readLong()
                val pcm = ByteArray(payload.size - 8)
                data.readFully(pcm)
                LanFrame.Audio(seq, pcm)
            }
            TYPE_ACK -> LanFrame.Ack(data.readLong())
            TYPE_PING -> LanFrame.Ping(data.readLong())
            TYPE_PONG -> LanFrame.Pong(data.readLong())
            TYPE_MEDIA_ACTIVITY -> LanFrame.MediaActivity(data.readLong(), data.readBoolean())
            TYPE_SKIP -> LanFrame.Skip(data.readLong())
            else -> throw IOException("Unknown LAN frame type: $type")
        }
    }
}

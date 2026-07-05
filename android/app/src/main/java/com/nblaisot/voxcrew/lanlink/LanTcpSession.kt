package com.nblaisot.voxcrew.lanlink

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket

/**
 * One full-duplex TCP session with a single LAN peer. Shared by [LanTcpClient] and
 * adopted by [LanTcpServer] for inbound connections.
 */
internal class LanTcpSession(
    private val scope: CoroutineScope,
    val peerUid: String,
    private val socket: Socket,
    private val out: DataOutputStream,
    private val input: DataInputStream,
    private val peerLink: PeerLink,
    private val transport: FrameTransport,
    private val onClosed: (String) -> Unit,
) {
    private val writeLock = Any()
    private var readerJob: Job? = null
    @Volatile var closed = false
        private set

    fun start() {
        readerJob = scope.launch(Dispatchers.IO) { readLoop() }
    }

    fun sendFrame(frame: LanFrame) {
        if (closed) return
        try {
            synchronized(writeLock) { LanProtocol.writeFrame(out, frame) }
        } catch (e: IOException) {
            close()
        }
    }

    private suspend fun readLoop() {
        try {
            while (currentCoroutineContext().isActive) {
                val frame = LanProtocol.readFrame(input) ?: break
                if (frame !is LanFrame.Hello) {
                    peerLink.onFrameReceived(transport, frame)
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "session with $peerUid read error: ${e.message}")
        } finally {
            close()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        readerJob?.cancel()
        runCatching { socket.close() }
        onClosed(peerUid)
    }

    companion object {
        private const val TAG = "LanTcpSession"
    }
}

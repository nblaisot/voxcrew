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
    val generation: Long,
    val retryKey: String,
    private val socket: Socket,
    private val out: DataOutputStream,
    private val input: DataInputStream,
    private val onFrame: (LanTcpSession, LanFrame) -> Unit,
    private val onClosed: (LanTcpSession) -> Unit,
) {
    private var readerJob: Job? = null
    private val writer = SerializedFrameWriter(
        scope = scope,
        write = { frame -> LanProtocol.writeFrame(out, frame) },
        onFailure = { error ->
            Log.d(TAG, "session with $peerUid write error: ${error.message}")
            close()
        },
    )
    @Volatile var closed = false
        private set
    @Volatile var confirmed = false
        private set

    fun start(): Boolean = synchronized(this) {
        if (closed) return@synchronized false
        writer.start()
        readerJob = scope.launch(Dispatchers.IO) { readLoop() }
        true
    }

    fun confirm(): Boolean = synchronized(this) {
        if (closed || confirmed) return@synchronized false
        confirmed = true
        true
    }

    fun sendFrame(frame: LanFrame) {
        if (closed) return
        if (!writer.tryWrite(frame)) {
            Log.d(TAG, "session with $peerUid outbound queue unavailable")
            close()
        }
    }

    private suspend fun readLoop() {
        try {
            while (currentCoroutineContext().isActive) {
                val frame = LanProtocol.readFrame(input) ?: break
                if (frame !is LanFrame.Hello) {
                    onFrame(this, frame)
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "session with $peerUid read error: ${e.message}")
        } finally {
            close()
        }
    }

    fun close() {
        val shouldClose = synchronized(this) {
            if (closed) return@synchronized false
            closed = true
            true
        }
        if (!shouldClose) return
        // Never invoke client callbacks while holding the session monitor. Adoption
        // holds the client arbiter briefly, so doing so would recreate the lock cycle
        // client -> session vs session -> client seen during VPN/LAN handover.
        writer.stop()
        readerJob?.cancel()
        runCatching { socket.close() }
        onClosed(this)
    }

    companion object {
        private const val TAG = "LanTcpSession"
    }
}

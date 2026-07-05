package com.nblaisot.voxcrew.lanlink

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared LAN TCP listener: one [ServerSocket] and beacon port for the whole device.
 * Inbound connections are dispatched to the registered [LanTcpClient] for that peer uid.
 */
class LanTcpServer(
    private val scope: CoroutineScope,
) {
    private var serverSocket: ServerSocket? = null
    private var localUid: String = ""
    private var acceptJob: Job? = null
    private val clients = ConcurrentHashMap<String, LanTcpClient>()

    /** Called when an inbound peer connects but no client is registered yet. */
    var onUnknownInboundPeer: ((String) -> Unit)? = null

    val localPort: Int get() = serverSocket?.localPort ?: 0

    fun start(localUid: String) {
        this.localUid = localUid
        if (serverSocket != null) return
        serverSocket = runCatching { ServerSocket(0) }.getOrNull()
        acceptJob = scope.launch(Dispatchers.IO) { acceptLoop() }
    }

    fun stop() {
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.clear()
    }

    fun registerClient(peerUid: String, client: LanTcpClient) {
        clients[peerUid] = client
    }

    fun unregisterClient(peerUid: String) {
        clients.remove(peerUid)
    }

    internal fun localUid(): String = localUid

    private suspend fun acceptLoop() {
        val server = serverSocket ?: return
        while (currentCoroutineContext().isActive) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                break
            }
            scope.launch(Dispatchers.IO) { handleAcceptedSocket(socket) }
        }
    }

    private suspend fun handleAcceptedSocket(socket: Socket) {
        try {
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val hello = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { LanProtocol.readFrame(input) }
            }
            if (hello !is LanFrame.Hello) {
                runCatching { socket.close() }
                return
            }
            val peerUid = hello.uid
            val client = clients[peerUid]
            if (client == null) {
                onUnknownInboundPeer?.invoke(peerUid)
                val retryClient = clients[peerUid]
                if (retryClient == null) {
                    runCatching { socket.close() }
                    return
                }
                retryClient.adoptInboundSession(peerUid, socket, out, input, hello.lastContiguousSeq)
            } else {
                LanProtocol.writeFrame(out, LanFrame.Hello(localUid, client.lastContiguousInSeq()))
                client.adoptInboundSession(peerUid, socket, out, input, hello.lastContiguousSeq)
            }
        } catch (e: IOException) {
            runCatching { socket.close() }
            Log.d(TAG, "accept handling failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "LanTcpServer"
        const val HANDSHAKE_TIMEOUT_MS = 5_000L
    }
}

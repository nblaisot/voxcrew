package com.nblaisot.voxcrew.connectivity.local

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

class LocalSignalingServer(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    data class ServerInfo(val host: String, val port: Int, val sessionSecret: LocalSessionSecret)

    private var server: ApplicationEngine? = null
    private val uidSockets = ConcurrentHashMap<String, WebSocketSession>()
    private val sessionStore = LocalSignalingSessionStore()
    private val presenceStore = LocalPresenceStore()

    private val _info = MutableStateFlow<ServerInfo?>(null)
    val info: StateFlow<ServerInfo?> = _info.asStateFlow()

    fun start(sessionId: String, port: Int = 0): ServerInfo {
        stop()
        val secret = LocalSessionSecret.generate(sessionId)
        val host = findLanAddress()
        val assignedPort = if (port > 0) port else 38472

        server = embeddedServer(CIO, host = "0.0.0.0", port = assignedPort) {
            install(WebSockets)
            routing {
                webSocket("/ws") {
                    val session = this
                    val handler = LocalSignalingConnectionHandler(
                        secret = secret,
                        store = sessionStore,
                        presenceStore = presenceStore,
                        sendToUid = { uid, envelope -> sendToUid(uid, envelope) },
                        broadcastAll = { envelope -> broadcastAll(envelope) },
                        onAuthenticated = { uid -> uidSockets[uid] = session },
                        closeConnection = {
                            scope.launch {
                                session.close(CloseReason(CloseReason.Codes.NORMAL, "closed"))
                            }
                        },
                    )
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) handler.handle(frame.readText())
                        }
                    } finally {
                        handler.onDisconnect()
                        uidSockets.entries.removeIf { it.value == session }
                    }
                }
            }
        }.start(wait = false)

        val info = ServerInfo(host, assignedPort, secret)
        _info.value = info
        return info
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        uidSockets.clear()
        sessionStore.clear()
        presenceStore.clear()
        _info.value = null
    }

    private fun broadcastAll(envelope: com.nblaisot.voxcrew.signaling.SignalingEnvelope) {
        uidSockets.keys.forEach { sendToUid(it, envelope) }
    }

    private fun sendToUid(uid: String, envelope: com.nblaisot.voxcrew.signaling.SignalingEnvelope) {
        scope.launch {
            val json = com.nblaisot.voxcrew.signaling.signalingJson.encodeToString(
                com.nblaisot.voxcrew.signaling.SignalingEnvelope.serializer(),
                envelope,
            )
            uidSockets[uid]?.send(Frame.Text(json))
        }
    }

    private fun findLanAddress(): String {
        NetworkInterface.getNetworkInterfaces().toList().forEach { ni ->
            if (!ni.isUp || ni.isLoopback) return@forEach
            ni.inetAddresses.toList().forEach { addr ->
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    return addr.hostAddress ?: "127.0.0.1"
                }
            }
        }
        return "127.0.0.1"
    }
}

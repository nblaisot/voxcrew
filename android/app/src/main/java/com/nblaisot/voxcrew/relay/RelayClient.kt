package com.nblaisot.voxcrew.relay

import android.util.Log
import com.nblaisot.voxcrew.lanlink.LanFrame
import com.nblaisot.voxcrew.lanlink.LanProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Device-wide WSS to the optional Mac Mini relay. Per-peer audio rides on
 * [RelayFrameTransport] after a UUID dial bridge (no peer IPs on the Mini).
 */
class RelayClient(
    private val scope: CoroutineScope,
    private val localUid: String,
    private val displayNameProvider: () -> String,
    private val settingsRepository: RelaySettingsRepository,
) {
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var settingsWatchJob: Job? = null
    private val sessions = ConcurrentHashMap<String, RelayFrameTransport>()
    private val dialWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val mutex = Mutex()

    /** Fired when the Mini reports an inbound dial (peer wants a bridge to us). */
    @Volatile var onInboundDial: ((peerUid: String) -> Unit)? = null

    /** Fired when a binary frame arrives for a peer we have not attached yet. */
    @Volatile var onUnattachedFrame: ((peerUid: String, frame: LanFrame) -> Unit)? = null

    fun start() {
        if (settingsWatchJob?.isActive == true) return
        settingsWatchJob = scope.launch {
            settingsRepository.settings.collect { reconnect() }
        }
    }

    fun stop() {
        settingsWatchJob?.cancel()
        settingsWatchJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        sessions.values.forEach { it.detach() }
        sessions.clear()
        webSocket?.close(1000, "stop")
        webSocket = null
        _ready.value = false
    }

    fun isReady(): Boolean = _ready.value

    fun transportFor(peerUid: String): RelayFrameTransport =
        sessions.getOrPut(peerUid) { RelayFrameTransport(peerUid, localUid, this) }

    fun closeSession(peerUid: String) {
        sessions.remove(peerUid)?.detach()
    }

    /**
     * Request a bridge to [peerUid]. Returns false on dial_fail / timeout / not ready.
     * Does not complete PeerLink Hello — caller starts [RelayFrameTransport.startHandshake].
     */
    suspend fun dial(peerUid: String): Boolean {
        if (peerUid == localUid || !_ready.value) return false
        val existing = dialWaiters[peerUid]
        if (existing != null) return existing.await()
        val deferred = CompletableDeferred<Boolean>()
        dialWaiters[peerUid] = deferred
        sendControl(JSONObject().put("type", "dial").put("peerUid", peerUid))
        val result = withTimeoutOrNull(DIAL_TIMEOUT_MS) { deferred.await() } ?: false
        dialWaiters.remove(peerUid, deferred)
        if (!deferred.isCompleted) deferred.complete(false)
        return result
    }

    internal fun sendControl(json: JSONObject) {
        webSocket?.send(json.toString())
    }

    internal fun sendBinary(peerUid: String, frame: LanFrame) {
        val payload = encodeEnvelope(peerUid, LanProtocol.encodeFrame(frame))
        webSocket?.send(payload.toByteString())
    }

    private suspend fun reconnect() = mutex.withLock {
        webSocket?.close(1000, "reconfig")
        webSocket = null
        _ready.value = false
        val settings = settingsRepository.current()
        if (!settings.isConfigured) return@withLock

        val client = buildHttpClient(settings)
        val request = Request.Builder().url(settings.url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                maybeStoreCert(settings, response)
                val hello = JSONObject()
                    .put("type", "hello")
                    .put("uid", localUid)
                    .put("displayName", displayNameProvider())
                    .put("secret", settings.secret)
                webSocket.send(hello.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleControl(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleBinary(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _ready.value = false
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "relay failure: ${t.message}")
                _ready.value = false
                scheduleReconnect()
            }
        })
    }

    private fun handleControl(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("type")) {
            "hello_ok" -> {
                _ready.value = true
                Log.i(TAG, "relay hello_ok")
            }
            "hello_reject" -> {
                _ready.value = false
                Log.w(TAG, "relay hello_reject ${msg.optString("reason")}")
            }
            "dial_ok" -> {
                val peerUid = msg.optString("peerUid")
                dialWaiters[peerUid]?.complete(true)
                if (peerUid.isNotBlank() && peerUid != localUid) {
                    transportFor(peerUid)
                    onInboundDial?.invoke(peerUid)
                }
            }
            "dial_fail" -> dialWaiters[msg.optString("peerUid")]?.complete(false)
            "peer_gone" -> {
                val peerUid = msg.optString("peerUid")
                sessions[peerUid]?.onPeerGone()
            }
        }
    }

    private fun handleBinary(bytes: ByteArray) {
        val env = decodeEnvelope(bytes) ?: return
        val frame = LanProtocol.decodeFrame(env.frame) ?: return
        val transport = sessions[env.peerUid]
        if (transport != null) {
            transport.onRemoteFrame(frame)
        } else {
            onUnattachedFrame?.invoke(env.peerUid, frame)
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(RECONNECT_MS)
            if (isActive) reconnect()
        }
    }

    private fun buildHttpClient(settings: RelaySettings): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)

        if (settings.url.startsWith("wss", ignoreCase = true)) {
            val expected = settings.certSha256?.lowercase()?.replace(":", "")
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                    val cert = chain.firstOrNull() ?: throw CertificateException("empty chain")
                    val actual = sha256Hex(cert.encoded)
                    if (expected.isNullOrBlank()) return // TOFU: accept once
                    if (!actual.equals(expected, ignoreCase = true)) {
                        throw CertificateException("relay cert fingerprint mismatch")
                    }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(trustManager), java.security.SecureRandom())
            builder.sslSocketFactory(ctx.socketFactory, trustManager)
            builder.hostnameVerifier(HostnameVerifier { _, _ -> true })
        }
        return builder.build()
    }

    private fun maybeStoreCert(settings: RelaySettings, response: Response) {
        if (!settings.certSha256.isNullOrBlank()) return
        val cert = response.handshake?.peerCertificates?.firstOrNull() as? X509Certificate ?: return
        val hex = sha256Hex(cert.encoded)
        settingsRepository.storeCertFingerprint(hex)
        Log.i(TAG, "relay TOFU certSha256=$hex")
    }

    companion object {
        private const val TAG = "RelayClient"
        private const val RECONNECT_MS = 3_000L
        private const val DIAL_TIMEOUT_MS = 5_000L

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun encodeEnvelope(peerUid: String, frame: ByteArray): ByteArray {
            val uid = peerUid.toByteArray(Charsets.UTF_8)
            require(uid.size <= 0xffff)
            return ByteArray(2 + uid.size + frame.size).also { out ->
                out[0] = ((uid.size ushr 8) and 0xff).toByte()
                out[1] = (uid.size and 0xff).toByte()
                System.arraycopy(uid, 0, out, 2, uid.size)
                System.arraycopy(frame, 0, out, 2 + uid.size, frame.size)
            }
        }

        fun decodeEnvelope(bytes: ByteArray): Envelope? {
            if (bytes.size < 2) return null
            val uidLen = ((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff)
            if (bytes.size < 2 + uidLen) return null
            val uid = String(bytes, 2, uidLen, Charsets.UTF_8)
            val frame = bytes.copyOfRange(2 + uidLen, bytes.size)
            return Envelope(uid, frame)
        }

        data class Envelope(val peerUid: String, val frame: ByteArray)
    }
}

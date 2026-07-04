package com.nblaisot.voxcrew.connectivity.local

import com.nblaisot.voxcrew.signaling.SignalingEnvelope
import com.nblaisot.voxcrew.signaling.SignalingMessageTypes
import com.nblaisot.voxcrew.signaling.jsonPayload
import com.nblaisot.voxcrew.signaling.signalingJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

internal class LocalSignalingSessionStore {
    data class Session(val id: String, val participants: MutableSet<String> = CopyOnWriteArraySet())

    private val sessions = ConcurrentHashMap<String, Session>()

    fun create(creatorId: String, sessionId: String? = null): Session {
        val id = sessionId ?: UUID.randomUUID().toString()
        val session = Session(id, CopyOnWriteArraySet(listOf(creatorId)))
        sessions[id] = session
        return session
    }

    fun join(sessionId: String, uid: String): Session? {
        val session = sessions[sessionId] ?: return null
        session.participants.add(uid)
        return session
    }

    fun get(sessionId: String): Session? = sessions[sessionId]

    fun leave(sessionId: String, uid: String) {
        sessions[sessionId]?.participants?.remove(uid)
        if (sessions[sessionId]?.participants?.isEmpty() == true) sessions.remove(sessionId)
    }

    fun removeFromAll(uid: String): List<String> {
        val affected = mutableListOf<String>()
        sessions.forEach { (id, session) ->
            if (session.participants.remove(uid)) {
                affected.add(id)
                if (session.participants.isEmpty()) sessions.remove(id)
            }
        }
        return affected
    }

    fun clear() = sessions.clear()
}

internal class LocalSignalingConnectionHandler(
    private val secret: LocalSessionSecret,
    private val store: LocalSignalingSessionStore = LocalSignalingSessionStore(),
    private val presenceStore: LocalPresenceStore,
    private val sendToUid: (String, SignalingEnvelope) -> Unit,
    private val broadcastAll: (SignalingEnvelope) -> Unit,
    private val onAuthenticated: (String) -> Unit,
    private val closeConnection: () -> Unit,
) {
    private var uid: String? = null
    private var authenticated = false

    fun handle(text: String) {
        val msg = runCatching {
            signalingJson.decodeFromString(SignalingEnvelope.serializer(), text)
        }.getOrElse { return sendError("INVALID_MESSAGE", "Invalid JSON") }

        if (!authenticated) {
            if (msg.type != SignalingMessageTypes.AUTHENTICATE) {
                return sendError("UNAUTHORIZED", "Authenticate first")
            }
            handleAuth(msg)
            return
        }

        when (msg.type) {
            SignalingMessageTypes.CREATE_SESSION -> handleCreate(msg)
            SignalingMessageTypes.JOIN_SESSION -> handleJoin(msg)
            SignalingMessageTypes.LEAVE_SESSION -> handleLeave(msg)
            SignalingMessageTypes.OFFER, SignalingMessageTypes.ANSWER, SignalingMessageTypes.ICE_CANDIDATE -> relay(msg)
            SignalingMessageTypes.PING -> sendToSelf(
                SignalingEnvelope(
                    type = SignalingMessageTypes.PONG,
                    requestId = msg.requestId,
                    payload = msg.payload,
                ),
            )
            SignalingMessageTypes.PRESENCE_REGISTER -> handlePresenceRegister(msg)
            SignalingMessageTypes.PRESENCE_HEARTBEAT -> handlePresenceHeartbeat(msg)
            else -> sendError("INVALID_MESSAGE", "Unsupported type", msg.requestId)
        }
    }

    fun onDisconnect() {
        val id = uid ?: return
        val offline = presenceStore.markOffline(id)
        if (offline != null) {
            broadcastAll(
                SignalingEnvelope(
                    type = SignalingMessageTypes.PRESENCE_OFFLINE,
                    senderId = id,
                    payload = jsonPayload(
                        "uid" to id,
                        "email" to offline.email,
                        "lastSeenMs" to offline.lastSeenMs.toString(),
                    ),
                ),
            )
        }
        store.removeFromAll(id).forEach { sessionId ->
            broadcast(sessionId, SignalingEnvelope(
                type = SignalingMessageTypes.PARTICIPANT_LEFT,
                sessionId = sessionId,
                senderId = id,
                payload = jsonPayload("participantId" to id, "reason" to "disconnect"),
            ), exclude = id)
        }
    }

    private fun handleAuth(msg: SignalingEnvelope) {
        val authKind = msg.payload["authKind"]?.jsonPrimitive?.content ?: "local"
        if (authKind != "local") {
            return authError("TOKEN_INVALID", "Local auth required")
        }
        val sessionId = msg.payload["sessionId"]?.jsonPrimitive?.content
        val token = msg.payload["localToken"]?.jsonPrimitive?.content
        val participantId = msg.payload["participantId"]?.jsonPrimitive?.content
        if (sessionId == null || token == null || participantId == null ||
            !LocalSessionSecret.validate(secret, sessionId, token)
        ) {
            return authError("TOKEN_INVALID", "Invalid local credentials")
        }
        uid = participantId
        authenticated = true
        onAuthenticated(participantId)
        val email = msg.payload["email"]?.jsonPrimitive?.content ?: participantId
        presenceStore.register(participantId, email, "local_lan")
        sendToSelf(
            SignalingEnvelope(
                type = SignalingMessageTypes.AUTHENTICATED,
                requestId = msg.requestId,
                senderId = participantId,
                payload = jsonPayload("uid" to participantId, "email" to email),
            ),
        )
        sendToSelf(buildPresenceSnapshot())
        broadcastPresenceUpdated(participantId, email, "local_lan")
    }

    private fun handleCreate(msg: SignalingEnvelope) {
        val id = uid ?: return
        val requestedId = msg.payload["sessionId"]?.jsonPrimitive?.content
        val session = store.create(id, requestedId)
        sendToSelf(
            SignalingEnvelope(
                type = SignalingMessageTypes.SESSION_CREATED,
                requestId = msg.requestId,
                sessionId = session.id,
                senderId = id,
                payload = buildJsonObject {
                    put("sessionId", JsonPrimitive(session.id))
                    put("participants", JsonPrimitive(session.participants.joinToString { "\"$it\"" }.let { "[$it]" }))
                },
            ),
        )
    }

    private fun handleJoin(msg: SignalingEnvelope) {
        val id = uid ?: return
        val sessionId = msg.payload["sessionId"]?.jsonPrimitive?.content ?: return sendError("INVALID_MESSAGE", "sessionId required", msg.requestId)
        val session = store.join(sessionId, id) ?: return sendError("SESSION_NOT_FOUND", "Not found", msg.requestId)
        sendToSelf(
            SignalingEnvelope(
                type = SignalingMessageTypes.SESSION_JOINED,
                requestId = msg.requestId,
                sessionId = sessionId,
                senderId = id,
                payload = buildJsonObject {
                    put("sessionId", JsonPrimitive(sessionId))
                    put("participants", JsonPrimitive(session.participants.joinToString { "\"$it\"" }.let { "[$it]" }))
                },
            ),
        )
        session.participants.filter { it != id }.forEach { other ->
            sendToUid(other, SignalingEnvelope(
                type = SignalingMessageTypes.PARTICIPANT_JOINED,
                sessionId = sessionId,
                senderId = id,
                payload = jsonPayload("participantId" to id),
            ))
        }
    }

    private fun handleLeave(msg: SignalingEnvelope) {
        val id = uid ?: return
        val sessionId = msg.sessionId ?: return
        store.leave(sessionId, id)
        broadcast(sessionId, SignalingEnvelope(
            type = SignalingMessageTypes.PARTICIPANT_LEFT,
            sessionId = sessionId,
            senderId = id,
            payload = jsonPayload("participantId" to id, "reason" to "leave"),
        ), exclude = id)
    }

    private fun relay(msg: SignalingEnvelope) {
        val recipient = msg.recipientId ?: return sendError("INVALID_MESSAGE", "recipientId required", msg.requestId)
        val sessionId = msg.sessionId ?: return sendError("NOT_IN_SESSION", "session required", msg.requestId)
        val session = store.get(sessionId) ?: return sendError("SESSION_NOT_FOUND", "Not found", msg.requestId)
        val from = uid ?: return
        if (!session.participants.contains(from) || !session.participants.contains(recipient)) {
            return sendError("UNAUTHORIZED", "Not in session", msg.requestId)
        }
        sendToUid(recipient, msg.copy(senderId = from))
    }

    private fun broadcast(sessionId: String, envelope: SignalingEnvelope, exclude: String) {
        store.get(sessionId)?.participants?.filter { it != exclude }?.forEach { sendToUid(it, envelope) }
    }

    private fun sendToSelf(envelope: SignalingEnvelope) {
        uid?.let { sendToUid(it, envelope) }
    }

    private fun sendError(code: String, message: String, requestId: String? = null) {
        sendToSelf(
            SignalingEnvelope(
                type = SignalingMessageTypes.ERROR,
                requestId = requestId,
                payload = jsonPayload("code" to code, "message" to message),
            ),
        )
    }

    private fun authError(code: String, message: String) {
        sendToSelf(
            SignalingEnvelope(
                type = SignalingMessageTypes.AUTHENTICATION_ERROR,
                payload = jsonPayload("code" to code, "message" to message),
            ),
        )
        closeConnection()
    }

    private fun handlePresenceRegister(msg: SignalingEnvelope) {
        val id = uid ?: return
        val email = msg.payload["email"]?.jsonPrimitive?.content ?: id
        val hint = msg.payload["transportHint"]?.jsonPrimitive?.content ?: "local_lan"
        presenceStore.register(id, email, hint)
        sendToSelf(buildPresenceSnapshot())
        broadcastPresenceUpdated(id, email, hint)
    }

    private fun handlePresenceHeartbeat(msg: SignalingEnvelope) {
        val id = uid ?: return
        val hint = msg.payload["transportHint"]?.jsonPrimitive?.content ?: "local_lan"
        val entry = presenceStore.heartbeat(id, hint)
            ?: presenceStore.register(id, id, hint)
        broadcastPresenceUpdated(id, entry.email, hint)
    }

    private fun buildPresenceSnapshot(): SignalingEnvelope {
        val members = buildJsonArray {
            presenceStore.snapshot().forEach { entry ->
                add(
                    buildJsonObject {
                        put("uid", entry.uid)
                        put("email", entry.email)
                        put("transportHint", entry.transportHint)
                        put("online", entry.online)
                        put("lastSeenMs", entry.lastSeenMs)
                    },
                )
            }
        }
        return SignalingEnvelope(
            type = SignalingMessageTypes.PRESENCE_SNAPSHOT,
            payload = buildJsonObject { put("members", members) },
        )
    }

    private fun broadcastPresenceUpdated(uid: String, email: String, hint: String) {
        broadcastAll(
            SignalingEnvelope(
                type = SignalingMessageTypes.PRESENCE_UPDATED,
                senderId = uid,
                payload = jsonPayload(
                    "uid" to uid,
                    "email" to email,
                    "transportHint" to hint,
                    "online" to "true",
                    "lastSeenMs" to System.currentTimeMillis().toString(),
                ),
            ),
        )
    }
}

import type { WebSocket } from "@fastify/websocket";
import type { FastifyBaseLogger } from "fastify";
import { AuthError, type TokenVerifier } from "../auth/index.js";
import { truncateUid } from "../logger.js";
import {
  authenticatePayloadSchema,
  buildServerMessage,
  clientMessageSchema,
  createSessionPayloadSchema,
  icePayloadSchema,
  joinSessionPayloadSchema,
  pingPayloadSchema,
  presenceHeartbeatPayloadSchema,
  presenceRegisterPayloadSchema,
  sdpPayloadSchema,
  type ClientMessage,
  type Envelope,
} from "../protocol/messages.js";
import { PresenceStore, OFFLINE_GRACE_MS } from "../presence/store.js";
import { SessionStore } from "../session/store.js";

const AUTH_TIMEOUT_MS = 10_000;
const MAX_MESSAGES_PER_MINUTE = 120;
const MAX_MESSAGE_BYTES = 64 * 1024;
const WS_PING_INTERVAL_MS = 30_000;
const MAX_MISSED_PONGS = 2;
const REPLACED_SOCKET_CODE = 4002;
const SESSION_DISCONNECT_GRACE_MS = 15_000;

interface ClientState {
  socket: WebSocket;
  uid?: string;
  sessionId?: string;
  authenticated: boolean;
  messageCount: number;
  windowStart: number;
  missedPongs: number;
  pingTimer?: ReturnType<typeof setInterval>;
}

export interface WsHandlerDeps {
  tokenVerifier: TokenVerifier;
  sessionStore: SessionStore;
  presenceStore: PresenceStore;
  logger: FastifyBaseLogger;
}

export class WsConnectionHandler {
  private readonly clients = new Map<WebSocket, ClientState>();
  private readonly uidToSocket = new Map<string, WebSocket>();
  private readonly sessionRemovalTimers = new Map<string, ReturnType<typeof setTimeout>>();

  constructor(private readonly deps: WsHandlerDeps) {}

  handleConnection(socket: WebSocket): void {
    const state: ClientState = {
      socket,
      authenticated: false,
      messageCount: 0,
      windowStart: Date.now(),
      missedPongs: 0,
    };
    this.clients.set(socket, state);
    this.startPingLoop(socket, state);

    const authTimer = setTimeout(() => {
      if (!state.authenticated) {
        this.send(socket, buildServerMessage("authentication_error", {
          code: "TIMEOUT",
          message: "Authentication timeout",
        }));
        socket.close(4001, "auth timeout");
      }
    }, AUTH_TIMEOUT_MS);

    socket.on("message", (raw) => {
      void this.onMessage(socket, state, raw, () => clearTimeout(authTimer));
    });

    socket.on("pong", () => {
      state.missedPongs = 0;
    });

    socket.on("close", () => {
      clearTimeout(authTimer);
      this.stopPingLoop(state);
      void this.onDisconnect(socket, state);
    });
  }

  private startPingLoop(socket: WebSocket, state: ClientState): void {
    state.pingTimer = setInterval(() => {
      if (socket.readyState !== 1) return;
      state.missedPongs += 1;
      if (state.missedPongs > MAX_MISSED_PONGS) {
        this.deps.logger.warn({ uid: state.uid ? truncateUid(state.uid) : undefined }, "terminating unresponsive socket");
        socket.terminate();
        this.stopPingLoop(state);
        return;
      }
      socket.ping();
    }, WS_PING_INTERVAL_MS);
  }

  private stopPingLoop(state: ClientState): void {
    if (state.pingTimer) {
      clearInterval(state.pingTimer);
      state.pingTimer = undefined;
    }
  }

  private async onMessage(
    socket: WebSocket,
    state: ClientState,
    raw: unknown,
    onAuthenticated: () => void,
  ): Promise<void> {
    if (!this.checkRateLimit(state)) {
      this.sendError(socket, state, "RATE_LIMITED", "Too many messages");
      return;
    }

    const text = this.rawToString(raw);
    if (text.length > MAX_MESSAGE_BYTES) {
      this.sendError(socket, state, "INVALID_MESSAGE", "Message too large");
      return;
    }

    let parsed: unknown;
    try {
      parsed = JSON.parse(text);
    } catch {
      this.sendError(socket, state, "INVALID_MESSAGE", "Invalid JSON");
      return;
    }

    const envelopeResult = clientMessageSchema.safeParse(parsed);
    if (!envelopeResult.success) {
      this.sendError(socket, state, "INVALID_MESSAGE", "Invalid message envelope");
      return;
    }

    const message = envelopeResult.data;

    if (message.type === "authenticate") {
      await this.handleAuthenticate(socket, state, message, onAuthenticated);
      return;
    }

    if (!state.authenticated || !state.uid) {
      this.sendError(socket, state, "UNAUTHORIZED", "Not authenticated", message.requestId);
      return;
    }

    switch (message.type) {
      case "create_session":
        this.handleCreateSession(socket, state, message);
        break;
      case "join_session":
        this.handleJoinSession(socket, state, message);
        break;
      case "leave_session":
        this.handleLeaveSession(socket, state, message);
        break;
      case "offer":
      case "answer":
      case "ice_candidate":
        this.handleWebRtc(socket, state, message);
        break;
      case "ping":
        this.handlePing(socket, state, message);
        break;
      case "presence_register":
        this.handlePresenceRegister(socket, state, message);
        break;
      case "presence_heartbeat":
        this.handlePresenceHeartbeat(socket, state, message);
        break;
      default:
        this.sendError(socket, state, "INVALID_MESSAGE", `Unsupported type: ${message.type}`, message.requestId);
    }
  }

  private async handleAuthenticate(
    socket: WebSocket,
    state: ClientState,
    message: ClientMessage,
    onAuthenticated: () => void,
  ): Promise<void> {
    if (state.authenticated) {
      this.sendError(socket, state, "INVALID_MESSAGE", "Already authenticated", message.requestId);
      return;
    }

    const payload = authenticatePayloadSchema.safeParse(message.payload);
    if (!payload.success) {
      this.send(socket, buildServerMessage("authentication_error", {
        code: "INVALID_MESSAGE",
        message: "Invalid authenticate payload",
      }, { requestId: message.requestId }));
      return;
    }

    try {
      const user = await this.deps.tokenVerifier.verify(payload.data.token);
      state.authenticated = true;
      state.uid = user.uid;
      onAuthenticated();

      const existing = this.uidToSocket.get(user.uid);
      if (existing && existing !== socket && existing.readyState === 1) {
        existing.close(REPLACED_SOCKET_CODE, "replaced by new connection");
      }
      this.uidToSocket.set(user.uid, socket);
      this.cancelSessionRemoval(user.uid);

      this.deps.logger.info({ uid: truncateUid(user.uid) }, "client authenticated");

      this.send(socket, buildServerMessage("authenticated", {
        uid: user.uid,
        displayName: user.displayName ?? null,
        email: user.email ?? null,
      }, {
        requestId: message.requestId,
        senderId: user.uid,
      }));

      const email = user.email ?? user.uid;
      this.deps.presenceStore.register(user.uid, email, "cloud");
      this.send(socket, this.buildPresenceSnapshot());
      this.broadcastPresence(buildServerMessage("presence_updated", {
        uid: user.uid,
        email,
        transportHint: "cloud",
        online: true,
        lastSeenMs: Date.now(),
      }, { senderId: user.uid }));
    } catch (error) {
      const code = error instanceof AuthError ? error.code : "TOKEN_INVALID";
      this.send(socket, buildServerMessage("authentication_error", {
        code,
        message: error instanceof Error ? error.message : "Authentication failed",
      }, { requestId: message.requestId }));
      socket.close(4003, "auth failed");
    }
  }

  private handleCreateSession(socket: WebSocket, state: ClientState, message: ClientMessage): void {
    const payload = createSessionPayloadSchema.safeParse(message.payload);
    if (!payload.success || !state.uid) {
      this.sendError(socket, state, "INVALID_MESSAGE", "Invalid create_session payload", message.requestId);
      return;
    }

    const session = this.deps.sessionStore.create(state.uid, payload.data.name, payload.data.sessionId);
    state.sessionId = session.id;
    this.cancelSessionRemoval(state.uid);

    this.send(socket, buildServerMessage("session_created", {
      sessionId: session.id,
      participants: this.deps.sessionStore.participantIds(session),
    }, {
      requestId: message.requestId,
      sessionId: session.id,
      senderId: state.uid,
    }));
  }

  private handleJoinSession(socket: WebSocket, state: ClientState, message: ClientMessage): void {
    const payload = joinSessionPayloadSchema.safeParse(message.payload);
    if (!payload.success || !state.uid) {
      this.sendError(socket, state, "INVALID_MESSAGE", "Invalid join_session payload", message.requestId);
      return;
    }

    const session = this.deps.sessionStore.join(payload.data.sessionId, state.uid);
    if (!session) {
      this.sendError(socket, state, "SESSION_NOT_FOUND", "Session not found", message.requestId);
      return;
    }

    state.sessionId = session.id;
    this.cancelSessionRemoval(state.uid);
    const participants = this.deps.sessionStore.participantIds(session);

    this.send(socket, buildServerMessage("session_joined", {
      sessionId: session.id,
      participants,
    }, {
      requestId: message.requestId,
      sessionId: session.id,
      senderId: state.uid,
    }));

    for (const participantId of participants) {
      if (participantId === state.uid) continue;
      this.sendToUid(participantId, buildServerMessage("participant_joined", {
        participantId: state.uid,
      }, {
        sessionId: session.id,
        senderId: state.uid,
      }));
    }
  }

  private handleLeaveSession(socket: WebSocket, state: ClientState, message: ClientMessage): void {
    if (!state.uid || !state.sessionId) {
      this.sendError(socket, state, "NOT_IN_SESSION", "Not in a session", message.requestId);
      return;
    }

    const sessionId = state.sessionId;
    const uid = state.uid;
    this.deps.sessionStore.leave(sessionId, uid);
    state.sessionId = undefined;

    this.broadcastToSession(sessionId, buildServerMessage("participant_left", {
      participantId: uid,
      reason: "leave",
    }, { sessionId, senderId: uid }), uid);
  }

  private handleWebRtc(socket: WebSocket, state: ClientState, message: ClientMessage): void {
    if (!state.uid || !state.sessionId) {
      this.sendError(socket, state, "NOT_IN_SESSION", "Not in a session", message.requestId);
      return;
    }

    if (!message.recipientId) {
      this.sendError(socket, state, "INVALID_MESSAGE", "recipientId required", message.requestId);
      return;
    }

    const session = this.deps.sessionStore.get(state.sessionId);
    if (!session?.participants.has(state.uid) || !session.participants.has(message.recipientId)) {
      this.sendError(socket, state, "UNAUTHORIZED", "Recipient not in session", message.requestId);
      return;
    }

    const schema =
      message.type === "ice_candidate" ? icePayloadSchema : sdpPayloadSchema;
    const payload = schema.safeParse(message.payload);
    if (!payload.success) {
      this.sendError(socket, state, "INVALID_MESSAGE", "Invalid WebRTC payload", message.requestId);
      return;
    }

    const delivered = this.sendToUid(message.recipientId, {
      version: 1,
      type: message.type,
      requestId: message.requestId,
      sessionId: state.sessionId,
      senderId: state.uid,
      recipientId: message.recipientId,
      payload: message.payload,
    });

    if (!delivered) {
      this.sendError(socket, state, "RECIPIENT_OFFLINE", "Recipient not connected", message.requestId);
    }
  }

  private handlePresenceRegister(socket: WebSocket, state: ClientState, message: ClientMessage): void {
    if (!state.uid) return;
    const payload = presenceRegisterPayloadSchema.safeParse(message.payload);
    if (!payload.success) {
      this.sendError(socket, state, "INVALID_MESSAGE", "Invalid presence_register payload", message.requestId);
      return;
    }
    const email = payload.data.email ?? state.uid;
    const hint = payload.data.transportHint ?? "cloud";
    this.deps.presenceStore.register(state.uid, email, hint);
    this.send(socket, this.buildPresenceSnapshot());
    this.broadcastPresence(buildServerMessage("presence_updated", {
      uid: state.uid,
      email,
      transportHint: hint,
      online: true,
      lastSeenMs: Date.now(),
    }, { senderId: state.uid }));
  }

  private handlePresenceHeartbeat(socket: WebSocket, state: ClientState, message: ClientMessage): void {
    if (!state.uid) return;
    const payload = presenceHeartbeatPayloadSchema.safeParse(message.payload);
    if (!payload.success) {
      this.sendError(socket, state, "INVALID_MESSAGE", "Invalid presence_heartbeat payload", message.requestId);
      return;
    }
    const entry = this.deps.presenceStore.heartbeat(state.uid, payload.data.transportHint);
    if (!entry) {
      this.deps.presenceStore.register(state.uid, state.uid, payload.data.transportHint);
    }
    this.broadcastPresence(buildServerMessage("presence_updated", {
      uid: state.uid,
      email: this.deps.presenceStore.get(state.uid)?.email ?? state.uid,
      transportHint: payload.data.transportHint,
      online: true,
      lastSeenMs: Date.now(),
    }, { senderId: state.uid }));
  }

  private buildPresenceSnapshot(): Envelope {
    const members = this.deps.presenceStore.snapshot().map((m) => ({
      uid: m.uid,
      email: m.email,
      transportHint: m.transportHint,
      online: m.online,
      lastSeenMs: m.lastSeenMs,
    }));
    return buildServerMessage("presence_snapshot", { members });
  }

  private broadcastPresence(message: Envelope): void {
    for (const client of this.clients.values()) {
      if (!client.authenticated) continue;
      this.send(client.socket, message);
    }
  }

  private handlePing(socket: WebSocket, state: ClientState, message: ClientMessage): void {
    const payload = pingPayloadSchema.safeParse(message.payload);
    this.send(socket, buildServerMessage("pong", {
      timestamp: payload.success ? payload.data.timestamp ?? Date.now() : Date.now(),
    }, { requestId: message.requestId, senderId: state.uid }));
  }

  private async onDisconnect(socket: WebSocket, state: ClientState): Promise<void> {
    this.clients.delete(socket);
    if (!state.uid) return;

    if (this.uidToSocket.get(state.uid) !== socket) {
      this.deps.logger.info({ uid: truncateUid(state.uid) }, "stale socket disconnected");
      return;
    }
    this.uidToSocket.delete(state.uid);

    this.scheduleSessionRemoval(state.uid);

    this.deps.presenceStore.scheduleMarkOffline(state.uid, OFFLINE_GRACE_MS, (offline) => {
      if (this.uidToSocket.has(state.uid!)) return;
      this.broadcastPresence(buildServerMessage("presence_offline", {
        uid: state.uid,
        email: offline.email,
        lastSeenMs: offline.lastSeenMs,
      }, { senderId: state.uid }));
    });

    this.deps.logger.info({ uid: truncateUid(state.uid) }, "client disconnected");
  }

  private cancelSessionRemoval(uid: string): void {
    const timer = this.sessionRemovalTimers.get(uid);
    if (timer) {
      clearTimeout(timer);
      this.sessionRemovalTimers.delete(uid);
    }
  }

  private scheduleSessionRemoval(uid: string): void {
    this.cancelSessionRemoval(uid);
    const timer = setTimeout(() => {
      this.sessionRemovalTimers.delete(uid);
      if (this.uidToSocket.has(uid)) return;
      const affected = this.deps.sessionStore.removeParticipantFromAll(uid);
      for (const sessionId of affected) {
        this.broadcastToSession(sessionId, buildServerMessage("participant_left", {
          participantId: uid,
          reason: "disconnect",
        }, { sessionId, senderId: uid }), uid);
      }
    }, SESSION_DISCONNECT_GRACE_MS);
    this.sessionRemovalTimers.set(uid, timer);
  }

  private sendToUid(uid: string, message: Envelope): boolean {
    const socket = this.uidToSocket.get(uid);
    if (!socket || socket.readyState !== 1) return false;
    this.send(socket, message);
    return true;
  }

  private broadcastToSession(sessionId: string, message: Envelope, excludeUid?: string): void {
    const session = this.deps.sessionStore.get(sessionId);
    if (!session) return;

    for (const participantId of session.participants.keys()) {
      if (participantId === excludeUid) continue;
      this.sendToUid(participantId, message);
    }
  }

  private send(socket: WebSocket, message: Envelope): void {
    if (socket.readyState === 1) {
      socket.send(JSON.stringify(message));
    }
  }

  private sendError(
    socket: WebSocket,
    state: ClientState,
    code: string,
    msg: string,
    requestId?: string,
  ): void {
    this.send(socket, buildServerMessage("error", { code, message: msg }, {
      requestId,
      senderId: state.uid,
      sessionId: state.sessionId,
    }));
  }

  private checkRateLimit(state: ClientState): boolean {
    const now = Date.now();
    if (now - state.windowStart > 60_000) {
      state.windowStart = now;
      state.messageCount = 0;
    }
    state.messageCount += 1;
    return state.messageCount <= MAX_MESSAGES_PER_MINUTE;
  }

  private rawToString(raw: unknown): string {
    if (typeof raw === "string") return raw;
    if (Buffer.isBuffer(raw)) return raw.toString("utf8");
    if (raw instanceof ArrayBuffer) return Buffer.from(raw).toString("utf8");
    return String(raw);
  }

  getConnectionCount(): number {
    return this.clients.size;
  }

  getUidSocket(uid: string): WebSocket | undefined {
    return this.uidToSocket.get(uid);
  }
}

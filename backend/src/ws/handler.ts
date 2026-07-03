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
  sdpPayloadSchema,
  type ClientMessage,
  type Envelope,
} from "../protocol/messages.js";
import { SessionStore } from "../session/store.js";

const AUTH_TIMEOUT_MS = 10_000;
const MAX_MESSAGES_PER_MINUTE = 120;
const MAX_MESSAGE_BYTES = 64 * 1024;

interface ClientState {
  socket: WebSocket;
  uid?: string;
  sessionId?: string;
  authenticated: boolean;
  messageCount: number;
  windowStart: number;
}

export interface WsHandlerDeps {
  tokenVerifier: TokenVerifier;
  sessionStore: SessionStore;
  logger: FastifyBaseLogger;
}

export class WsConnectionHandler {
  private readonly clients = new Map<WebSocket, ClientState>();

  constructor(private readonly deps: WsHandlerDeps) {}

  handleConnection(socket: WebSocket): void {
    const state: ClientState = {
      socket,
      authenticated: false,
      messageCount: 0,
      windowStart: Date.now(),
    };
    this.clients.set(socket, state);

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

    socket.on("close", () => {
      clearTimeout(authTimer);
      void this.onDisconnect(socket, state);
    });
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

      this.deps.logger.info({ uid: truncateUid(user.uid) }, "client authenticated");

      this.send(socket, buildServerMessage("authenticated", {
        uid: user.uid,
        displayName: user.displayName ?? null,
      }, {
        requestId: message.requestId,
        senderId: user.uid,
      }));
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

    const session = this.deps.sessionStore.create(state.uid, payload.data.name);
    state.sessionId = session.id;

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

    this.sendToUid(message.recipientId, {
      version: 1,
      type: message.type,
      requestId: message.requestId,
      sessionId: state.sessionId,
      senderId: state.uid,
      recipientId: message.recipientId,
      payload: message.payload,
    });
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

    const affected = this.deps.sessionStore.removeParticipantFromAll(state.uid);
    for (const sessionId of affected) {
      this.broadcastToSession(sessionId, buildServerMessage("participant_left", {
        participantId: state.uid,
        reason: "disconnect",
      }, { sessionId, senderId: state.uid }), state.uid);
    }

    this.deps.logger.info({ uid: truncateUid(state.uid) }, "client disconnected");
  }

  private sendToUid(uid: string, message: Envelope): void {
    for (const state of this.clients.values()) {
      if (state.uid === uid && state.socket.readyState === 1) {
        this.send(state.socket, message);
      }
    }
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
}

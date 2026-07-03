import { randomUUID } from "node:crypto";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import WebSocket from "ws";
import type { FastifyInstance } from "fastify";
import { buildServer } from "../src/server.js";
import { loadConfig } from "../src/config.js";
import type { Envelope } from "../src/protocol/messages.js";

function waitForMessage(ws: WebSocket, type: string, timeoutMs = 5000): Promise<Envelope> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`timeout waiting for ${type}`)), timeoutMs);
    const handler = (data: WebSocket.RawData): void => {
      const msg = JSON.parse(data.toString()) as Envelope;
      if (msg.type === type) {
        clearTimeout(timer);
        ws.off("message", handler);
        resolve(msg);
      }
    };
    ws.on("message", handler);
  });
}

function send(ws: WebSocket, type: string, payload: Record<string, unknown>, extra: Partial<Envelope> = {}): void {
  ws.send(JSON.stringify({
    version: 1,
    type,
    requestId: randomUUID(),
    payload,
    ...extra,
  }));
}

describe("WebSocket signaling", () => {
  let app: FastifyInstance;
  let baseUrl: string;

  beforeAll(async () => {
    const config = loadConfig({
      NODE_ENV: "test",
      PORT: "8080",
      GCP_PROJECT_ID: "test",
      ALLOWED_FIREBASE_UIDS: "user-a,user-b",
      LOG_LEVEL: "error",
    });
    app = await buildServer({ config, tokenVerifierMode: "fake" });
    await app.listen({ port: 0, host: "127.0.0.1" });
    const address = app.server.address();
    if (!address || typeof address === "string") throw new Error("no address");
    baseUrl = `ws://127.0.0.1:${address.port}/ws`;
  });

  afterAll(async () => {
    await app.close();
  });

  it("authenticates allowed user", async () => {
    const ws = new WebSocket(baseUrl);
    await new Promise<void>((resolve) => ws.on("open", () => resolve()));
    send(ws, "authenticate", { token: "token-user-a" });
    const msg = await waitForMessage(ws, "authenticated");
    expect(msg.payload.uid).toBe("user-a");
    ws.close();
  });

  it("rejects disallowed token", async () => {
    const ws = new WebSocket(baseUrl);
    await new Promise<void>((resolve) => ws.on("open", () => resolve()));
    send(ws, "authenticate", { token: "invalid" });
    const msg = await waitForMessage(ws, "authentication_error");
    expect(msg.payload.code).toBe("TOKEN_INVALID");
    ws.close();
  });

  it("creates session, joins, and relays offer", async () => {
    const wsA = new WebSocket(baseUrl);
    const wsB = new WebSocket(baseUrl);
    await Promise.all([
      new Promise<void>((r) => wsA.on("open", () => r())),
      new Promise<void>((r) => wsB.on("open", () => r())),
    ]);

    send(wsA, "authenticate", { token: "token-user-a" });
    send(wsB, "authenticate", { token: "token-user-b" });
    await waitForMessage(wsA, "authenticated");
    await waitForMessage(wsB, "authenticated");

    send(wsA, "create_session", { name: "test" });
    const created = await waitForMessage(wsA, "session_created");
    const sessionId = created.payload.sessionId as string;

    send(wsB, "join_session", { sessionId });
    await waitForMessage(wsB, "session_joined");
    await waitForMessage(wsA, "participant_joined");

    const offerPromise = waitForMessage(wsB, "offer");
    send(wsA, "offer", { sdp: "v=0", sdpType: "offer" }, {
      sessionId,
      recipientId: "user-b",
    });
    const offer = await offerPromise;
    expect(offer.senderId).toBe("user-a");

    send(wsA, "ping", { timestamp: Date.now() });
    const pong = await waitForMessage(wsA, "pong");
    expect(pong.type).toBe("pong");

    wsA.close();
    wsB.close();
  });

  it("blocks offer to user outside session", async () => {
    const wsA = new WebSocket(baseUrl);
    await new Promise<void>((resolve) => wsA.on("open", () => resolve()));
    send(wsA, "authenticate", { token: "token-user-a" });
    await waitForMessage(wsA, "authenticated");

    send(wsA, "create_session", {});
    const created = await waitForMessage(wsA, "session_created");
    const sessionId = created.payload.sessionId as string;

    send(wsA, "offer", { sdp: "v=0", sdpType: "offer" }, {
      sessionId,
      recipientId: "user-b",
    });
    const err = await waitForMessage(wsA, "error");
    expect(err.payload.code).toBe("UNAUTHORIZED");
    wsA.close();
  });
});

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

  it("creates session and joins participants", async () => {
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

    send(wsA, "ping", { timestamp: Date.now() });
    const pong = await waitForMessage(wsA, "pong");
    expect(pong.type).toBe("pong");

    wsA.close();
    wsB.close();
  });

  it("accepts presence_register without email", async () => {
    const ws = new WebSocket(baseUrl);
    await new Promise<void>((r) => ws.on("open", () => r()));
    send(ws, "authenticate", { token: "token-user-a" });
    await waitForMessage(ws, "authenticated");

    send(ws, "presence_register", { transportHint: "cloud" });
    const snapshot = await waitForMessage(ws, "presence_snapshot");
    expect(snapshot.type).toBe("presence_snapshot");
    ws.close();
  });

  it("broadcasts presence on authenticate and heartbeat", async () => {
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

    send(wsA, "presence_register", { email: "a@test.com", transportHint: "cloud" });
    await waitForMessage(wsA, "presence_snapshot");
    await waitForMessage(wsB, "presence_updated");

    send(wsA, "presence_heartbeat", { transportHint: "cloud" });
    const updated = await waitForMessage(wsB, "presence_updated");
    expect(updated.payload.uid).toBe("user-a");
    expect(updated.payload.online).toBe(true);

    wsA.close();
    const offline = await waitForMessage(wsB, "presence_offline", 20_000);
    expect(offline.payload.uid).toBe("user-a");
    wsB.close();
  }, 25_000);


  it("forwards p2p_connect_request and p2p_endpoints uid-to-uid without a session", async () => {
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

    const connectRequestPromise = waitForMessage(wsB, "p2p_connect_request");
    send(wsA, "p2p_connect_request", {}, { recipientId: "user-b" });
    const connectRequest = await connectRequestPromise;
    expect(connectRequest.senderId).toBe("user-a");

    const endpointsPromise = waitForMessage(wsA, "p2p_endpoints");
    send(wsB, "p2p_endpoints", {
      publicHost: "203.0.113.5",
      publicPort: 40000,
      localHost: "192.168.1.10",
      localPort: 50000,
    }, { recipientId: "user-a" });
    const endpoints = await endpointsPromise;
    expect(endpoints.senderId).toBe("user-b");
    expect(endpoints.payload.publicPort).toBe(40000);

    wsA.close();
    wsB.close();
  });

  it("errors p2p_connect_request when the recipient is not connected", async () => {
    const wsA = new WebSocket(baseUrl);
    await new Promise<void>((r) => wsA.on("open", () => r()));
    send(wsA, "authenticate", { token: "token-user-a" });
    await waitForMessage(wsA, "authenticated");

    send(wsA, "p2p_connect_request", {}, { recipientId: "user-b" });
    const err = await waitForMessage(wsA, "error");
    expect(err.payload.code).toBe("RECIPIENT_OFFLINE");
    wsA.close();
  });

  it("relays opaque binary frames uid-to-uid without touching JSON rate limit", async () => {
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

    const recipientId = Buffer.from("user-b", "utf8");
    const payload = Buffer.from([1, 2, 3, 4, 5]);
    const frame = Buffer.concat([Buffer.from([recipientId.length]), recipientId, payload]);

    const received = new Promise<Buffer>((resolve) => {
      wsB.once("message", (data, isBinary) => {
        expect(isBinary).toBe(true);
        resolve(data as Buffer);
      });
    });
    wsA.send(frame);
    const relayed = await received;
    expect(relayed.subarray(1 + recipientId.length).equals(payload)).toBe(true);

    wsA.close();
    wsB.close();
  });

  it("replaces duplicate uid socket without ejecting rejoined session", async () => {
    const wsA1 = new WebSocket(baseUrl);
    const wsA2 = new WebSocket(baseUrl);
    const wsB = new WebSocket(baseUrl);
    await Promise.all([
      new Promise<void>((r) => wsA1.on("open", () => r())),
      new Promise<void>((r) => wsA2.on("open", () => r())),
      new Promise<void>((r) => wsB.on("open", () => r())),
    ]);

    send(wsA1, "authenticate", { token: "token-user-a" });
    send(wsB, "authenticate", { token: "token-user-b" });
    await waitForMessage(wsA1, "authenticated");
    await waitForMessage(wsB, "authenticated");

    send(wsA1, "create_session", { name: "race" });
    const created = await waitForMessage(wsA1, "session_created");
    const sessionId = created.payload.sessionId as string;
    send(wsB, "join_session", { sessionId });
    await waitForMessage(wsB, "session_joined");

    send(wsA2, "authenticate", { token: "token-user-a" });
    await waitForMessage(wsA2, "authenticated");
    send(wsA2, "join_session", { sessionId });
    await waitForMessage(wsA2, "session_joined");

    wsA1.close();
    await new Promise((r) => setTimeout(r, 200));

    send(wsA2, "ping", { timestamp: Date.now() });
    const pong = await waitForMessage(wsA2, "pong");
    expect(pong.type).toBe("pong");

    wsA2.close();
    wsB.close();
  });
});

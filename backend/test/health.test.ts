import { afterAll, beforeAll, describe, expect, it } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildServer } from "../src/server.js";
import { loadConfig } from "../src/config.js";

describe("HTTP endpoints", () => {
  let app: FastifyInstance;

  beforeAll(async () => {
    const config = loadConfig({
      NODE_ENV: "test",
      PORT: "8080",
      GCP_PROJECT_ID: "test",
      ALLOWED_FIREBASE_UIDS: "user-a,user-b",
      LOG_LEVEL: "error",
    });
    app = await buildServer({ config, tokenVerifierMode: "fake" });
    await app.ready();
  });

  afterAll(async () => {
    await app.close();
  });

  it("GET /health", async () => {
    const res = await app.inject({ method: "GET", url: "/health" });
    expect(res.statusCode).toBe(200);
    const body = res.json() as { status: string; service: string };
    expect(body.status).toBe("ok");
    expect(body.service).toBe("voxcrew-signaling");
  });

  it("GET /ready", async () => {
    const res = await app.inject({ method: "GET", url: "/ready" });
    expect(res.statusCode).toBe(200);
    const body = res.json() as { status: string; connections: number };
    expect(body.status).toBe("ready");
    expect(body.connections).toBe(0);
  });
});

import websocket from "@fastify/websocket";
import Fastify, { type FastifyInstance } from "fastify";
import type { AppConfig } from "./config.js";
import { createLoggerOptions } from "./logger.js";
import { createTokenVerifier } from "./auth/index.js";
import { SessionStore } from "./session/store.js";
import { WsConnectionHandler } from "./ws/handler.js";

export interface BuildServerOptions {
  config: AppConfig;
  tokenVerifierMode?: "fake" | "firebase";
}

export async function buildServer(options: BuildServerOptions): Promise<FastifyInstance> {
  const { config } = options;

  const app = Fastify({
    logger: createLoggerOptions(config),
    disableRequestLogging: config.NODE_ENV === "test",
  });

  const sessionStore = new SessionStore();
  const tokenVerifier = createTokenVerifier(config, options.tokenVerifierMode);
  const wsHandler = new WsConnectionHandler({ tokenVerifier, sessionStore, logger: app.log });

  app.get("/health", async () => ({
    status: "ok",
    service: "voxcrew-signaling",
    timestamp: new Date().toISOString(),
  }));

  app.get("/ready", async () => ({
    status: "ready",
    connections: wsHandler.getConnectionCount(),
  }));

  await app.register(websocket);

  app.get("/ws", { websocket: true }, (socket) => {
    wsHandler.handleConnection(socket);
  });

  app.addHook("onClose", async () => {
    app.log.info("server shutting down");
  });

  return app;
}

export async function startServer(config: AppConfig): Promise<FastifyInstance> {
  const app = await buildServer({ config });
  await app.listen({ port: config.PORT, host: "0.0.0.0" });
  app.log.info({ port: config.PORT }, "signaling server listening");
  return app;
}

import pino from "pino";
import type { AppConfig } from "./config.js";

export function createLoggerOptions(config: AppConfig): pino.LoggerOptions {
  return {
    level: config.LOG_LEVEL,
    formatters: {
      level: (label) => ({ level: label }),
    },
    timestamp: pino.stdTimeFunctions.isoTime,
    base: {
      service: "voxcrew-signaling",
      env: config.NODE_ENV,
    },
  };
}

export function createLogger(config: AppConfig): pino.Logger {
  return pino(createLoggerOptions(config));
}

export function truncateUid(uid: string): string {
  return uid.length > 8 ? `${uid.slice(0, 8)}…` : uid;
}

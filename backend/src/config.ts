import { z } from "zod";

const logLevelSchema = z.enum(["fatal", "error", "warn", "info", "debug", "trace"]);

export const configSchema = z.object({
  NODE_ENV: z.enum(["development", "test", "production"]).default("development"),
  PORT: z.coerce.number().int().min(1).max(65535).default(8080),
  GCP_PROJECT_ID: z.string().min(1).default("voxcrew-private"),
  ALLOWED_FIREBASE_UIDS: z
    .string()
    .default("")
    .transform((value) =>
      value
        .split(",")
        .map((uid) => uid.trim())
        .filter(Boolean),
    ),
  LOG_LEVEL: logLevelSchema.default("info"),
});

export type AppConfig = z.infer<typeof configSchema>;

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  return configSchema.parse(env);
}

import { z } from "zod";

export const PROTOCOL_VERSION = 1;

export const messageTypeSchema = z.enum([
  "authenticate",
  "authenticated",
  "authentication_error",
  "create_session",
  "session_created",
  "join_session",
  "session_joined",
  "participant_joined",
  "participant_left",
  "offer",
  "answer",
  "ice_candidate",
  "leave_session",
  "ping",
  "pong",
  "error",
]);

export type MessageType = z.infer<typeof messageTypeSchema>;

export const envelopeSchema = z.object({
  version: z.literal(PROTOCOL_VERSION),
  type: messageTypeSchema,
  requestId: z.string().uuid().optional(),
  sessionId: z.string().min(1).max(128).optional(),
  senderId: z.string().min(1).max(128).optional(),
  recipientId: z.string().min(1).max(128).optional(),
  payload: z.record(z.unknown()).default({}),
});

export type Envelope = z.infer<typeof envelopeSchema>;

export const clientMessageSchema = envelopeSchema.extend({
  requestId: z.string().uuid(),
});

export type ClientMessage = z.infer<typeof clientMessageSchema>;

export const authenticatePayloadSchema = z.object({
  token: z.string().min(1),
});

export const createSessionPayloadSchema = z.object({
  name: z.string().max(128).optional(),
});

export const joinSessionPayloadSchema = z.object({
  sessionId: z.string().min(1).max(128),
});

export const sdpPayloadSchema = z.object({
  sdp: z.string().min(1).max(65536),
  sdpType: z.enum(["offer", "answer"]),
});

export const icePayloadSchema = z.object({
  candidate: z.string().min(1).max(8192),
  sdpMid: z.string().nullable().optional(),
  sdpMLineIndex: z.number().int().nullable().optional(),
});

export const pingPayloadSchema = z.object({
  timestamp: z.number().optional(),
});

export type ErrorCode =
  | "TOKEN_INVALID"
  | "TOKEN_EXPIRED"
  | "NOT_ALLOWED"
  | "TIMEOUT"
  | "INVALID_MESSAGE"
  | "NOT_IN_SESSION"
  | "SESSION_NOT_FOUND"
  | "UNAUTHORIZED"
  | "RATE_LIMITED"
  | "INTERNAL";

export function buildServerMessage(
  type: MessageType,
  payload: Record<string, unknown>,
  fields: Partial<Envelope> = {},
): Envelope {
  return {
    version: PROTOCOL_VERSION,
    type,
    payload,
    ...fields,
  };
}

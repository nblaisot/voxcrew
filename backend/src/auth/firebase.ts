import { getApps, initializeApp, type App } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import type { AppConfig } from "../config.js";
import { AuthError, type TokenVerifier, type VerifiedUser } from "./types.js";

export class FirebaseTokenVerifier implements TokenVerifier {
  private readonly allowedUids: Set<string>;
  private readonly app: App;

  constructor(config: AppConfig) {
    this.allowedUids = new Set(config.ALLOWED_FIREBASE_UIDS);
    this.app = getApps().length > 0 ? getApps()[0]! : initializeApp({ projectId: config.GCP_PROJECT_ID });
  }

  async verify(token: string): Promise<VerifiedUser> {
    try {
      const decoded = await getAuth(this.app).verifyIdToken(token);
      if (!this.allowedUids.has(decoded.uid)) {
        throw new AuthError("User not allowed", "NOT_ALLOWED");
      }
      return {
        uid: decoded.uid,
        email: decoded.email,
        displayName: decoded.name,
      };
    } catch (error) {
      if (error instanceof AuthError) {
        throw error;
      }
      const message = error instanceof Error ? error.message : "Token verification failed";
      if (message.toLowerCase().includes("expired")) {
        throw new AuthError("Token expired", "TOKEN_EXPIRED");
      }
      throw new AuthError("Invalid token", "TOKEN_INVALID");
    }
  }
}

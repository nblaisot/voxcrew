import type { AppConfig } from "../config.js";
import { AuthError, type TokenVerifier, type VerifiedUser } from "./types.js";

export class FakeTokenVerifier implements TokenVerifier {
  constructor(
    private readonly allowedUids: Set<string>,
    private readonly tokenMap: Map<string, VerifiedUser> = new Map(),
  ) {}

  static fromConfig(config: AppConfig): FakeTokenVerifier {
    const allowed = new Set(config.ALLOWED_FIREBASE_UIDS);
    const tokenMap = new Map<string, VerifiedUser>();
    for (const uid of allowed) {
      tokenMap.set(`token-${uid}`, { uid, email: `${uid}@test.local` });
    }
    return new FakeTokenVerifier(allowed, tokenMap);
  }

  async verify(token: string): Promise<VerifiedUser> {
    if (!token || token === "invalid") {
      throw new AuthError("Invalid token", "TOKEN_INVALID");
    }
    if (token === "expired") {
      throw new AuthError("Token expired", "TOKEN_EXPIRED");
    }
    const user = this.tokenMap.get(token);
    if (!user) {
      throw new AuthError("Invalid token", "TOKEN_INVALID");
    }
    if (!this.allowedUids.has(user.uid)) {
      throw new AuthError("User not allowed", "NOT_ALLOWED");
    }
    return user;
  }
}

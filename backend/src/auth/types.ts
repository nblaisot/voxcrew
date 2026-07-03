export interface VerifiedUser {
  uid: string;
  email?: string;
  displayName?: string;
}

export interface TokenVerifier {
  verify(token: string): Promise<VerifiedUser>;
}

export class AuthError extends Error {
  constructor(
    message: string,
    readonly code: "TOKEN_INVALID" | "TOKEN_EXPIRED" | "NOT_ALLOWED",
  ) {
    super(message);
    this.name = "AuthError";
  }
}

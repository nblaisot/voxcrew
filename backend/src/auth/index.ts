import type { AppConfig } from "../config.js";
import { FakeTokenVerifier } from "./fake.js";
import { FirebaseTokenVerifier } from "./firebase.js";
import type { TokenVerifier } from "./types.js";

export function createTokenVerifier(config: AppConfig, mode?: "fake" | "firebase"): TokenVerifier {
  const useFake = mode === "fake" || config.NODE_ENV === "test" || config.NODE_ENV === "development";
  if (useFake && mode !== "firebase") {
    return FakeTokenVerifier.fromConfig(config);
  }
  return new FirebaseTokenVerifier(config);
}

export { AuthError, type TokenVerifier, type VerifiedUser } from "./types.js";
export { FakeTokenVerifier } from "./fake.js";

import { describe, expect, it } from "vitest";
import { loadConfig } from "../src/config.js";

describe("loadConfig", () => {
  it("parses defaults", () => {
    const config = loadConfig({
      NODE_ENV: "test",
      PORT: "9090",
      GCP_PROJECT_ID: "test-project",
      ALLOWED_FIREBASE_UIDS: "uid-a, uid-b",
      LOG_LEVEL: "warn",
    });
    expect(config.PORT).toBe(9090);
    expect(config.ALLOWED_FIREBASE_UIDS).toEqual(["uid-a", "uid-b"]);
    expect(config.LOG_LEVEL).toBe("warn");
  });

  it("rejects invalid port", () => {
    expect(() => loadConfig({ PORT: "0" })).toThrow();
  });
});

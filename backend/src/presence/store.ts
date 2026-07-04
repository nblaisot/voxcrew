export type TransportHint = "local_lan" | "cloud" | "none";

export interface PresenceEntry {
  uid: string;
  email: string;
  transportHint: TransportHint;
  online: boolean;
  lastSeenMs: number;
}

const STALE_MS = 30_000;
export const OFFLINE_GRACE_MS = 15_000;

export class PresenceStore {
  private readonly entries = new Map<string, PresenceEntry>();
  private readonly offlineTimers = new Map<string, ReturnType<typeof setTimeout>>();

  register(uid: string, email: string, transportHint: TransportHint = "cloud"): PresenceEntry {
    this.cancelPendingOffline(uid);
    const now = Date.now();
    const entry: PresenceEntry = {
      uid,
      email,
      transportHint,
      online: true,
      lastSeenMs: now,
    };
    this.entries.set(uid, entry);
    return entry;
  }

  heartbeat(uid: string, transportHint: TransportHint): PresenceEntry | undefined {
    this.cancelPendingOffline(uid);
    const entry = this.entries.get(uid);
    if (!entry) return undefined;
    entry.lastSeenMs = Date.now();
    entry.transportHint = transportHint;
    entry.online = true;
    return entry;
  }

  markOffline(uid: string): PresenceEntry | undefined {
    const entry = this.entries.get(uid);
    if (!entry) return undefined;
    entry.online = false;
    entry.lastSeenMs = Date.now();
    return entry;
  }

  scheduleMarkOffline(
    uid: string,
    delayMs: number,
    onReady: (entry: PresenceEntry) => void,
  ): void {
    this.cancelPendingOffline(uid);
    const timer = setTimeout(() => {
      this.offlineTimers.delete(uid);
      const entry = this.markOffline(uid);
      if (entry) onReady(entry);
    }, delayMs);
    this.offlineTimers.set(uid, timer);
  }

  cancelPendingOffline(uid: string): void {
    const timer = this.offlineTimers.get(uid);
    if (timer) {
      clearTimeout(timer);
      this.offlineTimers.delete(uid);
    }
  }

  remove(uid: string): void {
    this.cancelPendingOffline(uid);
    this.entries.delete(uid);
  }

  get(uid: string): PresenceEntry | undefined {
    return this.entries.get(uid);
  }

  snapshot(nowMs: number = Date.now()): PresenceEntry[] {
    for (const entry of this.entries.values()) {
      if (entry.online && nowMs - entry.lastSeenMs > STALE_MS) {
        entry.online = false;
      }
    }
    return [...this.entries.values()];
  }
}

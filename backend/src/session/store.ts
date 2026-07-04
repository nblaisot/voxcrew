import { randomUUID } from "node:crypto";

export interface SessionParticipant {
  uid: string;
  joinedAt: number;
}

export interface Session {
  id: string;
  name?: string;
  createdBy: string;
  createdAt: number;
  participants: Map<string, SessionParticipant>;
}

export class SessionStore {
  private readonly sessions = new Map<string, Session>();

  create(creatorUid: string, name?: string, explicitId?: string): Session {
    if (explicitId) {
      const existing = this.sessions.get(explicitId);
      if (existing) {
        if (!existing.participants.has(creatorUid)) {
          existing.participants.set(creatorUid, { uid: creatorUid, joinedAt: Date.now() });
        }
        return existing;
      }
    }
    const id = explicitId ?? randomUUID();
    const session: Session = {
      id,
      name,
      createdBy: creatorUid,
      createdAt: Date.now(),
      participants: new Map([[creatorUid, { uid: creatorUid, joinedAt: Date.now() }]]),
    };
    this.sessions.set(id, session);
    return session;
  }

  get(sessionId: string): Session | undefined {
    return this.sessions.get(sessionId);
  }

  join(sessionId: string, uid: string): Session | undefined {
    const session = this.sessions.get(sessionId);
    if (!session) {
      return undefined;
    }
    if (!session.participants.has(uid)) {
      session.participants.set(uid, { uid, joinedAt: Date.now() });
    }
    return session;
  }

  leave(sessionId: string, uid: string): Session | undefined {
    const session = this.sessions.get(sessionId);
    if (!session) {
      return undefined;
    }
    session.participants.delete(uid);
    if (session.participants.size === 0) {
      this.sessions.delete(sessionId);
    }
    return session;
  }

  removeParticipantFromAll(uid: string): string[] {
    const affected: string[] = [];
    for (const [sessionId, session] of this.sessions) {
      if (session.participants.has(uid)) {
        session.participants.delete(uid);
        affected.push(sessionId);
        if (session.participants.size === 0) {
          this.sessions.delete(sessionId);
        }
      }
    }
    return affected;
  }

  participantIds(session: Session): string[] {
    return [...session.participants.keys()];
  }
}

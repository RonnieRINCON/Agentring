import type { Db } from "./db/database.js";
import { newId } from "./ids.js";
import { EPHEMERAL_TYPES, type Event, type EventDraft } from "./protocol.js";
import type { Principal } from "./auth.js";

export type Audience = "all" | "agent" | "devices";

export interface Subscriber {
  principal: Principal;
  send(event: Event): void;
}

type Listener = (event: Event) => void;

/**
 * Append-only event log with a global `seq`, plus live fan-out to WebSocket
 * subscribers. Persisted events are visible to every principal; ephemeral
 * events are delivered live only, to the given audience.
 */
export class EventLog {
  private readonly subscribers = new Set<Subscriber>();
  private readonly listeners = new Set<Listener>();

  constructor(private readonly db: Db) {}

  emit(draft: EventDraft, audience: Audience = "all"): Event {
    const event = { ...draft, id: newId("evt"), ts: Date.now() } as Event;
    if (!EPHEMERAL_TYPES.has(event.type)) {
      const res = this.db
        .prepare("INSERT INTO events (id, type, ts, body) VALUES (?, ?, ?, ?)")
        .run(event.id, event.type, event.ts, "");
      event.seq = Number(res.lastInsertRowid);
      this.db.prepare("UPDATE events SET body = ? WHERE seq = ?").run(JSON.stringify(event), event.seq);
    }
    for (const s of this.subscribers) {
      if (audience === "all" || (audience === "agent") === (s.principal.kind === "agent")) {
        try {
          s.send(event);
        } catch {
          // a broken socket is cleaned up by its close handler
        }
      }
    }
    for (const l of this.listeners) l(event);
    return event;
  }

  /** Called synchronously for every emitted event (used by the push dispatcher). */
  onEvent(listener: Listener): void {
    this.listeners.add(listener);
  }

  list(afterSeq: number, limit: number): { events: Event[]; lastSeq: number; hasMore: boolean } {
    const rows = this.db
      .prepare("SELECT body FROM events WHERE seq > ? ORDER BY seq LIMIT ?")
      .all(afterSeq, limit + 1) as Array<{ body: string }>;
    const hasMore = rows.length > limit;
    const events = rows.slice(0, limit).map((r) => JSON.parse(r.body) as Event);
    return { events, lastSeq: this.lastSeq(), hasMore };
  }

  lastSeq(): number {
    const row = this.db.prepare("SELECT MAX(seq) AS s FROM events").get() as { s: number | null };
    return row.s ?? 0;
  }

  subscribe(sub: Subscriber): () => void {
    this.subscribers.add(sub);
    return () => this.subscribers.delete(sub);
  }
}

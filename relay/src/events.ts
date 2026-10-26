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
/** Decides whether a principal may see an event (e.g. a targeted call's events). */
export type Visibility = (event: Event, principal: Principal) => boolean;

/**
 * Append-only event log with a global `seq`, plus live fan-out to WebSocket
 * subscribers. Persisted events are visible to every principal; ephemeral
 * events are delivered live only, to the given audience.
 */
export class EventLog {
  private readonly subscribers = new Set<Subscriber>();
  private readonly listeners = new Set<Listener>();
  private visible: Visibility = () => true;

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
      if ((audience === "all" || (audience === "agent") === (s.principal.kind === "agent")) && this.visible(event, s.principal)) {
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

  /** Restricts which events each principal gets, live and on replay. */
  setVisibility(visible: Visibility): void {
    this.visible = visible;
  }

  /**
   * A page of persisted events after `afterSeq`. With a principal, events it may
   * not see are left out (the page may then hold fewer than `limit`).
   */
  list(
    afterSeq: number,
    limit: number,
    principal?: Principal,
  ): { events: Event[]; lastSeq: number; hasMore: boolean; nextAfterSeq: number } {
    const rows = this.db
      .prepare("SELECT body FROM events WHERE seq > ? ORDER BY seq LIMIT ?")
      .all(afterSeq, limit + 1) as Array<{ body: string }>;
    const hasMore = rows.length > limit;
    const page = rows.slice(0, limit).map((r) => JSON.parse(r.body) as Event);
    const nextAfterSeq = page.at(-1)?.seq ?? afterSeq;
    const events = principal ? page.filter((e) => this.visible(e, principal)) : page;
    return { events, lastSeq: this.lastSeq(), hasMore, nextAfterSeq };
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

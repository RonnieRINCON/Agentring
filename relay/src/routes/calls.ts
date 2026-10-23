import type { FastifyInstance } from "fastify";
import type { Principal } from "../auth.js";
import { type Ctx, principal, requireAuth } from "../context.js";
import { ApiError, forbidden, notFound } from "../errors.js";
import { newId } from "../ids.js";
import type { Schemas } from "../protocol.js";

type Call = Schemas["Call"];
type CallState = Schemas["CallState"];
type Direction = Schemas["CallDirection"];

interface CallRow {
  call_id: string;
  direction: Direction;
  state: CallState;
  reason: string;
  priority: "normal" | "urgent";
  agent_name: string;
  created_at: number;
  expires_at: number;
  answered_at: number | null;
  ended_at: number | null;
  ended_by: Schemas["Actor"] | null;
  end_reason: Schemas["EndReason"] | null;
  device_id: string | null;
}

export function callFromRow(r: CallRow): Call {
  return {
    call_id: r.call_id,
    direction: r.direction,
    state: r.state,
    ...(r.reason ? { reason: r.reason } : {}),
    priority: r.priority,
    agent_name: r.agent_name,
    created_at: r.created_at,
    ...(r.answered_at ? { answered_at: r.answered_at } : {}),
    ...(r.ended_at ? { ended_at: r.ended_at } : {}),
    ...(r.ended_by ? { ended_by: r.ended_by } : {}),
    ...(r.end_reason ? { end_reason: r.end_reason } : {}),
    ...(r.answered_at && r.ended_at ? { duration_ms: r.ended_at - r.answered_at } : {}),
    ...(r.device_id ? { device_id: r.device_id } : {}),
  };
}

export const roomName = (callId: string) => callId;
const identityOf = (p: Principal) => (p.kind === "agent" ? "agent" : `device_${p.deviceId}`);
/** Which side placed the call. */
const callerKind = (d: Direction) => (d === "agent_to_user" ? "agent" : "device");
const invalidState = (state: CallState) => new ApiError(409, "invalid_state", `Call is ${state}`);

/**
 * Call state machine, ring timer and LiveKit room lifecycle.
 *
 *   ringing ─accept→ active ─end→ ended
 *   ringing ─decline→ declined | ─timeout→ missed | ─caller end→ canceled
 */
export class Calls {
  private readonly timers = new Map<string, NodeJS.Timeout>();
  /** Devices that already reported `ringing`, so the event is emitted once per device. */
  private readonly ringingDevices = new Map<string, Set<string>>();

  constructor(private readonly ctx: Ctx) {}

  get(callId: string): CallRow | undefined {
    return this.ctx.db.prepare("SELECT * FROM calls WHERE call_id = ?").get(callId) as unknown as CallRow | undefined;
  }

  open(): CallRow | undefined {
    return this.ctx.db
      .prepare("SELECT * FROM calls WHERE state IN ('ringing','active') ORDER BY call_id DESC LIMIT 1")
      .get() as unknown as CallRow | undefined;
  }

  /** After a restart: time out calls that expired while we were down and re-arm the others. */
  resume(): void {
    const ringing = this.ctx.db.prepare("SELECT * FROM calls WHERE state = 'ringing'").all() as unknown as CallRow[];
    for (const c of ringing) this.armTimer(c);
  }

  stop(): void {
    for (const t of this.timers.values()) clearTimeout(t);
    this.timers.clear();
  }

  create(p: Principal, body: Schemas["CreateCallRequest"]): CallRow {
    const direction: Direction = p.kind === "agent" ? "agent_to_user" : "user_to_agent";
    if (direction === "agent_to_user") {
      if (!body.reason) throw new ApiError(400, "invalid_request", "reason is required");
      const n = this.ctx.db.prepare("SELECT COUNT(*) AS n FROM devices WHERE revoked_at IS NULL").get() as { n: number };
      if (n.n === 0) throw new ApiError(409, "no_devices", "No paired devices");
    } else if (!this.ctx.hub.isAgentOnline()) {
      throw new ApiError(503, "agent_offline", "The agent is not connected");
    }
    if (this.open()) throw new ApiError(409, "busy", "Another call is ringing or active");

    const now = Date.now();
    const row: CallRow = {
      call_id: newId("call"),
      direction,
      state: "ringing",
      reason: body.reason ?? "",
      priority: body.priority ?? "normal",
      agent_name: (p.kind === "agent" ? body.agent_name : undefined) ?? this.ctx.cfg.agentName,
      created_at: now,
      expires_at: now + this.ctx.cfg.ringTimeoutS * 1000,
      answered_at: null,
      ended_at: null,
      ended_by: null,
      end_reason: null,
      device_id: p.kind === "device" ? p.deviceId : null,
    };
    this.ctx.db
      .prepare(
        `INSERT INTO calls (call_id, direction, state, reason, priority, agent_name, created_at, expires_at, device_id)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      )
      .run(row.call_id, direction, "ringing", row.reason, row.priority, row.agent_name, now, row.expires_at, row.device_id);
    this.ctx.events.emit({
      type: "call.invite",
      call_id: row.call_id,
      direction,
      agent_name: row.agent_name,
      reason: row.reason,
      priority: row.priority,
      expires_at: row.expires_at,
    });
    this.armTimer(row);
    return row;
  }

  ringing(callId: string, deviceId: string): void {
    const c = this.get(callId);
    if (!c) throw notFound("Call");
    if (c.state !== "ringing" || c.direction !== "agent_to_user") return;
    let set = this.ringingDevices.get(callId);
    if (!set) this.ringingDevices.set(callId, (set = new Set()));
    if (set.has(deviceId)) return;
    set.add(deviceId);
    this.ctx.events.emit({ type: "call.ringing", call_id: callId, device_id: deviceId });
  }

  accept(callId: string, p: Principal): CallRow {
    const c = this.mustBeCallee(callId, p);
    const now = Date.now();
    const deviceId = p.kind === "device" ? p.deviceId : c.device_id;
    const res = this.ctx.db
      .prepare("UPDATE calls SET state = 'active', answered_at = ?, device_id = ? WHERE call_id = ? AND state = 'ringing'")
      .run(now, deviceId, callId);
    if (res.changes !== 1) throw invalidState(this.get(callId)!.state);
    this.settle(callId);
    this.ctx.events.emit({
      type: "call.accept",
      call_id: callId,
      by: p.kind === "agent" ? "agent" : "user",
      ...(p.kind === "device" ? { device_id: p.deviceId } : {}),
    });
    return this.get(callId)!;
  }

  decline(callId: string, p: Principal): CallRow {
    this.mustBeCallee(callId, p);
    const res = this.ctx.db
      .prepare("UPDATE calls SET state = 'declined', ended_at = ?, ended_by = ? WHERE call_id = ? AND state = 'ringing'")
      .run(Date.now(), p.kind === "agent" ? "agent" : "user", callId);
    if (res.changes !== 1) throw invalidState(this.get(callId)!.state);
    this.settle(callId);
    this.ctx.events.emit({
      type: "call.decline",
      call_id: callId,
      by: p.kind === "agent" ? "agent" : "user",
      ...(p.kind === "device" ? { device_id: p.deviceId } : {}),
    });
    this.closeRoom(callId);
    return this.get(callId)!;
  }

  /** Hang up. While ringing only the caller may (→ canceled). Idempotent on terminal states. */
  end(callId: string, p: Principal): CallRow {
    const c = this.get(callId);
    if (!c) throw notFound("Call");
    if (p.kind === "device" && c.device_id && c.device_id !== p.deviceId && c.state === "active") {
      throw forbidden("Another device is on this call");
    }
    const by = p.kind === "agent" ? "agent" : "user";
    if (c.state === "ringing") {
      if (p.kind !== callerKind(c.direction)) throw forbidden("Decline a ringing call instead of ending it");
      return this.finish(callId, "ringing", "canceled", by, "canceled") ?? this.get(callId)!;
    }
    if (c.state === "active") return this.finish(callId, "active", "ended", by, "hangup") ?? this.get(callId)!;
    return c;
  }

  /** The room went away (LiveKit webhook) or the callee left it. */
  roomClosed(callId: string): void {
    this.finish(callId, "active", "ended", "relay", "room_closed");
  }

  private finish(
    callId: string,
    from: "ringing" | "active",
    to: CallState,
    by: Schemas["Actor"],
    reason: Schemas["EndReason"],
  ): CallRow | undefined {
    const now = Date.now();
    const res = this.ctx.db
      .prepare("UPDATE calls SET state = ?, ended_at = ?, ended_by = ?, end_reason = ? WHERE call_id = ? AND state = ?")
      .run(to, now, by, reason, callId, from);
    if (res.changes !== 1) return undefined;
    this.settle(callId);
    const row = this.get(callId)!;
    this.ctx.events.emit({
      type: "call.end",
      call_id: callId,
      by,
      reason,
      ...(row.answered_at ? { duration_ms: now - row.answered_at } : {}),
    });
    this.closeRoom(callId);
    return row;
  }

  private mustBeCallee(callId: string, p: Principal): CallRow {
    const c = this.get(callId);
    if (!c) throw notFound("Call");
    if (p.kind === callerKind(c.direction)) throw forbidden("The caller cannot answer its own call");
    return c;
  }

  private armTimer(c: CallRow): void {
    const t = setTimeout(() => this.timeout(c.call_id), Math.max(0, c.expires_at - Date.now()));
    t.unref();
    this.timers.set(c.call_id, t);
  }

  private timeout(callId: string): void {
    this.timers.delete(callId);
    const res = this.ctx.db
      .prepare("UPDATE calls SET state = 'missed', ended_at = ?, ended_by = 'relay' WHERE call_id = ? AND state = 'ringing'")
      .run(Date.now(), callId);
    if (res.changes !== 1) return;
    this.ringingDevices.delete(callId);
    this.ctx.events.emit({ type: "call.missed", call_id: callId });
    this.closeRoom(callId);
  }

  /** The call left `ringing`: stop the timer. */
  private settle(callId: string): void {
    clearTimeout(this.timers.get(callId));
    this.timers.delete(callId);
    this.ringingDevices.delete(callId);
  }

  private closeRoom(callId: string): void {
    const lk = this.ctx.livekit;
    if (!lk) return;
    lk.deleteRoom(roomName(callId)).catch(() => undefined);
  }
}

const CALL_ID = { type: "object", properties: { call_id: { type: "string", pattern: "^call_[0-9A-HJKMNP-TV-Z]{26}$" } } } as const;

export function callRoutes(app: FastifyInstance, ctx: Ctx, calls: Calls): void {
  const auth = requireAuth(ctx, "agent", "device");
  const livekit = () => {
    if (!ctx.livekit) throw new ApiError(503, "calls_unavailable", "Calls are not configured on this relay (LiveKit is missing)");
    return ctx.livekit;
  };
  const joinFor = (callId: string, p: Principal) =>
    livekit().join(roomName(callId), identityOf(p), p.kind === "agent" ? ctx.cfg.agentName : "user");

  app.post<{ Body: Schemas["CreateCallRequest"] }>(
    "/v1/calls",
    {
      preHandler: auth,
      schema: {
        body: {
          type: "object",
          additionalProperties: false,
          properties: {
            reason: { type: "string", maxLength: 500 },
            priority: { type: "string", enum: ["normal", "urgent"] },
            agent_name: { type: "string", minLength: 1, maxLength: 64 },
          },
        },
      },
    },
    async (req, reply) => {
      const p = principal(req);
      const lk = livekit();
      const row = calls.create(p, req.body ?? {});
      // The push is already on its way; create the room and the caller's token meanwhile.
      const [, join] = await Promise.all([
        lk.createRoom(roomName(row.call_id)).catch((err: unknown) => req.log.warn({ err }, "could not pre-create room")),
        joinFor(row.call_id, p),
      ]);
      req.log.info({ call: row.call_id, direction: row.direction, priority: row.priority }, "call created");
      return reply.code(201).send({ call: callFromRow(row), join });
    },
  );

  app.get<{ Querystring: { limit?: number; before?: string } }>(
    "/v1/calls",
    {
      preHandler: auth,
      schema: {
        querystring: {
          type: "object",
          properties: {
            limit: { type: "integer", minimum: 1, maximum: 500, default: 100 },
            before: { type: "string", maxLength: 64 },
          },
        },
      },
    },
    async (req) => {
      const { limit = 100, before } = req.query;
      const rows = (
        before
          ? ctx.db.prepare("SELECT * FROM calls WHERE call_id < ? ORDER BY call_id DESC LIMIT ?").all(before, limit)
          : ctx.db.prepare("SELECT * FROM calls ORDER BY call_id DESC LIMIT ?").all(limit)
      ) as unknown as CallRow[];
      return { calls: rows.map(callFromRow) };
    },
  );

  app.get<{ Params: { call_id: string } }>("/v1/calls/:call_id", { preHandler: auth, schema: { params: CALL_ID } }, async (req) => {
    const c = calls.get(req.params.call_id);
    if (!c) throw notFound("Call");
    return callFromRow(c);
  });

  app.post<{ Params: { call_id: string } }>(
    "/v1/calls/:call_id/ringing",
    { preHandler: requireAuth(ctx, "device"), schema: { params: CALL_ID } },
    async (req, reply) => {
      const p = principal(req);
      if (p.kind !== "device") throw new Error("unreachable");
      calls.ringing(req.params.call_id, p.deviceId);
      return reply.code(204).send();
    },
  );

  app.post<{ Params: { call_id: string } }>(
    "/v1/calls/:call_id/accept",
    { preHandler: auth, schema: { params: CALL_ID } },
    async (req) => {
      const p = principal(req);
      livekit();
      const row = calls.accept(req.params.call_id, p);
      return { call: callFromRow(row), join: await joinFor(row.call_id, p) };
    },
  );

  app.post<{ Params: { call_id: string } }>(
    "/v1/calls/:call_id/decline",
    { preHandler: auth, schema: { params: CALL_ID } },
    async (req) => callFromRow(calls.decline(req.params.call_id, principal(req))),
  );

  app.post<{ Params: { call_id: string } }>(
    "/v1/calls/:call_id/end",
    { preHandler: auth, schema: { params: CALL_ID } },
    async (req) => callFromRow(calls.end(req.params.call_id, principal(req))),
  );

  // LiveKit webhooks: the room closing (or the callee dropping out) ends an active call.
  app.register(async (scope) => {
    scope.addContentTypeParser("application/webhook+json", { parseAs: "string" }, (_req, body, done) => done(null, body));
    scope.addContentTypeParser("application/json", { parseAs: "string" }, (_req, body, done) => done(null, body));
    scope.post<{ Body: string }>("/v1/livekit/webhook", async (req, reply) => {
      let event;
      try {
        event = await livekit().receiveWebhook(req.body, req.headers.authorization);
      } catch {
        throw new ApiError(401, "unauthorized", "Bad webhook signature");
      }
      const room = event.room?.name;
      const c = room ? calls.get(room) : undefined;
      if (c?.state === "active") {
        const identity = event.participant?.identity ?? "";
        const leftCallee =
          event.event === "participant_left" &&
          (c.direction === "agent_to_user" ? identity === `device_${c.device_id}` : identity === "agent");
        const leftCaller =
          event.event === "participant_left" &&
          (c.direction === "agent_to_user" ? identity === "agent" : identity === `device_${c.device_id}`);
        if (event.event === "room_finished" || leftCallee || leftCaller) {
          req.log.info({ call: c.call_id, event: event.event, identity }, "call ended by LiveKit");
          calls.roomClosed(c.call_id);
        }
      }
      return reply.code(200).send({ ok: true });
    });
  });
}

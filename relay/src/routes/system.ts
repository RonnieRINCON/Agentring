import type { FastifyInstance } from "fastify";
import { type Ctx, principal, requireAuth } from "../context.js";
import type { Schemas } from "../protocol.js";

export const VERSION = "0.1.0";

export function systemRoutes(app: FastifyInstance, ctx: Ctx): void {
  const auth = requireAuth(ctx, "agent", "device");

  app.get("/healthz", async () => ({ ok: true }));

  app.get("/v1/status", { preHandler: auth }, async (req) => {
    const body: Schemas["Status"] = {
      relay_id: ctx.relayId,
      version: VERSION,
      agent_name: ctx.cfg.agentName,
      agent_online: ctx.hub.isAgentOnline(),
      push_provider: ctx.pushProviderName,
      active_call: null,
    };
    if (principal(req).kind === "agent") {
      const row = ctx.db.prepare("SELECT COUNT(*) AS n FROM devices WHERE revoked_at IS NULL").get() as { n: number };
      body.device_count = row.n;
    }
    return body;
  });

  app.get<{ Querystring: { after_seq?: number; limit?: number } }>(
    "/v1/events",
    {
      preHandler: auth,
      schema: {
        querystring: {
          type: "object",
          properties: {
            after_seq: { type: "integer", minimum: 0, default: 0 },
            limit: { type: "integer", minimum: 1, maximum: 500, default: 100 },
          },
        },
      },
    },
    async (req) => {
      const page = ctx.events.list(req.query.after_seq ?? 0, req.query.limit ?? 100);
      return { events: page.events, last_seq: page.lastSeq, has_more: page.hasMore };
    },
  );
}

import { buildApp } from "./app.js";
import { loadConfig } from "./config.js";

const cfg = loadConfig();
const { app, ctx, apiKey, apiKeyGenerated } = await buildApp(cfg);

if (apiKeyGenerated) {
  // Printed once so the user can configure the daemon. Also stored in DATA_DIR/api_key.
  console.log(`\n  agentring relay API key (configure the daemon with it):\n\n    ${apiKey}\n`);
}

for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.on(signal, () => {
    app.log.info({ signal }, "shutting down");
    void app.close().then(() => process.exit(0));
  });
}

await app.listen({ host: cfg.host, port: cfg.port });
app.log.info(
  { relay_id: ctx.relayId, public_url: cfg.publicUrl, push: ctx.pushProviderName },
  "agentring relay ready",
);

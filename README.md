# agentring

Let any AI agent (Claude Code, OpenClaw, n8n, scripts…) **call your phone over VoIP**
and message you — and call your agent back. Your phone rings like a real call, even
locked. No phone network, no numbers, no Twilio. Self-hosted, open source.

> **Status: milestone 1 of 5** — pairing and two-way messages work end to end.
> Voice calls arrive in milestones 2–4.

| Milestone | What | Status |
|---|---|---|
| M1 | Relay, pairing, messages, push | ✅ |
| M2 | The phone rings (locked, Doze) with native call UI | next |
| M3 | Voice conversation (Gemini Live / OpenAI Realtime) + `consult_agent` | |
| M4 | Call your agent from the app; missed/declined fallbacks | In progress (app side) |
| M5 | MCP server, skill, examples, 15-minute quickstart | |

## How it works

```
 agent ──CLI/MCP──▶ daemon ──WSS (outbound only)──▶ relay ──FCM push──▶ Android app
                                                    (your VPS, TLS)
```

- **relay** (`/relay`, TypeScript): pairing, message store, event log, push. Public.
- **daemon** (`/daemon`, Python): the `agentring` CLI (later also MCP server and voice
  worker). Runs next to your agent, even behind NAT.
- **app** (`/app-android`, Kotlin + Compose): pairs with a QR code, shows messages,
  replies from the notification.

Details: [architecture](docs/architecture.md) · [protocol](docs/protocol.md) ·
contract: [`protocol/relay.openapi.yaml`](protocol/relay.openapi.yaml)

## Quickstart (M1)

See **[docs/setup.md](docs/setup.md)**. In short:

```bash
# server
cd deploy && cp .env.example .env && $EDITOR .env && docker compose up -d --build

# your computer
uv tool install ./daemon
agentring init --relay https://relay.example.com --api-key ak_...
agentring pair                      # scan with the app
agentring msg "hola"
agentring replies
```

Phone APK: [android-preview release](https://github.com/mutonby/agentring/releases/tag/android-preview).
Push notifications use your own free Firebase project: [docs/firebase.md](docs/firebase.md).

## Development

```bash
cd relay && npm ci && npm test                         # relay
cd daemon && uv run pytest                             # daemon (+ e2e against the relay build)
cd app-android && ./gradlew :core:test :app:assembleDebug
```

CI builds the APK and runs an end-to-end test on an Android emulator against a
real relay (pair → message → reply in app → signed push → reply from the
notification → `agentring replies`).

## License

Apache-2.0

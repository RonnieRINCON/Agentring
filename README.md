# agentring

Let any AI agent (Claude Code, OpenClaw, n8n, scripts…) **call your phone over VoIP**
and message you, and call your agents back. Your phone rings like a real call, even
locked. A voice AI does the talking and can ask your agents for help mid-call.
No phone network, no numbers, no Twilio. Self-hosted, open source.

> **Status: pre-release.** Messages, calls (locked phone, Doze), voice AI with
> Gemini Live, calling the agent from the app, and the MCP server all work; they have
> been tested on one phone (Pixel 6, Android 16). Android only.

## What you can do

- **Your agent reaches you**: `send_message`, `get_replies`, or `call`. On a call, a
  voice AI explains why the agent is calling, talks with you, and returns the
  transcript to the agent. Use it over MCP (`agentring mcp`), the CLI or REST.
- **You reach your agents**: tap "Call agent" in the app. The voice AI can consult your
  own agents (`claude -p …`, scripts) and MCP servers, all listed in one
  `agents.yaml`. If an agent is slow, the call goes on and its answer arrives when ready.
- Missed a call? The reason arrives as a message you can answer.

## How it works

```
 agent ──MCP/CLI──▶ daemon ──WSS (outbound only)──▶ relay ──FCM push──▶ Android app
   ▲                  │                    (your server, TLS)               │
   └─ agents.yaml ◀── voice AI (Gemini Live) ◀──── LiveKit (call audio) ────┘
```

- **relay** (`/relay`, TypeScript): pairing, messages, call state, event log, push,
  LiveKit tokens. On your server, behind Caddy.
- **LiveKit**: the call audio (self-hosted, same compose file).
- **daemon** (`/daemon`, Python): the `agentring` CLI, the MCP server and the voice AI.
  Runs next to your agents, even behind NAT.
- **app** (`/app-android`, Kotlin): QR pairing, native call screen, messages.

Details: [architecture](docs/architecture.md) · [protocol](docs/protocol.md) ·
contract: [`protocol/relay.openapi.yaml`](protocol/relay.openapi.yaml)

## Quickstart

You need a Linux server with Docker and a domain name, a free Firebase project for
push ([docs/firebase.md](docs/firebase.md)), and, for the voice AI, a Gemini API key
(free tier: https://aistudio.google.com/apikey). Full guide: **[docs/setup.md](docs/setup.md)**.

```bash
# server: ports 80, 443, UDP 50000-50100 and TCP 7881 open
cd deploy && cp .env.example .env && $EDITOR .env && docker compose up -d --build
docker compose logs relay | grep -A2 "API key"

# your computer
uv tool install './daemon[voice]'
agentring init --relay https://relay.example.com --api-key ak_...
agentring pair                                  # scan the QR with the app
agentring msg "hola"
echo GEMINI_API_KEY=... >> ~/.agentring/.env
agentring call "Testing agentring" --ai

# your agent
claude mcp add agentring -- agentring mcp
```

Phone APK: [android-preview release](https://github.com/mutonby/agentring/releases/tag/android-preview).

Then: [connect your agents](docs/agents.md) · [voice AI options](docs/voice.md) ·
[examples](examples/) · [phones that don't ring](docs/troubleshooting-oem.md)

## Privacy in one paragraph

Messages and call metadata are stored on your relay. Push notifications go through
your own Firebase project; they are signed, not encrypted, so Google's FCM can read
pushed text. Call audio goes through your LiveKit server; with `--ai` it is sent to
the voice model's provider (Gemini). On Google's free tier, Google may use that data
to improve its products. More in [SECURITY.md](SECURITY.md).

## Development

```bash
cd relay && npm ci && npm test                         # relay
cd daemon && uv run --extra voice pytest               # daemon (+ e2e against the relay build)
cd app-android && ./gradlew :core:test :app:assembleDebug
```

CI builds the APK and runs an end-to-end test on an Android emulator against a real
relay. See [CONTRIBUTING.md](CONTRIBUTING.md) and [docs/release.md](docs/release.md).

## License

Apache-2.0

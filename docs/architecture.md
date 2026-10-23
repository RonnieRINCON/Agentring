# Architecture (F1)

> Status: **proposal for M1 review**. Nothing here is implemented yet.

agentring lets any AI agent (Claude Code, OpenClaw, n8n, plain scripts…) place a
VoIP call to the user's phone, send it messages, and receive calls from it. The
phone rings like a real call, even locked and in Doze. No PSTN, no phone
numbers, no Twilio.

F1 scope: one user, one agent, Android only, self-hosted, no accounts.

## Components

```
                    ┌──────────────────────── VPS (public) ────────────────────────┐
                    │                                                              │
  ┌──────────┐ HTTPS│  ┌───────┐   /v1/*    ┌─────────┐  server API  ┌──────────┐ │
  │ Android  │──────┼─▶│ Caddy │──────────▶ │  relay  │─────────────▶│ LiveKit  │ │
  │   app    │  WSS │  │ (TLS) │            │ (TS)    │◀─────────────│ server   │ │
  └────┬─────┘      │  └───┬───┘            └────┬────┘   webhooks   └────▲─────┘ │
       │            │      │ /rtc, /twirp        │ FCM HTTP v1             │       │
       │            │      └─────────────────────┼─────────────────────────┘       │
       │            └────────────────────────────┼─────────────────────────────────┘
       │  FCM high-priority data message         │
       ◀─────────────── Google FCM ◀─────────────┘
       │
       │ WebRTC (UDP/TCP, LiveKit SFU)           ┌──────────── user's PC (may be behind NAT) ───────────┐
       └──────────────────────────────────────── │  daemon (Python)                                      │
                                                  │   CLI · MCP (stdio/HTTP) · local HTTP · voice worker │
                                                  │   outbound WSS + HTTPS to relay (API key)            │
                                                  └──────────────────────────────────────────────────────┘
```

| Component | Responsibility | Stack |
|---|---|---|
| `relay` | Pairing, device registry, call state machine + ring timeout, message store, event log + fan-out (WS), push (FCM), LiveKit room/token minting, LiveKit webhook. Source of truth for calls and messages. | Node 22, TypeScript, Fastify 5, `@fastify/websocket`, `better-sqlite3`, `livekit-server-sdk`, `jose`, `google-auth-library` (FCM HTTP v1) |
| `daemon` | Agent-side endpoint. CLI, MCP server, local HTTP API, persistent relay connection, voice worker. Mirrors calls/messages into its own SQLite and stores transcripts + summaries. | Python ≥ 3.11, `uv`, typer, httpx, websockets, Starlette/uvicorn, official `mcp` SDK, `livekit-agents` (optional extra `agentring[voice]`) |
| `app-android` | Pairing (QR), push handling, self-managed `ConnectionService`, CallStyle + full-screen intent, LiveKit audio, messages thread. | Kotlin, Compose, Material 3, OkHttp, kotlinx.serialization, Room, CameraX + ML Kit barcode, LiveKit Android SDK, Firebase Messaging |
| `livekit-server` | SFU; handles NAT traversal (ICE/TURN). One room per call. | upstream image, self-hosted |
| `caddy` | Automatic TLS, reverse proxy. | upstream image |

## Repository layout

```
/protocol
  relay.openapi.yaml        # SINGLE SOURCE OF TRUTH: relay REST API + event schemas (OpenAPI 3.1 / JSON Schema 2020-12)
  examples/                 # golden JSON fixtures; every implementation has a conformance test against them
/relay
  package.json  tsconfig.json  Dockerfile
  src/
    server.ts               # Fastify bootstrap, auth hooks
    config.ts               # env parsing
    db/                     # better-sqlite3, migrations/*.sql
    auth/                   # api key, device JWT, pairing tokens
    routes/                 # pair.ts devices.ts calls.ts messages.ts events.ts ws.ts livekit-webhook.ts
    calls/                  # state machine + ring timer
    events/                 # event log, seq, fan-out to WS subscribers
    push/
      push-provider.ts      # interface PushProvider
      fcm.ts                # FCM HTTP v1 implementation
    livekit.ts              # room + token minting
    generated/              # types generated from /protocol (openapi-typescript), committed
  test/
/daemon
  pyproject.toml            # [project.scripts] agentring = "agentring.cli:app"
  src/agentring/
    cli.py                  # typer: pair, call, msg, replies, status, serve, devices
    config.py               # ~/.agentring/config.yaml + .env
    relay_client.py         # REST (httpx) + WS (websockets) with resume-from-seq
    store.py                # local SQLite mirror + transcripts
    http_api.py             # local HTTP API (Starlette)
    mcp_server.py           # MCP tools (stdio + streamable HTTP)
    voice/                  # worker, prompts, consult_agent backends   (extra: [voice])
    models.py               # generated from /protocol (datamodel-code-generator, pydantic v2), committed
  tests/
/app-android
  settings.gradle.kts  build.gradle.kts  gradle/libs.versions.toml
  app/src/main/java/dev/agentring/app/
    AgentringApp.kt
    data/                   # Room DB, repositories, settings (DataStore)
    net/                    # RelayApi (OkHttp), RelaySocket (WS + resume), protocol models (kotlinx.serialization)
    push/                   # AgentringMessagingService, push signature verification
    telecom/                # AgentringConnectionService, PhoneAccount registration, CallManager
    call/                   # LiveKit session, audio routing
    notify/                 # channels, CallStyle notification, message notifications + RemoteInput reply
    ui/                     # onboarding, home, call, messages, settings (Compose)
/deploy
  docker-compose.yml        # caddy, relay, livekit (+ optional daemon profile)
  Caddyfile  livekit.yaml  .env.example
/skills/agentring/SKILL.md
/examples
  claude-code/  openclaw/  n8n-http-workflow.json
/docs
  architecture.md  protocol.md  setup.md  firebase.md  troubleshooting-oem.md
LICENSE (Apache-2.0)  README.md
```

### Why a `/protocol` folder (small addition to the brief)

Three languages consume the same contract. `protocol/relay.openapi.yaml` is the
only place where the REST API and the event payloads are defined:

- **relay (TS)**: `openapi-typescript` → `relay/src/generated/`.
- **daemon (Python)**: `datamodel-code-generator` → pydantic v2 models.
- **app (Kotlin)**: hand-written `@Serializable` classes (generators for
  kotlinx are weak), kept honest by a unit test that round-trips every file in
  `protocol/examples/`.

CI fails if generated code is stale or a fixture doesn't round-trip.

## Key design decisions (please review)

1. **Commands over REST, events over WS (+FCM).** Every state change is a REST
   call (works when the app was just woken by FCM and has no socket yet; easy to
   retry, easy to describe in OpenAPI). The WebSocket is a read-only stream of
   events, resumable by `seq`. FCM carries the subset of events that must wake
   the phone.
2. **One event log with a global `seq`.** The relay persists every event; clients
   reconnect with `after_seq` and get what they missed. No lost replies when the
   daemon is offline.
3. **LiveKit tokens are not sent inside `call.invite`** *(deviation, needs your
   OK)*. The callee gets its token in the response of
   `POST /v1/calls/{id}/accept`. Reasons: tokens never transit Google's FCM; the
   token is minted only when needed so a short TTL (120 s) can't expire while the
   phone rings or while a delayed push is in flight; and the accept call is the
   atomic "first device wins" point. Cost: one extra HTTPS round trip
   (~50–150 ms) between tapping "answer" and joining the room. The caller still
   gets its token immediately in the `POST /v1/calls` response, so the voice
   worker is already in the room when the phone joins.
4. **Pushes are signed.** At pairing the device receives a per-device
   `push_secret`; every FCM payload carries `HMAC-SHA256(push_secret, event)`
   plus `relay_id`. The app drops anything that doesn't verify. This is how "the
   app only accepts calls from the paired relay" is enforced, even if a Firebase
   project is ever shared.
5. **Extra call states** *(small deviation)*: besides
   `ringing → active → ended` and `ringing → missed | declined`, add
   `ringing → canceled` (caller hung up before answer). Also an informational
   `call.ringing` event (the phone actually displayed the call) — useful to
   measure the M2 "< 5 s" target and to tell "not delivered" from "ignored".
6. **One call at a time** (F1): `POST /v1/calls` returns `409 busy` while another
   call is ringing or active.
7. **Multiple devices allowed, ring all, first accept wins.** Re-pairing a new
   phone doesn't need revoking the old one first. Still "one user".
8. **Daemon ↔ relay auth** with a single API key (`AGENTRING_API_KEY`) generated
   by the relay on first start (or set via env). Device auth with a
   long-lived HS256 `device_jwt` checked against the device table on every
   request, so revocation is immediate.
9. **`consult_agent` command backend uses an argv list, not a shell string**
   (M3): `command: ["claude", "-p", "{task}"]`. The task comes from a voice
   model and must never be interpolated into a shell.
10. **Single hostname** (M2): Caddy routes `/v1/*` to the relay and `/rtc*`,
    `/twirp*` to LiveKit, so the quickstart needs one DNS record. LiveKit media
    goes over UDP 50000–60000 (+ TCP 7881 fallback, + embedded TURN/TLS on 443
    if needed).
11. **Placeholder package id** `dev.agentring.app` for Android. Change it if you
    own a domain.

## Milestone plan

| | Relay | Daemon | App | Deploy |
|---|---|---|---|---|
| **M1** | pairing, devices, messages, events + WS, FCM, SQLite | `pair`, `msg`, `replies`, `status`, `devices`, `serve` (relay sync only) | onboarding (notifications), QR pairing, FCM, messages thread, notification reply, settings | compose: caddy + relay |
| **M2** | calls, ring timer, LiveKit rooms/tokens, webhook | `call`, worker in echo/tone mode | ConnectionService, CallStyle + FSI, DnD/alarm for urgent, LiveKit audio, call screen, full onboarding | + livekit |
| **M3** | — | Gemini Live / OpenAI Realtime, `consult_agent`, `end_call`, transcripts + summaries, `--wait` | live transcript | — |
| **M4** | inbound calls, missed/declined | auto-accept inbound, missed → `msg.new` fallback | "Call agent", missed/declined UX | — |
| **M5** | — | MCP server, local HTTP API | — | skill, examples, docs, quickstart |

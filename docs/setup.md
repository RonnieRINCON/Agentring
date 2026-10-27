# Setup

Three pieces:

- **relay**: a small public server (a €4 VPS is plenty) with a domain name.
- **daemon / CLI**: `agentring` on the machine where your agent runs. It can be behind NAT —
  it only makes outgoing connections to the relay.
- **Android app**: on your phone.

## 1. Relay (docker compose)

Requirements: a Linux server with Docker, a DNS record (e.g. `relay.example.com`)
pointing at it, and these ports open (also in any cloud firewall in front of it;
Docker-published ports bypass `ufw`):

- TCP 80 and 443: Caddy (automatic Let's Encrypt certificate).
- UDP 50000–50100 and TCP 7881: call audio (LiveKit).

```bash
git clone https://github.com/mutonby/agentring && cd agentring/deploy
cp .env.example .env
nano .env                          # set AGENTRING_DOMAIN (+ AGENTRING_AGENT_NAME, LIVEKIT_NODE_IP)
docker compose up -d --build
docker compose logs relay | grep -A2 "API key"
```

This starts the relay, LiveKit and Caddy on one hostname. The LiveKit secret is
generated on first start inside Docker volumes and never goes through `.env`.
`LIVEKIT_NODE_IP` is the server's public IPv4; leave it empty to let LiveKit find
it over STUN.

The relay generates its **API key** on first start and prints it once (it's
also stored in the data volume: `docker compose exec relay cat /data/api_key`).
You need it for the CLI.

Optional but recommended: [push notifications with your own Firebase project](firebase.md).

### Local network / development (no TLS)

The relay refuses plain HTTP unless you opt in. On a trusted LAN:

```bash
cd relay && npm ci && npm run build
AGENTRING_INSECURE_DEV=1 AGENTRING_PUBLIC_URL=http://192.168.1.20:8080 npm start
```

The app only accepts `http://` relays on private addresses (10.x, 192.168.x,
172.16–31.x, 127.x, 100.64–127.x (Tailscale/CGNAT), localhost, `::1`, `*.local`).

## 2. CLI / daemon

Requires Python ≥ 3.11 and [uv](https://docs.astral.sh/uv/) (or pipx).

```bash
uv tool install './daemon[voice]'   # from the repo checkout; [voice] is needed for calls
agentring init --relay https://relay.example.com --api-key ak_...
agentring status
```

Or run it on the server, next to the relay (`daemon` compose profile; the
commands are at the top of `deploy/docker-compose.yml`).

Configuration lives in `~/.agentring/` (`config.yaml`, and the key in `.env`,
mode 600). `AGENTRING_RELAY_URL` / `AGENTRING_API_KEY` env vars override it.

## 3. Phone

1. Install the APK from the
   [android-preview release](https://github.com/mutonby/agentring/releases/tag/android-preview)
   (allow "install unknown apps" for your browser).
2. Open it → **Get started** → allow notifications.
3. On your computer: `agentring pair`. Scan the QR code in the app
   (or copy the pairing code it prints and paste it in the app).
4. The CLI prints `Paired with <your phone> ✓`.

## 4. Try it

```bash
agentring msg "hola"               # phone gets a notification
# reply from the notification or the app's Messages tab
agentring replies                  # shows the reply (new ones only)
agentring replies --all --json     # everything, machine-readable
agentring serve                    # optional: live connection, logs events as they happen
```

Other commands: `agentring devices`, `agentring devices revoke <device_id>`.

Calls:

```bash
agentring call "test"              # rings; when you answer: a greeting (a beep off macOS), then your voice echoed
echo GEMINI_API_KEY=... >> ~/.agentring/.env   # free key: https://aistudio.google.com/apikey
agentring call "The backup failed" --ai        # a voice AI talks to you
agentring serve --ai               # answers "Call agent" from the app
```

Next: let your agents use it, and use them from the phone: [agents.md](agents.md).
If calls don't ring with the screen off: [troubleshooting-oem.md](troubleshooting-oem.md).

## Behind an existing reverse proxy (with calls)

Use `deploy/docker-compose.proxy.yml` when the server already runs Caddy, Traefik
or nginx on 80/443 (it is also what a Coolify "Docker Compose" app points at).

What it exposes: the relay on `127.0.0.1:8095` and LiveKit signalling on
`127.0.0.1:7880` (both reachable only through your proxy), plus LiveKit media on
**UDP 50000–50100 and TCP 7881** on all interfaces. Open those in any cloud
firewall in front of the server (Docker-published ports bypass `ufw`).

Hardening built in: both containers run as unprivileged users with a read-only
root filesystem, no Linux capabilities, `no-new-privileges` and CPU/memory/pid
limits; the relay image is pinned by digest, built without npm install scripts
and ships without npm/yarn; **no secret is passed as an environment variable**.

1. On the server, as root, with the two Firebase files in a directory:

   ```bash
   deploy/prepare-host.sh /path/to/firebase-files   # prints LIVEKIT_API_KEY=lk_…
   ```

   It creates `/opt/agentring/relay` (Firebase files + LiveKit secret, uid 1000)
   and `/opt/agentring/livekit` (config + `keys.yaml`, uid 65534). The LiveKit
   secret is generated there and never leaves the server.

2. Environment for the compose app (none of these is secret):
   `AGENTRING_DOMAIN`, `LIVEKIT_NODE_IP` (the server's public IPv4),
   `LIVEKIT_API_KEY` (from step 1), optionally `AGENTRING_AGENT_NAME`.

3. Proxy routes. Caddy example:

   ```caddyfile
   agentring.example.com {
   	header Strict-Transport-Security "max-age=31536000"
   	header -Server
   	# LiveKit's admin API and the relay's webhook are internal only.
   	handle /twirp/* {
   		respond 404
   	}
   	handle /v1/livekit/webhook {
   		respond 404
   	}
   	handle /rtc* {
   		reverse_proxy 127.0.0.1:7880
   	}
   	handle {
   		reverse_proxy 127.0.0.1:8095
   	}
   }
   ```

4. The relay generates its API key on first start; read it with
   `docker exec <relay container> cat /data/api_key` and give it only to the daemon
   (`agentring init`).

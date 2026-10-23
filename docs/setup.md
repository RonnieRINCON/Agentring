# Setup

Three pieces:

- **relay**: a small public server (a €4 VPS is plenty) with a domain name.
- **daemon / CLI**: `agentring` on the machine where your agent runs. It can be behind NAT —
  it only makes outgoing connections to the relay.
- **Android app**: on your phone.

## 1. Relay (docker compose)

Requirements: a Linux server with Docker, a DNS record (e.g. `relay.example.com`)
pointing at it, and ports 80 and 443 open.

```bash
git clone https://github.com/mutonby/agentring && cd agentring/deploy
cp .env.example .env
nano .env                          # set AGENTRING_DOMAIN (and AGENTRING_AGENT_NAME)
docker compose up -d --build
docker compose logs relay | grep -A2 "API key"
```

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
172.16–31.x, localhost, `*.local`).

## 2. CLI / daemon

Requires Python ≥ 3.11 and [uv](https://docs.astral.sh/uv/) (or pipx).

```bash
uv tool install ./daemon            # from the repo checkout
agentring init --relay https://relay.example.com --api-key ak_...
agentring status
```

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

## 4. Try it (milestone 1)

```bash
agentring msg "hola"               # phone gets a notification
# reply from the notification or the app's Messages tab
agentring replies                  # shows the reply (new ones only)
agentring replies --all --json     # everything, machine-readable
agentring serve                    # optional: live connection, logs events as they happen
```

Other commands: `agentring devices`, `agentring devices revoke <device_id>`.

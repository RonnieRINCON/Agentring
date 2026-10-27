#!/bin/sh
# One-shot helper for docker-compose.yml (the "livekit-init" service): generates the
# LiveKit API secret on first start and writes each container only what it needs.
#
#   /out/relay/livekit-api-secret   uid 1000 ("node" in the relay image), 0400
#   /out/livekit/keys.yaml          uid 65534 (LiveKit), 0600: "<key id>: <secret>"
#   /out/livekit/livekit.yaml       deploy/livekit.yaml with the key id filled in
#
# Idempotent: the secret is kept across restarts; the rest is rewritten each time,
# so changing LIVEKIT_API_KEY or LIVEKIT_NODE_IP in .env only needs `docker compose up -d`.
set -eu
umask 077

KEY=${LIVEKIT_API_KEY:?set LIVEKIT_API_KEY}
case "$KEY" in
  *[!A-Za-z0-9_-]*) echo "LIVEKIT_API_KEY may only contain letters, digits, _ and -" >&2; exit 1 ;;
esac
SECRET_FILE=/out/relay/livekit-api-secret

if [ ! -s "$SECRET_FILE" ]; then
  tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 48 > "$SECRET_FILE.tmp"
  mv "$SECRET_FILE.tmp" "$SECRET_FILE"
  echo "Generated a new LiveKit API secret"
fi
chown 1000:1000 "$SECRET_FILE"
chmod 0400 "$SECRET_FILE"

printf '%s: %s\n' "$KEY" "$(cat "$SECRET_FILE")" > /out/livekit/keys.yaml
chown 65534:65534 /out/livekit/keys.yaml
chmod 0600 /out/livekit/keys.yaml

# Without LIVEKIT_NODE_IP, LiveKit finds its public IP over STUN.
if [ -n "${LIVEKIT_NODE_IP:-}" ]; then external=false; else external=true; fi
sed -e "s/REPLACE_WITH_LIVEKIT_API_KEY/$KEY/" \
    -e "s/use_external_ip: false/use_external_ip: $external/" \
    /in/livekit.yaml > /out/livekit/livekit.yaml
chmod 0444 /out/livekit/livekit.yaml
echo "LiveKit configured (key id $KEY, node IP ${LIVEKIT_NODE_IP:-auto via STUN})"

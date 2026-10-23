#!/usr/bin/env bash
# Prepares /opt/agentring on the server for docker-compose.proxy.yml. Run as root
# on the server itself, so the LiveKit secret is generated there and never leaves it.
#
#   prepare-host.sh <dir with firebase-service-account.json and google-services.json>
#
# Idempotent: keeps existing keys. Prints the LiveKit key id (not secret) to put in
# the deployment environment as LIVEKIT_API_KEY.
set -euo pipefail
umask 077

FIREBASE_DIR=${1:?usage: prepare-host.sh <firebase dir>}
HERE=$(cd "$(dirname "$0")" && pwd)
BASE=/opt/agentring
RELAY_UID=1000      # "node" in the relay image
LIVEKIT_UID=65534   # "nobody", as set in docker-compose.proxy.yml

install -d -m 0755 -o root -g root "$BASE"
install -d -m 0500 -o $RELAY_UID -g $RELAY_UID "$BASE/relay"
install -d -m 0500 -o $LIVEKIT_UID -g $LIVEKIT_UID "$BASE/livekit"

if [[ ! -s "$BASE/livekit/keys.yaml" ]]; then
  key="lk_$(openssl rand -hex 6)"
  secret="$(openssl rand -base64 64 | tr -d '/+=\n' | cut -c1-48)"
  printf '%s: %s\n' "$key" "$secret" > "$BASE/livekit/keys.yaml"
  printf '%s' "$secret" > "$BASE/relay/livekit-api-secret"
  unset secret
fi
key=$(cut -d: -f1 "$BASE/livekit/keys.yaml")

sed "s/REPLACE_WITH_LIVEKIT_API_KEY/$key/" "$HERE/livekit.yaml" > "$BASE/livekit/livekit.yaml"
for f in firebase-service-account.json google-services.json; do
  install -m 0400 -o $RELAY_UID -g $RELAY_UID "$FIREBASE_DIR/$f" "$BASE/relay/$f"
done

chown $LIVEKIT_UID:$LIVEKIT_UID "$BASE/livekit/keys.yaml" "$BASE/livekit/livekit.yaml"
chmod 0600 "$BASE/livekit/keys.yaml"
chmod 0400 "$BASE/livekit/livekit.yaml"
chown $RELAY_UID:$RELAY_UID "$BASE/relay/livekit-api-secret"
chmod 0400 "$BASE/relay/livekit-api-secret"

echo "LIVEKIT_API_KEY=$key"

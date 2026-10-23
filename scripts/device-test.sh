#!/usr/bin/env bash
# Test agentring on a real Android phone connected over USB.
#
#   scripts/device-test.sh [path/to/agentring.apk]           # install + guided test
#   scripts/device-test.sh --instrumented                    # build + run the automated e2e test
#
# Needs: adb (USB debugging enabled on the phone), Node 22+, uv.
# --instrumented also needs the Android SDK (ANDROID_HOME) and JDK 17+.
#
# A local relay runs on this computer; `adb reverse` makes it reachable from
# the phone as http://localhost:8080, so no VPS, TLS or Wi-Fi setup is needed.
# Push notifications need Firebase (docs/firebase.md): export
# FCM_SERVICE_ACCOUNT_FILE and FCM_GOOGLE_SERVICES_FILE before running.
# Without them, messages arrive while the app is open.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT=8080
API_KEY=ak_device_test_key
WORK="$(mktemp -d)"
export AGENTRING_HOME="$WORK/agentring-home"

INSTRUMENTED=0
APK=""
for arg in "$@"; do
  case "$arg" in
    --instrumented) INSTRUMENTED=1 ;;
    *) APK="$arg" ;;
  esac
done

say() { printf '\n\033[1;35m==> %s\033[0m\n' "$*"; }
agentring() { (cd "$ROOT/daemon" && uv run -q agentring "$@"); }

cleanup() {
  [[ -n "${RELAY_PID:-}" ]] && kill "$RELAY_PID" 2>/dev/null || true
  adb reverse --remove "tcp:$PORT" 2>/dev/null || true
}
trap cleanup EXIT

say "Checking the phone"
adb get-state >/dev/null || { echo "No phone found: enable USB debugging and accept the prompt on the phone."; exit 1; }
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release

say "Starting a local relay (logs: $WORK/relay.log)"
(cd "$ROOT/relay" && npm ci --no-audit --no-fund --silent && npm run -s build)
(
  cd "$ROOT/relay"
  PORT=$PORT HOST=127.0.0.1 AGENTRING_PUBLIC_URL="http://localhost:$PORT" AGENTRING_INSECURE_DEV=1 \
  AGENTRING_DATA_DIR="$WORK/relay-data" AGENTRING_API_KEY="$API_KEY" AGENTRING_AGENT_NAME="Test Agent" \
    exec node --disable-warning=ExperimentalWarning dist/main.js
) > "$WORK/relay.log" 2>&1 &
RELAY_PID=$!
for _ in $(seq 1 30); do curl -sf "http://127.0.0.1:$PORT/healthz" >/dev/null && break; sleep 0.5; done
adb reverse "tcp:$PORT" "tcp:$PORT"

if [[ $INSTRUMENTED == 1 ]]; then
  say "Running the automated end-to-end test on the phone (it resets the app's pairing)"
  (cd "$ROOT/app-android" && ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.relayUrl="http://localhost:$PORT" \
    -Pandroid.testInstrumentationRunnerArguments.apiKey="$API_KEY")
  mkdir -p "$ROOT/app-android/e2e-screenshots"
  for f in $(adb shell 'ls /data/local/tmp/agentring-*.png 2>/dev/null' | tr -d '\r'); do
    adb pull "$f" "$ROOT/app-android/e2e-screenshots/" >/dev/null
  done
  say "Passed. Screenshots in app-android/e2e-screenshots/"
  agentring init --relay "http://127.0.0.1:$PORT" --api-key "$API_KEY" >/dev/null
  agentring replies --all
  exit 0
fi

say "Installing the app"
if [[ -n "$APK" ]]; then
  adb install -r "$APK"
else
  (cd "$ROOT/app-android" && ./gradlew -q :app:installDebug)
fi

say "Pairing"
agentring init --relay "http://127.0.0.1:$PORT" --api-key "$API_KEY"
adb shell am start -n dev.agentring.app/.ui.MainActivity >/dev/null
echo "On the phone: Get started → (allow notifications) → Scan QR code, and scan the code below."
agentring pair

say "Message agent → phone"
agentring msg "Hola 👋 soy tu agente. Contéstame desde la app o desde la notificación."
echo "Check the phone shows it (Messages tab, or a notification if the app is in the background"
echo "and Firebase is configured). Reply to it, then press Enter here."
read -r _

say "Replies the agent received"
agentring replies --all

say "Status"
agentring status
echo
echo "Relay log: $WORK/relay.log   (Ctrl+C or Enter to stop the relay)"
read -r _

#!/usr/bin/env bash
# Runs inside the emulator in CI (cwd: app-android). Executes the instrumented
# end-to-end test against the relay on the host (10.0.2.2), then collects
# screenshots and logcat whatever the outcome.
set -u
adb logcat -c || true
./gradlew --stacktrace :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.relayUrl=http://10.0.2.2:8080 \
  -Pandroid.testInstrumentationRunnerArguments.apiKey="${CI_API_KEY}"
status=$?
mkdir -p e2e-screenshots
for f in $(adb shell 'ls /data/local/tmp/agentring-*.png 2>/dev/null' | tr -d '\r'); do
  adb pull "$f" e2e-screenshots/ || true
done
adb logcat -d > "${RUNNER_TEMP:-/tmp}/logcat.txt" || true
exit $status

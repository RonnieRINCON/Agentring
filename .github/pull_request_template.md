## What and why

<!-- What this changes and the reason. Link the issue if there is one. -->

## How it was tested

<!-- Tests run, emulator, real phone (model + Android version), deployment used. -->

## Checklist

- [ ] Relay: `npm run typecheck && npm test` pass
- [ ] Daemon: `uv run --extra voice pytest -q` passes
- [ ] App: `./gradlew :core:test :app:assembleDebug` passes
- [ ] Protocol changes: `protocol/relay.openapi.yaml` updated first, `protocol/examples/` still valid, `npm run gen:types` re-run
- [ ] New or updated dependencies are pinned, ≥ ~2 weeks old and checked against OSV
- [ ] Docs updated (and they describe only what the code does)
- [ ] No secrets, keys or personal data in the diff

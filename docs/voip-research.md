# Reliable incoming VoIP calls on Android: report for agentring

## 1. Ringing reliability

**FCM**
- A high-priority data-only message is fine as long as **every** one produces a visible notification. FCM looks at each app instance's last 7 days. High-priority messages that show nothing get downgraded to normal priority, and so do all messages when notifications are disabled. Since Android 13 the standby bucket no longer caps how many high-priority messages an app can receive. ([FCM priority](https://firebase.google.com/docs/cloud-messaging/android/message-priority), [power details](https://developer.android.com/topic/performance/power/power-details))
- Only a message that actually arrives as high priority lets the app start a foreground service (FGS) from the background. On a downgraded message the FGS start throws `ForegroundServiceStartNotAllowedException`. Check `message.priority == PRIORITY_HIGH` and log `originalPriority` against it. ([bg-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start))
- `onMessageReceived` only gets "several seconds". Verify the HMAC, then immediately register the call with Telecom, start the FGS and post the notification. Make no network call before the UI is showing; `POST /ringing` happens afterwards. ([Firebase blog 2025](https://firebase.blog/posts/2025/04/fcm-on-android))
- **TTL** = time left in the ring window. Don't use a collapse key on `call.invite`; FCM can deliver late after a long time unreachable ([Twilio FAQ](https://www.twilio.com/docs/voice/sdks/android/faq)). Clock skew: measure the expiry from `RemoteMessage.sentTime` plus the ring duration, not only by comparing against the phone's clock.
- Cancel pushes (accept, decline, end, missed) show nothing, so they count against you. Have the ringing FGS open the relay WebSocket and take cancels from there. Keep FCM cancels as a backup and post a "Missed call" notification so they become user-visible.
- Relay side: cache and refresh the OAuth access token for FCM HTTP v1, because the first push otherwise pays for a token fetch. Send the push **in parallel with** creating the LiveKit room, not after it.

**Foreground service and Android 12–16 rules**
- Use FGS type `phoneCall` (permission `FOREGROUND_SERVICE_PHONE_CALL`; the only prerequisite is declaring `MANAGE_OWN_CALLS`) ([FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types)).
- Never start a `microphone`-type FGS from the background: it throws, and the result is one-way audio. This is a known bug on Android 14/15 with targetSdk 35 ([twilio_voice#297](https://github.com/cybex-dev/twilio_voice/issues/297)).
- Signal declares one FGS with `dataSync|microphone|camera|phoneCall|mediaProjection`. Start it as `phoneCall` while ringing. When the user answers, the app is in the foreground, so call `startForeground` again with `phoneCall|microphone` then.
- Android 15+: a `phoneCall` FGS cannot be started from `BOOT_COMPLETED`.
- `ActivityManager.isBackgroundRestricted()` blocks the FGS start. Signal checks it ([CallNotificationBuilder](https://github.com/signalapp/Signal-Android/blob/main/app/src/main/java/org/thoughtcrime/securesms/webrtc/CallNotificationBuilder.java)); onboarding must catch it.

**CallStyle and full-screen intent (FSI)**
- Android 14+ rejects a CallStyle notification that is not attached to an FGS and has no FSI: *"CallStyle notifications must be for a foreground service or user initiated job or use a fullScreenIntent."* So post it as the FGS notification.
- Settings that Signal and Element use: `PRIORITY_MAX`/`HIGH`, `CATEGORY_CALL`, `setOngoing(true)`, `setFullScreenIntent(pi, true)`, and `setTimeoutAfter(ring timeout)` ([Element X RingingCallNotificationCreator](https://github.com/element-hq/element-x-android/blob/develop/features/call/impl/src/main/kotlin/io/element/android/features/call/impl/notifications/RingingCallNotificationCreator.kt)).
- The Answer action must be `PendingIntent.getActivity` straight to the call activity (`showWhenLocked`, `turnScreenOn`). Android 12+ blocks notification trampolines, and opening an activity directly also gives the app while-in-use microphone access.
- FSI permission on Android 14+: it is granted by default only to calling and alarm apps. Google Play revokes it at install for other apps and requires a Play Console declaration. A sideloaded APK keeps the default grant, but the user can revoke it. Check `NotificationManager.canUseFullScreenIntent()` and send the user to `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`. Without it the call shows only as a heads-up notification. ([AOSP FSI limits](https://source.android.com/docs/core/permissions/fsi-limits), [Play policy](https://support.google.com/googleplay/android-developer/answer/13392821))
- Self-managed calls get **no system ringer or system UI**: the app plays the ringtone and shows the UI itself. Use `USAGE_NOTIFICATION_RINGTONE` with vibration and respect the ringer mode. For urgent calls use `USAGE_ALARM` plus a channel with `setBypassDnd` once DnD policy access is granted.

**Platform ConnectionService vs Jetpack `androidx.core:core-telecom` (CallsManager)**
- Google recommends Core-Telecom, including migrating existing ConnectionService apps ([blog](https://android-developers.googleblog.com/2023/11/alpha-release-of-telecom-library.html), [guide](https://developer.android.com/develop/connectivity/telecom/voip-app/telecom)). It uses ConnectionService on API ≤ 33 and FGS types on API 34+. It exposes audio routes as Flows (`availableEndpoints`, `currentCallEndpoint`, `requestEndpointChange`) and works with watches, Bluetooth headsets and Android Auto. Latest versions: stable 1.0.1, 1.1.0-rc01.
- Rules it imposes: post the CallStyle notification within 5 s of `addCall`; answer and other callbacks must complete within 5 s; never call `setCommunicationDevice` or `startBluetoothSco` yourself; play audio on `STREAM_VOICE_CALL`.
- What the open-source apps do:
  - **Signal** has migrated to CallsManager ([TelecomCallController.kt](https://github.com/signalapp/Signal-Android/blob/main/app/src/main/java/org/thoughtcrime/securesms/service/webrtc/TelecomCallController.kt)) but keeps it behind a remote flag `useJetPackTelecom=false` with `telecomMinSdkVersion=37` by default. On failure it sets `systemRejected` and falls back to plain CallStyle + FSI ([AndroidTelecomUtil.kt](https://github.com/signalapp/Signal-Android/blob/main/app/src/main/java/org/thoughtcrime/securesms/service/webrtc/AndroidTelecomUtil.kt)).
  - **Linphone** uses core-telecom.
  - **Element X** appears to use only CallStyle + FSI with no Telecom; I found no core-telecom or `MANAGE_OWN_CALLS` references.
  - **Jitsi** has a switch to turn ConnectionService off and, when it is on, routes audio only through it.
- **Recommendation:** use core-telecom rather than writing a raw ConnectionService. Treat Telecom as optional, like Signal: wrap `addCall` in try/catch and fall back to "FGS + CallStyle + FSI without Telecom" if it fails. The ringing path must work without Telecom.
- **Deviation from the planned design (needs the user's OK):** this replaces the planned self-managed ConnectionService/PhoneAccount, so it should be recorded in `docs/architecture.md`.

**OEM battery killers**
- Onboarding must cover: notifications, FSI, the battery-optimisation exemption (`isIgnoringBatteryOptimizations`), `isBackgroundRestricted`, and DnD access for urgent calls.
- Per-OEM steps from [dontkillmyapp](https://dontkillmyapp.com/xiaomi) and [Acrobits](https://acrobits.net/resources/knowledge-base/sip-background-ringing-android/):
  - **Xiaomi:** Autostart ON, battery saver "No restrictions", lock the app in Recents, and turn on "Show on lock screen" and "Display pop-up windows while running in background".
  - **Samsung:** add the app to "Never sleeping apps" and turn off "Put unused apps to sleep".
  - **Huawei:** App launch → Manage manually, all three toggles on.
- A force-stopped app gets no FCM at all, so say so in `docs/troubleshooting-oem.md`.

## 2. Call setup latency (< 1 s from answer to audio)

- **During ringing:** create the `Room` object (building the PeerConnectionFactory is slow). Call `room.prepareConnection(livekitUrl)` to warm DNS and TLS ([Room.kt](https://github.com/livekit/client-sdk-android/blob/main/livekit-android-sdk/src/main/java/io/livekit/android/room/Room.kt)). Warm the relay's OkHttp connection, which `POST /ringing` already does.
- **On answer:** `POST /accept` over the warm connection (~1 RTT), then `room.connect(url, token, ConnectOptions(audio = true))` so the microphone is published while connecting.
- **Pre-connect audio:** set `AudioTrackPublishDefaults(preconnect = true)`. The SDK starts recording before the agent subscribes and sends that buffer to the LiveKit Agents worker, so the user's first words are not lost ([withPreconnectAudio](https://docs.livekit.io/reference/client-sdk-android/livekit-android-sdk/io.livekit.android.audio/with-preconnect-audio.html)).
- `adaptiveStream` and `dynacast` only affect video; leave them off.
- **Optional faster path (another deviation, would need the user's OK):** have `/ringing` return a token with `canPublish=false` so the phone joins silently while ringing. On accept, the relay switches on publishing with UpdateParticipant. Audio would then flow ~100–200 ms after answering.
- **Agent side:** open the Gemini Live / OpenAI Realtime session while the phone is still ringing. Have the agent speak first as soon as the phone's audio track is subscribed.

## 3. Audio quality and stability

- **Only one component may control audio routing.**
  - LiveKit's default `AudioSwitchHandler` uses `CommDeviceAudioSwitch` on API 31+ (it calls `setCommunicationDevice`). It sets `MODE_IN_COMMUNICATION`, and its default device order is **Speakerphone before Earpiece** ([AudioSwitchHandler.kt](https://github.com/livekit/client-sdk-android/blob/main/livekit-android-sdk/src/main/java/io/livekit/android/audio/AudioSwitchHandler.kt)).
  - With Telecom active, pass `NoAudioHandler` via `LiveKitOverrides` and route with `requestEndpointChange`, as Signal and Jitsi do.
  - Without Telecom (the fallback path), keep `AudioSwitchHandler` with `preferredDeviceList = [Bluetooth, Wired, Earpiece, Speakerphone]`.
- **Proximity sensor:** take a `PROXIMITY_SCREEN_OFF_WAKE_LOCK` only while the route is the earpiece. Self-managed calls don't get this automatically.
- **AEC/NS:**
  - The SDK defaults turn on AEC, NS, AGC and high-pass filtering, and Pixel uses its hardware AEC.
  - SDK versions 2.19.0–2.23.0 broke hardware AEC (echo, chirping); fixed in 2.23.1 ([#796](https://github.com/livekit/client-sdk-android/issues/796)).
  - Echo on Samsung A-series and other devices is still open ([#947](https://github.com/livekit/client-sdk-android/issues/947), [#840](https://github.com/livekit/client-sdk-android/issues/840)). Signal decides software vs hardware AEC per device via remote config, so expose a debug toggle that forces software AEC.
- **Opus:** defaults are DTX on, RED (redundant audio) on, 48 kbps. For mono voice, `AudioPresets.SPEECH` (24 kbps) is enough and RED helps on lossy networks.
- **Network changes (Wi-Fi ↔ cellular):** the SDK first tries an ICE restart, then a full reconnect. Known bugs:
  - The stable side's `Room` disconnects when the peer drops in a 1:1 call ([#979](https://github.com/livekit/client-sdk-android/issues/979)).
  - Reconnect problems when Wi-Fi comes back ([#900](https://github.com/livekit/client-sdk-android/issues/900)).
  - Audio goes to the earpiece after reusing a `Room`; create a new `Room` per call ([#966](https://github.com/livekit/client-sdk-android/issues/966)).
  - Pin SDK 2.29.x.
- **Self-hosted LiveKit** ([deployment](https://docs.livekit.io/transport/self-hosting/deployment.md), [config-sample](https://github.com/livekit/livekit/blob/master/config-sample.yaml)):
  - Use `network_mode: host`. Publishing 10k UDP ports through Docker's bridge network is very slow.
  - Point Caddy at it via `host.docker.internal:7880` (add `extra_hosts: host-gateway`).
  - Set `rtc.use_external_ip: true` (or `node_ip`), `tcp_port: 7881` and `port_range 50000-60000`.
  - Turn on the embedded TURN server: `udp_port: 3478` (or 443/UDP) and TURN/TLS on 443. If Caddy holds 443/TCP, it needs the `caddy-l4` module to route the TURN subdomain by SNI to LiveKit's `tls_port`, with `external_tls: true`. TURN needs its own subdomain and certificate.
  - Firewall: 80, 443 tcp+udp, 7881, 3478/udp, 50000–60000/udp.

## 4. Voice AI latency (LiveKit Agents 1.8.x)

- **Gemini Live:** use its built-in turn detection (on by default) ([docs](https://docs.livekit.io/agents/models/realtime/plugins/gemini.md)).
  - LiveKit's adaptive interruption and `inference.TurnDetector` need **LiveKit Cloud** credentials and billing, and adaptive interruption does not support Gemini Live ([docs](https://docs.livekit.io/agents/logic/turns/adaptive-interruption-handling.md)).
  - Keep thinking at "minimal".
  - Gemini Live function calls are synchronous: the model waits for the result. So `consult_agent` must say a filler line first and return quickly or run as a background task.
- **OpenAI Realtime:** `semantic_vad` with `eagerness` medium or high, or `server_vad` with `silence_duration_ms` 300–500. Use `interrupt_response=True` for barge-in ([docs](https://docs.livekit.io/agents/models/realtime/plugins/openai.md)).
- **Audio formats:** Gemini takes 16 kHz PCM in and returns 24 kHz out; the room is Opus at 48 kHz. Let the plugins resample once; don't add extra conversion.
- **Noise cancellation:** the BVC/Krisp plugins are LiveKit Cloud only. With self-hosted LiveKit, rely on WebRTC's NS on the phone.
- **Gemini session limits:** the connection lasts about 10 min and an audio session about 15 min. Turn on session resumption and context-window compression ([docs](https://ai.google.dev/gemini-api/docs/live-session)).

## 5. Pitfalls checklist

- High-priority message downgraded, then the FGS start throws.
- Microphone FGS started from the background, giving one-way audio.
- CallStyle notification without an FGS or FSI throws `IllegalArgumentException`.
- Two components fighting over audio routing (Telecom and AudioSwitch).
- LiveKit defaults to the speakerphone.
- Reusing a `Room` between calls.
- Echo regressions tied to SDK version.
- Docker bridge networking with 10k ports.
- No TURN/TLS, so calls fail on corporate or hotel Wi-Fi.
- Late FCM delivery rings for an already-ended call.

## Prioritized checklist for M2/M3

1. Manifest: `MANAGE_OWN_CALLS`, `FOREGROUND_SERVICE` + `_PHONE_CALL` + `_MICROPHONE`, `USE_FULL_SCREEN_INTENT`, `RECORD_AUDIO`, `ACCESS_NOTIFICATION_POLICY`, `WAKE_LOCK`. One call service with `foregroundServiceType="phoneCall|microphone"`.
2. `onMessageReceived`:
   1. Verify the HMAC and expiry (using `sentTime`).
   2. Check that priority is HIGH.
   3. `startForegroundService` → `startForeground(phoneCall)` with the CallStyle notification (FSI, ongoing, timeout).
   4. Core-telecom `addCall` in try/catch, falling back to no Telecom.
   5. Start the ringtone.
   6. Only then `POST /ringing` and open the WebSocket.
3. Answer action = `getActivity`. The call activity sets `showWhenLocked`/`turnScreenOn` and promotes the FGS to `phoneCall|microphone`.
4. Onboarding with checks: notifications, `canUseFullScreenIntent`, battery exemption, `isBackgroundRestricted`, DnD access (urgent), OEM instructions, and a test call that times the `call.ringing` latency.
5. Relay: cache the FCM token, send the push in parallel with room creation, TTL = time left, use the WebSocket for cancels, post a missed-call notification.
6. LiveKit client: new `Room` per call created during ringing, `prepareConnection` during ringing, `connect(audio = true)` + preconnect audio, `NoAudioHandler` with Telecom (earpiece-first `AudioSwitchHandler` without it), proximity wake lock, SDK ≥ 2.29.x, debug toggle for software AEC.
7. Compose: livekit `network_mode: host`, `use_external_ip`, embedded TURN (3478/udp + TLS 443 via caddy-l4 SNI), firewall ports. Test from cellular with UDP blocked.
8. Acceptance test script: `adb shell dumpsys deviceidle force-idle`, `am set-standby-bucket dev.agentring.app rare`, screen off for 10 min, then `agentring call` and measure invite → ringing.
9. M3: prestart the model session while ringing, model's own VAD (Gemini) or `semantic_vad` (OpenAI), agent greets first, `consult_agent` non-blocking, Gemini session resumption.

Two items need the user's OK under the project's rule on design deviations: swapping the planned self-managed ConnectionService for core-telecom with a no-Telecom fallback, and the optional listen-only token returned from `/ringing`.
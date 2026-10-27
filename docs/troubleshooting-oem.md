# Calls don't ring: battery savers and OEM background killers

agentring wakes the phone with a high-priority FCM data message. The app then
starts a foreground call service, registers the call with Android's Telecom
stack (with its own fallback if that fails) and shows a CallStyle notification
with a full-screen intent. If any of those steps is blocked, the call is late or
never rings.

Common causes:

- **Doze and App Standby.** With the screen off and the phone still, Android
  defers network access and jobs. High-priority FCM messages are allowed
  through, but FCM may **deprioritize** an app whose high-priority pushes don't
  show a notification, and a "restricted" standby bucket limits them further.
- **"Restricted" battery usage** (Android 9+). The app cannot start its call
  service from the background, so the push arrives but nothing rings.
- **OEM background killers.** Xiaomi, Samsung, Huawei, OnePlus, Oppo/Realme and
  others add their own task killers on top of Android. They can stop the app
  entirely, so FCM messages are not delivered until you open it again.
- **No Google Play services** (most recent Huawei phones, de-Googled ROMs):
  FCM doesn't work at all. The Home screen shows *Push: Unavailable*.

## What the app already checks

The **Ready to take calls** checklist appears on the onboarding's last screen,
on Home whenever a required item is missing, and always in **Settings**. Each
item has a **Fix** button that opens the right system screen.

| Item | Needed for | Fix opens |
|---|---|---|
| Notifications | required | permission prompt (Android 13+) or the app's notification settings |
| Microphone | required | permission prompt |
| Full-screen calls on the lock screen | required (Android 14+ needs explicit permission) | *Full-screen intents* setting for the app |
| Background activity allowed | required (not "Restricted") | app info |
| Unrestricted battery use | strongly recommended (shown in red) | the *ignore battery optimisations* dialog |
| Ring urgent calls in Do Not Disturb | optional (`agentring call --urgent`) | *Do Not Disturb access* |

These are the standard Android settings. OEM-specific switches (autostart,
"never sleeping apps", lock in recents) can't be checked by the app; set them by
hand as below. Menu names change between versions; if a path doesn't match, search
the Settings app for the setting's name, and see
[dontkillmyapp.com](https://dontkillmyapp.com) for your exact model.

Settings → **Use Android's call system** can be turned off if calls misbehave
on a particular phone; the app then rings with its own service and notification.

## Per vendor

### Pixel and stock Android (also Motorola, Nokia, Sony, Fairphone)

Usually only the app's checklist is needed.

- Settings → Apps → agentring → **App battery usage** → **Unrestricted**.
- Android 14+: Settings → Apps → Special app access → **Full screen intents** →
  agentring on.
- If you rarely open the app, Android may put it in a lower standby bucket; the
  "Unrestricted" setting above prevents most of the effect.

### Samsung (One UI)

- Settings → Apps → agentring → Battery → **Unrestricted**.
- Settings → Battery (or Device care → Battery) → **Background usage limits**:
  - add agentring to **Never sleeping apps**;
  - make sure it is not in *Sleeping apps* or *Deep sleeping apps*;
  - consider turning off **Put unused apps to sleep**.
- Settings → Apps → agentring → Notifications: allow, and check the call
  channels are not silenced.

### Xiaomi / Redmi / POCO (MIUI, HyperOS)

- Settings → Apps → Manage apps → agentring:
  - **Autostart** on;
  - Battery saver → **No restrictions**;
  - Other permissions → **Show on Lock screen** and **Display pop-up windows
    while running in the background** allowed (needed for the full-screen call
    screen when locked);
  - Notifications → allow, including lock-screen notifications.
- Open recent apps, long-press agentring and tap the **lock** icon so cleaning
  the recents doesn't kill it.
- Disable any "battery saver" / "ultra battery saver" mode while you need calls.

### Huawei / Honor (EMUI, Magic UI)

- Without Google Play services FCM doesn't work and the phone will not wake for
  calls (UnifiedPush support is planned). Older models with Play services:
- Settings → Battery → **App launch** → agentring → turn off *Manage
  automatically*, then enable **Auto-launch**, **Secondary launch** and **Run in
  background**.
- Settings → Apps → agentring → Battery → **Power-intensive prompt** off (if
  present) and *Keep running after screen off* on.
- Lock the app in recent apps.

### OnePlus (OxygenOS)

- Settings → Apps → agentring → Battery usage → **Allow background activity**
  (older versions: Settings → Battery → Battery optimisation → agentring →
  **Don't optimise**).
- Settings → Battery → Advanced settings: turn off **Optimise battery use** /
  **Sleep standby optimisation** / **Deep optimisation**, whichever exist.
- Lock the app in recent apps.

### Oppo / Realme (ColorOS, Realme UI)

- Settings → Apps → App management → agentring → Battery usage: allow
  **foreground activity**, **background activity** and **auto launch**.
- Settings → Battery → More settings: turn off **Sleep standby optimisation**
  and any *App quick freeze* that includes agentring.
- Lock the app in recent apps.

## Verify

1. Check the app's checklist is all green (Settings tab) and Home shows
   *Push: Registered*. `agentring status` should show `push ok` for the device.
2. Force Doze with the phone on USB (or wireless adb) and the screen off:

   ```bash
   adb shell dumpsys battery unplug
   adb shell dumpsys deviceidle force-idle
   adb shell dumpsys deviceidle get deep        # should print IDLE
   ```

   Optionally also simulate a rarely used app:
   `adb shell am set-standby-bucket dev.agentring.app rare`.

3. Place a test call from the daemon (needs the `[voice]` extra):

   ```bash
   agentring call "test"
   ```

   It prints a timeline: `ringing on dev_…` means the phone showed the call
   (aim for under 5 s); `no answer` after 30 s with no `ringing` line means the
   phone never displayed it.

4. Restore normal behaviour:

   ```bash
   adb shell dumpsys deviceidle unforce
   adb shell dumpsys battery reset
   adb shell am set-standby-bucket dev.agentring.app active
   ```

Real Doze differs from the forced one, and OEM killers usually act after some
time with the screen off, so also try with the phone locked and untouched for
10+ minutes before calling.

If it still doesn't ring, collect logs while you call:

```bash
adb logcat -s PushHandler AgentringFCM CallManager CallService TelecomBridge Ringer
```

- Nothing from `PushHandler` → the push never reached the app (FCM, Play
  services or an OEM killer).
- `AgentringFCM: push deprioritized` → FCM downgraded the message from high to
  normal priority.
- `PushHandler: rejected push` → the signature or relay id didn't match; pair
  the phone again.

Then [open a bug report](https://github.com/mutonby/agentring/issues/new/choose)
with the phone model, Android version, the checklist state and these logs.

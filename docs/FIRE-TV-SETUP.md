---
layout: default
title: Fire TV setup runbook
---

# Fire TV — Real-Device Setup Runbook

Steps to validate ScreenTamer on real Fire TV hardware, plus a growing
FAQ/gotchas log for problems encountered in the field. Keep this file updated
as new issues appear (see [FAQ / gotchas](#faq--gotchas)).

---

## Preconditions

- Fire TV on the same LAN, **ADB Debugging ON** and **Apps from Unknown
  Sources ON** (Settings → My Fire TV → Developer Options).
- A computer (macOS/Linux) with `adb` from the Android SDK
  (`~/Library/Android/sdk/platform-tools`).
- A dashboard password of your choice (examples below use `MyPass123`).

## Step 0 — Shell environment (do this once per terminal)

```bash
# adb on PATH
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"

# Target the TV with every adb call (avoids "more than one device/emulator")
export ANDROID_SERIAL=<fire-tv-ip>:5555
```

Persist in `~/.zshrc` (macOS):

```bash
echo 'export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"' >> ~/.zshrc
echo 'export ANDROID_SERIAL=<fire-tv-ip>:5555' >> ~/.zshrc
source ~/.zshrc
```

> `ANDROID_SERIAL` is inherited by the test suites' internal adb calls — keep
> it set in the shell that runs the E2E suite.

## Step 1 — Fresh build

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd agent && ./gradlew assembleDebug --console=plain
# -> app/build/outputs/apk/debug/app-debug.apk
```

## Step 2 — Connect + sideload

```bash
adb connect <fire-tv-ip>:5555
adb devices                      # TV must be listed (emulator may also appear)
adb install -r agent/app/build/outputs/apk/debug/app-debug.apk
```

## Step 3 — Grants + prefs injection (one script)

```bash
./scripts/setup-firestick.sh <fire-tv-ip> adb MyPass123 8080 ""
```

This grants `PACKAGE_USAGE_STATS`, `SYSTEM_ALERT_WINDOW`, enables the
accessibility watchdog, and injects the dashboard password + port into the
app's prefs (`server_url` blank → device-only mode).

> If prefs injection fails on your device (see FAQ #3), skip it: everything
> can be typed in the on-TV app instead.

## Step 4 — In-app configuration (on the TV)

1. Open **ScreenTamer** — opening the app now **auto-starts the agent**
   (no manual "Start Agent" needed; the accessibility watchdog + boot
   receiver keep it alive afterwards).
2. Fields should be pre-filled from Step 3; otherwise type:
   - **Dashboard password** — `MyPass123`
   - **Dashboard port** — `8080`
   - **Relay server URL** — leave blank (device-only)
3. Press **Save & Restart Service**.
4. Press **Test Local ADB** — must show `ADB: OK`.
5. Status should show `Dashboard: http://<this-device>:8080`.
6. Optional: press **Open Dashboard on TV** to view the parent dashboard
   inside the app (WebView → the on-device server at `127.0.0.1:8080`).

## Step 5 — Verify from your computer

```bash
adb forward tcp:8080 tcp:8080

curl -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/          # expect 200 (dashboard)
curl -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/api/state # expect 401 (no auth)
curl -s -H "x-parent-password: MyPass123" http://127.0.0.1:8080/api/state | \
  python3 -m json.tool | grep -E "online|locked|health|startCount"
```

The device card in the dashboard should show the health line
(`service started N× · last tick … · tick failures 0`).

## Step 6 — Manual enforcement sanity pass

```bash
curl -s -X POST -H "x-parent-password: MyPass123" \
  -d '{"command":{"type":"lock"}}' http://127.0.0.1:8080/api/command     # TV locks
curl -s -X POST -H "x-parent-password: MyPass123" \
  -d '{"command":{"type":"unlock"}}' http://127.0.0.1:8080/api/command   # TV unlocks
```

## Step 7 — E2E evidence suite on the TV

```bash
cd server-relay
DASH_URL=http://127.0.0.1:8080 DASH_PASSWORD=MyPass123 npm run test:e2e
```

Drives the full cycle on the real TV — home, lock overlay, unlock, curfew
enforcement, policy restore, history, dashboard render — and captures
screenshots + state/health snapshot to `evidence/e2e-<timestamp>/`.

## Step 8 — Optional relay (remote monitoring from a computer)

```bash
cd server-relay && npm start          # on the Mac; token printed on first run
```

Set the TV app's **Relay URL** to `ws://<mac-ip>:3000/ws` + the pairing token
(from `server-relay/data/config.json`), **Save & Restart Service**. Verify in
the relay console: `[ws] agent hello` then `usage` lines every 30 s.

---

## FAQ / gotchas

### 1. `adb: command not found` in a new shell
PATH isn't persistent — see [Step 0](#step-0--shell-environment-do-this-once-per-terminal).

### 2. `adb: more than one device/emulator`
Both the test emulator and the TV are attached. Set `ANDROID_SERIAL=<tv-ip>:5555`
(Step 0) so every adb call targets the TV. The setup script already hardcodes
`-s <ip>:5555`, so it is unaffected.

### 3. `sh: can't create temporary file /data/local/sh*.tmp: Permission denied`
Happens in the setup script's prefs-injection step: Android's `sh` cannot
materialize heredocs inside `run-as`. The script now pushes the prefs file to
`/data/local/tmp` and copies it via `run-as` (fixed in
`scripts/setup-firestick.sh`). If you hit this on an old script version:

```bash
# build the prefs file locally, then:
adb push screentamer_prefs.xml /data/local/tmp/
adb shell "run-as com.screentamer.agent sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/screentamer_prefs.xml shared_prefs/screentamer_prefs.xml'"
adb shell rm -f /data/local/tmp/screentamer_prefs.xml
```

Alternatively skip injection entirely and type the values in the on-TV app.
Note the grants in the script already ran before this step — permissions are
in place either way.

### 4. Prefs injection wipes earlier manual config
The script writes a fresh prefs file. If you had already configured the app
(e.g. relay token), re-running the script loses those values. Re-enter them in
the app and **Save & Restart Service** afterward.

### 5. Prefs changed but the agent still uses old values
SharedPreferences caches in memory. After any external prefs change, use the
app's **Save & Restart Service** (or `am force-stop` the app) so a fresh
process re-reads the file.

### 6. Agent won't start / auto-restart doesn't happen
Opening the app auto-starts the agent (`MainActivity` → `AgentService.start`),
and the accessibility watchdog is the long-term keep-alive path. Check:
`adb shell settings get secure enabled_accessibility_services` — must contain
`com.screentamer.agent/.AgentAccessibilityService`. Otherwise check logcat
(`adb logcat -s ScreenTamer`) for the auto-start line from
`ScreenTamer/MainActivity`.

### 7. "Test Local ADB" fails
Re-toggle **ADB Debugging OFF → ON** on the TV (restarts adbd), wait ~10s,
retest. The agent connects to `127.0.0.1:5555` on the TV itself.

### 8. Dashboard unreachable from phone but works from the Mac
Try the Mac first via `adb forward` (Step 5). From a phone, ensure it's on the
same LAN/VLAN (not a guest SSID) and use `http://<tv-ip>:8080/` directly.

### 9. E2E flakes on overlay text / uiautomator
Fire OS uiautomator is slow; the suite waits up to 60s and retries. Just re-run
`npm run test:e2e`.

### 10. Fire OS killed the service (background limits)
Expected behavior — the accessibility watchdog + `START_STICKY` revive it. The
health record shows the restart (`startCount` climbs, `lastStartAt` updates).
Keep Accessibility enabled.

### 11. `nc: not found` / "loopback adb NOT reachable" from the setup script
Fire OS has no `nc`. The script now checks `/proc/net/tcp` for an adbd listener
on :5555 instead, and the result is informational — the authoritative test is
the app's **Test Local ADB** button on the TV.

### 12. Dashboard unreachable from the laptop: `http://<tv-ip>:8080/` won't load
Diagnose in order:

```bash
# 1. Is the agent process alive?
adb shell ps -A | grep screentamer

# 2. Not running? Start it (service is not exported, so run-as):
adb shell am start -n com.screentamer.agent/.MainActivity
adb shell "run-as com.screentamer.agent am startservice --user 0 -n com.screentamer.agent/.AgentService -a com.screentamer.agent.START"

# 3. Is the embedded server actually serving?
adb logcat -s ScreenTamer | grep http     # expect "[http] 127.0.0.1 GET / -> 200"

# 4. Confirm via adb forward (bypasses LAN entirely):
adb forward tcp:8080 tcp:8080
curl -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/   # 200 = server up

# 5. Then retry from the laptop:
curl -o /dev/null -w "%{http_code}\n" http://192.168.1.7:8080/
```

- Step 4 works but step 5 fails → **network path** problem: same LAN/VLAN
  (no guest SSID / AP isolation), and on macOS Sequoia+ give your browser
  **Local Network** permission (System Settings → Privacy & Security → Local
  Network).
- Both fail → service not running or bound elsewhere; re-check steps 1–3.
- `curl` vs browser can differ (browser sends `OPTIONS`/extra headers; ignore
  the 405/501 noise in the log — `GET /` is what matters).

### 13. Dashboard loads but renders a black/blank screen
The served HTML is correct, but the login screen was started `hidden` by
default and only shown after a logout — a first-time visitor (no saved
password) got an invisible page. Fixed: the login screen is now visible by
default (`#login` no longer starts with `hidden` in `index.html`). If you see
this, you're on an older APK — rebuild and reinstall (`adb install -r`).

### 14. App shows a generic/blank icon in the Fire TV launcher
The icon used to be a vector drawable; the Leanback launcher needs bitmap
icons, so it failed to render. Icons are now PNGs (rendered from
`art/ic_launcher.svg` by `scripts/gen-icons.sh`) + an adaptive icon on
Android 8+. Rebuild and reinstall to pick them up.

### 15. Improvements introduced 2026-08-08 (this build)
- **Auto-start**: opening the app starts the agent — no manual Start Agent.
- **In-TV dashboard**: "Open Dashboard on TV" renders the parent dashboard in
  a WebView against `127.0.0.1:<port>`.
- **Friendly app names + letter avatars** in the dashboard (known-app map +
  package-name heuristics).
- **Logcat tags** unified under `ScreenTamer/*` — `adb logcat -s ScreenTamer`.
- **Settings UI** split into Service / Dashboard / Relay / Device / About
  cards, with the old vector icon replaced by proper PNG launcher icons.
- **More logs**: request client IPs, relay reconnect attempts, adb connect
  details, store writes/sweeps, watchdog binds/unbinds.

### 16. Reports section empty until you press Refresh (relay dashboard)
`loadData` fetched history before knowing which device was selected, so the
relay answered `400 deviceId required` and the reports never rendered until a
manual refresh. Fixed: state is fetched first, then history with the resolved
deviceId (embedded server was unaffected — it ignores deviceId).

### 17. TV-UI overhaul 2026-08-08 (dashboard-first build)
- **Settings is the launcher screen** (app icon → `MainActivity`). The in-TV
  dashboard (`DashboardActivity`, WebView → `http://127.0.0.1:<port>/?tv=1`)
  opens **only on demand** via "Open Dashboard on TV". `DashboardActivity`
  starts `AgentService` itself, so a cold launch still boots the embedded
  server.
- **Race at cold start:** the WebView could hit `ERR_CONNECTION_REFUSED`
  before the server bound the port. `DashboardActivity` now retries the load
  with a backoff (1.5s → max 5s) until the server answers.
- **TV-mode layout (`?tv=1`)**: `app.js` adds `body.tv-mode`, which enlarges
  the whole dashboard (19px base, 640px cards, poster-style current-app tile
  with 104px avatar, big controls) and adds **D-pad focus states**: focused
  buttons get a 4px accent outline, a blue glow, and scale up to 1.07 with a
  140ms animation (verified via CDP: `matrix(1.07,0,0,1.07,0,0)`).
- **Settings sizing lesson:** a first pass applied a huge global button style
  (72dp min-height, 20sp) — on the TV it dwarfed the page. Reverted to a
  modest `ScreenTamer.Button` (52dp, 16sp) with the focus border + subtle
  scale animator, no global EditText override, and a small (18sp) page title.

### 18. Why do Lock Now / Unlock Now exist on the TV UI?
They are the only remote commands that work **without adb**. Pause/Play/Go
home/Force-stop are implemented via `adb shell input`/`am force-stop`, which
only works when a host drives the device over adb — on the standalone TV they
log `(adb FAILED)`. Lock/Unlock are implemented natively (policy flag +
overlay + accessibility-service enforcement), so they always work on-device —
instant freeze with no phone or browser needed.

### 19. I can't see my screenshots / evidence
`evidence/tv-2026-08-08/` holds screenshots (`dashboard-tvmode-login.png`,
`settings-focus-states.png`) — view them in Finder/Preview on the Mac.

### 20. Verifying WebView styles from the Mac (CDP)
```bash
adb -s <ip>:5555 forward tcp:9222 localabstract:webview_devtools_remote_$(adb -s <ip>:5555 shell pidof com.screentamer.agent)
curl -s http://127.0.0.1:9222/json
```
Then evaluate JS over the WebSocket to read `getComputedStyle` (tv-mode class,
sizes, focus outline/transform) or take real screenshots of the dashboard.

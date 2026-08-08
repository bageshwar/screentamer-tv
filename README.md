# ScreenTamer

Self-hosted parental control + remote monitoring for Amazon Fire TV (Fire OS).

Two components:

- **`agent/`** — Firestick agent APK (Kotlin). Foreground service that tracks
  screen time, runs an **embedded dashboard server on the TV itself**, enforces
  limits/curfews/blacklists with a full-screen lock overlay, and executes remote
  commands through the device's **own local adbd** (`127.0.0.1:5555`) — no root
  needed. Optionally pushes usage/logs to a relay server.
- **`server-relay/`** — Optional relay (Node.js, only dependency: `ws`) + the
  same web dashboard (vanilla HTML/JS/CSS). Not required for a single-TV setup;
  useful to monitor several TVs from one computer without port forwarding.

## How it works

The agent embeds its own HTTP server (`:8080` by default) that serves the
parent dashboard and the REST API. Everything lives on the TV:

```
Fire TV (agent APK)
 ├─ UsageStatsManager  ── today's per-app screen time ──┐
 ├─ local adbd :5555    ── keyevents / force-stop ──────┤  HTTP :8080 (embedded server)
 ├─ SYSTEM_ALERT_WINDOW overlay ── lock screen ────────┤      │  ▲
 ├─ Accessibility watchdog ── keeps agent alive ────────┘      ▼  │ REST (on-demand fetch)
 └─ DeviceStore ── per-day history / log / health ─────────── Parent dashboard (any browser)
```

- The dashboard **fetches on demand** (Refresh button, reload after actions) —
  no polling, no push channel.
- The agent persists per-day usage, an activity log, and a **health record**
  (service start count, last tick, tick failures, last error) on the TV, all
  visible in the dashboard for diagnosing service lifecycle problems.
- Commands (pause, play, go home, force-stop, lock now) are injected via ADB
  media key events (`input keyevent 126/127/3`) and `am force-stop`, exactly
  like a desktop adb host would.

## 1. Agent APK (primary)

Open `agent/` in Android Studio, or build from the CLI (JDK 17+):

```bash
cd agent
./gradlew assembleDebug
```

then sideload `app/build/outputs/apk/debug/app-debug.apk`:

```bash
adb connect <fire-tv-ip>:5555
adb install app-debug.apk
```

Fire TV prerequisites (Settings → My Fire TV → Developer Options):
**ADB Debugging ON** and **Apps from Unknown Sources ON**.

## 2. Post-install setup

Fire OS hides the permission screens, so grant them from your computer:

```bash
chmod +x scripts/setup-firestick.sh
./scripts/setup-firestick.sh <fire-tv-ip> adb <dashboard-password> <dashboard-port> <relay-url-optional>
```

Equivalent manual commands:

```bash
adb shell pm grant com.screentamer.agent android.permission.PACKAGE_USAGE_STATS
adb shell appops set com.screentamer.agent SYSTEM_ALERT_WINDOW allow
adb shell settings put secure enabled_accessibility_services \
  com.screentamer.agent/.AgentAccessibilityService
```

Then open the ScreenTamer app on the Fire TV, enter the dashboard password +
port (and optionally the relay URL), press **Save & Restart Service**, and
**Test Local ADB**.

> First ADB loopback connection: the agent generates its own RSA key, presents
> it to adbd, and Fire TV registers it without a confirmation dialog. After
> that the key is persisted in app storage.

## 3. Using the dashboard

- On the TV itself: `http://<fire-tv-ip>:8080/` (dashboard password set on the
  device).
- From your computer: `adb forward tcp:8080 tcp:8080`, then open
  `http://127.0.0.1:8080/`.

## 4. Optional relay (`server-relay/`)

When you want several TVs reported to one computer (or a home server), set the
agent's relay URL and run the relay on your LAN:

```bash
cd server-relay
npm install
npm start
```

First run generates `server-relay/data/config.json` with a random **device
token** (entered into the agent app) and **parent password** (dashboard login)
— printed to the console.

- Dashboard: `http://<server-ip>:3000/`
- Agent relay URL: `ws://<server-ip>:3000/ws` (left blank, the agent runs
  device-only and never phones home)

## Dashboard features

- Device list (online/offline, current app, lock state) + agent health
  (start count, last tick, tick failures)
- Today's screen time per app + totals
- **Pause TV Now** (instant lockdown), pause/play, go home, force-stop any app
- Daily time limits, curfew windows (wraps midnight), package blacklist
- Activity log per device

## Enforcement behavior

| Policy | Agent action |
| --- | --- |
| Daily limit reached | full-screen lock overlay + parked on home screen |
| Curfew active | same as above |
| App in blacklist | `am force-stop` + home key, no overlay |
| Lock Now (dashboard) | overlay until parent unlocks |
| Pause / Play / Home | `input keyevent 127 / 126 / 3` |

Overlay auto-releases when the curfew ends or a parent unlocks. Blacklisted
apps are force-stopped on every poll while in the foreground.

## Tests

- `server-relay/`: `npm test` (relay API smoke, 17 checks),
  `npm run test:headless` (dashboard in real Chromium; set `DASH_URL` /
  `DASH_PASSWORD` to target the embedded server),
  `npm run test:e2e` (full emulator evidence suite; artifacts in `evidence/`).
  Point the suites at the embedded server with
  `DASH_URL=http://127.0.0.1:8080 DASH_PASSWORD=<pw>` (requires
  `adb forward tcp:8080 tcp:8080`).

## Known limitations

- **No content-level tracking** — DRM/sandbox encryption prevents seeing which
  video/title is playing; only package usage durations are available.
- Fire OS may kill the background service; the accessibility watchdog and
  `START_STICKY` restart it. Keep Accessibility enabled for the agent to
  auto-revive. The dashboard's health record makes such restarts visible.
- The lock overlay requires `SYSTEM_ALERT_WINDOW` (granted via the script).
- Agent must be rebuilt with Android Studio; this repo ships no binaries.

## Security notes

This is a hobby project for your own devices. The dashboard auth is a shared
password sent over the LAN — put the server behind a VPN or `ssh -L` tunnel
before exposing it to the internet. The ADB loopback channel is the same
privilege a laptop with `adb connect` would have: treat the agent APK as
effectively root-adjacent on the TV.

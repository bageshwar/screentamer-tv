# ScreenTamer

Self-hosted parental control and screen-time monitoring for Amazon Fire TV (Fire OS). The parent dashboard and enforcement engine live **on the TV itself** — no dedicated server box, no cloud, no subscriptions.

> **Design essence:** the agent is the control plane. It embeds the dashboard server, persists its own history/logs/health on-device, and only talks to the outside world when you explicitly point it at an optional relay.

---

## Table of contents

- [Why](#why)
- [Features](#features)
- [Architecture](#architecture)
- [Quick start (single TV, device-only)](#quick-start-single-tv-device-only)
- [Using the dashboard](#using-the-dashboard)
- [Optional relay for multiple TVs](#optional-relay-for-multiple-tvs)
- [Policy & enforcement model](#policy--enforcement-model)
- [Observability](#observability)
- [Project layout](#project-layout)
- [Tests](#tests)
- [Security model](#security-model)
- [Known limitations](#known-limitations)
- [Further reading](#further-reading)

---

## Why

Fire TV has no built-in per-app screen-time limits. This project builds a complete parental control loop that needs nothing but the TV itself:

1. **Track** per-app screen time (UsageStatsManager).
2. **Enforce** daily limits, curfews and app blacklists from a dashboard.
3. **Control** remotely — pause/play, go home, force-stop, instant lockdown — via the device's own adbd (`127.0.0.1:5555`), no root.

## Features

- Embedded **parent dashboard** served by the agent on `http://<device-name>.local:8080/` (or by IP `http://<tv-ip>:8080/`)
  — no server process to host. The agent broadcasts its own mDNS/DNS-SD record
  (`<device-name>._http._tcp.local.` → `<hostname>.local:<port>` + A record), so
  no IP hunting and no `NsdManager` quirks.
- On-demand UI: refresh button + reload after every action (no polling, no push).
- Daily time limits, curfew windows (wraps midnight), package blacklists.
- Instant lockdown ("Pause TV Now"), pause/play, go home, force-stop any app.
- Per-day usage history (90-day retention) with a **when-apps-were-used timeline** (hour-by-hour band toggleable to per-app lanes), activity log, and agent **health record**.
- Optional **relay** (`server-relay/`) mirrors one or more TVs to a single
  computer dashboard — agents still work 100% without it.
- Fire OS survival kit: accessibility watchdog + `START_STICKY` service.

## Architecture

```
                        ┌─────────────────────────────────────────────┐
                        │             Fire TV (agent APK)              │
                        │                                             │
  browser on the LAN    │   Embedded HTTP server  :8080               │
  (phone/laptop) ──────►│   ├─ GET  /                 dashboard HTML  │
                        │   ├─ GET  /static/*         assets          │
                        │   ├─ POST /api/login        auth            │
                        │   ├─ GET  /api/state        live state      │
                        │   ├─ GET  /api/history      usage history   │
                        │   ├─ POST /api/config       policy update   │
                        │   └─ POST /api/command      remote actions  │
                        │                                             │
                        │   AgentService (foreground, tick every 30s) │
                        │   ├─ UsageStatsManager ── per-app screen    │
                        │   ├─ adbd :5555 (loopback) ── keyevents /   │
                        │   │                        force-stop       │
                        │   ├─ LockOverlay (SYSTEM_ALERT_WINDOW)      │
                        │   ├─ DeviceStore ── history/ log/ health    │
                        │   └─ AgentSocket ──► optional relay push    │
                        └──────────────┬──────────────▲───────────────┘
                                       │   ws://host:3000/ws (only if
                                       │   a relay URL is configured)
                                       ▼
                       server-relay/ (optional, Node.js)
                       aggregates several TVs for one dashboard
```

**Component responsibilities**

| Component | Location | Responsibility |
| --- | --- | --- |
| Agent service | `agent/` (Kotlin) | Tracking, enforcement, embedded server, persistence |
| Embedded HTTP server | `http/EmbeddedServer.kt` | Zero-dependency HTTP/1.1: dashboard + REST API |
| DeviceStore | `http/DeviceStore.kt` | Per-day usage files, activity log, health record |
| ADB client | `core/AdbClient.kt` | Raw ADB wire protocol to local adbd (media keys, force-stop) |
| LockOverlay | `overlay/LockOverlay.kt` | Full-screen lock over apps |
| Relay | `server-relay/` (Node.js) | Optional multi-TV aggregation + mirrored dashboard |
| Dashboard frontend | `server-relay/public/` | Served by both the embedded server and the relay |

## Quick start (single TV, device-only)

### 1. Build the agent APK

```bash
cd agent
./gradlew assembleDebug
# -> agent/app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+. On macOS with Android Studio:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

### 2. Sideload + grant permissions

On the TV: **Settings → My Fire TV → Developer Options → ADB Debugging ON** and **Apps from Unknown Sources ON**.

```bash
adb connect <fire-tv-ip>:5555
adb install agent/app/build/outputs/apk/debug/app-debug.apk
./scripts/setup-firestick.sh <fire-tv-ip>
```

The setup script grants the hidden permissions Fire OS hides:

```bash
adb shell pm grant com.screentamer.agent android.permission.PACKAGE_USAGE_STATS
adb shell appops set com.screentamer.agent SYSTEM_ALERT_WINDOW allow
adb shell settings put secure enabled_accessibility_services \
  com.screentamer.agent/.AgentAccessibilityService
```

### 3. Configure + start

Open the ScreenTamer app on the TV and set:

- **Dashboard password** — what parents type in the dashboard (required).
- **Dashboard port** — default `8080`.
- **Relay server URL** — leave blank for device-only mode.

Press **Save & Restart Service**, then **Test Local ADB** to verify the loopback
channel. The agent is live when the status shows the `.local` URL (e.g. `Dashboard: http://firetv.local:8080`).

> First ADB loopback connection: the agent generates its own RSA key, presents
> it to adbd, and Fire TV registers it without a confirmation dialog.

## Using the dashboard

Open `http://<device-name>.local:8080/` or `http://<fire-tv-ip>:8080/` from any browser on your LAN (or from a
computer via `adb forward tcp:8080 tcp:8080` → `http://127.0.0.1:8080/`). The
agent broadcasts a DNS-SD service named `<device-name>` (`_http._tcp`) whose
SRV record targets `<device-name>.local:<port>` and whose A record carries the
TV's LAN IP — `dns-sd -B _http._tcp` (macOS) or `avahi-browse -r _http._tcp`
(Linux) list every ScreenTamer on the network.

The dashboard is one responsive UI with three tabs, styled per client: the
**Report** tab is the default; the phone layout (`max-width: 700px`) is
thumb-first with 48px touch targets; the TV WebView (`?tv=1`) switches to
large D-pad-first cards with strong focus rings.

| Tab | Contents |
| --- | --- |
| Report | Device status strip (online/offline, model, last seen, now playing), day navigation (‹ prev · Today · next › or click a chart bar), today's total, yesterday / 7-day / average stats, 14-day usage chart, per-app breakdown with app icons, and a **when-apps-were-used timeline** — a color band of the dominant app per hour (toggle to per-app lanes), with app icons and durations; days without hourly data show the plain breakdown |
| Activity | Per-device activity log (newest first) + agent health card (start count, last tick, tick failures, last error) |
| Settings | Device controls (lock/unlock, pause/play, home, force-stop) + device info + policy editor (daily limit, curfew, blacklist with tap-to-add recent apps) |

App icons degrade gracefully: the device's **real icon** (`/api/icon?pkg=…`,
only on the agent), then a bundled brand SVG for well-known apps
(`/static/icons/*.svg`), then a colored letter avatar.

The dashboard fetches on demand: it loads on open, on **Refresh**, and
automatically after any action. No polling, no live socket.

### Design / dev loop (no device, no APK build)

The dashboard is plain HTML/CSS/JS in `server-relay/public/` — no compile
step. For fast design iteration with realistic mock data and hot reload:

```bash
cd server-relay
npm run dev        # http://127.0.0.1:4000  (password: demo)
```

Edit anything under `public/` and the open tab reloads automatically.
Screenshots of all three clients come from the headless harness:

```bash
DASH_URL=http://127.0.0.1:4000 DASH_PASSWORD=demo npm run test:headless
```

## Optional relay for multiple TVs

The relay (`server-relay/`, Node.js, only dependency: `ws`) collects several
TVs into one dashboard and keeps usage history per device on the host.

```bash
cd server-relay
npm install
npm start
```

First run generates `server-relay/data/config.json` with a random **device
token** and **parent password** (printed to the console). Then on each TV's
agent app, set:

- **Relay server URL**: `ws://<server-ip>:3000/ws`
- **Pairing token**: the token from `config.json`

The relay dashboard is available at `http://screentamer.local:3000/` (or `http://<server-ip>:3000/`). With no relay configured the
agent is fully self-sufficient and never phones home.

## Policy & enforcement model

| Policy | Agent action |
| --- | --- |
| Daily limit reached | Full-screen lock overlay + parked on home screen |
| Curfew active | Same as above (window wraps past midnight) |
| App in blacklist | `am force-stop` + home key, no overlay |
| Lock Now | Overlay until a parent unlocks |
| Pause / Play / Home | `input keyevent 127 / 126 / 3` |

The overlay auto-releases when the curfew ends or a parent unlocks. Policy
changes are applied instantly (config endpoint), persisted on-device, and
survive reboots.

## Observability

Every layer logs and every device keeps a persistent record:

| Layer | Where it logs |
| --- | --- |
| Agent HTTP server | `logcat -s ScreenTamer` — `[http] <ip> METHOD path -> status (ms)` per request |
| Agent service | `logcat -s ScreenTamer` — ticks, enforcement, commands, login rejections, lifecycle |
| Agent on-device | `files/data/{history/,log.json,health.json}` — served to the dashboard |
| Relay | Console — `[http]` access log, `[ws]` connect/hello/usage/log/disconnect |
| Dashboard | Browser console — `[dashboard]` login, fetches, actions, failures |

**Health record** (`health.json` on the TV, shown in the dashboard):
`startCount`, `lastStartAt`, `lastTickAt`, `tickFailures`, `lastError{ts,msg,trace}`.
A TV whose service keeps dying shows up immediately: tick failures climbing,
`lastTickAt` going stale.

## Project layout

```
screentamer-tv/
├── agent/                        # Fire TV agent (Kotlin, Gradle)
│   └── app/src/main/
│       ├── java/com/screentamer/agent/
│       │   ├── AgentService.kt           # foreground service: loop, server, relay
│       │   ├── MainActivity.kt           # on-TV settings UI (auto-starts agent)
│       │   ├── DashboardActivity.kt      # in-TV dashboard (WebView → embedded server)
│       │   ├── Prefs.kt                  # shared-prefs accessors
│       │   ├── core/                     # AdbClient, AgentSocket, PolicyManager
│       │   ├── http/                     # EmbeddedServer, DeviceStore
│       │   ├── tracking/                 # UsageTracker
│       │   ├── overlay/                  # LockOverlay
│       │   └── data/                     # Protocol, KnownApps
│       └── assets/www/                   # embedded dashboard (mirror of public/)
├── art/                           # icon/banner SVG sources (gen-icons.sh)
├── server-relay/                 # optional relay (Node.js)
│   ├── server.js                 # REST + WebSocket + dashboard
│   ├── lib/                      # store (JSON files), protocol
│   ├── public/                   # dashboard (served by both server+agent)
│   ├── data/                     # config.json, state, history (gitignored)
│   └── test/                     # smoke, headless, e2e suites
├── scripts/
│   ├── setup-firestick.sh        # grants + prefs injection
│   └── gen-icons.sh              # renders launcher/banner PNGs (qlmanage)
├── evidence/                     # captured test evidence (screenshots, results)
├── docs/
│   └── FIRE-TV-SETUP.md          # real-device runbook + FAQ/gotchas
├── screentamer-project-spec.md   # design specification
└── README.md
```

## Tests

All suites live under `server-relay/test/` and run with `npm`:

| Suite | Command | Target | What it proves |
| --- | --- | --- | --- |
| Smoke | `npm test` | Relay (`:3000`) | REST API contract, WS auth, history, state |
| Headless | `npm run test:headless` | `DASH_URL` | Dashboard renders real data in Chromium, no JS errors |
| E2E | `npm run test:e2e` | `DASH_URL` | Full device scenario: home, lock, unlock, curfew, restore + evidence |

Point the suites at the agent's embedded server:

```bash
adb forward tcp:8080 tcp:8080
DASH_URL=http://127.0.0.1:8080 DASH_PASSWORD=<pw> npm run test:e2e
```

E2E artifacts (screenshots, state/health snapshot, history) land in `evidence/e2e-<timestamp>/`.

## Security model

- Dashboard auth is a **shared password** (`parent_password`), sent in the
  request body or `x-parent-password` header; the embedded server and relay
  both enforce it (401 otherwise).
- The agent relays nothing unless a relay URL + device token are configured.
- The ADB loopback channel grants what a laptop with `adb connect` gets —
  treat the agent as root-adjacent on the TV.
- Hobby-grade: put any exposed service behind a VPN or SSH tunnel; don't
  expose the dashboard to the public internet.

## Known limitations

- **No content-level tracking** — DRM/sandbox encryption prevents seeing
  which video/title plays; only package usage durations are available.
- Fire OS may kill background services; the accessibility watchdog and
  `START_STICKY` revive the agent. Keep Accessibility enabled.
- Lock overlay requires `SYSTEM_ALERT_WINDOW` (granted via the script).
- The agent must be built from source; this repo ships no binaries.

## Further reading

- [`agent/README.md`](agent/README.md) — agent internals, prefs, enforcement
- [`server-relay/README.md`](server-relay/README.md) — relay setup, REST + WS protocol
- [`screentamer-project-spec.md`](screentamer-project-spec.md) — design specification

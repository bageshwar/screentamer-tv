# ScreenTamer Agent (Fire TV)

Kotlin foreground service that runs the entire ScreenTamer control plane on the
TV: it **tracks** screen time, **serves the parent dashboard**, **enforces**
policies, and **controls** the device through its own adbd loopback.

See the [root README](../README.md) for the full picture. This document covers
building, installing, configuration and internals.

## What it does

- **Track** — per-package foreground time via `UsageStatsManager`, sampled on a
  30-second loop.
- **Serve** — an embedded zero-dependency HTTP/1.1 server (`:8080` by default)
  hosting the dashboard (`/`) and REST API (`/api/*`).
- **Persist** — per-day usage history, an activity log and a health record in
  the app's private storage (`DeviceStore`).
- **Enforce** — daily limits, curfews, blacklists, instant lockdown; full-screen
  lock overlay + home-screen parking via `input keyevent 3`.
- **Control** — media keys (`input keyevent 126/127`), `am force-stop`, home,
  lock/unlock — through the device's own **adbd at `127.0.0.1:5555`**.
- **Relay (optional)** — pushes usage + logs to `server-relay/` over WebSocket
  only when a relay URL is configured.
- **Survive** — accessibility-service watchdog + `START_STICKY` keep the agent
  alive against Fire OS's aggressive process killer.

## Building

```bash
cd agent
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # macOS
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

## Installing & configuring

```bash
adb connect <fire-tv-ip>:5555
adb install app/build/outputs/apk/debug/app-debug.apk
../scripts/setup-firestick.sh <fire-tv-ip>
```

The setup script grants `PACKAGE_USAGE_STATS`, `SYSTEM_ALERT_WINDOW` and the
accessibility service, then injects `parent_password`, `server_port` and an
optional `server_url` directly into the app's prefs. Everything can also be
entered in the on-TV settings screen (**Save & Restart Service**).

### Prefs reference

Stored in `shared_prefs/screentamer_prefs.xml` (app-private).

| Key | Default | Meaning |
| --- | --- | --- |
| `parent_password` | *(empty)* | Dashboard login — required, else every `/api/*` call 401s |
| `server_port` | `8080` | Embedded dashboard/API port |
| `server_url` | *(empty)* | Optional relay WebSocket URL (`ws://host:3000/ws`); blank = device-only |
| `pairing_token` | *(empty)* | Relay pairing token (from the relay's `config.json`) |
| `device_name` | `Build.MODEL` | Name shown in dashboards |
| `adb_mode` | `device` | `device` = local adbd; `host` = desktop adb bridge (test rig) |
| `adb_host` / `adb_port` | `127.0.0.1` / `5555` | adbd address (`10.0.2.2` / `5037` for the emulator bridge) |
| `adb_transport_id` | *(empty)* | Host-bridge transport (e.g. `emulator-5554`) |
| `last_policy` | *(none)* | Last applied policy (survives reboots) |

## Internals

```
AgentService (foreground, notification required)
 ├─ loop: every 30s ── tick()
 │    ├─ UsageTracker.usageToday()       → per-app ms today
 │    ├─ UsageTracker.foregroundApp()    → current package
 │    ├─ PolicyManager.shouldLock(...)   → enforcement decision
 │    ├─ LockOverlay.show/hide + home key
 │    ├─ DeviceStore.recordUsage/noteTick
 │    └─ AgentSocket.send(usage)         → relay (if connected)
 ├─ EmbeddedServer (thread-per-request)
 │    └─ Handler → PolicyManager / AdbClient / DeviceStore
 └─ AgentSocket → relay messages: welcome/config/command
```

- **Tick loop** (`TRACK_INTERVAL_MS = 30_000`): every failure is caught and
  recorded in the health record (`noteTickFailure`).
- **Embedded server** (`http/EmbeddedServer.kt`): routes `GET /`,
  `GET /static/*`, `POST /api/login`, `GET /api/state`, `GET /api/history`,
  `POST /api/config`, `POST /api/command`. Auth: `x-parent-password` header or
  `password` in query/body. Assets are served from `assets/www/` (a mirror of
  `server-relay/public/`).
- **DeviceStore** (`http/DeviceStore.kt`): `files/data/history/<yyyy-mm-dd>.json`
  (atomic writes), `files/data/log.json` (cap 200 entries), `files/data/health.json`:

  ```json
  {
    "startCount": 3,
    "lastStartAt": 1786157667410,
    "lastTickAt": 1786158247573,
    "tickFailures": 0,
    "lastError": { "ts": 0, "msg": "", "trace": "" }
  }
  ```

  History retention: 90 days (`sweep` on service start).

- **ADB client** (`core/AdbClient.kt`): speaks the real adb wire protocol
  (24-byte little-endian headers, CNXN → AUTH → key registration). Two modes:
  - `DEVICE` — talks to the TV's own adbd over loopback (production).
  - `HOST_BRIDGE` — talks to a desktop adb server (`adb -a server start`) and
    selects a transport by id; this is how the emulator test rig works
    (`10.0.2.2:5037`, transport `emulator-5554`).

## Enforcement

| Trigger | Result |
| --- | --- |
| `dailyLimitMs` exceeded | overlay + home key |
| Curfew window active | overlay + home key |
| Foreground app blacklisted | `am force-stop <pkg>` + home key |
| `lockdown: true` (Lock Now) | overlay until unlocked |

Commands: `pause` → keyevent 127, `play` → 126, `home` → 3, `stopApp <pkg>` →
`am force-stop`. Command results (adb ok/FAILED) are logged to the activity log.

## Logging

All tags are prefixed `ScreenTamer/` so `adb logcat -s ScreenTamer` captures
the whole agent.

| Tag | What |
| --- | --- |
| `ScreenTamer/AgentService` | ticks, enforcement, commands, logins, lifecycle |
| `ScreenTamer/EmbeddedServer` | `[http] <client-ip> METHOD path -> status (ms)` per request |
| `ScreenTamer/AgentSocket` | connect attempts, reconnects, failures, message types |
| `ScreenTamer/AdbClient` | connects, bridge handshake, shell failures |
| `ScreenTamer/UsageTracker` | usage snapshots, missing PACKAGE_USAGE_STATS |
| `ScreenTamer/DeviceStore` | persistence writes, sweeps, service-start bumps |
| `ScreenTamer/LockOverlay` | overlay show/hide |
| `ScreenTamer/Accessibility` | watchdog binds/unbinds |
| `ScreenTamer/BootReceiver` | boot-time service start |
| `ScreenTamer/MainActivity` | app opens, save/start/stop actions, adb self-test |
| `ScreenTamer/Dashboard` | in-TV WebView dashboard activity (launcher; `?tv=1`) |

The app icon opens the **settings screen** (`MainActivity`); "Open Dashboard
on TV" opens the in-TV dashboard (`DashboardActivity`, `?tv=1`), which starts
the agent and retries the WebView load until the embedded server answers.

## Testing on the emulator

```bash
# host adb server reachable from the guest, agent in HOST_BRIDGE mode:
adb -a server start
adb shell "run-as com.screentamer.agent am startservice --user 0 \
  -n com.screentamer.agent/.AgentService -a com.screentamer.agent.START"
adb forward tcp:8080 tcp:8080
curl -H "x-parent-password: <pw>" http://127.0.0.1:8080/api/state
```

Opening the app (`am start`) auto-starts the agent service — no manual
"Start Agent" needed; the accessibility watchdog + boot receiver keep it
alive afterwards.

The full E2E evidence suite runs against the embedded server with
`DASH_URL=http://127.0.0.1:8080 DASH_PASSWORD=<pw>` (see
[`server-relay/test/e2e`](../server-relay/test/e2e/run.js)).

# ScreenTamer — Design Specification

Self-hosted parental control and screen-time monitoring for Amazon Fire TV (Fire OS), built as a hobby project. Current as of the 2026-08 device-only release.

## 1. Design essence

**The TV is the control plane.** The agent embeds the dashboard server, persists its own history/logs/health on-device, and enforces policies with its own loopback adb channel. There is no required server, cloud, or subscription.

Five principles drive the design:

1. **Device-only by default** — every `/api/*` route, the dashboard HTML, and all persistence live inside the agent APK. The TV works standalone.
2. **On-demand UX** — the dashboard fetches on load, on Refresh, and after each action. No polling, no push channel, no sockets in the UI. Fewer moving parts, honest state.
3. **Persistence is local** — per-day usage files, an activity log and a health record are written atomically on the device; the dashboard reads them back over HTTP.
4. **Observability is a feature** — a persistent health record (start count, last tick, tick failures, last error) plus request/command logs make service-lifecycle problems visible from the dashboard on real hardware.
5. **Relay is opt-in** — `server-relay/` (Node.js) mirrors one or more TVs over WebSocket when a relay URL is configured. It is the only component the agent "phones home" to.

## 2. System architecture

```
                    ┌──────────────────────────────────────────────┐
                    │                  Fire TV                      │
   parent browser   │  EmbeddedServer (HTTP :8080, zero-dep)        │
   ───────────────► │  ├─ static dashboard  (GET /, /static/*)      │
   (any device      │  ├─ /api/login  /api/state  /api/history      │
    on the LAN)     │  └─ /api/config /api/command  (auth required) │
                    │                                               │
                    │  AgentService (foreground; 30s tick loop)     │
                    │  ├─ UsageTracker   → per-app screen time      │
                    │  ├─ PolicyManager  → limit/curfew/blacklist   │
                    │  ├─ LockOverlay    → SYSTEM_ALERT_WINDOW lock │
                    │  ├─ AdbClient      → local adbd :5555         │
                    │  └─ DeviceStore    → history/ log/ health     │
                    │  └─ AgentSocket    → relay (only if URL set)  │
                    └───────────────┬──────────────▲────────────────┘
                                    │ ws://host:3000/ws (optional)
                                    ▼
                        server-relay/ (Node.js, ws only)
                        aggregates N TVs; same dashboard served
```

### Components

| Component | Tech | Role |
| --- | --- | --- |
| `agent/` | Kotlin, min API 23 (Android 6.0) | Fire TV agent: tracking, enforcement, embedded server, persistence, optional relay client |
| `EmbeddedServer` | Kotlin (JDK sockets) | Thread-per-request HTTP/1.1; static assets + REST; password auth (header/query/body) |
| `DeviceStore` | Kotlin (JSON files) | Per-day history (atomic tmp+rename), capped activity log (200), health record |
| `AdbClient` | Kotlin (raw ADB wire) | 24-byte LE framing, CNXN/AUTH handshake, key registration; DEVICE and HOST_BRIDGE modes |
| `LockOverlay` | Kotlin (WindowManager) | Full-screen lock overlay |
| `UsageTracker` | Kotlin (UsageStatsManager) | Per-package foreground ms for the current day; foreground app from usage events |
| `AgentSocket` | Kotlin (OkHttp WS) | Optional relay push: hello/usage/log; receives welcome/config/command |
| `server-relay/` | Node.js (`ws`) | Optional aggregation server; same REST contract + dashboard |
| Dashboard | Vanilla HTML/CSS/JS | Served identically by the embedded server and the relay |

## 3. Agent internals

### 3.1 Lifecycle

- `AgentService` is a foreground service (`START_STICKY`, dataSync type) with a notification.
- Started by `MainActivity` (user), `AgentAccessibilityService.onServiceConnected` (watchdog), or `BootReceiver` (boot).
- `onCreate`: init store (`bumpServiceStart`, `sweep(90d)`), start `EmbeddedServer`, start relay socket if a URL is set, start the 30s loop.
- `onDestroy`: cancel loop, stop socket/server, hide overlay.
- `RECONFIGURE`: restarts the relay socket when prefs change.

### 3.2 Tick loop (every 30 s)

1. `UsageTracker.snapshot()` → per-app ms today (absolute totals) + the delta since the last tick.
2. `foregroundApp()` → current package (from usage events, not removed `runningTasks` API).
3. Enforce: blacklisted foreground app → `force-stop` + home; limit/curfew/lockdown → show overlay + home; else hide overlay.
4. `DeviceStore.recordUsage(today, apps, hourly)` + `noteTick()` — the tick delta is attributed to the current hour's bucket (`_hourly`).
5. If relay connected: `send(usage{deviceId,date,apps,hourly,totalMs,currentApp,locked})` — `hourly` is the day's full per-hour map (reconnect-safe).
6. Any exception → `noteTickFailure(e)` (health record) — the loop survives.

### 3.3 Embedded server (REST contract)

Auth: password from `x-parent-password` header, or `password` query/body. Unauthenticated `/api/*` → 401. Static paths are public.

| Route | Handler | Notes |
| --- | --- | --- |
| `GET /` , `GET /static/*` | assets from `assets/www/` | mirror of `server-relay/public/` (subdirs preserved, e.g. `icons/`) |
| `GET /api/icon?pkg=` | real app icon (PNG) | via `PackageManager.getApplicationIcon`; unknown package → 404; relay/mock 404 too → client falls back |
| `POST /api/login` | `{password}` | → `{ok}` / 401 |
| `GET /api/state` | `{defaultPolicy, devices{}, usage{}}` | device: online, lastSeen, currentApp, locked, totalMs, policy, **log**, **health**, serverPort, **iconEndpoint** |
| `GET /api/history?days=` | `{deviceId, days, today, history[]}` | oldest→newest, 1–365 days |
| `POST /api/config` | `{policy}` | normalizes + applies + persists policy |
| `POST /api/command` | `{command:{type,pkg?}}` | dispatches; unknown type → 400 |

### 3.4 Persistence (DeviceStore)

```
files/data/
├── history/<yyyy-mm-dd>.json    # {pkg: msToday, "_hourly": {"<hour>": {pkg: ms}}} per day, atomic writes
├── log.json                     # [{ts, msg}] capped at 200
└── health.json                  # see §6
```

Retention: 90 days, swept on service start. The `_hourly` key stores per-hour
per-app buckets (the dashboard's timeline source); it is never counted toward
daily totals and is stripped from state/history responses where the client
doesn't need it.

### 3.5 Enforcement model

| Policy | Condition | Agent action |
| --- | --- | --- |
| Daily limit | `totalMsToday ≥ dailyLimitMs` | overlay + home key |
| Curfew | clock inside window (wraps midnight) | overlay + home key |
| Blacklist | app is foreground + listed | `am force-stop` + home key, no overlay |
| Lockdown | `lockdown: true` (Lock Now) | overlay until unlocked |
| Media | command | `keyevent 127` pause / `126` play / `3` home |

Overlay releases automatically when the condition clears; the policy survives reboots (`last_policy` pref).

### 3.6 ADB client

- Raw wire protocol: 24-byte little-endian headers, `CNXN` hello, `AUTH` token → RSA signature → public-key registration (Fire TV accepts without dialog).
- `DEVICE` mode: loopback `127.0.0.1:5555` (production).
- `HOST_BRIDGE` mode: desktop adb server + `host:transport:<id>` (test rig / parent-machine bridging). This is how the emulator rig works: host `adb -a server start`, agent connects to `10.0.2.2:5037`, transport `emulator-5554`.

## 4. Relay (`server-relay/`)

- `ws://<host>:3000/ws`; agents push `hello` (token-paired), `usage` (heartbeat every 30s, carrying the day's `hourly` per-hour map so the dashboard can draw the timeline), `log`; relay sends `welcome`/`config` (policy) and `command`.
- REST contract identical to the agent's embedded server (dashboard client works against either).
- State persists to `data/state.json` + `data/history/<deviceId>/<yyyy-mm-dd>.json`; liveness sweep marks agents offline after 45 s silence; history pruned after 90 days.
- Console access log (`[http]`/`[ws]`) covers requests, connects, hellos, usage, logs, rejections and disconnects.

## 5. Dashboard (shared frontend)

- One responsive UI, three views, styled per client:
  - **Report** (default): device status strip (online/offline dot, model/OS,
    last seen, now playing, lock/blacklist badges), day navigation
    (‹ prev · Today · next ›; chart bars are clickable too), today/yesterday/
    7-day/average stats, 14-day chart, and a per-app breakdown. A **when-apps-were-used timeline** shows the day's hour-by-hour activity: a color band (dominant app per hour, color-blind-safe Okabe-Ito palette) toggleable to per-app lanes with exact hour blocks; the legend uses app icons + durations. Days recorded before the agent collected hourly data fall back to the plain breakdown.
  - **Activity**: per-device log (newest first, "usage reported" heartbeats
    collapsed) + agent health card (start count, last tick, tick failures,
    last error).
  - **Settings**: device controls (lock/unlock, pause/play, home, force-stop),
    device info, policy editor (daily limit, curfew, blacklist with
    tap-to-add "recent apps" chips + manual package input).
- **Per-client layout**: laptop (default, ≤1240px content), phone
  (`max-width: 700px`: full-width tab row, 48px touch targets, 2-col stats,
  top-6 breakdown with "show more"), TV (`?tv=1`: large cards, 5px focus
  rings + glow + scale on every focusable element, 300px chart).
- **App icons degrade gracefully**: device real icon (`/api/icon?pkg=`, only
  when state says `iconEndpoint: true`) → bundled brand SVG
  (`/static/icons/*.svg`) → deterministic colored letter avatar.
- On-demand data flow: `loadData()` = `Promise.all(/api/state, /api/history)`
  on boot, Refresh button, and after every command/policy save.
  `x-parent-password` header on every call.
- Day labels trust the server's `today` over the browser clock (timezone
  safety); chart hint stamps the fetch time (`updated hh:mm`).

## 6. Observability

| Signal | Where | Purpose |
| --- | --- | --- |
| `health.json` | dashboard device card | `startCount`, `lastStartAt`, `lastTickAt`, `tickFailures`, `lastError{ts,msg,trace}` — detect dead service / crash loops on hardware |
| HTTP access log | logcat `EmbeddedServer` / relay console | `[http] METHOD path -> status (ms)` |
| Activity log | dashboard | policy changes, commands (+ adb ok/FAILED), enforcement events, relay connect/disconnect, failed logins |
| Tick summary | logcat | `totalMs=… apps=N current=…` |

Failed dashboard logins are logged (logcat always; device log for non-empty password attempts).

## 7. Permissions & deployment

| Capability | Permission | Grant path |
| --- | --- | --- |
| Usage tracking | `PACKAGE_USAGE_STATS` | `pm grant` (script) |
| Lock overlay | `SYSTEM_ALERT_WINDOW` | `appops set … allow` (script) |
| Watchdog / auto-restart | `BIND_ACCESSIBILITY_SERVICE` | `settings put secure enabled_accessibility_services …` |
| Media keys / force-stop | adbd loopback | Fire TV Developer Options: ADB Debugging ON |

Setup: build APK → sideload → `scripts/setup-firestick.sh` (grants + prefs injection: password, port, optional relay URL).

## 8. Security model

- Shared parent password (configurable per device; relay has its own). Sent in body/header; nothing stored client-side beyond the browser's localStorage.
- Relay pairing requires the device token; wrong token/password are rejected and logged.
- Relay URL blank ⇒ the agent makes no outbound connection.
- ADB loopback is root-adjacent by nature; treat the APK as trusted.
- Not hardened for the public internet: use VPN/SSH tunnels if exposing.

## 9. Testing strategy

| Suite | Scope | Command |
| --- | --- | --- |
| Smoke | Relay REST/WS contract (20 checks) | `npm test` (in `server-relay/`) |
| Headless | Dashboard in real Chromium, real data, no JS errors; laptop + phone + TV layouts, timeline band/lanes/toggle, screenshots | `npm run test:headless` (against relay, `DASH_URL=…:4000 DASH_PASSWORD=demo` for mock) |
| E2E | Emulator: home → lock → unlock → curfew → restore → history → dashboard, 18 checks + screenshots/evidence | `npm run test:e2e` |

Design iteration without any device or APK build: `npm run dev` in
`server-relay/` (mock dataset + hot reload on `public/` changes).

E2E/headless target either server via `DASH_URL` (+ `DASH_PASSWORD` for the embedded server). Evidence (screenshots, state/health snapshot, history JSON, results) lands in `evidence/e2e-<timestamp>/` and is committed to the repo.

## 10. Out of scope / limitations

- No content-level tracking (DRM prevents title-level detail) — package durations only.
- Fire OS may kill the service; the watchdog revives it (observable via the health record).
- No binaries shipped; build from source.
- Single-parent-password model; no per-child accounts.
- Relay is single-user; no TLS by default (LAN/VPN only).

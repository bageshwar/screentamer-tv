# ScreenTamer Relay (`server-relay/`)

Optional Node.js relay that mirrors ScreenTamer agents to a single computer
dashboard. The **agent does not need it**: every TV serves its own dashboard
(`http://<tv-ip>:8080`). Configure a relay only when you want several TVs
aggregated in one place — e.g. a home server monitoring the whole house.

- **REST** — the same `/api/*` contract the agent's embedded server implements,
  so the dashboard works against either.
- **WebSocket** (`/ws`) — agents push usage/logs; the relay forwards policy
  config and commands back.
- **Storage** — JSON files: config, per-device state, per-device per-day history.

## Setup

```bash
cd server-relay
npm install
npm start
```

First run generates `data/config.json` (print the secrets once, on the console):

```json
{
  "deviceToken": "…random…",
  "parentPassword": "…random…",
  "port": 3000
}
```

- Dashboard: `http://screentamer.local:3000/` (or `http://<server-ip>:3000/`)
- Agent relay URL: `ws://<server-ip>:3000/ws`
- On each TV's agent app: set the relay URL + pairing token (the device token).

## Design / dev loop (no device, no APK build)

```bash
npm run dev        # http://127.0.0.1:4000  (password: demo)
```

Serves the dashboard from `public/` against a generated mock dataset (three
devices, 14 days of usage, realistic logs/health) with **hot reload** — edit
anything under `public/` and the browser reloads automatically. `npm start`
serves the same dashboard against real agents instead.

## REST API

Auth: `POST` bodies carry `password`; `GET` endpoints are public on the relay
(but the same endpoints on the agent's embedded server require
`x-parent-password` — the dashboard sends it everywhere, so one client works
for both).

| Method | Path | Body / query | Returns |
| --- | --- | --- | --- |
| POST | `/api/login` | `{password}` | `{ok}` or 401 |
| GET | `/api/state` | — | `{defaultPolicy, devices{…}, usage{…}}` |
| GET | `/api/history` | `deviceId`, `days` (1–365, default 14) | `{deviceId, days, today, history[]}` |
| POST | `/api/config` | `{password, deviceId, policy}` | `{ok}`; pushes policy to the agent |
| POST | `/api/command` | `{password, deviceId, command:{type, pkg?}}` | `{ok, delivered}`; forwards to the agent |
| GET | `/` , `/static/*`, `/favicon.ico` | — | Dashboard assets |

Commands: `pause`, `play`, `home`, `stopApp` (with `pkg`), `lock`, `unlock`.

> `/api/icon?pkg=…` (real app icons) exists only on the agent's embedded
> server. The relay and dev server 404 it, and the dashboard falls back to
> bundled brand SVGs / letter avatars.

Policy shape:

```json
{
  "dailyLimitMs": 7200000,
  "curfew": { "enabled": false, "start": "20:00", "end": "06:00" },
  "blacklist": ["com.netflix.ninja"],
  "lockdown": false
}
```

## WebSocket protocol (`/ws`)

Envelope: `{"type": "<type>", …fields}`.

| Direction | Type | Fields | Meaning |
| --- | --- | --- | --- |
| Agent → relay | `hello` | `role:"agent"`, `token`, `deviceId`, `name`, `model`, `version` | Pairing; wrong token → `error` |
| Relay → agent | `welcome` | `policy` | Current device policy |
| Agent → relay | `usage` | `deviceId`, `date`, `apps{}`, `totalMs`, `currentApp`, `locked` | Heartbeat + telemetry (every 30s) |
| Agent → relay | `log` | `deviceId`, `msg`, `ts` | Activity log line |
| Relay → agent | `config` | `policy` | Policy update from dashboard |
| Relay → agent | `command` | `command`, `pkg?` | Remote action |
| Parent → relay | `hello` | `role:"parent"`, `password` | Dashboard login; then `state` pushes |

Liveness: agents marked offline after 45s without a message (heartbeat sweep);
history files older than 90 days are pruned on boot and every 6 hours.

## Storage layout

```
data/
├── config.json          # secrets + port (gitignored)
├── state.json           # per-device live state + activity log
└── history/<deviceId>/<yyyy-mm-dd>.json
```

## Console logging

```
[http] GET /api/state -> 200 (0ms)
[ws] connection opened (agents=0, parents=0)
[ws] agent hello: 9d4a2d25e2f1bff2 (sdk_google_atv64_arm64, 13)
[ws] usage: 9d4a2d25e2f1bff2 2026-08-08 totalMs=3087883 locked=false apps=3
[ws] log from 9d4a2d25e2f1bff2: command: instant lockdown
[ws] closed (agents=1, parents=0, <id> offline)
```

## Tests

Run from `server-relay/`:

```bash
npm test                                    # smoke: REST + WS contract (relay)
npm run test:headless                       # dashboard in real Chromium
npm run test:e2e                            # full emulator evidence suite
```

Target the agent's embedded server instead of the relay:

```bash
adb forward tcp:8080 tcp:8080
DASH_URL=http://127.0.0.1:8080 DASH_PASSWORD=<pw> npm run test:e2e
```

Or iterate on design only, against mock data:

```bash
npm run dev                                   # mock server on :4000
DASH_URL=http://127.0.0.1:4000 DASH_PASSWORD=demo npm run test:headless
```

The headless harness now covers all three clients — laptop view, phone
viewport (touch-target checks) and `?tv=1` (D-pad focus-ring proof) — and
writes screenshots to `test/headless/artifacts/`.

E2E artifacts land in `evidence/e2e-<timestamp>/` at the repo root.

## Known issues

- **Real device app icons don't load through the relay.** The dashboard's
  device-icon endpoint (`GET /api/icon?pkg=`) only exists on the agent's
  embedded server; the relay has no route for it, so `404` responses push the
  dashboard to its bundled-SVG / letter-avatar fallback chain. Real per-app
  icons therefore appear only when the dashboard is served directly by the
  agent (the primary path — `adb forward` + the agent's `serverPort`), not via
  the relay. Fix idea: proxy `/api/icon` from the relay to the connected agent
  (same way commands/config are forwarded), or mirror package-name → icon
  lookups on the relay host.

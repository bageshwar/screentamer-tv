---
layout: default
title: Policy & enforcement
---

# Policy & enforcement model

Policies are edited in the **Settings** tab and applied instantly on save.
They are persisted on-device and survive reboots.

| Policy | Agent action |
| --- | --- |
| Daily limit reached | Full-screen lock overlay + parked on home screen |
| Curfew active | Same as above (window wraps past midnight) |
| App in blacklist | `am force-stop` + home key, no overlay |
| Lock Now | Overlay until a parent unlocks |
| Pause / Play / Home | `input keyevent 127 / 126 / 3` |

The overlay auto-releases when the curfew ends or a parent unlocks.

## Settings you can edit

- **Daily limit** — maximum screen time per app per day.
- **Curfew window** — a start/stop window that wraps midnight; active while
  it's inside the window.
- **Blacklist** — apps force-stopped on launch; add by typing a package name
  or tapping a recent app from the quick-add chips.

## Observability

Every layer logs and every device keeps a persistent record:

| Layer | Where it logs |
| --- | --- |
| Agent HTTP server | `logcat -s ScreenTamer` — per-request access lines |
| Agent service | `logcat -s ScreenTamer` — ticks, enforcement, commands, login rejections |
| Agent on-device | `files/data/{history/,log.json,health.json}` |
| Relay | Console — `[http]` and `[ws]` lines |
| Dashboard | Browser console — `[dashboard]` login, fetches, actions, failures |

The health record (`health.json`) carries `startCount`, `lastStartAt`,
`lastTickAt`, `tickFailures`, `lastError{ts,msg,trace}` — a TV whose service
keeps dying shows up immediately in the dashboard.
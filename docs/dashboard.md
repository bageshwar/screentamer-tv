---
layout: default
title: Using the dashboard
---

# Using the dashboard

Open `http://<device-name>.local:8080/` (or `http://<fire-tv-ip>:8080/`) from
any browser on your LAN, or reach it from a computer via
`adb forward tcp:8080 tcp:8080` → `http://127.0.0.1:8080/`.

## Finding your TV on the network

The agent broadcasts a DNS-SD (mDNS) service named `<device-name>`
(`_http._tcp`). Its SRV record targets `<device-name>.local:<port>` and its A
record carries the TV's LAN IP.

- **macOS:** `dns-sd -B _http._tcp`
- **Linux:** `avahi-browse -r _http._tcp`

This lists every ScreenTamer on the network — no IP hunting.

## Entering the password

The dashboard asks for the **parent password** you set in the app. It is stored
in your browser and sent with each request.

## Tabs

The dashboard is one responsive UI with three tabs. It styles itself per
client: phone (`max-width: 700px`) gets thumb-first layout with 48px touch
targets; the TV WebView (`?tv=1`) switches to large D-pad-first cards with
strong focus rings.

| Tab | Contents |
| --- | --- |
| **Report** | Device status strip, day navigation (‹ prev · Today · next ›), today's total, yesterday / 7-day / average stats, 14-day usage chart, per-app breakdown with icons, and the when-apps-were-used timeline (hour-by-hour color band, toggleable to per-app lanes) |
| **Activity** | Per-device activity log (newest first) + agent health card (start count, last tick, tick failures, last error) |
| **Settings** | Device controls (lock/unlock, pause/play, home, force-stop), device info, policy editor (daily limit, curfew, blacklist with tap-to-add recent apps) |

## App icons

Icons degrade gracefully:

1. the device's **real icon** (`/api/icon?pkg=…`, only from the agent),
2. a bundled brand SVG for well-known apps (`/static/icons/*.svg`),
3. a colored letter avatar.

## How loading works

The dashboard fetches on demand: on open, on **Refresh**, and automatically
after any action. No polling, no live socket.
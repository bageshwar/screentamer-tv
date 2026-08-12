---
layout: default
title: Relay for multiple TVs
---

# Optional relay for multiple TVs

The relay (`server-relay/`, Node.js — only dependency: `ws`) collects several
TVs into one dashboard and keeps usage history per device on the host. Agents
work 100% without it.

## Setup

```bash
cd server-relay
npm install
npm start
```

First run generates `server-relay/data/config.json` with a random **device
token** and **parent password** (printed to the console).

## Pairing each TV

On each TV's ScreenTamer app, set:

- **Relay server URL:** `ws://<server-ip>:3000/ws`
- **Pairing token:** the token printed from `config.json`

The relay dashboard is available at `http://screentamer.local:3000/` (or
`http://<server-ip>:3000/`).

## Payloads

The agent pushes per-day usage snapshots, activity log lines and health
records over a WebSocket (`ws://host:3000/ws`), gated on the pairing token.

## Privacy

With no relay configured the agent is fully self-sufficient and never phones
home. Set the relay URL only if you want remote/multi-TV aggregation.

## Network exposure

The relay is plain HTTP/`ws://` — pairing tokens and relay traffic are
unencrypted. Keep the relay on a **trusted LAN only**, or reach it exclusively
through a **VPN or SSH tunnel**. Do not expose the dashboard directly to the
public internet. See [Security](security.html) for the full threat model.
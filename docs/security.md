---
layout: default
title: Security
---

# Security model

- Dashboard auth is a **shared password** (`parent_password`), sent in the
  request body or `x-parent-password` header; the embedded server and relay
  both enforce it (401 otherwise).
- The agent relays nothing unless a relay URL + device token are configured.
- The ADB loopback channel grants what a laptop with `adb connect` gets —
  treat the agent as root-adjacent on the TV.
- Hobby-grade: put any exposed service behind a VPN or SSH tunnel; don't
  expose the dashboard to the public internet.

## Known limitations

- **No content-level tracking** — DRM/sandbox encryption prevents seeing which
  video/title plays; only package usage durations are available.
- Fire OS may kill background services; the accessibility watchdog and
  `START_STICKY` revive the agent. Keep Accessibility enabled.
- The lock overlay requires `SYSTEM_ALERT_WINDOW` (granted via the setup
  script).
- The agent must be built from source; this repo ships no binaries.
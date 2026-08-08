# Project Specification: ScreenTamer (Firestick Parental Control Hobby App)

## 1. Project Overview
**ScreenTamer** is a custom, self-hosted parental control and remote monitoring system designed for Amazon Fire TV devices (Fire OS). Built as an experimental hobby project, it leverages local ADB loopback commands, Accessibility Services, and a lightweight web dashboard to enforce screen time limits and remotely control device playback.

---

## 2. Architecture & Components

The system consists of two primary components:

1. **Firestick Agent App (Android APK)** — the primary control plane
   - Foreground service running on Fire OS.
   - **Embedded HTTP server on the device** (`:8080`) serving the parent
     dashboard and the REST API (on-demand fetch — no polling, no push).
   - On-device persistence: per-day usage history files, activity log, and a
     health record (service start count, last tick, tick failures, last error)
     so service lifecycle problems are visible from the dashboard.
   - Local ADB loopback executor (`localhost:5555`) for system actions.
   - Optional WebSocket client that pushes usage/logs to a relay server when
     a relay URL is configured.

2. **Parent Dashboard (Web)** — served by the agent itself
   - Vanilla HTML/JS/CSS, works in any smartphone/desktop browser on the LAN.
   - The same dashboard is also served by an optional **relay server**
     (`server-relay/`, Node.js) that aggregates multiple TVs from one computer.

---

## 3. Core Features & Functional Specs

### 3.1 Usage Tracking & Time Management
- **Active App Detection:** Polls current foreground package via `UsageStatsManager` or `dumpsys window`.
- **Session & Daily Timers:** Tracks cumulative time spent across streaming applications (e.g., YouTube, Netflix).
- **Time Limits & Curfews:** Configurable daily allowances (e.g., max 2 hours/day) and blackout windows (e.g., block after 8:00 PM).

### 3.2 Lock Screen & Enforcement
- **Overlay Enforcement:** Displays a full-screen lock overlay (`SYSTEM_ALERT_WINDOW`) when screen time limits are reached.
- **App Termination:** Sends ADB commands (`input keyevent 3` / `am force-stop <package>`) to force the user back to the home screen or kill blacklisted apps.

### 3.3 Remote Control & Commands
- **Pause / Play Remote:** Parents can trigger media key events (`input keyevent 127` / `126`) from the web dashboard.
- **Instant Lockdown:** A "Pause TV Now" toggle on the web app immediately locks the Firestick interface.

---

## 4. Technical Feasibility & Android/Fire OS Permissions

Fire OS restricts standard Android settings screens for key permissions. The following permissions and privileges are required:

| Feature / Action | Required Permission / OS Capability | Fire OS Status & Setup Method |
| :--- | :--- | :--- |
| **Foreground App Tracking** | `android.permission.PACKAGE_USAGE_STATS` | Hidden UI. Granted via ADB: `adb shell pm grant <package> android.permission.PACKAGE_USAGE_STATS` |
| **Lock Screen Overlay** | `android.permission.SYSTEM_ALERT_WINDOW` | Restricted. Granted via ADB: `adb shell appops set <package> SYSTEM_ALERT_WINDOW allow` |
| **App Interception** | `BIND_ACCESSIBILITY_SERVICE` | Supported. Enabled via Fire TV Settings > Accessibility or via ADB shell commands |
| **System Key Injection / Killing** | Local ADB Loopback (`127.0.0.1:5555`) | ADB Debugging must be toggled ON in Fire TV Developer Options |

---

## 5. Deployment & Setup Workflow

Because this relies on system-level developer permissions, it cannot be distributed via the Amazon Appstore.

1. **Enable Developer Options:** Toggle **ADB Debugging** and **Apps from Unknown Sources** on the Fire TV device.
2. **Sideload Application:** Install the compiled `ScreenTamer.apk` using standard ADB (`adb install FireGuard.apk`).
3. **Execute Post-Install ADB Script:** Run a script to grant hidden app ops and permissions:
   ```bash
   adb shell pm grant com.example.fireguard android.permission.PACKAGE_USAGE_STATS
   adb shell appops set com.example.fireguard SYSTEM_ALERT_WINDOW allow
   adb shell settings put secure enabled_accessibility_services com.example.fireguard/.AccessibilityService
   ```
4. **Pair with Dashboard:** Open `http://<fire-tv-ip>:8080/` in any browser
   (or `adb forward tcp:8080 tcp:8080` from a computer). Enter the dashboard
   password on the TV to lock/unlock and set policies. For multi-TV setups,
   optionally set a relay URL (`ws://<server>:3000/ws`) in the agent app to
   mirror usage/logs to the relay's dashboard.

---

## 6. Out of Scope / Known Technical Limitations

- **Detailed Content History:** Due to DRM and sandbox encryption, tracking specific video titles or watch history within 3rd-party apps (e.g., specific YouTube videos or Netflix shows) is **not possible**. Only application package usage duration can be recorded.
- **Background Service Persistence:** Fire OS aggressive memory management may occasionally terminate background services; local watchdogs or accessibility bindings are required to auto-restart the agent app.

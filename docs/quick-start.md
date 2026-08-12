---
layout: default
title: Quick start
---

# Quick start

Get one ScreenTamer agent running on a single Fire TV in a few minutes.

## 1. Get the APK

### Easy: download a release

Grab the latest prebuilt APK from the **Sideload release**:

- **<https://tinyurl.com/screentamer>** (– or visit
  [github.com/bageshwar/screentamer-tv/releases](https://github.com/bageshwar/screentamer-tv/releases)
  and download the `screentamer-agent-*.apk` asset)

No Android SDK or build tooling required — anyone can sideload this.

### Alternative: build it yourself

Want to build from source instead? Requires JDK 17+. On macOS with
Android Studio:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd agent
./gradlew assembleDebug
# -> agent/app/build/outputs/apk/debug/app-debug.apk
cd ..
```

Either way you now have an APK to sideload — continue with step 2.

## 2. Sideload + grant permissions

On the TV: **Settings → My Fire TV → Developer Options → ADB Debugging ON**
and **Apps from Unknown Sources ON**.

```bash
adb connect <fire-tv-ip>:5555
adb install <path-to>/screentamer-agent.apk
./scripts/setup-firestick.sh <fire-tv-ip>
```

The setup script grants the hidden permissions Fire OS hides:

```bash
adb shell pm grant com.screentamer.agent android.permission.PACKAGE_USAGE_STATS
adb shell appops set com.screentamer.agent SYSTEM_ALERT_WINDOW allow
adb shell settings put secure enabled_accessibility_services \
  com.screentamer.agent/.AgentAccessibilityService
```

## 3. Configure + start

Open the ScreenTamer app on the TV and set:

- **Dashboard password** — what parents type in the dashboard (required).
- **Dashboard port** — default `8080`.
- **Relay server URL** — leave blank for device-only mode.

Press **Save & Restart Service**, then **Test Local ADB**. The agent is live
when the status shows the `.local` URL (e.g. `Dashboard: http://firetv.local:8080`).

> First ADB loopback connection: the agent generates its own RSA key, presents
> it to adbd, and Fire TV registers it without a confirmation dialog.

## 4. Open the dashboard

From any browser on your LAN, open `http://<device-name>.local:8080/` or
`http://<fire-tv-ip>:8080/`. See the [dashboard guide](dashboard.html).
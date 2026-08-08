#!/usr/bin/env bash
# ScreenTamer — Fire TV post-install setup.
# Sideload the APK first, then run this script from your computer:
#   ./scripts/setup-firestick.sh 192.168.1.50
#   ./scripts/setup-firestick.sh 192.168.1.50 /path/to/adb XZ_1mxTq
#
# Grants the hidden permissions the agent needs (Fire OS hides these UIs):
#   - PACKAGE_USAGE_STATS  (foreground app tracking)
#   - SYSTEM_ALERT_WINDOW  (lock overlay)
#   - accessibility service (watchdog / auto-restart)
# Also injects the dashboard password, dashboard port and optional relay URL
# directly into the app's prefs so nothing has to be typed on the TV.
set -euo pipefail

DEVICE_IP="${1:?Usage: $0 <fire-tv-ip> [adb-path] [dashboard-password]}"
ADB="${2:-adb}"
PKG="com.screentamer.agent"
SERVICE="com.screentamer.agent/.AgentAccessibilityService"

DASH_PASSWORD="${3:-}"
DASH_PORT="${4:-8080}"
RELAY_URL="${5:-}"

echo "==> Connecting to $DEVICE_IP (make sure ADB Debugging is ON in Developer Options)"
"$ADB" connect "$DEVICE_IP:5555"

echo "==> Waiting for device"
"$ADB" -s "$DEVICE_IP:5555" wait-for-device

echo "==> Granting PACKAGE_USAGE_STATS"
"$ADB" -s "$DEVICE_IP:5555" shell pm grant "$PKG" android.permission.PACKAGE_USAGE_STATS

echo "==> Allowing SYSTEM_ALERT_WINDOW (lock overlay)"
"$ADB" -s "$DEVICE_IP:5555" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow

echo "==> Enabling accessibility watchdog service"
"$ADB" -s "$DEVICE_IP:5555" shell settings put secure enabled_accessibility_services "$SERVICE"
"$ADB" -s "$DEVICE_IP:5555" shell settings put secure accessibility_enabled 1

echo "==> Injecting dashboard prefs (password, port, relay URL)"
# NOTE: a heredoc piped into `run-as` fails on Fire OS/Android ("can't create
# temporary file /data/local/sh*.tmp: Permission denied"), so we push the file
# via adb and copy it into the app data dir with `run-as`.
if [ -n "$DASH_PASSWORD" ]; then
    PREFS_TMP="$(mktemp -t screentamer-prefs)"
    cat > "$PREFS_TMP" <<EOF
<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<map>
    <string name="parent_password">$DASH_PASSWORD</string>
    <int name="server_port" value="$DASH_PORT" />
    <string name="server_url">$RELAY_URL</string>
</map>
EOF
    "$ADB" -s "$DEVICE_IP:5555" push "$PREFS_TMP" /data/local/tmp/screentamer_prefs.xml >/dev/null
    "$ADB" -s "$DEVICE_IP:5555" shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/screentamer_prefs.xml shared_prefs/screentamer_prefs.xml'"
    "$ADB" -s "$DEVICE_IP:5555" shell rm -f /data/local/tmp/screentamer_prefs.xml
    rm -f "$PREFS_TMP"
fi

echo "==> Checking adbd is reachable from the app itself (loopback :5555)"
# NOTE: `nc` does not exist on Fire OS. adbd listening on 0.0.0.0 (00000000) or
# 127.0.0.1 (0100007F) means loopback connects will work; port 5555 = 0x15B3.
"$ADB" -s "$DEVICE_IP:5555" shell 'grep -qE "(0100007F|00000000):15B3" /proc/net/tcp 2>/dev/null && echo "loopback adb OK" || echo "loopback adb NOT detected — confirm with Test Local ADB in the app"'

echo "==> Starting the agent service"
"$ADB" -s "$DEVICE_IP:5555" shell am start -n "$PKG/.MainActivity"

echo ""
echo "Done. Dashboard on the Fire TV: http://$DEVICE_IP:$DASH_PORT"
echo "  Password: ${DASH_PASSWORD:-<set one in the agent app>}"
echo "  Relay (optional): ${RELAY_URL:-not configured — on-device server only}"

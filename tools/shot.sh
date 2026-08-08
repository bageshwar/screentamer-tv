#!/bin/bash
# Fast single-frame capture for UI iteration loops.
# Usage: shot.sh [out.png]
set -e
SERIAL="${TV_SERIAL:-192.168.1.7:5555}"
ADB="$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")"
run() { run -s "$SERIAL" "$@"; }
OUT="${1:-/tmp/shot.png}"
run shell input keyevent KEYCODE_WAKEUP
run shell am force-stop com.screentamer.agent || true
sleep 1
run shell am start -n com.screentamer.agent/.MainActivity
sleep 2
run shell screencap -p /sdcard/shot.png
run pull /sdcard/shot.png "$OUT" >/dev/null
echo "$OUT"

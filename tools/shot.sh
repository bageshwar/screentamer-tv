#!/bin/bash
# Fast single-frame capture for UI iteration loops.
# Usage: shot.sh [out.png] [down-swipes]
# The TV's SurfaceFlinger occasionally serves a stale frame after relaunch and
# the ScrollView restore is nondeterministic, so the capture is verified
# against the live view hierarchy and retried (max 3 attempts).
set -e
SERIAL="${TV_SERIAL:-192.168.1.7:5555}"
ADB="$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")"
run() { "$ADB" -s "$SERIAL" "$@"; }
OUT="${1:-/tmp/shot.png}"
SWIPES="${2:-0}"

y_of() { # $1=resource-id  -> echo visible top-y or 9999
    run shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
    run shell cat /sdcard/ui.xml 2>/dev/null | tr '<' '\n<' | grep -o "resource-id=\"com.screentamer.agent:id/$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" | grep -o '\[[0-9]*,[0-9]*\]' | head -1 | sed 's/\[//;s/,/ /'
}

for attempt in 1 2 3; do
    run shell input keyevent KEYCODE_WAKEUP
    run shell am force-stop com.screentamer.agent || true
    sleep 1
    run shell am start -n com.screentamer.agent/.MainActivity
    sleep 2
    run shell am force-stop com.screentamer.agent || true
    sleep 1
    run shell am start -n com.screentamer.agent/.MainActivity
    sleep 3
    for i in $(seq 1 "$SWIPES"); do
        run shell input swipe 960 900 960 300 200
        sleep 1
    done
    run shell screencap -p /sdcard/shot.png
    run pull /sdcard/shot.png "$OUT" >/dev/null
    if [ "$SWIPES" = "0" ]; then
        top=$(y_of btnStart)
        if [ -z "$top" ]; then top=9999; fi
        [ "$top" -ge 400 ] && [ "$top" -le 520 ] && { echo "$OUT"; exit 0; }
    else
        if y_of btnSave >/dev/null 2>&1 && [ -n "$(y_of btnSave)" ]; then
            { echo "$OUT"; exit 0; }
        fi
    fi
    echo "attempt $attempt failed verification, retrying" >&2
    sleep 2
done
echo "$OUT" >&2
exit 1

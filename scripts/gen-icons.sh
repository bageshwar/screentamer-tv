#!/usr/bin/env bash
# ScreenTamer — render launcher icon + leanback banner PNGs from the SVG
# sources in art/ (uses macOS QuickLook: qlmanage).
#
#   ./scripts/gen-icons.sh
#
# Produces:
#   agent/app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png
#   agent/app/src/main/res/drawable-nodpi/banner.png
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
RES="$HERE/agent/app/src/main/res"

command -v qlmanage >/dev/null || { echo "qlmanage not found (macOS required)"; exit 1; }

mkdir -p "$RES/mipmap-mdpi" "$RES/mipmap-hdpi" "$RES/mipmap-xhdpi" \
    "$RES/mipmap-xxhdpi" "$RES/mipmap-xxxhdpi" "$RES/drawable-nodpi"

render() { # render <svg> <out.png> <px>
    local svg="$1" out="$2" px="$3"
    local tmp
    tmp="$(mktemp -d)"
    qlmanage -t -s "$px" -o "$tmp" "$svg" >/dev/null 2>&1
    cp "$tmp/$(basename "$svg").png" "$out"
    rm -rf "$tmp"
}

echo "==> Launcher icons"
render "$HERE/art/ic_launcher.svg" "$RES/mipmap-mdpi/ic_launcher.png" 48
render "$HERE/art/ic_launcher.svg" "$RES/mipmap-hdpi/ic_launcher.png" 72
render "$HERE/art/ic_launcher.svg" "$RES/mipmap-xhdpi/ic_launcher.png" 96
render "$HERE/art/ic_launcher.svg" "$RES/mipmap-xxhdpi/ic_launcher.png" 144
render "$HERE/art/ic_launcher.svg" "$RES/mipmap-xxxhdpi/ic_launcher.png" 192

echo "==> Leanback banner"
render "$HERE/art/banner.svg" "$RES/drawable-nodpi/banner.png" 320

echo "Done."

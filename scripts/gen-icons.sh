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



mkdir -p "$RES/mipmap-mdpi" "$RES/mipmap-hdpi" "$RES/mipmap-xhdpi" \
    "$RES/mipmap-xxhdpi" "$RES/mipmap-xxxhdpi" "$RES/drawable-nodpi" \
    "$RES/drawable-mdpi" "$RES/drawable-hdpi" "$RES/drawable-xhdpi" \
    "$RES/drawable-xxhdpi" "$RES/drawable-xxxhdpi"

render() { # render <svg> <out.png> <width> [height]
    local svg="$1" out="$2" w="$3"
    local h="${4:-$w}"
    node "$HERE/scripts/render-svg.js" "$svg" "$out" "$w" "$h"
}

echo "==> Launcher icons"
render "$HERE/art/ic_launcher.svg" "$RES/mipmap-mdpi/ic_app_icon.png" 128
render "$HERE/art/ic_launcher.svg" "$RES/mipmap-hdpi/ic_app_icon.png" 192
render "$HERE/art/ic_launcher.svg" "$RES/mipmap-xhdpi/ic_app_icon.png" 256
render "$HERE/art/ic_launcher.svg" "$RES/mipmap-xxhdpi/ic_app_icon.png" 384
render "$HERE/art/ic_launcher.svg" "$RES/mipmap-xxxhdpi/ic_app_icon.png" 512

echo "==> Adaptive icon foregrounds"
render "$HERE/art/ic_launcher_foreground.svg" "$RES/drawable-mdpi/ic_launcher_foreground.png" 128
render "$HERE/art/ic_launcher_foreground.svg" "$RES/drawable-hdpi/ic_launcher_foreground.png" 192
render "$HERE/art/ic_launcher_foreground.svg" "$RES/drawable-xhdpi/ic_launcher_foreground.png" 256
render "$HERE/art/ic_launcher_foreground.svg" "$RES/drawable-xxhdpi/ic_launcher_foreground.png" 384
render "$HERE/art/ic_launcher_foreground.svg" "$RES/drawable-xxxhdpi/ic_launcher_foreground.png" 512

echo "==> Leanback banner"
render "$HERE/art/banner.svg" "$RES/drawable-nodpi/banner.png" 1280 720

echo "Done."

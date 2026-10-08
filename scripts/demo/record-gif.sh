#!/bin/bash
# Redraws docs/demo.gif: installs the debug build on an emulator, plays a scripted
# child-style drawing session (session.py) and converts the screen recording.
#
#   scripts/demo/record-gif.sh [adb-serial]      # default: emulator-5554
#
# The coordinates in session.py are for a 1080x2400, 480 dpi emulator (for
# example a Pixel 6-sized AVD on API 34) with the toolbar visible. Needs ffmpeg.
# The app's data is cleared first, so do not point this at a phone you use.
set -euo pipefail
cd "$(dirname "$0")/../.."
SERIAL=${1:-emulator-5554}
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb -s $SERIAL"
PKG=io.github.johanneshoh.childpaint
TMP=$(mktemp -d)

./gradlew -q assembleDebug
$ADB install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
python3 scripts/demo/session.py "$TMP/session.sh"
$ADB push "$TMP/session.sh" /data/local/tmp/session.sh >/dev/null
$ADB shell pm clear $PKG >/dev/null
$ADB shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 3

$ADB shell rm -f /sdcard/demo.mp4
$ADB shell screenrecord --bit-rate 6000000 --size 540x1200 /sdcard/demo.mp4 &
REC=$!
sleep 1
$ADB shell sh /data/local/tmp/session.sh
$ADB shell pkill -INT screenrecord
wait $REC || true
sleep 2
$ADB pull /sdcard/demo.mp4 "$TMP/demo.mp4" >/dev/null

# 1.4x speed, a held last frame, 320 px wide: about 17 s and 250 KB.
ffmpeg -v error -y -ss 1.2 -i "$TMP/demo.mp4" -filter_complex \
  "setpts=PTS/1.4,tpad=stop_mode=clone:stop_duration=2.5,fps=12,scale=320:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=96:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" \
  docs/demo.gif
ls -la docs/demo.gif

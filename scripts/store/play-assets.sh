#!/usr/bin/env bash
# Play Store graphics, re-runnable: scripts/store/play-assets.sh
#
# Phone screenshots are captured from the REAL debug build on an Android emulator (adb screencap),
# then composited onto Play's canvas: 1080x2160 JPEG (Play refuses a long side over twice the short
# one, and PNG with alpha), a caption band on top (one short present-tense sentence, <= ~40 chars),
# the app below. The icon (512x512 PNG, no alpha) and feature graphic (1024x500 PNG, no alpha) are drawn
# from the launcher icon's own paths and the app's design tokens (res/values/colors.xml).
#
# Usage:
#   ADB_SERIAL=emulator-5582 scripts/store/play-assets.sh            # everything
#   ADB_SERIAL=emulator-5582 scripts/store/play-assets.sh shots      # capture + compose screenshots
#   scripts/store/play-assets.sh graphics                            # icon + feature graphic only (no device)
#
# Needs: ImageMagick (`magick`), adb, node with `playwright` resolvable (NODE_PATH=.../node_modules) for graphics, a booted emulator with a portrait ~1344x2992 screen (Pixel 8 Pro
# class; the tap coordinates below are for that size). Builds the DEBUG apk only: never a signed build.
# Output: fastlane/metadata/android/en-US/images/{phoneScreenshots,icon.png,featureGraphic.png}.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/fastlane/metadata/android/en-US/images"
RAW="${RAW_DIR:-$(mktemp -d)}"
PKG=io.github.johanneshoh.childpaint
FONT="$ROOT/app/src/main/res/font/fredoka_variable.ttf"
INK='#3F2E22'; CREAM='#FFF3E3'; SKY='#5BC0EB'; ORANGE='#FF8A3D'
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
SER="${ADB_SERIAL:?set ADB_SERIAL, e.g. emulator-5582}"
a() { "$ADB" -s "$SER" "$@"; }
tap() { a shell input tap "$1" "$2"; sleep 0.4; chk "tap $*"; }
# A box drag pulls a corner that hangs ~260px ABOVE the finger (PaintCanvasView.dragCorner), so a shape
# drag ends that much lower than the corner it means.
STRING=260
sdrag() { drag "$1" "$2" "$3" "$(($4+STRING))"; }
drag() { a shell input swipe "$1" "$2" "$3" "$4" "${5:-1200}"; sleep 0.5; chk "drag $*"; }
chk() { [ -z "${DEBUG:-}" ] || focus_ok || echo "LOST after $1" >&2; }
focus_ok() { a shell dumpsys window | grep mCurrentFocus | grep -q "$PKG"; }
snap() { focus_ok || { echo "app lost focus before $1: $(a shell dumpsys window | grep mCurrentFocus)" >&2; exit 1; }; a exec-out screencap -p > "$RAW/$1.png"; }

# Screen map (1344x2992): paper y 115..1850; colours rows y=1940 / 2100; tools y=2430; shapes y=2616.
COL1=(112 270 430 590 750 910 1072 1233)   # black gray white brown red orange amber yellow
COL2=(112 270 430 590 750 910 1072 1233)   # green dkgreen cyan blue navy purple pink lightpink
color() { if [ "$1" -lt 8 ]; then tap "${COL1[$1]}" 1940; else tap "${COL2[$(($1-8))]}" 2100; fi; }
BLACK=0 BROWN=3 RED=4 ORANGE_C=5 AMBER=6 YELLOW=7 GREEN=8 DKGREEN=9 CYAN=10 BLUE=11 PURPLE=13 PINK=14 LPINK=15
T_BRUSH=158 T_BUCKET=415 T_ERASER=672 T_TEXT=928 T_HAND=1185
S_LINE=158 S_RECT=415 S_OVAL=672 S_TRI=928 S_MORE=1185
tool() { tap "$1" 2430; }
shape() { tap "$1" 2616; }
# A shape stays adjustable until the next touch of the paper or a tool change. A bare tap makes a default
# shape (don't!), so the next drag, or picking a tool, is what puts the previous shape down.
commit() { tool $T_BRUSH; }
newshape() { commit; shape "$1"; color "$2"; }

shots() {
  mkdir -p "$OUT/phoneScreenshots"
  (cd "$ROOT" && ./gradlew -q assembleDebug)
  a install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
  a shell pm clear $PKG >/dev/null            # fresh, empty paper: nothing of anyone's on screen
  a shell input keyevent KEYCODE_HOME; sleep 1
  a shell am start -n $PKG/com.childpaint.MainActivity >/dev/null; sleep 3

  # Sky, then grass. Picking a colour recolours a shape that is still selected, so every new shape
  # starts with commit (brush tool) -> shape tool -> colour -> drag.
  tool $T_BUCKET; color $CYAN; tap 600 400
  # Never start a drag on the screen edge: that is Android's back gesture. Two overlapping halves.
  newshape $S_RECT $GREEN; sdrag 760 1450 0 1850
  newshape $S_RECT $GREEN; sdrag 600 1450 1344 1850
  # House: body, roof, door, window
  newshape $S_RECT $RED; sdrag 250 950 1000 1550
  newshape $S_TRI $BROWN; sdrag 180 500 1070 950
  newshape $S_RECT $AMBER; sdrag 560 1230 700 1550
  newshape $S_RECT $BLUE; sdrag 780 1060 920 1200
  # Sun and a tree (trunk, then the top, which stays selected for the knobs picture)
  newshape $S_OVAL $AMBER; sdrag 960 300 1260 600
  newshape $S_RECT $BROWN; sdrag 1090 960 1150 1200
  newshape $S_TRI $DKGREEN; sdrag 960 600 1280 980; sleep 0.6
  snap 02-shape-knobs
  commit

  # Flowers by hand: a stem, then a dot of colour for the head (brush, a fat size)
  tool $T_BRUSH; tap 900 2260
  for x in 150 330 1030 1200; do color $DKGREEN; drag $x 1800 $x 1690 300; color $PINK; drag $((x-30)) 1650 $((x+30)) 1650 150; drag $x 1620 $x 1680 150; done
  snap 01-picture

  # More shapes sheet
  shape $S_MORE; sleep 1; snap 03-more-shapes
  a shell input keyevent BACK; sleep 0.8

  # Kid mode start dialog (the padlock)
  tap 1215 2800; sleep 1; snap 04-kid-mode
  a shell input keyevent BACK; sleep 0.8

  # My pictures (save button)
  tap 1048 2800; sleep 1; snap 05-my-pictures
  a shell input keyevent BACK; sleep 0.8

  compose
}

CAPTIONS=(
  "01-picture|Paint with big, friendly tools"
  "02-shape-knobs|Move, resize and turn any shape"
  "03-more-shapes|Stars, hearts, clouds and more"
  "04-kid-mode|Hand it over with a timer"
  "05-my-pictures|Keep your favourite pictures"
)
compose() {
  local dir="$OUT/phoneScreenshots" i=0 pair name cap
  rm -f "$dir"/*.jpg "$dir"/*.png
  for pair in "${CAPTIONS[@]}"; do
    i=$((i+1)); name="${pair%%|*}"; cap="${pair#*|}"
    # App picture: drop the status bar, scale to fit 1900 tall, round the corners.
    magick "$RAW/$name.png" -alpha off -crop 1344x2900+0+92 +repage -resize x1880 \
      \( +clone -alpha extract -fill black -colorize 100 -fill white -draw "roundrectangle 0,0 %[fx:w-1],%[fx:h-1] 40,40" \) \
      -alpha off -compose CopyOpacity -composite "$RAW/app-$i.png"
    magick -size 1080x2160 "xc:$SKY" \
      \( -size 1080x250 "xc:$CREAM" -font "$FONT" -pointsize 72 -fill "$INK" -gravity center -annotate +0+0 "$cap" \) -gravity north -composite \
      "$RAW/app-$i.png" -gravity south -geometry +0+28 -composite \
      -background "$SKY" -alpha remove -alpha off -quality 90 "$dir/$i.jpg"
  done
  rm -f "$dir"/1.png   # the old unframed capture; the framed set above replaces it
  echo "wrote $i screenshots to $dir"
}

graphics() {
  mkdir -p "$OUT"
  # Chromium draws both (the launcher icon's own paths + Fredoka); NODE_PATH must reach `playwright`.
  node "$ROOT/scripts/store/graphics.cjs" "$OUT" "$ROOT/app/src/main/res/font/fredoka_variable.ttf"
}

case "${1:-all}" in
  shots) shots ;;
  graphics) graphics ;;
  all) graphics; shots ;;
  *) echo "usage: $0 [all|shots|graphics]"; exit 2 ;;
esac

#!/bin/bash
# Screenshots of every screen at the sizes of the phones and tablets most sold in Japan (docs/release.md,
# "Screens on many devices"). Needs the public E2E build and its tests installed on the device:
#   ./gradlew :app:installPublicE2e :app:installPublicE2eAndroidTest
# Usage: tools/screen-matrix.sh <out dir> [themes, default kinari]   (ANDROID_SERIAL picks the device)
# Each profile overrides the display size, density and font scale, and everything is reset at the end.
set -u
ADB=${ADB:-adb}
OUT=$1
THEMES=${2:-kinari}
# name:size:density:font scale — widths 360dp (AQUOS sense), 384dp (Galaxy A, measured on an A25), 411dp (Pixel),
# a 1.3x system font on the two narrow ones, and a 1920x1200 tablet both ways.
PROFILES="p360:1080x2340:480:1.0 p384:720x1600:300:1.0 p411:1080x2424:420:1.0 p360f13:1080x2340:480:1.3 p384f13:720x1600:300:1.3 t800:1200x1920:240:1.0 t1280:1920x1200:240:1.0"
SCREENS="shelf search aozora page bars work aa aa2 toc log"
for pr in $PROFILES; do
  IFS=: read -r name size dens fs <<< "$pr"
  $ADB shell wm size "$size"; $ADB shell wm density "$dens"; $ADB shell settings put system font_scale "$fs"
  sleep 3
  D=$OUT/$name; mkdir -p "$D"
  $ADB logcat -c
  ($ADB shell am instrument -w -e class jp.harusame.aozorareader.ScreensTest -e screens 1 -e themes "$THEMES" \
      jp.harusame.tategakicho.e2e.test/androidx.test.runner.AndroidJUnitRunner > "$D/inst.txt" &)
  for t in ${THEMES//,/ }; do
    for n in $SCREENS; do
      for _ in $(seq 1 120); do $ADB logcat -d -s SHOT:I | grep -q " ${t}_$n\$" && break; sleep 0.5; done
      sleep 1.5; $ADB exec-out screencap -p > "$D/${t}_$n.png"
    done
  done
  for _ in $(seq 1 200); do grep -q "OK\|FAIL" "$D/inst.txt" 2>/dev/null && break; sleep 1; done
  echo "$name: $(grep -o 'OK (1 test)\|FAILURES!!!' "$D/inst.txt" | head -1)"
done
$ADB shell wm size reset; $ADB shell wm density reset; $ADB shell settings put system font_scale 1.0

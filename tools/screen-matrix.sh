#!/bin/bash
# Screenshots of every screen at the sizes of the phones and tablets most sold in Japan (docs/release.md,
# "Screens on many devices"). Needs the public E2E build and its tests installed on the device:
#   ./gradlew :app:installPublicE2e :app:installPublicE2eAndroidTest
# Usage: tools/screen-matrix.sh <out dir> [themes, default kinari]   (ANDROID_SERIAL picks the device; PROFILES and
# SCREENS narrow the run, e.g. PROFILES=p384:720x1600:300:1.0 for one size). Exits nonzero if any screen was missed.
# Each profile overrides the display size, density and font scale; the device's own values come back at the end.
set -u
ADB=${ADB:-adb}
OUT=$1
THEMES=${2:-kinari}
# name:size:density:font scale — widths 360dp (AQUOS sense), 384dp (Galaxy A, measured on an A25), 411dp (Pixel),
# a 1.3x system font on the two narrow ones, and a 1920x1200 tablet both ways.
PROFILES=${PROFILES:-"p360:1080x2340:480:1.0 p384:720x1600:300:1.0 p411:1080x2424:420:1.0 p360f13:1080x2340:480:1.3 p384f13:720x1600:300:1.3 t800:1200x1920:240:1.0 t1280:1920x1200:240:1.0"}
SCREENS=${SCREENS:-"shelf search aozora page bars work aa aa2 toc log"}

# Put the device back as it was, also when the run is cut short: on a phone the user's own display size, density and
# font scale come back, not the defaults.
ORIG_SIZE=$($ADB shell wm size | sed -n 's/^Override size: //p' | tr -d '\r')
ORIG_DENS=$($ADB shell wm density | sed -n 's/^Override density: //p' | tr -d '\r')
ORIG_FS=$($ADB shell settings get system font_scale | tr -d '\r')
restore() {
  if [ -n "$ORIG_SIZE" ]; then $ADB shell wm size "$ORIG_SIZE"; else $ADB shell wm size reset; fi
  if [ -n "$ORIG_DENS" ]; then $ADB shell wm density "$ORIG_DENS"; else $ADB shell wm density reset; fi
  case "$ORIG_FS" in ''|null) $ADB shell settings put system font_scale 1.0 ;; *) $ADB shell settings put system font_scale "$ORIG_FS" ;; esac
}
trap restore EXIT
FAILED=0
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
      seen=
      for _ in $(seq 1 120); do $ADB logcat -d -s SHOT:I | grep -q " ${t}_$n\$" && { seen=1; break; }; sleep 0.5; done
      # A screen that never said it was ready is not captured: a picture of whatever is showing would pass for it.
      if [ -z "$seen" ]; then echo "$name: ${t}_$n never became ready, not captured"; FAILED=1; continue; fi
      sleep 1.5
      if ! $ADB exec-out screencap -p > "$D/${t}_$n.png" || [ ! -s "$D/${t}_$n.png" ]; then
        echo "$name: ${t}_$n could not be captured"; rm -f "$D/${t}_$n.png"; FAILED=1
      fi
    done
  done
  for _ in $(seq 1 200); do grep -q "OK\|FAIL" "$D/inst.txt" 2>/dev/null && break; sleep 1; done
  result=$(grep -o 'OK (1 test)\|FAILURES!!!' "$D/inst.txt" | head -1)
  echo "$name: ${result:-no result from the test}"
  [ "$result" = "OK (1 test)" ] || FAILED=1
done
exit "$FAILED"

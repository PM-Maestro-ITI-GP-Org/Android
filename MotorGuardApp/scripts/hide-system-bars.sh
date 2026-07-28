#!/usr/bin/env bash
# Allow the Automotive system bars (top status bar + bottom nav/climate bar) to be
# HIDDEN by the foreground app.
#
# By default AAOS makes the CarSystemBars "persistent" — they ignore an app's
# immersive request. This enables the shipped immersive bar-policy overlay so that
# MainActivity's WindowInsetsController.hide(systemBars()) actually takes effect.
# The bars remain swipe-revealable. Combine with scripts/set-as-home.sh.
#
# Usage:  ./scripts/hide-system-bars.sh [serial]
# Revert: ./scripts/hide-system-bars.sh --revert   (bars persistent again)
#
# For a real image, bake this overlay (or config_enable*SystemBar=false) into the build.
set -euo pipefail

IMMERSIVE=com.android.car.systemui.systembar.persistency.immersive
DEFAULT=com.android.car.systemui.systembar.persistency.non_immersive

SERIAL_ARG=(); REVERT=0
for a in "$@"; do
  case "$a" in
    --revert) REVERT=1 ;;
    *) SERIAL_ARG=(-s "$a") ;;
  esac
done

adb "${SERIAL_ARG[@]}" wait-for-device
if [ "$REVERT" = "1" ]; then
  adb "${SERIAL_ARG[@]}" shell cmd overlay enable "$DEFAULT" || true
  adb "${SERIAL_ARG[@]}" shell cmd overlay disable "$IMMERSIVE" || true
  echo "System bars restored to persistent (always shown)."
else
  adb "${SERIAL_ARG[@]}" shell cmd overlay enable "$IMMERSIVE"
  echo "Immersive bar policy enabled — the foreground app can now hide the system bars."
fi

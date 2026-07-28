#!/usr/bin/env bash
# Make Motor Guard the default HOME/launcher on a running AAOS emulator/device.
#
# Why this is needed: the manifest declares MainActivity as a HOME activity, but
# Automotive ships CarLauncher as the configured default, and the driver runs as a
# secondary user (typically user 10) — so the home preference must be set for the
# CURRENT user, not user 0. This script handles that.
#
# Usage:  ./scripts/set-as-home.sh [serial]
#   e.g.  ./scripts/set-as-home.sh emulator-5554
# Revert: ./scripts/set-as-home.sh --revert
set -euo pipefail

PKG=com.motorguard.ivi
ACT=$PKG/.MainActivity
STOCK=com.android.car.carlauncher/.CarLauncher

SERIAL_ARG=()
REVERT=0
for a in "$@"; do
  case "$a" in
    --revert) REVERT=1 ;;
    *) SERIAL_ARG=(-s "$a") ;;
  esac
done

adb "${SERIAL_ARG[@]}" wait-for-device
USER_ID=$(adb "${SERIAL_ARG[@]}" shell am get-current-user | tr -d '\r')
echo "current user: $USER_ID"

if [ "$REVERT" = "1" ]; then
  adb "${SERIAL_ARG[@]}" shell cmd package set-home-activity --user "$USER_ID" "$STOCK"
  echo "Reverted default home to stock CarLauncher."
else
  adb "${SERIAL_ARG[@]}" shell cmd package set-home-activity --user "$USER_ID" "$ACT"
  adb "${SERIAL_ARG[@]}" shell input keyevent KEYCODE_HOME
  echo "Motor Guard is now the default home for user $USER_ID."
fi

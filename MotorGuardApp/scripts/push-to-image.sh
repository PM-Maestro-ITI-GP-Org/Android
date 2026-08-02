#!/usr/bin/env bash
# Push Motor Guard onto an already-flashed AOSP image, without rebuilding the image.
#
# Why re-signing is not optional: the app asks for signature|privileged permissions
# (NETWORK_SETTINGS, BLUETOOTH_PRIVILEGED, MANAGE_USERS...) and declares itself HOME. The
# platform only grants those to an APK signed with the *platform key* AND sitting in
# /system/priv-app AND listed in privapp-permissions. A Gradle APK is signed with the debug
# key, so `adb install`-ing it gives you the UI with none of the privileges — the Wi-Fi and
# Bluetooth switches will fall back to system screens exactly like they do on a phone.
#
# Usage:
#   AOSP=/path/to/aosp ./scripts/push-to-image.sh [serial]
#
# Requires: an eng/userdebug image (adb root must work), and the platform keys in the tree.
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERIAL="${1:-}"
ADB=(adb)
[[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")

: "${AOSP:?Set AOSP to your AOSP tree, e.g. AOSP=~/rpi5-aosp ./scripts/push-to-image.sh}"
KEY_DIR="$AOSP/build/target/product/security"
PEM="$KEY_DIR/platform.x509.pem"
PK8="$KEY_DIR/platform.pk8"
[[ -f "$PEM" && -f "$PK8" ]] || { echo "Platform keys not found in $KEY_DIR"; exit 1; }

APKSIGNER="$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/apksigner | sort -V | tail -1)"
ZIPALIGN="$(dirname "$APKSIGNER")/zipalign"

echo "==> 1/6 building release APK"
( cd "$APP_DIR" && ./gradlew :app:assembleRelease --console=plain -q )
RAW="$APP_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
[[ -f "$RAW" ]] || RAW="$APP_DIR/app/build/outputs/apk/release/app-release.apk"

OUT=/tmp/MotorGuard-platform.apk
echo "==> 2/6 zipalign + re-sign with the platform key"
"$ZIPALIGN" -p -f 4 "$RAW" /tmp/MotorGuard-aligned.apk
"$APKSIGNER" sign --key "$PK8" --cert "$PEM" --out "$OUT" /tmp/MotorGuard-aligned.apk
"$APKSIGNER" verify --print-certs "$OUT" | head -3

echo "==> 3/6 making /system writable"
"${ADB[@]}" root >/dev/null; "${ADB[@]}" wait-for-device
# disable-verity needs one reboot to take effect; harmless if already disabled.
if "${ADB[@]}" disable-verity 2>&1 | grep -qi "now disabled\|reboot"; then
  "${ADB[@]}" reboot; "${ADB[@]}" wait-for-device; sleep 20; "${ADB[@]}" root >/dev/null; "${ADB[@]}" wait-for-device
fi
"${ADB[@]}" remount

echo "==> 4/6 installing to /system/priv-app"
"${ADB[@]}" shell mkdir -p /system/priv-app/MotorGuard
"${ADB[@]}" push "$OUT" /system/priv-app/MotorGuard/MotorGuard.apk
"${ADB[@]}" shell chmod 644 /system/priv-app/MotorGuard/MotorGuard.apk

echo "==> 5/6 installing the privileged-permission allow-list"
"${ADB[@]}" push "$APP_DIR/aosp/privapp_permissions_com.motorguard.ivi.xml" \
  /system/etc/permissions/privapp_permissions_com.motorguard.ivi.xml
"${ADB[@]}" shell chmod 644 /system/etc/permissions/privapp_permissions_com.motorguard.ivi.xml

echo "==> 6/6 rebooting (privileged perms are only re-read at boot)"
"${ADB[@]}" reboot
echo
echo "After boot, this must print granted=true for both:"
echo "  adb shell dumpsys package com.motorguard.ivi | grep -E 'NETWORK_SETTINGS|BLUETOOTH_PRIVILEGED'"

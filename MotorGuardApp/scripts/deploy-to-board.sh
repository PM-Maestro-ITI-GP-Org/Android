#!/usr/bin/env bash
#
# Install a signed Motor Guard APK onto a running board, in place, without reflashing.
#
#   ./scripts/deploy-to-board.sh 192.168.2.2:5555 [path/to/MotorGuard.apk]
#
# Motor Guard ships in /system/priv-app, so this replaces the file rather than using
# `pm install` — a privileged system app cannot be updated by a normal install, and it
# must keep the platform signature or it loses its privileged permissions.
#
# TWO THINGS THAT LOOK LIKE THEY WORK BUT DO NOT
# ----------------------------------------------
#  1. `adb push` onto a read-only /system reports success and changes nothing. Every
#     copy here is therefore verified by md5 against the local file, not trusted.
#
#  2. The APK's own lib/ entries are not enough. PackageManager recorded this app's
#     nativeLibraryDir when the image was built, and keeps using it, so a .so that
#     exists only inside the new APK is invisible: System.loadLibrary still fails with
#     "library not found". The libraries are therefore extracted into that directory
#     too. Skipping this is what left the Diagnostics tab crash-looping on a board
#     whose APK plainly contained libfilament-jni.so.
#
set -euo pipefail

SERIAL="${1:?usage: deploy-to-board.sh <serial|ip:port> [apk]}"
APP_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${2:-$APP_ROOT/app/build/outputs/apk/aaos/release/MotorGuard.apk}"

TARGET_DIR=/system/priv-app/MotorGuard
TARGET="$TARGET_DIR/MotorGuard.apk"
LIB_DIR="$TARGET_DIR/lib/arm64"

bold=$'\033[1m'; grn=$'\033[32m'; red=$'\033[31m'; off=$'\033[0m'
die() { printf '%s\n' "${red}error:${off} $*" >&2; exit 1; }
step() { printf '%s\n' "${bold}==> $*${off}"; }
sh_() { adb -s "$SERIAL" shell "$@"; }

[[ -f "$APK" ]] || die "no APK at $APK (run ./scripts/build-signed-apk.sh first)"

step "connecting to $SERIAL"
adb connect "$SERIAL" >/dev/null 2>&1 || true
adb -s "$SERIAL" wait-for-device
adb -s "$SERIAL" root >/dev/null 2>&1 || true
sleep 3
adb connect "$SERIAL" >/dev/null 2>&1 || true
adb -s "$SERIAL" wait-for-device
sh_ 'mount -o rw,remount /' >/dev/null 2>&1 || true

LOCAL_MD5="$(md5sum "$APK" | cut -d' ' -f1)"

step "staging the APK"
adb -s "$SERIAL" push "$APK" /data/local/tmp/MotorGuard.apk >/dev/null
STAGED_MD5="$(sh_ 'md5sum /data/local/tmp/MotorGuard.apk' | cut -d' ' -f1 | tr -d '\r')"
[[ "$STAGED_MD5" == "$LOCAL_MD5" ]] || die "push corrupted the APK ($STAGED_MD5 != $LOCAL_MD5)"

step "installing into $TARGET"
sh_ "cp /data/local/tmp/MotorGuard.apk $TARGET && chmod 644 $TARGET && (restorecon $TARGET || true)"
INSTALLED_MD5="$(sh_ "md5sum $TARGET" | cut -d' ' -f1 | tr -d '\r')"
[[ "$INSTALLED_MD5" == "$LOCAL_MD5" ]] \
  || die "install did not take — /system is probably still read-only ($INSTALLED_MD5 != $LOCAL_MD5)"

# See note 2 in the header: the APK's own lib/ entries are not enough.
step "extracting native libraries into $LIB_DIR"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -o -j -q "$APK" 'lib/arm64-v8a/*.so' -d "$TMP"
sh_ "mkdir -p $LIB_DIR" >/dev/null
for so in "$TMP"/*.so; do
    name="$(basename "$so")"
    # Symlinks into /system/lib64 are how the image build supplies its own libs; leave
    # those alone and only add what is genuinely missing, so a stale prebuilt here can
    # never shadow the platform's copy.
    if sh_ "test -L $LIB_DIR/$name" 2>/dev/null; then
        printf '    skip %s (symlink to a platform library)\n' "$name"
        continue
    fi
    adb -s "$SERIAL" push "$so" "/data/local/tmp/$name" >/dev/null
    sh_ "cp /data/local/tmp/$name $LIB_DIR/$name && chmod 644 $LIB_DIR/$name && (restorecon $LIB_DIR/$name || true); rm -f /data/local/tmp/$name"
    printf '    %s\n' "$name"
done

sh_ 'rm -f /data/local/tmp/MotorGuard.apk' || true

step "restarting Motor Guard"
# Force-stopping the launcher makes the system restart it. On this board that also
# restarts system_server, so the connection drops for ~20s — that is expected.
sh_ 'am force-stop com.motorguard.ivi' >/dev/null 2>&1 || true

printf '%s\n' "${grn}✓${off} deployed and verified (md5 $LOCAL_MD5)"
printf '   the board may take ~30s to come back; then: adb connect %s\n' "$SERIAL"

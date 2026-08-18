#!/usr/bin/env bash
# Pushes scripts/models/whisper_ar.bin (the Egyptian-dialect Whisper STT
# model) into the app's files dir so WhisperStt/ModelPaths picks it up on
# next launch -- see the search order documented in ModelPaths.kt.
#
# Tries `run-as` first (works on any debuggable build, no root needed --
# right for the emulator). Falls back to the `su 0` method from
# WhisperStt.kt's doc comment for a rooted physical device where the app
# isn't debuggable.
set -euo pipefail

PKG=com.motorguard.ivi
MODEL="$(cd "$(dirname "${BASH_SOURCE[0]}")/models" && pwd)/whisper_ar.bin"

if [ ! -f "$MODEL" ]; then
    echo "error: $MODEL not found" >&2
    exit 1
fi

echo "waiting for device..."
adb wait-for-device

echo "pushing $(basename "$MODEL") ($(du -h "$MODEL" | cut -f1))..."
adb push "$MODEL" /data/local/tmp/whisper_ar.bin >/dev/null

# This is a multi-user AAOS image (`pm list users` shows a Driver profile per
# user, e.g. 0 and 10) -- the app usually runs under a non-zero secondary
# user, NOT user 0. Plain `run-as PKG` (no --user) silently operates on user
# 0's copy of the app, which the running process never reads from. Find the
# user actually running the app; fall back to whichever user already has a
# files/ dir with models in it.
#
# Also: `run-as PKG sh -c '...'` silently runs as shell, not the app -- it
# only switches uid when the command is invoked directly as its own argv.
APP_USER="$(adb shell ps -A -o USER,ARGS 2>/dev/null | grep "$PKG" | head -1 | grep -oP '(?<=^u)[0-9]+(?=_a)' || true)"
if [ -z "$APP_USER" ]; then
    for u in $(adb shell pm list users | grep -oP '(?<=UserInfo\{)[0-9]+'); do
        if adb shell run-as "$PKG" --user "$u" test -e files/whisper_en.bin 2>/dev/null; then
            APP_USER="$u"
            break
        fi
    done
fi

if [ -n "$APP_USER" ] && adb shell run-as "$PKG" --user "$APP_USER" true 2>/dev/null; then
    RUNAS="adb shell run-as $PKG --user $APP_USER"
    $RUNAS mkdir -p files
    $RUNAS cp /data/local/tmp/whisper_ar.bin files/whisper_ar.bin
    echo "pushed via run-as --user $APP_USER -> files/whisper_ar.bin"
elif adb shell run-as "$PKG" true 2>/dev/null; then
    adb shell run-as "$PKG" mkdir -p files
    adb shell run-as "$PKG" cp /data/local/tmp/whisper_ar.bin files/whisper_ar.bin
    echo "pushed via run-as (user 0) -> files/whisper_ar.bin -- verify this is the user the app actually runs as!"
else
    # Physical rooted device, non-debuggable build. User id varies by
    # profile -- adjust if `su 0 ls /data/user/` shows something other than 10.
    adb shell su 0 cp /data/local/tmp/whisper_ar.bin \
        "/data/user/10/$PKG/files/whisper_ar.bin"
    adb shell su 0 chown "u10_a103:u10_a103" \
        "/data/user/10/$PKG/files/whisper_ar.bin"
    adb shell su 0 restorecon -R "/data/user/10/$PKG/files/"
    echo "pushed via su 0 -> /data/user/10/$PKG/files/whisper_ar.bin"
fi

adb shell rm /data/local/tmp/whisper_ar.bin
adb shell am force-stop "$PKG"
echo "done -- force-stopped $PKG so the next launch reloads the model"

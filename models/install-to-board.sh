#!/usr/bin/env bash
# Install the voice models onto a board that already has Motor Guard flashed.
#
# WHY THIS EXISTS
#
# whisper.bin, piper.onnx/.json and espeak-ng-data are ~105 MB and are
# deliberately not bundled into the APK -- WhisperStt.kt and PiperTts.kt both
# say so in their KDoc, and both name an adb-push as the install step so a
# model can be swapped without a rebuild. That is a reasonable decision and
# this script does not change it.
#
# What it fixes is that the step lived only in those two KDoc blocks. A freshly
# flashed board has the app, the native library and no models, and the only
# symptom is the assistant answering "speech recognition failed" -- the models
# are missing is not something the UI can say, because by the time anything
# asks for a transcription the failure is three layers down. Every one of these
# is already logged under the MotorGuardVoice tag:
#
#     E MotorGuardVoice: whisper.bin not found in /data/user/10/...
#     E MotorGuardVoice: recognition requested but no model is loaded
#     I MotorGuardVoice: recognizer error 4
#
# Run this once per board after flashing.
#
#     ./models/install-to-board.sh              # first/only device
#     ./models/install-to-board.sh <serial>     # a specific one
#
# Survives a reboot (it writes to the app's data directory, not to a mount).
# Does NOT survive `pm clear`, an uninstall, or a reflash -- rerun it then.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG=com.motorguard.ivi

ADB=(adb)
if [ $# -ge 1 ]; then
  ADB=(adb -s "$1")
fi

"${ADB[@]}" wait-for-device

# The driver profile, not user 0: Automotive runs the app as a secondary user
# and the files directory that WhisperStt reads is that user's, so hardcoding
# 10 here would be wrong on a board provisioned differently.
USER_ID="$("${ADB[@]}" shell am get-current-user | tr -d '\r')"
DEST="/data/user/$USER_ID/$PKG/files"

echo "== target: user $USER_ID, $DEST"

if ! "${ADB[@]}" shell "[ -d $DEST ]" 2>/dev/null; then
  echo "ERROR: $DEST does not exist -- is $PKG installed for user $USER_ID," >&2
  echo "       and has it been launched at least once?" >&2
  exit 1
fi

# The app's own uid, read back rather than assumed: it is assigned at install
# time (u10_a55 on the current image, but that number moves whenever the set of
# installed apps changes) and a file owned by anyone else is one the app cannot
# open, which fails exactly like the file being absent.
OWNER="$("${ADB[@]}" shell stat -c '%U:%G' "$DEST" | tr -d '\r')"
echo "== owner: $OWNER"

for f in whisper.bin piper.onnx piper.json; do
  if [ ! -f "$DIR/$f" ]; then
    echo "ERROR: $DIR/$f missing from the checkout." >&2
    exit 1
  fi
  echo "== push $f ($(du -h "$DIR/$f" | cut -f1))"
  "${ADB[@]}" push "$DIR/$f" "/data/local/tmp/$f" >/dev/null
  "${ADB[@]}" shell "cp /data/local/tmp/$f $DEST/$f && rm /data/local/tmp/$f"
done

echo "== push espeak-ng-data ($(du -sh "$DIR/espeak" | cut -f1))"
"${ADB[@]}" push "$DIR/espeak" /data/local/tmp/espeak >/dev/null
"${ADB[@]}" shell "mkdir -p $DEST/espeak && cp -r /data/local/tmp/espeak/* $DEST/espeak/ && rm -rf /data/local/tmp/espeak"

# chown then restorecon, in that order and both required. The copies above were
# made by the shell user, so they land owned by root with the shell's SELinux
# label; the app is denied on either count alone.
"${ADB[@]}" shell "chown -R $OWNER $DEST/whisper.bin $DEST/piper.onnx $DEST/piper.json $DEST/espeak"
"${ADB[@]}" shell "restorecon -R $DEST/whisper.bin $DEST/piper.onnx $DEST/piper.json $DEST/espeak" 2>/dev/null || true

"${ADB[@]}" shell "am force-stop $PKG"

cat <<'DONE'

Installed. Relaunch Motor Guard and confirm with:

    adb logcat -d | grep MotorGuardVoice

Expect "whisper: model loaded from ...", "espeak: initialised" and
"using piper.onnx from ...". If any line still reads "not found", the
chown/restorecon above did not take -- check the owner reported at the
top of this run against `adb shell ls -laZ` on the files directory.
DONE

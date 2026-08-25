#!/usr/bin/env bash
#
# Time a Whisper model on the board, in place, without a rebuild.
#
# WHY THIS EXISTS
#
# "Will the Pi handle base.en" is not a question worth estimating. WhisperStt
# already logs every decode --
#
#     I MotorGuardVoice: whisper: 2400ms audio in 780ms (3.1x realtime)
#
# -- so the answer is one adb push and a handful of spoken commands away, and
# any figure argued from parameter counts is worse than the one the board will
# tell you.
#
# HOW IT WORKS
#
# ModelPaths searches the app's files directory BEFORE the system image, so a
# model pushed here overrides the installed one for as long as it is present.
# That makes the swap reversible without touching the image: --restore deletes
# the override and the shipped model is live again on the next utterance.
#
#     ./models/bench-stt.sh ~/Downloads/ggml-base.en.bin     # try a candidate
#     ./models/bench-stt.sh --restore                        # back to shipped
#     ./models/bench-stt.sh --measure                        # time what is live
#
# Get models from whisper.cpp's published set, e.g.
#   https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin
# The .en models are the ones to use here: whisper_jni.cpp hardcodes
# language="en", so a multilingual model spends capacity on languages this
# never asks for.
#
# WHAT TO WATCH
#
# The realtime multiple is the number that matters, not the millisecond count:
# it says how the model scales with how long you spoke. Decode happens AFTER
# endpointing, so whatever it costs lands as silence between the driver
# finishing and the assistant answering. Past roughly a second of that, the
# assistant reads as having ignored you.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG=com.motorguard.ivi
TAG=MotorGuardVoice

ADB=(adb)
[ -n "${ANDROID_SERIAL:-}" ] && ADB=(adb -s "$ANDROID_SERIAL")
"${ADB[@]}" wait-for-device

# Same reasoning as install-to-board.sh: the driver profile, not user 0.
USER_ID="$("${ADB[@]}" shell am get-current-user | tr -d '\r')"
DEST="/data/user/$USER_ID/$PKG/files"
OVERRIDE="$DEST/whisper.bin"

if ! "${ADB[@]}" shell "[ -d $DEST ]" 2>/dev/null; then
  echo "ERROR: $DEST does not exist -- is $PKG installed for user $USER_ID," >&2
  echo "       and has it been launched at least once?" >&2
  exit 1
fi

measure() {
  echo
  echo "== speak a few commands now; Ctrl-C when done"
  echo "== ('is there a fault in the motor', 'what does E-31 mean', 'skip this song')"
  echo
  # Only the decode lines. Every one carries both numbers, so no parsing beyond this.
  "${ADB[@]}" logcat -c
  "${ADB[@]}" logcat -s "$TAG:I" \
    | grep --line-buffered -oE 'whisper: [0-9]+ms audio in [0-9]+ms \([0-9.]+x realtime\)' \
    | tee /dev/stderr \
    | awk '
        # Field 5 is the decode time: "whisper: 2400ms audio in 780ms (3.1x realtime)".
        # Positional rather than a capture group, because 3-argument match() is a gawk
        # extension and this has to run under mawk too.
        { d = $5; sub(/ms$/, "", d); sum += d; n += 1 }
        END { if (n) printf "\n== %d utterances, mean decode %.0f ms\n", n, sum / n }
      '
}

case "${1:-}" in
  --restore)
    "${ADB[@]}" shell "rm -f $OVERRIDE" && echo "== override removed; the shipped model is live again"
    exit 0
    ;;
  --measure)
    measure
    exit 0
    ;;
  "")
    echo "usage: $0 <model.bin> | --restore | --measure" >&2
    exit 2
    ;;
esac

MODEL="$1"
[ -f "$MODEL" ] || { echo "ERROR: $MODEL not found." >&2; exit 1; }

echo "== push $(basename "$MODEL") ($(du -h "$MODEL" | cut -f1)) as the whisper.bin override"
OWNER="$("${ADB[@]}" shell stat -c '%U:%G' "$DEST" | tr -d '\r')"
"${ADB[@]}" push "$MODEL" /data/local/tmp/whisper.bin >/dev/null
"${ADB[@]}" shell "cp /data/local/tmp/whisper.bin $OVERRIDE && rm /data/local/tmp/whisper.bin"
"${ADB[@]}" shell "chown $OWNER $OVERRIDE && chmod 644 $OVERRIDE"

# WhisperStt loads the model once and holds it, so the running process is still
# using the old weights -- the override only takes effect on a fresh load.
echo "== restart $PKG so the new weights are loaded"
"${ADB[@]}" shell "am force-stop $PKG"
"${ADB[@]}" shell "monkey -p $PKG -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1 || true

measure

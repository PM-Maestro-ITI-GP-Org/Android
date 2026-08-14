#!/usr/bin/env bash
#
# Build a complete, platform-signed Motor Guard APK.
#
# This is the whole recipe in one command, because reconstructing it by hand is how
# pieces go missing. It:
#
#   1. installs the 3D vehicle model into assets/ (gitignored, so a fresh clone has none)
#   2. builds the aaos release APK with Gradle
#   3. zipaligns and signs it with the AOSP platform key
#   4. verifies the signer fingerprint matches the one the image trusts
#
#   ./scripts/build-signed-apk.sh                 # build + sign
#   ./scripts/build-signed-apk.sh --deploy IP     # ...and push it to a running board
#
# WHY GRADLE AND NOT SOONG
# ------------------------
# The Soong module in aosp/Android.bp lists exactly one JNI library
# (jni_libs: ["libmotorguardvoice"]) and does not extract JNI from its AAR
# dependencies, so an image built that way ships an APK with NO filament, gltfio,
# maplibre or onnxruntime .so at all. Observed on a real board:
#
#   UnsatisfiedLinkError: dlopen failed: library "libfilament-jni.so" not found
#     at Car3dRenderer.Render  ->  the Diagnostics tab crashes the whole launcher
#
# and, less loudly, the nav map and the wake word simply never start. Gradle packages
# every AAR's native libs automatically, which is why this path produces a working APK
# and why aosp/Android.bp option (B) (android_app_import) is the one to build images
# with. See docs and the header of aosp/Android.bp.
#
set -euo pipefail

APP_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$APP_ROOT"

# The platform key is NOT in the repo — it is a signing key, and the app must be signed
# with the same one the system image trusts or the platform will not grant it the
# privileged permissions it needs (and a reboot can drop it entirely).
KEY_DIR="${PLATFORM_KEY_DIR:-$HOME/motorguard-backups/platform-key}"
PK8="$KEY_DIR/platform.pk8"
PEM="$KEY_DIR/platform.x509.pem"

# The certificate the running image is signed with. Verified against the board's own
# /system/priv-app/MotorGuard/MotorGuard.apk — if a build ever stops matching this, the
# key is wrong and the APK must not be shipped.
EXPECTED_SHA256="c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"

bold=$'\033[1m'; grn=$'\033[32m'; red=$'\033[31m'; off=$'\033[0m'
die() { printf '%s\n' "${red}error:${off} $*" >&2; exit 1; }
step() { printf '%s\n' "${bold}==> $*${off}"; }

[[ -f "$PK8" && -f "$PEM" ]] || die "platform key not found in $KEY_DIR
  It is the AOSP test key (build/target/product/security/platform.{pk8,x509.pem}).
  Point PLATFORM_KEY_DIR at a directory holding both files."

# Newest build-tools wins; apksigner and zipalign both live there.
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)" \
  || die "no build-tools under $SDK"
[[ -x "$BT/apksigner" ]] || die "apksigner not found in $BT"

# --- 1. the 3D model -------------------------------------------------------
# Gitignored (it is 15 MB of binary), so a fresh clone builds a Diagnostics tab that
# renders "Vehicle model unavailable" until this runs.
if [[ ! -f app/src/main/assets/car_model.glb ]]; then
    step "installing the vehicle model"
    ./scripts/select-car-model.sh 3
else
    step "vehicle model already present"
fi

# --- 2. build --------------------------------------------------------------
step "building aaos release"
./gradlew :app:assembleAaosRelease -q --console=plain

UNSIGNED="app/build/outputs/apk/aaos/release/app-aaos-release-unsigned.apk"
[[ -f "$UNSIGNED" ]] || die "gradle produced no APK at $UNSIGNED"

# --- 3. align + sign -------------------------------------------------------
OUT_DIR="app/build/outputs/apk/aaos/release"
ALIGNED="$OUT_DIR/app-aaos-release-aligned.apk"
SIGNED="$OUT_DIR/MotorGuard.apk"

step "aligning and signing"
"$BT/zipalign" -p -f 4 "$UNSIGNED" "$ALIGNED"
"$BT/apksigner" sign --key "$PK8" --cert "$PEM" --out "$SIGNED" "$ALIGNED"

# --- 4. verify -------------------------------------------------------------
# Match on "SHA-256 digest" alone: build-tools 36 prints "Signer #1 certificate SHA-256
# digest:" while 37 prints "V3.0 Signer: certificate SHA-256 digest:", and pinning
# either wording makes this check silently yield nothing on the other.
GOT="$("$BT/apksigner" verify --print-certs "$SIGNED" 2>/dev/null \
        | awk '/certificate SHA-256 digest/ {print $NF; exit}')"
[[ "$GOT" == "$EXPECTED_SHA256" ]] \
  || die "signer mismatch — refusing to ship
  expected $EXPECTED_SHA256
  got      $GOT"

# A build that silently lost its native libs is the failure this whole script exists to
# prevent, so check rather than trust.
#
# The listing is taken once into a variable and matched with a here-string rather than
# piped: under `set -o pipefail`, `unzip -l big.apk | grep -q x` fails even on a match,
# because grep exits at the first hit, unzip dies of SIGPIPE, and pipefail reports the
# pipeline as failed. That turns a perfectly good APK into "the build is incomplete".
LISTING="$(unzip -l "$SIGNED")"
for entry in \
    lib/arm64-v8a/libfilament-jni.so \
    lib/arm64-v8a/libmaplibre.so \
    lib/arm64-v8a/libonnxruntime.so \
    lib/arm64-v8a/libmotorguardvoice.so \
    assets/car_model.glb
do
    grep -q -- "$entry" <<<"$LISTING" || die "$entry missing from the APK — the build is incomplete"
done

printf '%s\n' "${grn}✓${off} $SIGNED"
printf '   signer %s\n' "$GOT"
ls -la "$SIGNED"

# --- 5. optional deploy ----------------------------------------------------
if [[ "${1:-}" == "--deploy" ]]; then
    SERIAL="${2:?--deploy needs a device, e.g. 192.168.2.2:5555}"
    step "deploying to $SERIAL"
    "$APP_ROOT/scripts/deploy-to-board.sh" "$SERIAL" "$SIGNED"
fi

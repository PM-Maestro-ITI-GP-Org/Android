#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

APP_BRANCH=""
AOSP_ROOT=""
CAR_MODEL=""
SKIP_FETCH=0

if [ -f "$ROOT/deploy.conf" ]; then
  # shellcheck source=deploy.conf
  . "$ROOT/deploy.conf"
fi

for arg in "$@"; do
  case "$arg" in
    --no-fetch) SKIP_FETCH=1 ;;
    *) if [ -d "$arg" ] && [ -f "$arg/build/envsetup.sh" ]; then
         AOSP_ROOT="$arg"
       elif [ -n "$arg" ]; then
         APP_BRANCH="$arg"
       fi ;;
  esac
done

if [ -z "$APP_BRANCH" ]; then
  echo "ERROR: no app branch set. Set APP_BRANCH in deploy.conf or pass it: ./deploy.sh <aosp-root> <branch>" >&2
  exit 2
fi
if [ -z "$AOSP_ROOT" ] || [ ! -f "$AOSP_ROOT/build/envsetup.sh" ]; then
  echo "ERROR: no AOSP tree. Set AOSP_ROOT in deploy.conf or pass it: ./deploy.sh <aosp-root>" >&2
  exit 2
fi
AOSP_ROOT="$(cd "$AOSP_ROOT" && pwd)"

SUBMODULE="$ROOT/vendor/motorguard/MotorGuard/MotorGuard_Application/app"

echo "== 1/6 select app source: branch '$APP_BRANCH'"
if [ ! -f "$SUBMODULE/.git" ] && [ ! -d "$SUBMODULE/.git" ]; then
  echo "  initializing app submodule (git submodule update --init)"
  git -C "$ROOT" submodule update --init -- "$SUBMODULE"
fi
git -C "$SUBMODULE" fetch origin "$APP_BRANCH"
git -C "$SUBMODULE" checkout -f "origin/$APP_BRANCH"

if [ ! -f "$SUBMODULE/MotorGuardApp/app/src/main/AndroidManifest.xml" ]; then
  echo "ERROR: branch '$APP_BRANCH' does not contain MotorGuardApp/app/src/main/AndroidManifest.xml" >&2
  echo "  (layout contract: the app must live at MotorGuardApp/app/src/main in that branch)" >&2
  exit 1
fi

echo "== 2/6 patch the app source for the in-tree build"

apply_app_patch() {
  local name="$1" marker_file="$2" marker="$3"
  local src="$ROOT/vendor/motorguard/MotorGuard/MotorGuard_Application/$name"
  if grep -qF "$marker" "$SUBMODULE/$marker_file" 2>/dev/null; then
    echo "  $name: already applied"
  elif git -C "$SUBMODULE" apply --check "$src" >/dev/null 2>&1; then
    git -C "$SUBMODULE" apply "$src"
    echo "  $name: applied (git apply)"
  elif patch -p1 -d "$SUBMODULE" --dry-run < "$src" >/dev/null 2>&1; then
    patch -p1 -d "$SUBMODULE" < "$src"
    echo "  $name: applied (patch -p1)"
  else
    echo "ERROR: could not apply $name to $SUBMODULE" >&2
    echo "  The app branch has moved under it. Re-cut the patch against branch '$APP_BRANCH'." >&2
    exit 1
  fi
}

# package= on the manifest root (AOSP needs it, Gradle does not) + the
# CACHE_BYTES declaration-order bug that only the in-tree kotlinc rejects.
apply_app_patch "build-fixes.patch" \
  "MotorGuardApp/app/src/main/AndroidManifest.xml" 'package="com.motorguard.ivi"'

# Hands the motor signal and the capture button to the SOME/IP link when the
# native library is present, and leaves the fake in place when it is not. Only
# the AOSP build gets this; the Gradle branch keeps building against the fake,
# which is why it stays a patch instead of living on the app branch.
apply_app_patch "motorservice/vehicledata-someip.patch" \
  "MotorGuardApp/app/src/main/java/com/motorguard/ivi/ui/diagnostics/VehicleData.kt" \
  "SomeIpVehicleData"

echo "== 3/6 install diagnostics assets (3D vehicle model)"

# The blue print compiles core/vehicle-data-{api,fake} into the app. A branch without them
# is a pre-diagnostics branch and would fail deep inside kotlinc on unresolved
# com.motorguard.ivi.data.vehicle.* imports, so say it here instead.
for m in vehicle-data-api vehicle-data-fake; do
  if [ ! -d "$SUBMODULE/MotorGuardApp/core/$m/src/main/java" ]; then
    echo "ERROR: branch '$APP_BRANCH' has no MotorGuardApp/core/$m — it predates the" >&2
    echo "  diagnostics work. Use the drop-in branch that matches it, or set APP_BRANCH" >&2
    echo "  to a branch carrying the diagnostics modules." >&2
    exit 1
  fi
done

ASSETS="$SUBMODULE/MotorGuardApp/app/src/main/assets"
MODEL_SRC="$SUBMODULE/vehicle3dModel/$CAR_MODEL"

# car_model.glb is .gitignored on the app branch (15 MB of binary), so it is never in the
# checkout — install it from the model library in the same checkout, exactly as
# MotorGuardApp/scripts/select-car-model.sh does for the Gradle build.
#
# `git checkout -f` in step 1 does not remove untracked files, so a model installed by an
# earlier deploy survives. Re-install only when it is not what CAR_MODEL now names —
# otherwise changing CAR_MODEL would silently keep building the old car.
INSTALLED_MODEL=""
if [ -f "$ASSETS/car_model.source.txt" ]; then
  INSTALLED_MODEL="$(sed -n 's/^model: //p' "$ASSETS/car_model.source.txt" | head -1)"
fi

if [ -z "$CAR_MODEL" ] && [ -f "$ASSETS/car_model.glb" ]; then
  echo "  car_model.glb: present (${INSTALLED_MODEL:-source unrecorded}), CAR_MODEL unset — left alone"
elif [ -z "$CAR_MODEL" ]; then
  echo "ERROR: CAR_MODEL is unset and there is no car_model.glb to fall back on." >&2
  echo "  Set CAR_MODEL in deploy.conf to a path under vehicle3dModel/." >&2
  exit 1
elif [ -f "$ASSETS/car_model.glb" ] && [ "$INSTALLED_MODEL" = "$CAR_MODEL" ]; then
  echo "  car_model.glb: already installed from $CAR_MODEL"
elif [ -f "$MODEL_SRC" ]; then
  cp "$MODEL_SRC" "$ASSETS/car_model.glb"
  printf 'model: %s\ninstalled: %s\nsize: %s\nby: deploy.sh (AOSP in-tree build)\n' \
    "$CAR_MODEL" "$(date -Is)" "$(du -h "$MODEL_SRC" | cut -f1)" \
    > "$ASSETS/car_model.source.txt"
  echo "  car_model.glb: installed from vehicle3dModel/$CAR_MODEL"
else
  echo "ERROR: model not found: $MODEL_SRC" >&2
  echo "  Available under vehicle3dModel/ on branch '$APP_BRANCH':" >&2
  find "$SUBMODULE/vehicle3dModel" -name '*.glb' -not -path '*/motor_battery_models/*' \
    -printf '    %P\n' >&2 2>/dev/null || true
  exit 1
fi

# The stage floor is loaded by name (Car3dTuning.BACKDROP_*_ASSET). Missing files are not a
# build error — the app degrades to a bare stage — so warn rather than stop.
for a in stage_floor_day.png stage_floor_night.png; do
  [ -f "$ASSETS/$a" ] || echo "  WARNING: $a missing on branch '$APP_BRANCH' — the 3D stage will render without its floor"
done

echo "== 4/6 install drop-in into $AOSP_ROOT"
mkdir -p "$AOSP_ROOT/vendor/motorguard"

# Preserve already-fetched prebuilts across deploys. The artifacts are never committed and
# re-downloading all of them every deploy is slow; fetch.sh re-verifies SHA256 anyway and
# skips whatever is already present.
PREBUILT_BAK=""
if [ -d "$AOSP_ROOT/vendor/motorguard/MotorGuard/MotorGuard_Application/prebuilts" ]; then
  PREBUILT_BAK="$AOSP_ROOT/vendor/motorguard/MotorGuard.prebuilts.bak"
  rm -rf "$PREBUILT_BAK"
  mv "$AOSP_ROOT/vendor/motorguard/MotorGuard/MotorGuard_Application/prebuilts" "$PREBUILT_BAK"
fi

rm -rf "$AOSP_ROOT/vendor/motorguard/MotorGuard"
cp -r "$ROOT/vendor/motorguard/MotorGuard" "$AOSP_ROOT/vendor/motorguard/"
rm -f "$AOSP_ROOT/vendor/motorguard/MotorGuard/MotorGuard_Application/app/.git"

# The model library is 121 MB of .glb/.zip that nothing in the tree reads — the one model
# the app needs was copied into assets/ in step 3. Drop it from the installed copy (not
# from this repo's checkout) so each deploy doesn't leave another 121 MB in the AOSP tree.
rm -rf "$AOSP_ROOT/vendor/motorguard/MotorGuard/MotorGuard_Application/app/vehicle3dModel"

if [ -n "$PREBUILT_BAK" ] && [ -d "$PREBUILT_BAK" ]; then
  # Restore ONLY the downloaded artifacts, never fetch.sh or README.md. Restoring the
  # whole directory put the PREVIOUS deploy's fetch.sh back, so a drop-in that added
  # dependencies kept running the old manifest: it printed "All 17 prebuilts present
  # and SHA256-verified", exited 0, and the build then died in soong with
  #   module "motorguard-kotlin-math": source path ".../kotlin-math-jvm-1.5.3.jar" does not exist
  # fetch.sh is idempotent and re-verifies SHA256, so keeping the artifacts alone is enough.
  NEW_PREBUILTS="$AOSP_ROOT/vendor/motorguard/MotorGuard/MotorGuard_Application/prebuilts"
  find "$PREBUILT_BAK" -maxdepth 1 -type f \( -name '*.aar' -o -name '*.jar' \) \
    -exec mv -n {} "$NEW_PREBUILTS/" \; 2>/dev/null || true
  # The extracted JNI .so files too — fetch.sh re-extracts them, but moving them saves
  # re-unzipping five AARs on every deploy.
  if [ -d "$PREBUILT_BAK/jni" ]; then
    mkdir -p "$NEW_PREBUILTS/jni"
    cp -rn "$PREBUILT_BAK/jni/." "$NEW_PREBUILTS/jni/" 2>/dev/null || true
  fi
  rm -rf "$PREBUILT_BAK"
  echo "  preserved downloaded artifacts (fetch.sh from the drop-in will re-verify)"
fi

# The app branch still ships an obsolete self-contained MotorGuardApp/aosp/Android.bp
# (the pre-drop-in layout). The drop-in blue print (MotorGuard_Application/Android.bp)
# supersedes it; Soong would otherwise see both and fail on duplicate module names.
STALE_BP="$AOSP_ROOT/vendor/motorguard/MotorGuard/MotorGuard_Application/app/MotorGuardApp/aosp/Android.bp"
if [ -f "$STALE_BP" ]; then
  mv "$STALE_BP" "$STALE_BP.disabled"
  echo "  disabled obsolete MotorGuardApp/aosp/Android.bp (superseded by new blue print)"
fi

echo "  installed (app from branch $APP_BRANCH + build fixes)"

echo "== 5/6 apply device integration patches"

apply_device_patch() {
  local pname="$1" target="$2" marker="$3"
  local src="$ROOT/device/brcm/rpi5/$pname"
  local tgt="$AOSP_ROOT/device/brcm/rpi5/$target"
  if [ -f "$tgt" ] && grep -qF "$marker" "$tgt"; then
    echo "  $pname: already applied"
  elif git -C "$AOSP_ROOT/device/brcm/rpi5" apply --check "$src" >/dev/null 2>&1; then
    git -C "$AOSP_ROOT/device/brcm/rpi5" apply "$src"
    echo "  $pname: applied (git apply)"
  elif patch -p1 -d "$AOSP_ROOT/device/brcm/rpi5" --dry-run < "$src" >/dev/null 2>&1; then
    patch -p1 -d "$AOSP_ROOT/device/brcm/rpi5" < "$src"
    echo "  $pname: applied (patch -p1)"
  else
    echo "ERROR: could not apply $src to $tgt" >&2
    exit 1
  fi
}

apply_device_patch "aosp_rpi5_car.mk.patch" "aosp_rpi5_car.mk" "CarSystemUISystemBarPersistcyImmersive"
apply_device_patch "BoardConfig.mk.patch" "BoardConfig.mk" "connected monitor's native EDID"
apply_device_patch "vendor.prop.patch" "vendor.prop" "service.adb.tcp.port=5555"
# eth0's connected route is not in Android's policy tables (the ConnectivityRpiOverlay
# strips NET_CAPABILITY_INTERNET from eth0), so without this adb/scrcpy over ethernet AND
# the SOME/IP link to the QNX diagnostics unit both fall through to ip rule 32000.
apply_device_patch "eth0_routes.patch" "ramdisk/eth0_routes.sh" "persist.motorguard.eth0_addr"
# Mono capture-only USB microphones are rejected by the stock policy.
apply_device_patch "usb_audio_policy_configuration.xml.patch" \
  "audio/usb_audio_policy_configuration.xml" "Mono instead of stereo"
# USB speaker via car audio — the stock config only has Speaker for every context, so a USB
# speaker (card3 pcm0p) is detected (Available output devices shows it) but never selected.
# One volume group with three outputs (Speaker + USB Device/Headset) shares the same volume
# so the car UI's slider controls all, and the bar not increasing at max is fixed.
apply_device_patch "car_audio_configuration.xml.patch" \
  "car/car_audio_configuration.xml" "USB Device Out"
# The USB DAC enumerates as AUDIO_DEVICE_OUT_USB_HEADSET (it has a capture endpoint too,
# for the mic), so the stock hearing-safety limiter clamps STREAM_MUSIC to its safe index
# (~1/4 of max) no matter what the user sets. Disable the limiter instead of reclassifying
# the device type — reclassifying would also drop the automatic USB-mic association the
# voice assistant needs, which is what broke the mic the last time this was "fixed".
apply_device_patch "android_rpi_overlay_safe_volume.patch" \
  "overlay/AndroidRpiOverlay/res/values/config.xml" "config_safe_media_volume_enabled"
# USB microphones (the C-Media "USB PnP" on this board) ship with the capture path muted
# and half gain by default; nothing in the stock USB audio module unmutes an input, so
# pcm_read() returns no samples even though the stream opens. Unmute + set a sane capture
# level both on device connect (UsbAlsaMixerControl) and on stream start (StreamAlsa), since
# either path can be the one that first touches the card.
apply_device_patch "usb_mic_unmute.patch" \
  "audio/include/core-impl/StreamAlsa.h" "unmuteCapture"
# This board runs two USB audio peripherals at once: a combo speaker+mic unit and a
# separate standalone microphone. The framework's USB device selection tracks only one
# "current" device per direction and always locks onto the combo unit (it enumerates
# first), so input silently read from its low-sensitivity onboard mic instead of the
# actual microphone -- confirmed live: capture was healthy (unmuted, gain correct), it
# just never heard anything, because nothing was said near that card. Override input
# routing to whichever connected USB card is capture-only (no playback controls at all),
# which is what distinguishes the standalone mic from the combo unit.
apply_device_patch "usb_mic_route.patch" \
  "audio/usb/StreamUsb.cpp" "findDedicatedCaptureOnlyCard"

# Guard against this list going stale again: every *.patch in the drop-in's
# device/brcm/rpi5/ must be accounted for above. Two patches (eth0_routes,
# usb_audio_policy_configuration) sat in the repo unapplied for exactly this reason —
# they were added without being wired in here, and nothing said so.
for p in "$ROOT"/device/brcm/rpi5/*.patch; do
  pname="$(basename "$p")"
  case "$pname" in
    aosp_rpi5_car.mk.patch|BoardConfig.mk.patch|vendor.prop.patch| \
    eth0_routes.patch|usb_audio_policy_configuration.xml.patch|car_audio_configuration.xml.patch| \
    android_rpi_overlay_safe_volume.patch|usb_mic_unmute.patch|usb_mic_route.patch|car_bluetooth_prop.patch) ;;
    *) echo "WARNING: $pname exists but deploy.sh never applies it" >&2 ;;
  esac
done

# packages/services/Car, not device/brcm/rpi5: the Car product's bluetooth.prop sets
# bluetooth.profile.map.client.enabled=true while motorguard.mk sets it =false, and
# gen_build_prop treats a property assigned by both a prop-file and
# PRODUCT_SYSTEM_PROPERTIES as an ERROR, not an override:
#   error: found duplicate sysprop assignments: bluetooth.profile.map.client.enabled
# This only breaks the FULL image build (`m systemimage`), never `m MotorGuard`, which
# is why it went unnoticed. The override itself is required — this board has no
# telephony, so MapClientContent crash-loops the BT stack when a phone connects.
CAR_DIR="$AOSP_ROOT/packages/services/Car"
CAR_PATCH="$ROOT/device/brcm/rpi5/car_bluetooth_prop.patch"
CAR_TGT="$CAR_DIR/car_product/properties/bluetooth.prop"
if [ ! -f "$CAR_TGT" ]; then
  echo "  car_bluetooth_prop.patch: no packages/services/Car in this tree — skipped"
elif grep -qF "set by vendor/motorguard" "$CAR_TGT"; then
  echo "  car_bluetooth_prop.patch: already applied"
elif git -C "$CAR_DIR" apply --check "$CAR_PATCH" >/dev/null 2>&1; then
  git -C "$CAR_DIR" apply "$CAR_PATCH"; echo "  car_bluetooth_prop.patch: applied (git apply)"
elif patch -p1 -d "$CAR_DIR" --dry-run < "$CAR_PATCH" >/dev/null 2>&1; then
  patch -p1 -d "$CAR_DIR" < "$CAR_PATCH"; echo "  car_bluetooth_prop.patch: applied (patch -p1)"
else
  echo "ERROR: could not apply car_bluetooth_prop.patch to $CAR_TGT" >&2
  exit 1
fi

echo "== 6/6 fetch prebuilt AARs/jars"
if [ "$SKIP_FETCH" = "1" ]; then
  echo "  skipped (--no-fetch)"
else
  "$AOSP_ROOT/vendor/motorguard/MotorGuard/MotorGuard_Application/prebuilts/fetch.sh"
fi

cat <<EOF

Done. Next steps:
  cd "$AOSP_ROOT"
  source build/envsetup.sh
  lunch aosp_rpi5_car-bp1a-userdebug
  export GOMEMLIMIT=10GiB GOGC=50
  nice -n 10 m MotorGuard -j6                      # app only, fast iteration
  nice -n 10 m bootimage systemimage vendorimage -j2   # full image (use -j2: javac OOMs at -j6)
EOF

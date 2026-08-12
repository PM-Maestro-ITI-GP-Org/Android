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
  rm -rf "$AOSP_ROOT/vendor/motorguard/MotorGuard/MotorGuard_Application/prebuilts"
  mv "$PREBUILT_BAK" "$AOSP_ROOT/vendor/motorguard/MotorGuard/MotorGuard_Application/prebuilts"
  echo "  preserved existing prebuilts (fetch.sh will skip verified files)"
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

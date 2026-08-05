#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

APP_BRANCH=""
AOSP_ROOT=""
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

echo "== 1/5 select app source: branch '$APP_BRANCH'"
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

echo "== 2/5 apply required build fixes (package= + CACHE_BYTES order)"
FIXES="$ROOT/vendor/motorguard/MotorGuard/MotorGuard_Application/build-fixes.patch"
if grep -q 'package="com.motorguard.ivi"' "$SUBMODULE/MotorGuardApp/app/src/main/AndroidManifest.xml"; then
  echo "  already applied"
elif git -C "$SUBMODULE" apply --check "$FIXES" >/dev/null 2>&1; then
  git -C "$SUBMODULE" apply "$FIXES"
  echo "  applied (git apply)"
elif patch -p1 -d "$SUBMODULE" --dry-run < "$FIXES" >/dev/null 2>&1; then
  patch -p1 -d "$SUBMODULE" < "$FIXES"
  echo "  applied (patch -p1)"
else
  echo "ERROR: could not apply build fixes to $SUBMODULE" >&2
  exit 1
fi

echo "== 3/5 install drop-in into $AOSP_ROOT"
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

echo "== 4/5 apply device integration patches"

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
apply_device_patch "BoardConfig.mk.patch" "BoardConfig.mk" "vc4.force_hotplug"
apply_device_patch "vendor.prop.patch" "vendor.prop" "service.adb.tcp.port=5555"

echo "== 5/5 fetch prebuilt AARs/jars"
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

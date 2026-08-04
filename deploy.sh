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
rm -rf "$AOSP_ROOT/vendor/motorguard/MotorGuard"
cp -r "$ROOT/vendor/motorguard/MotorGuard" "$AOSP_ROOT/vendor/motorguard/"
rm -f "$AOSP_ROOT/vendor/motorguard/MotorGuard/MotorGuard_Application/app/.git"
echo "  installed (app from branch $APP_BRANCH + build fixes)"

echo "== 4/5 apply device integration patch"
PATCH_SRC="$ROOT/device/brcm/rpi5/aosp_rpi5_car.mk.patch"
PATCH_TARGET="$AOSP_ROOT/device/brcm/rpi5/aosp_rpi5_car.mk"
if [ -f "$PATCH_TARGET" ] && grep -q "CarSystemUISystemBarPersistcyImmersive" "$PATCH_TARGET"; then
  echo "  already applied"
elif git -C "$AOSP_ROOT/device/brcm/rpi5" apply --check "$PATCH_SRC" >/dev/null 2>&1; then
  git -C "$AOSP_ROOT/device/brcm/rpi5" apply "$PATCH_SRC"
  echo "  applied (git apply)"
elif patch -p1 -d "$AOSP_ROOT/device/brcm/rpi5" --dry-run < "$PATCH_SRC" >/dev/null 2>&1; then
  patch -p1 -d "$AOSP_ROOT/device/brcm/rpi5" < "$PATCH_SRC"
  echo "  applied (patch -p1)"
else
  echo "ERROR: could not apply $PATCH_SRC to $PATCH_TARGET" >&2
  exit 1
fi

echo "== 5/5 fetch prebuilt AARs/jars"
if [ "$SKIP_FETCH" = "1" ]; then
  echo "  skipped (--no-fetch)"
else
  "$AOSP_ROOT/vendor/motorguard/MotorGuard/prebuilts/fetch.sh"
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

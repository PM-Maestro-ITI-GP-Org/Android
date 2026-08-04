#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AOSP="${1:-}"
SKIP_FETCH=0
[ "${2:-}" = "--no-fetch" ] && SKIP_FETCH=1

if [ -z "$AOSP" ] || [ ! -f "$AOSP/build/envsetup.sh" ]; then
  echo "usage: $0 <aosp-root> [--no-fetch]" >&2
  echo "  <aosp-root> must be an AOSP tree containing build/envsetup.sh" >&2
  exit 2
fi

AOSP="$(cd "$AOSP" && pwd)"

mkdir -p "$AOSP/vendor/motorguard"
rm -rf "$AOSP/vendor/motorguard/MotorGuard"
cp -r "$ROOT/vendor/motorguard/MotorGuard" "$AOSP/vendor/motorguard/"
echo "installed: $AOSP/vendor/motorguard/MotorGuard"

PATCH_SRC="$ROOT/device/brcm/rpi5/aosp_rpi5_car.mk.patch"
PATCH_TARGET="$AOSP/device/brcm/rpi5/aosp_rpi5_car.mk"
if [ -f "$PATCH_TARGET" ] && grep -q "CarSystemUISystemBarPersistcyImmersive" "$PATCH_TARGET"; then
  echo "patch: already applied to $PATCH_TARGET"
elif git -C "$AOSP/device/brcm/rpi5" apply --check "$PATCH_SRC" >/dev/null 2>&1; then
  git -C "$AOSP/device/brcm/rpi5" apply "$PATCH_SRC"
  echo "patch: applied to $AOSP/device/brcm/rpi5 (git apply)"
elif patch -p1 -d "$AOSP/device/brcm/rpi5" --dry-run < "$PATCH_SRC" >/dev/null 2>&1; then
  patch -p1 -d "$AOSP/device/brcm/rpi5" < "$PATCH_SRC"
  echo "patch: applied to $AOSP/device/brcm/rpi5 (patch -p1)"
else
  echo "ERROR: could not apply $PATCH_SRC to $AOSP/device/brcm/rpi5" >&2
  exit 1
fi

if [ "$SKIP_FETCH" = "1" ]; then
  echo "fetch: skipped (--no-fetch)"
else
  "$AOSP/vendor/motorguard/MotorGuard/aosp/prebuilts/fetch.sh"
fi

cat <<EOF

Next steps:
  cd "$AOSP"
  source build/envsetup.sh
  lunch aosp_rpi5_car-bp1a-userdebug
  export GOMEMLIMIT=10GiB GOGC=50
  nice -n 10 m MotorGuard -j6        # app only, fast iteration
  nice -n 10 m bootimage systemimage vendorimage -j2   # full image (use -j2: javac OOMs at -j6)
EOF

# Motor Guard — AAOS in-tree build

This branch is the **Soong drop-in**: Motor Guard laid out to be deployed into an
AOSP / Raspberry Pi (KonstaKANG) tree and built with `m MotorGuard` as a platform-signed,
privileged AAOS launcher. There is no Gradle build here — `Android.bp` is the only build file.

> **The app source is a submodule.** `vendor/motorguard/MotorGuard/MotorGuard_Application/app/`
> is a git submodule of `PM-Maestro-ITI-GP-Org/Android`, checked out to a branch of your choice
> (default `media-nav-settings-voice`). That branch holds the **final application**. `deploy.sh`
> fetches and checks out its tip, and the blue print reads the sources from
> `app/MotorGuardApp/app/src/main/...`, ignoring the Gradle files and everything else.
> When your friends ship a new final build on a new branch, set `APP_BRANCH` in `deploy.conf`
> and re-run `./deploy.sh` — nothing else to change.

## Layout

The tree mirrors the AOSP hierarchy so `deploy.sh` can drop it in verbatim:

```
deploy.conf                    the ONLY file you edit per release: APP_BRANCH + AOSP_ROOT
deploy.sh                      one command: select app branch → copy → patch → fetch prebuilts
vendor/motorguard/MotorGuard/  the drop-in package (blue print + glue)
  motorguard.mk                product makefile snippet (PRODUCT_PACKAGES += MotorGuard)
  MotorGuard_Application/
    app/                       git submodule → app source at app/MotorGuardApp/app/src/main/
    Android.bp                 the blue print — all Soong modules (paths relative to this file)
    prebuilts/fetch.sh         downloads + SHA256-verifies the 17 prebuilt AARs/jars (never
                               committed; skips files already present — deploy.sh preserves them)
    privapp_permissions_com.motorguard.ivi.xml   privileged allow-list → /system/etc/permissions
    res-platform/              overlay flipping use_real_connectivity → true (real radios)
 device/brcm/rpi5/
   aosp_rpi5_car.mk.patch     device integration patch (applied by deploy.sh)
   BoardConfig.mk.patch       display note: use the connected monitor's native EDID (no override)
   vendor.prop.patch          adb over ethernet (HWC mode follows the monitor's native EDID, e.g. 1920x1080)
```

`Android.bp` defines the `MotorGuard` app, `libmotorguardvoice` (the native voice/reasoning
core — the Soong equivalent of `cpp/CMakeLists.txt`, since Soong does not run CMake), and the
vendored import modules for ONNX Runtime, media3, MapLibre, okhttp/okio and friends. A commented
`android_app_import` at the bottom is path (B): bundle a Gradle-built, platform-re-signed APK
instead of compiling from source.

## Quick start (clone → build → flash)

1. **Check out the AOSP 15 tree** (raspberry-vanilla, Ubuntu 22.04 LTS):

   ```bash
   sudo apt-get install dosfstools e2fsprogs fdisk kpartx mtools rsync
   repo init -u https://android.googlesource.com/platform/manifest -b android-15.0.0_r32
   curl -o .repo/local_manifests/manifest_brcm_rpi.xml -L \
     https://raw.githubusercontent.com/raspberry-vanilla/android_local_manifest/android-15.0/manifest_brcm_rpi.xml --create-dirs
   repo sync
   ```

   (A shallow `--depth=1` clone plus `remove_projects.xml` shrinks the download — see the
   raspberry-vanilla README. `adb root` on the Pi requires `userdebug`.)

2. **Configure and deploy** — set two values in `deploy.conf`, then one command:

   ```bash
   # deploy.conf
   APP_BRANCH=final-app            # branch holding the final application (friends' branch)
   AOSP_ROOT=/path/to/aosp         # your AOSP 15 tree
   ./deploy.sh                     # selects app branch, copies the drop-in, patches device, fetches prebuilts
   # override on the CLI: ./deploy.sh /path/to/aosp final-app
   ```

   That does the equivalent of the manual steps in the *Manual install* section below.

3. **Build:**

   ```bash
   cd /path/to/aosp
   source build/envsetup.sh
   lunch aosp_rpi5_car-bp1a-userdebug
   export GOMEMLIMIT=10GiB GOGC=50
   nice -n 10 m MotorGuard -j6                      # app only — fast iteration
   nice -n 10 m bootimage systemimage vendorimage -j2
   ```

   > **Use `-j2` for the full image.** `-j6` OOMs: the CarSystemUI javac (dagger,
   > `-J-Xmx8192M`) is killed at ~8 GB RSS. On a 16 GB machine also add swap
   > (`sudo fallocate -l 16G /swapfile2 && sudo mkswap /swapfile2 && sudo swapon /swapfile2`)
   > and the `GOMEMLIMIT`/`GOGC` exports above (soong_build alone peaks at ~11 GB).

4. **Flash** (from the AOSP tree, lunch env loaded, no sudo for mkimg):

   ```bash
   ./rpi5-mkimg.sh                                  # assembles the SD image in out/target/product/rpi5/
   sudo ./rpi5-wrimg.sh /dev/sdX                   # only auto-detects sdX names
   # built-in SD slot (mmcblk0): sudo dd if=out/target/product/rpi5/RaspberryVanillaAOSP15-*.img of=/dev/mmcblk0 bs=4M status=progress conv=fsync
   ```

   It is already the launcher: `overrides: ["CarLauncher"]` drops the stock Car launcher, so
   Motor Guard is HOME from first boot. The immersive overlay makes the system bars
   swipe-revealable kiosk-style.

## Manual install (what deploy.sh automates)

```bash
git clone --recurse-submodules -b media-nav-settings-voice_forAAOS git@github.com:PM-Maestro-ITI-GP-Org/Android.git dropin
cp -r dropin/vendor/motorguard/MotorGuard /path/to/aosp/vendor/motorguard/
git -C /path/to/aosp/device/brcm/rpi5 apply dropin/device/brcm/rpi5/aosp_rpi5_car.mk.patch
git -C /path/to/aosp/device/brcm/rpi5 apply dropin/device/brcm/rpi5/BoardConfig.mk.patch
git -C /path/to/aosp/device/brcm/rpi5 apply dropin/device/brcm/rpi5/vendor.prop.patch
/path/to/aosp/vendor/motorguard/MotorGuard/MotorGuard_Application/prebuilts/fetch.sh
```

The device patches touch `device/brcm/rpi5/`:

`aosp_rpi5_car.mk`:

```make
$(call inherit-product, vendor/motorguard/MotorGuard/motorguard.mk)
PRODUCT_PACKAGES += \
    CarSystemUISystemBarPersistcyImmersive
```

`BoardConfig.mk` (display): the connected monitor's native EDID is used as-is. Monitors
with working DDC/EDID (e.g. 1920x1080 panels) are driven at their native resolution, so no
`force_hotplug` / `drm.edid_firmware` override is applied (a custom firmware EDID would be
needed only for DDC-less panels like the QDTECH MPI7002).

`vendor.prop` (debug): adb over ethernet; the HWC mode (`vendor.hwc.drm.force_mode`)
defaults to the monitor's native resolution baked at build time (1920x1080):

```
service.adb.tcp.port=5555
vendor.hwc.drm.force_mode=1920x1080
```

## Voice subsystem (Vega) prerequisites

- **Prebuilts** — the 17 AARs/jars (ONNX Runtime, media3, MapLibre, okhttp/okio, …) are never
  committed; `MotorGuard_Application/prebuilts/fetch.sh` downloads each and SHA256-verifies it
  (idempotent: re-running skips files that already match). Without them soong fails to resolve
  `ai.onnxruntime.*`, `androidx.media3.*` and `org.maplibre.*`.
- **SQLite** — `app/MotorGuardApp/app/src/main/cpp/sqlite3.c` + `sqlite3.h` (the official
  amalgamation) are committed in the app submodule and compiled straight in, because the
  platform does not expose libsqlite to apps.
- **Wake-word models** — the `*.onnx` files under
  `app/MotorGuardApp/app/src/main/assets/wakeword/` are kept uncompressed via
  `aaptflags: ["-0 .onnx"]` (ONNX Runtime mmaps them).

## Why it has to be built this way

The app controls real Wi-Fi and Bluetooth radios (`setWifiEnabled`, `addNetwork`,
`BluetoothAdapter.enable`, `removeBond`). Those calls **return `false` for any non-system app** —
they need `NETWORK_SETTINGS` / `OVERRIDE_WIFI_CONFIG` / `BLUETOOTH_PRIVILEGED`, which require all
three of: the app being platform-signed, shipping in `/system/priv-app`, and being named in the
privapp allow-list. `adb install` gives you none of that.

`Conn.init()` reads `bool/use_real_connectivity`. The Gradle build leaves it `false` (mock repos);
`res-platform` flips it `true` in this build, selecting `RealWifiRepo` / `RealBtRepo`. There is
also a runtime override in Settings ▸ System, added because the Gradle APK cannot carry this overlay.

> **The manifest and the allow-list must stay in sync.** A privileged permission needs to be BOTH
> requested in `AndroidManifest.xml` and allow-listed in
> `privapp_permissions_com.motorguard.ivi.xml`. If a permission is allow-listed but not requested
> it silently does nothing; if one is requested but not allow-listed the platform **refuses to boot
> the app at all**. Change one, change the other.
>
> **Layout contract for app branches:** the blue print hardcodes `MotorGuardApp/app/src/main/...`
> inside the submodule. A branch that moves the app breaks the AOSP build until the paths in
> `MotorGuard_Application/Android.bp` are updated. The app-branch manifest must carry
> `package="com.motorguard.ivi"` on the root element (Gradle does not need it; AOSP does).

## Status — verified

- **`m MotorGuard` builds clean** on the KonstaKANG android-15.0.0_r32 tree (28 min on a
  16 GB machine with the knobs above). All soong/kotlin/native issues are fixed in this tree:
  media3/onnxruntime/MapLibre imports, `jni_headers` for `jni.h`, `-fexceptions` for the C++
  core, the constraintlayout module name, `lifecycle-viewmodel-compose`, the `CACHE_BYTES`
  declaration-order bug, the `package=` manifest attribute and the `INTERNET` permission.
- **Full image (`bootimage systemimage vendorimage -j2`) builds** and the packaged image was
  verified: `MotorGuard.apk` in `/system/priv-app`, CarLauncher removed, privapp allow-list
  present.
- The image was flashed to a Pi 5 and booted; log tags: `MotorGuardVoice` (kotlin + native),
  `MotorGuardNav`, `diag`/`intent`/`asst` (assistant-core C++). Debug via
  `adb logcat -s MotorGuardVoice MotorGuardNav diag intent asst`.
- Known gaps still to verify on device: privileged radio control end-to-end, Bluetooth A2DP
  now-playing (the `BluetoothSessionMirror` was never written — the Bluetooth media tab shows a
  connection indicator and no track/artwork/controls).

## Switching to a new final app branch

When your friends finish a new final application on a branch (e.g. `final-app`):

```bash
# 1. Edit deploy.conf — the ONLY file that changes:
#      APP_BRANCH=final-app
#      AOSP_ROOT=/path/to/aosp
# 2. Run:
./deploy.sh
# 3. Build:
cd /path/to/aosp && source build/envsetup.sh && lunch aosp_rpi5_car-bp1a-userdebug && m MotorGuard -j6
```

`deploy.sh` fetches the branch and checks out its tip into the `app/` submodule (no commit
needed in the superproject), copies the drop-in, applies the device patch and fetches the
prebuilts. Re-run it any time your friends push to the same branch to pick up their latest.

> **What the branch must satisfy:** the app lives at `MotorGuardApp/app/` (Gradle project as on
> `media-nav-settings-voice`), its manifest has `package="com.motorguard.ivi"`, and any new
> AndroidX/Gradle dependencies are mirrored into `static_libs` in `MotorGuard_Application/Android.bp`
> (nothing checks that for you). `deploy.sh` verifies the manifest path and aborts otherwise.

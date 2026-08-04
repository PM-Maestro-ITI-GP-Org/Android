# Motor Guard — AAOS in-tree build

This branch is the **Soong drop-in**: Motor Guard laid out to be deployed into an
AOSP / Raspberry Pi (KonstaKANG) tree and built with `m MotorGuard` as a platform-signed,
privileged AAOS launcher. There is no Gradle here — `Android.bp` is the only build file.

> **The app source lives on `media-nav-settings-voice`.** That branch is where you edit code
> and iterate with Gradle + the emulator. This branch is the packaged form of the same commit.
> Changes flow one way: dev branch → here. See *Keeping this branch in sync* below.

## Layout

The tree mirrors the AOSP hierarchy so `deploy.sh` can drop it in verbatim:

```
deploy.sh                     one-command installer: copies files + applies patch + fetches prebuilts
vendor/motorguard/MotorGuard/ the drop-in package (Android.bp, app/, aosp/, .gitignore)
  Android.bp                  Soong modules — the whole build (paths are relative to this file)
  app/src/main/               the app: java/ res/ assets/ cpp/ AndroidManifest.xml
  aosp/motorguard.mk          product makefile snippet (PRODUCT_PACKAGES += MotorGuard)
  aosp/privapp_permissions_*  privileged-permission allow-list → /system/etc/permissions
  aosp/res-platform/          overlay flipping use_real_connectivity → true (real radios)
  aosp/prebuilts/fetch.sh     downloads + SHA256-verifies the 17 prebuilt AARs/jars (never committed)
device/brcm/rpi5/aosp_rpi5_car.mk.patch   device integration patch (applied by deploy.sh)
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

2. **Deploy Motor Guard into the tree** — one command, run from this repo:

   ```bash
   ./deploy.sh /path/to/aosp   # copies vendor/, applies the device patch, downloads prebuilts
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
cp -r vendor/motorguard/MotorGuard /path/to/aosp/vendor/motorguard/
git -C /path/to/aosp/device/brcm/rpi5 apply /path/to/this/repo/device/brcm/rpi5/aosp_rpi5_car.mk.patch
/path/to/aosp/vendor/motorguard/MotorGuard/aosp/prebuilts/fetch.sh
```

The device patch adds to `device/brcm/rpi5/aosp_rpi5_car.mk`:

```make
$(call inherit-product, vendor/motorguard/MotorGuard/aosp/motorguard.mk)
PRODUCT_PACKAGES += \
    CarSystemUISystemBarPersistcyImmersive
```

## Voice subsystem (Vega) prerequisites

- **Prebuilts** — the 17 AARs/jars (ONNX Runtime, media3, MapLibre, okhttp/okio, …) are never
  committed; `aosp/prebuilts/fetch.sh` downloads each and SHA256-verifies it (idempotent:
  re-running skips files that already match). Without them soong fails to resolve
  `ai.onnxruntime.*`, `androidx.media3.*` and `org.maplibre.*`.
- **SQLite** — `app/src/main/cpp/sqlite3.c` + `sqlite3.h` (the official amalgamation) are
  committed and compiled straight in, because the platform does not expose libsqlite to apps.
- **Wake-word models** — the `*.onnx` files under `app/src/main/assets/wakeword/` are committed
  and kept uncompressed via `aaptflags: ["-0 .onnx"]`.

## Why it has to be built this way

The app controls real Wi-Fi and Bluetooth radios (`setWifiEnabled`, `addNetwork`,
`BluetoothAdapter.enable`, `removeBond`). Those calls **return `false` for any non-system app** —
they need `NETWORK_SETTINGS` / `OVERRIDE_WIFI_CONFIG` / `BLUETOOTH_PRIVILEGED`, which require all
three of: the app being platform-signed, shipping in `/system/priv-app`, and being named in the
privapp allow-list. `adb install` gives you none of that.

`Conn.init()` reads `bool/use_real_connectivity`. The Gradle build leaves it `false` (mock repos);
`aosp/res-platform` flips it `true` in this build, selecting `RealWifiRepo` / `RealBtRepo`. There
is also a runtime override in Settings ▸ System, added because the Gradle APK cannot carry this
overlay.

> **The manifest and the allow-list must stay in sync.** A privileged permission needs to be BOTH
> requested in `AndroidManifest.xml` and allow-listed in
> `aosp/privapp_permissions_com.motorguard.ivi.xml`. If a permission is allow-listed but not
> requested it silently does nothing; if one is requested but not allow-listed the platform
> **refuses to boot the app at all**. Change one, change the other.

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

## Keeping this branch in sync

This branch is `media-nav-settings-voice` with the Gradle build system removed and `Android.bp`
laid out under `vendor/motorguard/MotorGuard/`. To pull in later app changes:

```bash
git checkout media-nav-settings-voice_forAAOS && git merge media-nav-settings-voice
```

Expect conflicts wherever a file moved (`MotorGuardApp/app/…` → `vendor/motorguard/MotorGuard/app/…`);
resolve by keeping this branch's layout. If `app/build.gradle.kts` changes a dependency on the dev
branch, mirror it into `static_libs` in `Android.bp` — nothing checks that for you.

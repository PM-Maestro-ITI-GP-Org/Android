# Motor Guard — AAOS in-tree build

This is the **Soong drop-in**: Motor Guard laid out to be deployed into an AOSP /
Raspberry Pi (KonstaKANG) tree and built with `m MotorGuard` as a platform-signed,
privileged AAOS launcher. There is no Gradle build here — `Android.bp` is the only
build file (Gradle files live in the app submodule and are ignored).

> **The app source is a submodule.** `MotorGuard_Application/app/` is a git submodule
> checked out to a branch of `PM-Maestro-ITI-GP-Org/Android` (default
> `media-nav-settings-voice`). `deploy.sh` fetches and checks out the tip of whatever
> branch you set in `deploy.conf` — that branch is the source of the final application.
> The blue print (`MotorGuard_Application/Android.bp`) reads the sources it needs from
> `app/MotorGuardApp/app/src/main/...` and ignores everything else (Gradle files, docs…).

## Layout

```
MotorGuard_Application/
  app/                git submodule → same repo, branch per deploy.conf (e.g. media-nav-settings-voice)
                      app source at app/MotorGuardApp/app/src/main/{java,res,assets,cpp,AndroidManifest.xml}
  Android.bp          the blue print — all Soong modules, paths relative to this file
  prebuilts/          fetch.sh + the 17 vendored AARs/jars (fetched, never committed)
  privapp_permissions_com.motorguard.ivi.xml   privileged allow-list → /system/etc/permissions
  res-platform/       overlay flipping use_real_connectivity → true (real radios)
motorguard.mk         product makefile snippet (PRODUCT_PACKAGES += MotorGuard)
README.md
```

`Android.bp` defines the `MotorGuard` app, `libmotorguardvoice` (the native
voice/reasoning core — the Soong equivalent of `app/MotorGuardApp/app/src/main/cpp/CMakeLists.txt`,
since Soong does not run CMake), and the vendored import modules (ONNX Runtime, media3,
MapLibre, okhttp/okio, …). A commented `android_app_import` at the bottom is path (B):
bundle a Gradle-built, platform-re-signed APK instead of compiling from source.

## Build (via the repo's deploy.sh — recommended)

```bash
cd <repo>                     # media-nav-settings-voice_forAAOS branch
./deploy.sh                   # reads deploy.conf: APP_BRANCH + AOSP_ROOT
# or: ./deploy.sh /path/to/aosp my-branch
```

`deploy.sh` does everything: checks out the app submodule to `APP_BRANCH`, copies this
drop-in into `<aosp>/vendor/motorguard/MotorGuard/`, applies the device patch, and
fetches the prebuilts. Then:

```bash
cd <aosp>
source build/envsetup.sh
lunch aosp_rpi5_car-bp1a-userdebug
export GOMEMLIMIT=10GiB GOGC=50
nice -n 10 m MotorGuard -j6                    # app only — fast iteration
nice -n 10 m bootimage systemimage vendorimage -j2   # full image (use -j2: javac OOMs at -j6)
```

> **Use `-j2` for the full image.** `-j6` OOMs: the CarSystemUI javac (dagger,
> `-J-Xmx8192M`) is killed at ~8 GB RSS. On a 16 GB machine also add swap
> (`sudo fallocate -l 16G /swapfile2 && sudo mkswap /swapfile2 && sudo swapon /swapfile2`)
> and keep the `GOMEMLIMIT`/`GOGC` exports (soong_build alone peaks at ~11 GB).

## Manual install (what deploy.sh automates)

```bash
git clone --recurse-submodules -b media-nav-settings-voice_forAAOS git@github.com:PM-Maestro-ITI-GP-Org/Android.git dropin
cp -r dropin/vendor/motorguard/MotorGuard <aosp>/vendor/motorguard/
git -C <aosp>/device/brcm/rpi5 apply dropin/device/brcm/rpi5/aosp_rpi5_car.mk.patch
git -C <aosp>/device/brcm/rpi5 apply dropin/device/brcm/rpi5/BoardConfig.mk.patch
git -C <aosp>/device/brcm/rpi5 apply dropin/device/brcm/rpi5/vendor.prop.patch
<aosp>/vendor/motorguard/MotorGuard/MotorGuard_Application/prebuilts/fetch.sh
```

The device patches touch `device/brcm/rpi5/`:

`aosp_rpi5_car.mk`:

```make
$(call inherit-product, vendor/motorguard/MotorGuard/motorguard.mk)
PRODUCT_PACKAGES += \
    CarSystemUISystemBarPersistcyImmersive
```

`BoardConfig.mk` (display): force HDMI0 hotplug + built-in EDID override — the QDTECH MPI7002
panel does not answer on the DDC/EDID bus of the Pi 5 (`BSC_A no ACK`), so vc4-kms-v3d would
expose no display mode. `edid/1024x768.bin` is compiled into the kernel:

```
BOARD_KERNEL_CMDLINE += vc4.force_hotplug=0x01 drm.edid_firmware=HDMI-A-1:edid/1024x768.bin
```

`vendor.prop` (debug): enables adb over ethernet:

```
service.adb.tcp.port=5555
```

## Voice subsystem (Vega) prerequisites

- **Prebuilts** — the 17 AARs/jars (ONNX Runtime, media3, MapLibre, okhttp/okio, …) are
  never committed; `MotorGuard_Application/prebuilts/fetch.sh` downloads each and SHA256-verifies it, skipping files
  already present (idempotent). `deploy.sh` preserves the fetched files across deploys, so a
  re-deploy only re-fetches what is missing.
  Without them soong fails to resolve `ai.onnxruntime.*`, `androidx.media3.*`, `org.maplibre.*`.
- **SQLite** — `app/MotorGuardApp/app/src/main/cpp/sqlite3.c` + `sqlite3.h` (the official
  amalgamation) are committed in the app submodule and compiled straight in, because the
  platform does not expose libsqlite to apps.
- **Wake-word models** — the `*.onnx` files under `app/MotorGuardApp/app/src/main/assets/wakeword/`
  are kept uncompressed via `aaptflags: ["-0 .onnx"]` (ONNX Runtime mmaps them).

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
> inside the submodule. A branch that moves the app (e.g. renames `MotorGuardApp`) breaks the AOSP
> build until the paths in `MotorGuard_Application/Android.bp` are updated. The manifest on the app
> branch must carry `package="com.motorguard.ivi"` on the root element (the Gradle build does not
> need it, the AOSP build does).

## Status — verified

- **`m MotorGuard` builds clean** on the KonstaKANG android-15.0.0_r32 tree (28 min on a
  16 GB machine with the knobs above). All soong/kotlin/native issues are fixed in the blue print:
  media3/onnxruntime/MapLibre imports, `jni_headers` for `jni.h`, `-fexceptions` for the C++ core,
  the constraintlayout module name, `lifecycle-viewmodel-compose`, and the prebuilt imports.
- **Full image (`bootimage systemimage vendorimage -j2`) builds** and the packaged image was
  verified: `MotorGuard.apk` in `/system/priv-app`, CarLauncher removed, privapp allow-list present.
- The image was flashed to a Pi 5 and booted; log tags: `MotorGuardVoice` (kotlin + native),
  `MotorGuardNav`, `diag`/`intent`/`asst` (assistant-core C++). Debug via
  `adb logcat -s MotorGuardVoice MotorGuardNav diag intent asst`.
- Known gaps still to verify on device: privileged radio control end-to-end, Bluetooth A2DP
  now-playing (the `BluetoothSessionMirror` was never written — the Bluetooth media tab shows a
  connection indicator and no track/artwork/controls).

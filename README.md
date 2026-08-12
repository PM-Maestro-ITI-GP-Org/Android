# Motor Guard — AAOS in-tree build

This branch is the **Soong drop-in**: Motor Guard laid out to be deployed into an
AOSP / Raspberry Pi (KonstaKANG) tree and built with `m MotorGuard` as a platform-signed,
privileged AAOS launcher. There is no Gradle build here — `Android.bp` is the only build file.

This branch is the **diagnostics** drop-in: media, navigation, settings, voice **and** the
diagnostics screen — the 3D vehicle stage, hotspots, health ring, alert list and the
engineering-insights capture — in one image. It is `media-nav-settings-voice_forAAOS` plus
everything the diagnostics work needs from the tree; see [What diagnostics adds to the
build](#what-diagnostics-adds-to-the-build).

> **The app source is a submodule.** `vendor/motorguard/MotorGuard/MotorGuard_Application/app/`
> is a git submodule of `PM-Maestro-ITI-GP-Org/Android`, checked out to a branch of your choice
> (default `media-nav-settings-voice-diagnostics`). That branch holds the **final application**.
> `deploy.sh` fetches and checks out its tip, and the blue print reads the sources from
> `app/MotorGuardApp/app/src/main/...` and `app/MotorGuardApp/core/vehicle-data-*/src/main/...`,
> ignoring the Gradle files and everything else.
> When your friends ship a new final build on a new branch, set `APP_BRANCH` in `deploy.conf`
> and re-run `./deploy.sh` — nothing else to change.

## Layout

The tree mirrors the AOSP hierarchy so `deploy.sh` can drop it in verbatim:

```
deploy.conf                    the ONLY file you edit per release: APP_BRANCH + AOSP_ROOT + CAR_MODEL
deploy.sh                      one command: select app branch → install car model → copy → patch → fetch prebuilts
vendor/motorguard/MotorGuard/  the drop-in package (blue print + glue)
  motorguard.mk                product makefile snippet (PRODUCT_PACKAGES += MotorGuard)
  MotorGuard_Application/
    app/                       git submodule → app source at app/MotorGuardApp/app/src/main/
                               plus the diagnostics libraries at app/MotorGuardApp/core/vehicle-data-*/
    Android.bp                 the blue print — all Soong modules (paths relative to this file)
    prebuilts/fetch.sh         downloads + SHA256-verifies the 22 prebuilt AARs/jars (never
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
vendored import modules for ONNX Runtime, media3, MapLibre, SceneView/Filament, okhttp/okio and
friends. A commented `android_app_import` at the bottom is path (B): bundle a Gradle-built,
platform-re-signed APK instead of compiling from source.

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

2. **Configure and deploy** — set the values in `deploy.conf`, then one command:

   ```bash
   # deploy.conf
   APP_BRANCH=final-app            # branch holding the final application (friends' branch)
   AOSP_ROOT=/path/to/aosp         # your AOSP 15 tree
   CAR_MODEL=porsche_mission_e/porsche_mission_e_diag_v2.glb   # 3D model, path under vehicle3dModel/
   ./deploy.sh                     # selects app branch, installs the car model, copies the drop-in,
                                   # patches device, fetches prebuilts
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
git clone --recurse-submodules -b media-nav-settings-voice-diagnostics_forAAOS git@github.com:PM-Maestro-ITI-GP-Org/Android.git dropin
# the diagnostics 3D model — .gitignored on the app branch, so install it by hand here
APP=dropin/vendor/motorguard/MotorGuard/MotorGuard_Application/app
cp $APP/vehicle3dModel/porsche_mission_e/porsche_mission_e_diag_v2.glb \
   $APP/MotorGuardApp/app/src/main/assets/car_model.glb
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

## What diagnostics adds to the build

Everything below is the difference between this branch and `media-nav-settings-voice_forAAOS`.
The app branch it deploys carries the diagnostics screen; these are the four things the
in-tree build needs that Gradle was doing for it.

**1. Two extra source roots.** Gradle builds the diagnostics domain as the library modules
`:core:vehicle-data-api` (the `VehicleDataSource` / `MotorCaptureSource` contract, severity
model, capture analysis) and `:core:vehicle-data-fake` (the source it currently runs against).
Neither has resources or a manifest, so the blue print compiles both straight into the app
rather than as `android_library` modules:

```
srcs: [
    "app/MotorGuardApp/app/src/main/java/**/*.kt",
    "app/MotorGuardApp/core/vehicle-data-api/src/main/java/**/*.kt",
    "app/MotorGuardApp/core/vehicle-data-fake/src/main/java/**/*.kt",
],
```

`deploy.sh` fails early and says so if the selected `APP_BRANCH` has no `core/` — that is a
pre-diagnostics branch, and the failure otherwise surfaces as unresolved
`com.motorguard.ivi.data.vehicle.*` imports deep inside kotlinc.

**2. SceneView 2.3.0 + Filament 1.56.0.** The 3D car stage. Five more vendored artifacts
(`sceneview`, `filament-android`, `gltfio-android`, `filament-utils-android`,
`kotlin-math-jvm`), each fetched and SHA256-verified by `prebuilts/fetch.sh`, each with an
import module in `Android.bp`. The three Filament AARs carry their own `.so` and are imported
with `extract_jni: true`.

> **The SceneView AAR also ships assets the app loads by name** —
> `environments/neutral/neutral_ibl.ktx` (the stage's indirect lighting) and
> `materials/*.filamat`. Soong merges an AAR's assets into the app package the way Gradle
> does. If the stage ever comes up unlit with an asset-not-found in logcat, that merge is the
> first thing to check; the fallback is to unzip the AAR's `assets/` into the drop-in and add
> it to `asset_dirs`.

> **Do not bump SceneView past 2.3.0** on either branch without a toolchain decision: 2.3.0 is
> the last release built against kotlin-stdlib 2.0.21, and 4.x requires Kotlin 2.3+, which
> moves every fragment owner at once.

**3. The 3D vehicle model.** `app/src/main/assets/car_model.glb` is **`.gitignored` on the app
branch** — 15 MB of binary that would sit in every clone's history — so the app checkout never
contains it, and an image built without this step shows an empty stage. The model library
*is* committed, at `vehicle3dModel/`, and `deploy.sh` installs the one named by `CAR_MODEL`
into the assets directory before copying the tree. That is the in-tree equivalent of
`MotorGuardApp/scripts/select-car-model.sh`, and it writes the same `car_model.source.txt`.
It re-installs when `CAR_MODEL` changes and skips the copy when it has not.

The `.glb` is stored uncompressed (`aaptflags: ["-0 .glb"]`) — it is already-compressed
texture payload, so re-zipping it only costs build time.

> The Porsche Mission E model is **CC-BY-4.0**: attribution is a licence condition, not a
> courtesy. The app credits the author under the car stage
> (`Car3dTuning.MODEL_CREDIT`). Swap the model and you update that string,
> `vehicle3dModel/MODEL_LICENSE.md` and `CAR_MODEL` together.

**4. Model library pruning.** `vehicle3dModel/` is 121 MB and nothing in the tree reads it
after the model is installed, so `deploy.sh` drops it from the *installed* copy (never from
this repo's checkout) rather than leaving another 121 MB in the AOSP tree per deploy.

Not needed, and deliberately absent: no new permissions (the diagnostics screen adds none, so
the manifest and `privapp_permissions_com.motorguard.ivi.xml` are untouched), no new native
code, no device-patch changes.

### Where the SOME/IP motor service will plug in

The diagnostics screen currently renders `FakeVehicleDataSource` / `FakeMotorCaptureSource`.
The real source is the SOME/IP bridge to the diagnostics Pi, specified in the app branch's
`docs/09-motor-service-aaos.md` (Android side) and `docs/10-motor-service-someip.md` (Pi
side). Nothing in this drop-in blocks it; what it will cost here when it lands:

- **Sources** — the bridge replaces `vehicle-data-fake` in `srcs`. If it becomes its own
  Gradle module, add its source root the same way; `vehicle-data-api` stays either way,
  because the contract is what the UI is written against.
- **Native transport** — if vsomeip/CommonAPI is used rather than a pure-Kotlin socket client,
  it needs a `cc_library_shared` beside `libmotorguardvoice`, and a decision this drop-in has
  not made: vsomeip bundled into the APK, or built into the image as a platform library.
  Bundled is simpler; in-image is what you want if anything else on the head unit ever speaks
  SOME/IP.
- **Manifest and allow-list** — a socket client needs `INTERNET` (already requested). A native
  daemon needs SELinux policy, which is a device-patch change and not app work. Whatever is
  added must be in **both** the manifest and the privapp allow-list — see the warning below.
- **Nothing in the UI changes.** `ui/diagnostics/VehicleData.kt` is the only file permitted to
  name a concrete source; the acceptance criteria are §9 of `09-motor-service-aaos.md`.

## Voice subsystem (Vega) prerequisites

- **Prebuilts** — the 22 AARs/jars (ONNX Runtime, media3, MapLibre, SceneView/Filament,
  okhttp/okio, …) are never
  committed; `MotorGuard_Application/prebuilts/fetch.sh` downloads each and SHA256-verifies it
  (idempotent: re-running skips files that already match). Without them soong fails to resolve
  `ai.onnxruntime.*`, `androidx.media3.*`, `org.maplibre.*` and `io.github.sceneview.*`.
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

## Status

### Diagnostics additions — NOT yet built on a tree

Everything in [What diagnostics adds to the build](#what-diagnostics-adds-to-the-build) is
written and self-consistent, and nothing here has been through `m MotorGuard` yet. What was
checked, and what was not:

- **Checked** — all five new prebuilt URLs resolve and their SHA256s match the artifacts Gradle
  resolved for the app branch (so the AAR the image links is byte-identical to the one the
  Gradle build was developed against); `build-fixes.patch` still applies cleanly to the
  diagnostics branch; `deploy.sh` step 3 was exercised against a real checkout of that branch,
  including the re-install, unchanged, missing-model and pre-diagnostics-branch paths.
- **Not checked** — nothing has been compiled. Expect the usual first-build Soong friction and
  fix it here rather than on the app branch. The two most likely places: whether the tree's
  `androidx.compose.foundation_foundation` / `androidx.fragment_fragment-ktx` module names
  match what `motorguard-sceneview` lists, and whether the SceneView AAR's assets land in the
  APK (see the note above — that one shows up at runtime as an unlit stage, not as a build
  error, so check `adb shell ls /system/priv-app/MotorGuard/` output or unzip the APK).
- **Not checked, and not this drop-in's to fix** — the app branch's tip is missing
  `stage_floor_day.png` / `stage_floor_night.png`, which `Car3dTuning.BACKDROP_*_ASSET` loads
  by name. They exist in the app author's working tree but are not committed. `deploy.sh`
  warns; the stage renders without its floor until they are pushed.

### Everything else — verified (unchanged from `media-nav-settings-voice_forAAOS`)

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
> `media-nav-settings-voice-diagnostics`), the diagnostics libraries at `MotorGuardApp/core/
> vehicle-data-{api,fake}/`, the model library at `vehicle3dModel/`, its manifest has
> `package="com.motorguard.ivi"`, and any new AndroidX/Gradle dependencies are mirrored into
> `static_libs` in `MotorGuard_Application/Android.bp` (nothing checks that for you).
> `deploy.sh` verifies the manifest path and the `core/` modules and aborts otherwise.

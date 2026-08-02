# Motor Guard — AAOS in-tree build

This branch is the **Soong drop-in**: Motor Guard laid out to be copied straight into an
AOSP / Raspberry Pi (KonstaKANG) tree and built with `m MotorGuard` as a platform-signed,
privileged AAOS launcher. There is no Gradle here — `Android.bp` is the only build file.

> **The app source lives on `media-nav-settings-voice`.** That branch is where you edit code
> and iterate with Gradle + the emulator. This branch is the packaged form of the same commit.
> Changes flow one way: dev branch → here. See *Keeping this branch in sync* below.

## Layout

```
Android.bp                    Soong modules — the whole build (paths are relative to this file)
app/src/main/                 the app: java/ res/ assets/ cpp/ AndroidManifest.xml
aosp/motorguard.mk            product makefile snippet (PRODUCT_PACKAGES += MotorGuard)
aosp/privapp_permissions_*    privileged-permission allow-list → /system/etc/permissions
aosp/res-platform/            overlay flipping use_real_connectivity → true (real radios)
aosp/prebuilts/               you must drop the ONNX Runtime AAR here — see that README
```

`Android.bp` defines three modules: the `MotorGuard` app, `libmotorguardvoice` (the native
voice/reasoning core — the Soong equivalent of `cpp/CMakeLists.txt`, since Soong does not run
CMake), and `motorguard-onnxruntime` (the vendored ONNX Runtime AAR). A commented
`android_app_import` at the bottom is path (B): bundle a Gradle-built, platform-re-signed APK
instead of compiling from source. Use it if your tree's AndroidX/Compose prebuilts fight you.

## Build

1. **Copy this tree in** as `vendor/motorguard/MotorGuard/`:

   ```bash
   git clone -b media-nav-settings-voice_forAAOS git@github.com:PM-Maestro-ITI-GP-Org/Android.git vendor/motorguard/MotorGuard
   ```

2. **Add the ONNX Runtime AAR.** Download
   `com.microsoft.onnxruntime:onnxruntime-android:1.19.2` from Maven Central and place it at
   `aosp/prebuilts/onnxruntime-android-1.19.2.aar`. It is a ~15 MB binary and is deliberately
   not committed. Without it, `m MotorGuard` cannot resolve `ai.onnxruntime.*`.

3. **Inherit the product makefile** from your device product `.mk` (e.g.
   `device/<vendor>/rpi5/aosp_rpi5_car.mk`):

   ```make
   $(call inherit-product, vendor/motorguard/MotorGuard/aosp/motorguard.mk)
   ```

4. **Build:**

   ```bash
   source build/envsetup.sh && lunch <your_rpi5_car target> && m MotorGuard
   ```

   Soong compiles the C++ core with the tree's clang and links the AAR — no NDK or CMake step.

5. **Flash and boot.** It is already the launcher: `overrides: ["CarLauncher"]` drops the stock
   Car launcher, so Motor Guard is HOME from first boot with no runtime step. Optionally hide the
   system bars with the immersive overlay noted in `aosp/motorguard.mk`.

### Voice subsystem (Vega) prerequisites

The app bundles a native voice/reasoning core and an ONNX wake-word engine, both built from
source here:

- **SQLite** — `app/src/main/cpp/sqlite3.c` + `sqlite3.h` (the official amalgamation) are
  committed and compiled straight in, because the NDK/platform does not expose libsqlite to
  apps. If they are ever missing, see `app/src/main/cpp/README-native.md`.
- **Wake-word models** — the `*.onnx` files under `app/src/main/assets/wakeword/` are committed
  and kept uncompressed via `aaptflags: ["-0 .onnx"]`.
- **ONNX Runtime** — step 2 above.

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

## Status — read before you trust it

Nothing in this tree has been compiled by Soong or run on real hardware. It was authored on a
machine with no AOSP tree and no Pi, so treat the notes below as predictions, not results:

- **The `m MotorGuard` build itself is unverified.** Expect module-name and flag tweaks on the
  first attempt, particularly the Compose compiler plugin
  (`androidx.compose.compiler_compiler-hosted-plugin`) and the AndroidX `static_libs` names, which
  vary by tree and platform version. Check `prebuilts/sdk/current/androidx` for the exact names.
  If Soong keeps fighting Compose, switch to path (B) — it is the reliable route for a Compose app.
- **`libmotorguardvoice`** is built platform-variant (no `sdk_version`). If your tree complains
  about an STL/NDK mismatch against the app, add `sdk_version: "current"` + `stl: "c++_static"` to
  the `cc_library_shared`. The ONNX Runtime AAR ships arm64 + x86 `.so`; the Pi uses arm64.
- **The wake-word models must stay uncompressed** (`aaptflags: ["-0 .onnx"]`) — ONNX Runtime mmaps
  them. If the detector fails to load them, check a custom aapt step did not re-compress them.
- **Privileged behaviour is unproven on hardware**: direct radio control actually succeeding,
  HOME-from-boot, and the allow-list being honoured all need a flashed `userdebug` image. Everything
  verified so far was on the emulator or an ordinary phone, where those paths necessarily fall back
  to the system settings panels.
- **`RealBtRepo` connect/unpair** are partly stubbed and marked `TODO(on-device)`; `RealWifiRepo.connect`
  uses the legacy `addNetwork` flow.
- **Real Bluetooth (phone pairing / A2DP)** only works on real hardware — not on Cuttlefish
  (virtual Rootcanal BT) or the emulator.
- **Known gap:** Bluetooth *audio* now-playing. The platform does A2DP-sink audio, not the app, but
  `BluetoothSessionMirror` — referenced in `BluetoothMediaSource`'s KDoc — was never written, so the
  Bluetooth media tab shows a connection indicator and no track, artwork, or transport controls.
  It needs `MediaSessionManager` mirroring of the AVRCP session.

## Keeping this branch in sync

This branch is `media-nav-settings-voice` with the Gradle build system removed and `Android.bp`
hoisted to the root. To pull in later app changes:

```bash
git checkout media-nav-settings-voice_forAAOS && git merge media-nav-settings-voice
```

Expect conflicts wherever a file moved (`MotorGuardApp/app/…` → `app/…`); resolve by keeping this
branch's layout. If `app/build.gradle.kts` changes a dependency on the dev branch, mirror it into
`static_libs` in `Android.bp` — nothing checks that for you.

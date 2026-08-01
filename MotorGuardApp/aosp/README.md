# Building Motor Guard into the AOSP / Pi image

These files build `MotorGuardApp` as a **platform-signed, privileged AAOS launcher** in
your Trout / Raspberry Pi (KonstaKANG) tree, so the real Wi-Fi / Bluetooth APIs work.

## Files
| File | Purpose |
|------|---------|
| `Android.bp` | Soong modules. Path (A) builds from source: the `MotorGuard` app, `libmotorguardvoice` (native voice/reasoning core), and the `motorguard-onnxruntime` AAR import. Path (B, commented) imports a Gradle-built APK re-signed with the platform key. |
| `privapp_permissions_com.motorguard.ivi.xml` | Allow-list for the privileged Wi-Fi/BT permissions. Installed to `/system/etc/permissions`. |
| `res-platform/values/bools.xml` | Overlay flipping `use_real_connectivity` → `true` (real radios instead of mock). |
| `motorguard.mk` | Product makefile snippet: `PRODUCT_PACKAGES += MotorGuard`, HOME + system-bar notes. |
| `prebuilts/onnxruntime-android-1.19.2.aar` | **You must add this** (not committed). The ONNX Runtime AAR the wake-word engine links against — see step 2. |

## Voice subsystem (Vega) prerequisites
The integrated app bundles a native voice/reasoning core and an ONNX wake-word engine.
Path (A) builds both from source in the tree, so before building:
- **SQLite**: `app/src/main/cpp/sqlite3.c` + `sqlite3.h` (the amalgamation) must be present.
  They are committed on this branch; if absent, see `app/src/main/cpp/README-native.md`.
- **ONNX Runtime**: download `onnxruntime-android-1.19.2.aar` from Maven Central
  (`com.microsoft.onnxruntime:onnxruntime-android:1.19.2`) and place it at
  `aosp/prebuilts/onnxruntime-android-1.19.2.aar`. The `android_library_import` in
  `Android.bp` links it and extracts its bundled `.so`.
- **Wake-word models**: the `*.onnx` files under `app/src/main/assets/wakeword/` are
  committed and kept uncompressed via `aaptflags: ["-0 .onnx"]`.

## Steps
1. Copy the whole `MotorGuardApp/` dir into your device/vendor tree, e.g.
   `vendor/motorguard/MotorGuard/`.
2. Drop the ONNX Runtime AAR at `aosp/prebuilts/onnxruntime-android-1.19.2.aar`
   (see the Voice subsystem prerequisites above). Without it, `m MotorGuard` fails
   to resolve `ai.onnxruntime.*`.
3. In your device product `.mk`, add:
   `$(call inherit-product, vendor/motorguard/MotorGuard/aosp/motorguard.mk)`
4. `source build/envsetup.sh && lunch <your_rpi5_car target>` then `m MotorGuard`
   (or a full `m` for the image). Soong builds `libmotorguardvoice` from the C++
   sources and links the onnxruntime AAR — no NDK/CMake step, the tree's clang does it.
5. Flash / boot. It's already the launcher — `overrides: ["CarLauncher"]` in `Android.bp`
   drops the stock launcher and makes Motor Guard HOME from first boot (no runtime step).
   Optionally hide the system bars via the immersive overlay in the image.

> The `scripts/set-as-home.sh` / `hide-system-bars.sh` helpers are only for the **emulator**,
> where you can't rebuild the image. On the Pi build, `overrides` + the product `.mk`
> handle it permanently.

## The app switches to real APIs automatically
`Conn.init()` reads `bool/use_real_connectivity`. Emulator/Gradle build = `false` (mock);
this platform overlay = `true` → `RealWifiRepo` / `RealBtRepo`.

## Caveats to finish on hardware
- The Compose compiler plugin / AndroidX module names in `Android.bp` may need tweaking to
  match your tree's prebuilts. If it fights you, use path (B) (prebuilt APK) — reliable.
- `RealWifiRepo.connect` uses the legacy addNetwork flow; `RealBtRepo` connect/unpair are
  stubbed (need profile proxies / `removeBond()` reflection). Marked `TODO(on-device)`.
- Real Bluetooth (phone pairing / A2DP) only works on **real hardware** — not Cuttlefish
  (virtual Rootcanal BT) or the emulator.
- Voice (Vega): `libmotorguardvoice` is built platform-variant (no `sdk_version`). If your
  tree complains about STL/NDK mismatch against the app, add `sdk_version: "current"` +
  `stl: "c++_static"` to the `cc_library_shared`. The onnxruntime AAR ships arm64 + x86
  `.so`; the arm64 ones are what the Pi uses. The `MotorGuard` module here was not
  compiled locally (no NDK/CMake on the authoring machine) — expect small module-name/flag
  tweaks on first `m MotorGuard`, and fall back to path (B) if Soong Compose fights you.
- The wake-word `.onnx` models must stay uncompressed (`aaptflags: ["-0 .onnx"]`); if the
  detector fails to mmap them, confirm they weren't re-compressed by a custom aapt step.

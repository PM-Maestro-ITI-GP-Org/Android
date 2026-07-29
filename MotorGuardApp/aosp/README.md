# Building Motor Guard into the AOSP / Pi image

These files build `MotorGuardApp` as a **platform-signed, privileged AAOS launcher** in
your Trout / Raspberry Pi (KonstaKANG) tree, so the real Wi-Fi / Bluetooth APIs work.

## Files
| File | Purpose |
|------|---------|
| `Android.bp` | Soong module. Path (A) builds from source; path (B, commented) imports a Gradle-built APK re-signed with the platform key. |
| `privapp_permissions_com.motorguard.ivi.xml` | Allow-list for the privileged Wi-Fi/BT permissions. Installed to `/system/etc/permissions`. |
| `res-platform/values/bools.xml` | Overlay flipping `use_real_connectivity` → `true` (real radios instead of mock). |
| `motorguard.mk` | Product makefile snippet: `PRODUCT_PACKAGES += MotorGuard`, HOME + system-bar notes. |

## Steps
1. Copy the whole `MotorGuardApp/` dir into your device/vendor tree, e.g.
   `vendor/motorguard/MotorGuard/`.
2. In your device product `.mk`, add:
   `$(call inherit-product, vendor/motorguard/MotorGuard/aosp/motorguard.mk)`
3. `source build/envsetup.sh && lunch <your_rpi5_car target>` then `m MotorGuard`
   (or a full `m` for the image).
4. Flash / boot. It's already the launcher — `overrides: ["CarLauncher"]` in `Android.bp`
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

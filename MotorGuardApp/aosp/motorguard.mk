# Motor Guard product bits. Include from your device product makefile, e.g. in
# device/<vendor>/rpi5/aosp_rpi5_car.mk (KonstaKANG):
#
#     $(call inherit-product, vendor/motorguard/MotorGuard/aosp/motorguard.mk)
#
# (adjust the path to wherever you drop this tree).

# Build + install the app (module name from Android.bp).
PRODUCT_PACKAGES += MotorGuard

# --- Make Motor Guard the HOME/launcher ---
# Handled at BUILD time by `overrides: ["CarLauncher"]` in Android.bp: the stock launcher
# isn't installed and Motor Guard becomes the default HOME from first boot. Nothing to run
# after booting. (The runtime scripts/set-as-home.sh is only for the emulator, where you
# can't rebuild the image.)

# --- Hide the Automotive system bars (optional, for the fullscreen kiosk look) ---
# Enable the immersive CarSystemUI overlay in the image (or via the running-device
# script MotorGuardApp/scripts/hide-system-bars.sh):
#     PRODUCT_PACKAGES += com.android.car.systemui.systembar.persistency.immersive

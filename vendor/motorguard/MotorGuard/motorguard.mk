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
# after booting. (The emulator-only set-as-home.sh helper lives on the Gradle branch,
# media-nav-settings-voice, under MotorGuardApp/scripts/ — it is not part of this tree,
# because on an image build you rebuild instead of poking a running device.)

# --- Hide the Automotive system bars (optional, for the fullscreen kiosk look) ---
# Enable the immersive CarSystemUI overlay in the image. The module name is the Soong
# module (packages/apps/Car/SystemUI/samples/SystemBarPersistencyImmersive/Android.bp),
# NOT the overlay package id used at runtime by `cmd overlay enable`.
#     PRODUCT_PACKAGES += CarSystemUISystemBarPersistcyImmersive

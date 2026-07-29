# Motor Guard product bits. Include from your device product makefile, e.g. in
# device/<vendor>/rpi5/aosp_rpi5_car.mk (KonstaKANG):
#
#     $(call inherit-product, vendor/motorguard/MotorGuard/aosp/motorguard.mk)
#
# (adjust the path to wherever you drop this tree).

# Build + install the app (module name from Android.bp).
PRODUCT_PACKAGES += MotorGuard

# --- Make Motor Guard the HOME/launcher ---
# Option 1 (simplest for a dev image): disable the stock Car launcher so Motor Guard is
# the only HOME candidate. Add a runtime overlay (RRO) or:
#     PRODUCT_PACKAGES += <your-rro-that-disables-carlauncher>
#
# Option 2: point the framework's default home at Motor Guard via an overlay that sets
#     <string name="config_defaultHomeActivity">com.motorguard.ivi/.MainActivity</string>
# in a frameworks/base RRO. On a running device you can also do:
#     adb shell cmd package set-home-activity --user 10 com.motorguard.ivi/.MainActivity
# (see MotorGuardApp/scripts/set-as-home.sh)

# --- Hide the Automotive system bars (optional, for the fullscreen kiosk look) ---
# Enable the immersive CarSystemUI overlay in the image (or via the running-device
# script MotorGuardApp/scripts/hide-system-bars.sh):
#     PRODUCT_PACKAGES += com.android.car.systemui.systembar.persistency.immersive

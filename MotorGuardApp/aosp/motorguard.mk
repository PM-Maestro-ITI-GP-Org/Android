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

# --- Turn off the Bluetooth MAP client (REQUIRED on the Pi) ---
# Without this the Bluetooth stack crash-loops as soon as a phone connects, taking the adapter
# down with it and disconnecting the phone, over and over:
#
#   java.lang.UnsupportedOperationException: addSubInfo is unsupported
#       without android.hardware.telephony.subscription
#     at com.android.bluetooth.mapclient.MapClientContent.<init>
#     at com.android.bluetooth.mapclient.MceStateMachine$Connected.enter
#
# MAP is the message-access profile — it syncs the phone's SMS — and MapClientContent registers
# a telephony subscription to hold those messages. The Pi has no telephony hardware, so
# `pm list features | grep telephony.subscription` is empty and the call throws the moment the
# profile reaches its Connected state. AAOS ships the profile enabled because a real head unit
# has a modem; this board does not.
#
# Nothing here needs MAP: media playback is A2DP_SINK + AVRCP, which are unaffected. Drop this
# override if you ever build for hardware that does have a subscription-capable modem.
PRODUCT_SYSTEM_PROPERTIES += \
    bluetooth.profile.map.client.enabled=false

# --- Hide the Automotive system bars (optional, for the fullscreen kiosk look) ---
# Enable the immersive CarSystemUI overlay in the image (or via the running-device
# script MotorGuardApp/scripts/hide-system-bars.sh):
#     PRODUCT_PACKAGES += com.android.car.systemui.systembar.persistency.immersive

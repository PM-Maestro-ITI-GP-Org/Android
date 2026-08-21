# Motor Guard product bits. Include from your device product makefile, e.g. in
# device/<vendor>/rpi5/aosp_rpi5_car.mk (KonstaKANG):
#
#     $(call inherit-product, vendor/motorguard/MotorGuard/motorguard.mk)
#
# (adjust the path to wherever you drop this tree).

# Build + install the app (module name from Android.bp).
PRODUCT_PACKAGES += MotorGuard

# --- Voice STT/TTS models, shipped in the image ---
# whisper.bin / piper.onnx / piper.json / espeak-ng-data (~105 MB) are deliberately
# NOT inside the APK (see WhisperStt.kt / PiperTts.kt KDoc), and adb-pushing them to
# /data only works until the next reflash — a fresh image boots with no models and
# the assistant answers "speech recognition failed". ModelPaths.kt resolves them
# first from /data, then from /system_ext/etc/motorguard and /system/etc/motorguard,
# so shipping them here makes a flash work out of the box. The app branch tracks them
# at app/models/; models/install-to-board.sh remains for overriding a single board's
# copy via /data.
MOTORGUARD_MODELS := vendor/motorguard/MotorGuard/MotorGuard_Application/app/models
PRODUCT_COPY_FILES += \
    $(foreach f,$(shell find $(MOTORGUARD_MODELS) -type f ! -name '*.sh' 2>/dev/null),$(f):system/etc/motorguard/$(patsubst $(MOTORGUARD_MODELS)/%,%,$(f)))

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

# --- Turn off the Bluetooth MAP client (REQUIRED on the Pi) ---
# Without this the Bluetooth stack crash-loops as soon as a phone connects, taking the adapter
# down with it and disconnecting the phone, over and over:
#
#   java.lang.UnsupportedOperationException: addSubInfo is unsupported
#       without android.hardware.telephony.subscription
#     at com.android.bluetooth.mapclient.MapClientContent.<init>
#
# MAP is the message-access profile. MapClientContent registers a telephony subscription to
# hold the phone's SMS, and this board has no telephony hardware, so the call throws the moment
# the profile reaches Connected. Nothing here needs MAP: media playback is A2DP_SINK + AVRCP and
# calls are HFP, none of which are affected. Drop this override on hardware that does have a
# subscription-capable modem.
PRODUCT_SYSTEM_PROPERTIES += \
    bluetooth.profile.map.client.enabled=false

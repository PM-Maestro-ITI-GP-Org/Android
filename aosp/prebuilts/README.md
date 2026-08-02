# Vendored prebuilts

Drop `onnxruntime-android-1.19.2.aar` here (not committed — ~15 MB binary).

Get it from Maven Central — `com.microsoft.onnxruntime:onnxruntime-android:1.19.2`,
the same version the Gradle build pins on the `media-nav-settings-voice` branch:

    https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.19.2/onnxruntime-android-1.19.2.aar

The `android_library_import { name: "motorguard-onnxruntime" }` module in
`../../Android.bp` links it and extracts its bundled native `.so`. Without this file,
`m MotorGuard` fails to resolve `ai.onnxruntime.*`.

Keep the two versions in step: if the Gradle branch bumps ONNX Runtime, bump the
filename here and in `Android.bp` too, or the in-tree build silently links an old
runtime against newer wake-word models.

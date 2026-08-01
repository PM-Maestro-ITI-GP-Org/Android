# Vendored prebuilts

Drop `onnxruntime-android-1.19.2.aar` here (not committed — ~15 MB binary).

Get it from Maven Central, matching the version pinned in `app/build.gradle.kts`
(`com.microsoft.onnxruntime:onnxruntime-android:1.19.2`):

    https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.19.2/onnxruntime-android-1.19.2.aar

The `android_library_import { name: "motorguard-onnxruntime" }` module in
`../Android.bp` links it and extracts its bundled native `.so`. Without this file,
`m MotorGuard` fails to resolve `ai.onnxruntime.*`.

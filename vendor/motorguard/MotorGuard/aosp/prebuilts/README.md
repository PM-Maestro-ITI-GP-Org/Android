# Vendored prebuilts

All files here are fetched, never committed (~20 MB total). See `.gitignore`
(`aosp/prebuilts/*.aar`). None of these artifacts exist in the AOSP prebuilts
(`prebuilts/sdk/current/androidx/m2repository` has no media3, no onnxruntime, and
`external/kotlinx.coroutines` has no guava variant), so they are imported with
`android_library_import` / `java_import` modules in `../../Android.bp`.

## ONNX Runtime — `onnxruntime-android-1.19.2.aar` (~15 MB)

Maven Central — `com.microsoft.onnxruntime:onnxruntime-android:1.19.2`, the same
version the Gradle build pins on the `media-nav-settings-voice` branch:

    https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.19.2/onnxruntime-android-1.19.2.aar

The `android_library_import { name: "motorguard-onnxruntime" }` module links it and
extracts its bundled native `.so`. Without this file, `m MotorGuard` fails to resolve
`ai.onnxruntime.*`.

## media3 (ExoPlayer + session) — `media3-*-1.4.1.aar` (8 AARs)

Google Maven — `androidx.media3:*:1.4.1`, the same version the Gradle branch pins.
AOSP's prebuilts have no media3 at all, so the full chain is vendored:

    https://dl.google.com/android/maven2/androidx/media3/media3-common/1.4.1/media3-common-1.4.1.aar
    https://dl.google.com/android/maven2/androidx/media3/media3-database/1.4.1/media3-database-1.4.1.aar
    https://dl.google.com/android/maven2/androidx/media3/media3-datasource/1.4.1/media3-datasource-1.4.1.aar
    https://dl.google.com/android/maven2/androidx/media3/media3-decoder/1.4.1/media3-decoder-1.4.1.aar
    https://dl.google.com/android/maven2/androidx/media3/media3-extractor/1.4.1/media3-extractor-1.4.1.aar
    https://dl.google.com/android/maven2/androidx/media3/media3-container/1.4.1/media3-container-1.4.1.aar
    https://dl.google.com/android/maven2/androidx/media3/media3-exoplayer/1.4.1/media3-exoplayer-1.4.1.aar
    https://dl.google.com/android/maven2/androidx/media3/media3-session/1.4.1/media3-session-1.4.1.aar

(NOT Maven Central — the earlier `repo1.maven.org` URL 404s for androidx artifacts.)
The `androidx.media3_*` `android_library_import` modules in `Android.bp` mirror the
artifacts' real dependency chain. `androidx.media:media` 1.7.0-alpha02 and
`androidx.concurrent:concurrent-futures` already exist in the tree's prebuilts.

## kotlinx-coroutines-guava — `kotlinx-coroutines-guava-1.8.1.jar`

Maven Central — `org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.8.1` (JVM-only
artifact, no AAR), bridging the `ListenableFuture`s the media3 session returns to
coroutines:

    https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-guava/1.8.1/kotlinx-coroutines-guava-1.8.1.jar

Imported via `java_import { name: "kotlinx-coroutines-guava" }`; pulls the tree's
`guava` module onto the classpath.

## MapLibre Native (nav map) — `android-sdk-11.8.0.aar` + 4 helpers

Maven Central — `org.maplibre.gl:*:11.8.0`, the version the Gradle branch pins in
`app/build.gradle.kts` (`implementation("org.maplibre.gl:android-sdk:11.8.0")`):

    https://repo1.maven.org/maven2/org/maplibre/gl/android-sdk/11.8.0/android-sdk-11.8.0.aar
    https://repo1.maven.org/maven2/org/maplibre/gl/android-sdk-geojson/6.0.1/android-sdk-geojson-6.0.1.jar   (JAR, not AAR)
    https://repo1.maven.org/maven2/org/maplibre/gl/android-sdk-turf/6.0.1/android-sdk-turf-6.0.1.jar         (JAR, not AAR)
    https://repo1.maven.org/maven2/org/maplibre/gl/maplibre-android-gestures/0.0.3/maplibre-android-gestures-0.0.3.aar
    https://repo1.maven.org/maven2/com/jakewharton/timber/timber/5.0.1/timber-5.0.1.aar                      (AAR, not JAR)
    https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar
    https://repo1.maven.org/maven2/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar

`motorguard-maplibre` (`android_library_import`, `extract_jni: true` for
`jni/arm64-v8a/libmaplibre.so`) wires them together and reuses tree modules for
the rest of the POM's transitive set: `gson`,
`androidx.annotation_annotation`, `androidx.fragment_fragment`,
`androidx.interpolator_interpolator`, `androidx.core_core-ktx`. Note the tree's
own `okhttp` module is ART-internal (restricted visibility, `sdk_version: none`)
and cannot be linked into apps, so okhttp 4.12.0 + okio 3.6.0 are vendored too
(`motorguard-okhttp` / `motorguard-okio`).

## Keeping versions in step

Bump the groups together when the Gradle branch (`media-nav-settings-voice`)
bumps them: ONNX Runtime, media3, coroutines, MapLibre — filename here, module
names in `Android.bp`, or the in-tree build silently links an old runtime
against newer wake-word models.

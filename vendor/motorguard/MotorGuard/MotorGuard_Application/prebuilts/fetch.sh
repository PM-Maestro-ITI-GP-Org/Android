#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOTAL=0

while read -r HASH NAME URL; do
  [ -z "${HASH:-}" ] && continue
  TOTAL=$((TOTAL + 1))
  if [ -f "$DIR/$NAME" ] && echo "$HASH  $DIR/$NAME" | sha256sum -c - >/dev/null 2>&1; then
    printf 'ok      %s (already present)\n' "$NAME"
    continue
  fi
  printf 'fetch   %s\n' "$NAME"
  curl -fsSL --retry 3 -o "$DIR/$NAME.tmp" "$URL"
  mv "$DIR/$NAME.tmp" "$DIR/$NAME"
  if ! echo "$HASH  $DIR/$NAME" | sha256sum -c - >/dev/null 2>&1; then
    rm -f "$DIR/$NAME"
    echo "ERROR: checksum mismatch for $NAME (expected $HASH)" >&2
    exit 1
  fi
done <<'EOF'
84737dd1fdb715f0ee695c0a9864f6949b66ecb4c46ca733bc9211bf0c2a7b5f  onnxruntime-android-1.19.2.aar  https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.19.2/onnxruntime-android-1.19.2.aar
973e5e7b0ecf8e6c8cd825cab35145b84c56020af7c3ec52815e595ef71baa16  media3-common-1.4.1.aar         https://dl.google.com/android/maven2/androidx/media3/media3-common/1.4.1/media3-common-1.4.1.aar
2170ae6448cd499fc570dafa81e5687ad813995b7c2b7ff6801acaaa9a88bb4a  media3-database-1.4.1.aar       https://dl.google.com/android/maven2/androidx/media3/media3-database/1.4.1/media3-database-1.4.1.aar
1c0bb41ee88a6bfaf24720bbfe2d29a033abad21c30e0921efbdce6c1fa28842  media3-datasource-1.4.1.aar     https://dl.google.com/android/maven2/androidx/media3/media3-datasource/1.4.1/media3-datasource-1.4.1.aar
f7d97c5a39dca3c3c21bc8a70145d931d8633ed0dae89198c370e7b7e3c3f183  media3-decoder-1.4.1.aar        https://dl.google.com/android/maven2/androidx/media3/media3-decoder/1.4.1/media3-decoder-1.4.1.aar
1f51e4633e1e6137f0a4b80552afc399e269208b700352f96543d4513be6bf28  media3-extractor-1.4.1.aar      https://dl.google.com/android/maven2/androidx/media3/media3-extractor/1.4.1/media3-extractor-1.4.1.aar
65b6d22c96dfb5ce76754b08f5b004ee0dbdbe60793c5eab9f95c1f54f95f4b0  media3-container-1.4.1.aar      https://dl.google.com/android/maven2/androidx/media3/media3-container/1.4.1/media3-container-1.4.1.aar
66cd0f99209191660823d32c8fc329e383a8a7ff33e0d676632387361abcba70  media3-exoplayer-1.4.1.aar      https://dl.google.com/android/maven2/androidx/media3/media3-exoplayer/1.4.1/media3-exoplayer-1.4.1.aar
43546b6b2ef0c2816f5d3af181c2179a5c1bddb9df85b7b84b3428cd37c887de  media3-exoplayer-hls-1.4.1.aar  https://dl.google.com/android/maven2/androidx/media3/media3-exoplayer-hls/1.4.1/media3-exoplayer-hls-1.4.1.aar
b619b200405e237136e7daaaec79eb694348f0562390c8b8b545a4294e3641d4  media3-session-1.4.1.aar        https://dl.google.com/android/maven2/androidx/media3/media3-session/1.4.1/media3-session-1.4.1.aar
1d7635d0f0f423f18d5dd827bf1e76a262154ba01843bb7ef8ba94bf5998b26a  kotlinx-coroutines-guava-1.8.1.jar https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-guava/1.8.1/kotlinx-coroutines-guava-1.8.1.jar
3c9c4740d97c9cb454d3b3a1c09c7b3d228fb171cd42140af20e1ba0aab63082  android-sdk-11.8.0.aar          https://repo1.maven.org/maven2/org/maplibre/gl/android-sdk/11.8.0/android-sdk-11.8.0.aar
0c4e4258ce258da9740c17c76f3ae90f89fcfa5822a797f5d466b35410571363  android-sdk-geojson-6.0.1.jar   https://repo1.maven.org/maven2/org/maplibre/gl/android-sdk-geojson/6.0.1/android-sdk-geojson-6.0.1.jar
c0fd543d17fbd3b7c2e946515caf2d53b602f0eac1438ad4dca731d9a508af79  android-sdk-turf-6.0.1.jar      https://repo1.maven.org/maven2/org/maplibre/gl/android-sdk-turf/6.0.1/android-sdk-turf-6.0.1.jar
f794d3fa3a4f2e890f72cf25b8504e47f40a4efbc0ac7ad0401aa137b778b640  maplibre-android-gestures-0.0.3.aar https://repo1.maven.org/maven2/org/maplibre/gl/maplibre-android-gestures/0.0.3/maplibre-android-gestures-0.0.3.aar
c6edddfcc8eff42a1604c8577fcfa4b4ffd9f252122c52ea36cfe7967f512f71  timber-5.0.1.aar                https://repo1.maven.org/maven2/com/jakewharton/timber/timber/5.0.1/timber-5.0.1.aar
b1050081b14bb7a3a7e55a4d3ef01b5dcfabc453b4573a4fc019767191d5f4e0  okhttp-4.12.0.jar               https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar
67543f0736fc422ae927ed0e504b98bc5e269fda0d3500579337cb713da28412  okio-jvm-3.6.0.jar              https://repo1.maven.org/maven2/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar
ba48305e4f26630b37776f6fe1b03bf0e90b2f3036dda6ac029cbc41b981d0d3  sceneview-2.3.0.aar             https://repo1.maven.org/maven2/io/github/sceneview/sceneview/2.3.0/sceneview-2.3.0.aar
b03d35dd50698d0c61e28eec24734b7b257a0f13017817375598c0d3af49da39  filament-android-1.56.0.aar     https://repo1.maven.org/maven2/com/google/android/filament/filament-android/1.56.0/filament-android-1.56.0.aar
25370a7b6a5af3be66367b6d609deed18b37333e1963b7ee6856f50e0dab544e  gltfio-android-1.56.0.aar       https://repo1.maven.org/maven2/com/google/android/filament/gltfio-android/1.56.0/gltfio-android-1.56.0.aar
31c5628d422af803ca6bd1cc93b77782c21ade30184883a529674b01fb611be0  filament-utils-android-1.56.0.aar https://repo1.maven.org/maven2/com/google/android/filament/filament-utils-android/1.56.0/filament-utils-android-1.56.0.aar
1bed2190b7d7846649538b91d6b7519d7c2e78395a021b59b4f0eefc66c8865d  kotlin-math-jvm-1.5.3.jar       https://repo1.maven.org/maven2/dev/romainguy/kotlin-math-jvm/1.5.3/kotlin-math-jvm-1.5.3.jar
9e38b4b2b4f6bae5491c0975c1c4784ba9b7edc4a54f37acbca30db2c6516a35  fuel-2.3.1.jar                https://repo1.maven.org/maven2/com/github/kittinunf/fuel/fuel/2.3.1/fuel-2.3.1.jar
0eea67f95bf5bb03b37fbf3c82e4a0b835fe7fa940fdeb607b4adf58e51a7625  fuel-android-2.3.1.aar         https://repo1.maven.org/maven2/com/github/kittinunf/fuel/fuel-android/2.3.1/fuel-android-2.3.1.aar
235f85a12624ea6772d68e39c02efa20b79667a21f3976294a2b47794b525ffd  fuel-coroutines-2.3.1.jar      https://repo1.maven.org/maven2/com/github/kittinunf/fuel/fuel-coroutines/2.3.1/fuel-coroutines-2.3.1.jar
e02e7e0a9395174bb57bbd4043fb1fa6c4a69aa4da5ce4c65331651d22e89313  result-3.1.0.jar               https://repo1.maven.org/maven2/com/github/kittinunf/result/result/3.1.0/result-3.1.0.jar
EOF

echo "All $TOTAL prebuilts present and SHA256-verified."

# ---------------------------------------------------------------------------
# Extract the AARs' arm64 native libraries.
#
# Why this is done here and not by Soong: the imports below set
# `extract_jni: true`, but that rule only fires for each entry in
# ctx.MultiTargets(), which is EMPTY for an android_library_import in this tree
# — so no *_jni.zip is ever produced and the APK ships with zero .so entries.
# The failure is silent at build time and shows up on device as:
#
#   java.lang.UnsatisfiedLinkError: dlopen failed: library "libfilament-jni.so" not found
#
# So the .so files are pulled out here and declared as cc_prebuilt_library_shared
# modules in Android.bp, which puts them in the app's jni_libs the same way
# libmotorguardvoice/libmotorguardsomeip already work.
#
# Not committed: these come straight out of the AARs above, which are themselves
# fetched and SHA256-verified, so the checksum already covers them.
# ---------------------------------------------------------------------------
JNI_DIR="$DIR/jni/arm64-v8a"
mkdir -p "$JNI_DIR"
JNI_COUNT=0
for AAR in onnxruntime-android-1.19.2.aar android-sdk-11.8.0.aar \
           filament-android-1.56.0.aar gltfio-android-1.56.0.aar \
           filament-utils-android-1.56.0.aar; do
  [ -f "$DIR/$AAR" ] || continue
  while read -r ENTRY; do
    [ -n "$ENTRY" ] || continue
    SO="$(basename "$ENTRY")"
    unzip -o -j -q "$DIR/$AAR" "$ENTRY" -d "$JNI_DIR"
    JNI_COUNT=$((JNI_COUNT + 1))
  done <<EOJ
$(unzip -Z1 "$DIR/$AAR" 'jni/arm64-v8a/*.so' 2>/dev/null)
EOJ
done
echo "Extracted $JNI_COUNT arm64 native libraries to prebuilts/jni/arm64-v8a/."

# Native voice core — setup

> **On this AAOS branch everything below is already committed** — `sqlite3.{c,h}` and all
> three `.onnx` models are in the tree, and Soong (`cc_library_shared libmotorguardvoice`
> in the root `Android.bp`) compiles them with the platform clang. `CMakeLists.txt` in this
> directory is the Gradle/NDK equivalent, kept for the `media-nav-settings-voice` branch;
> Soong ignores it. The rest of this file is the from-scratch setup, for reference.

Two kinds of binary blob are normally **not** committed (one is 9 MB of third-party C,
the others are trained models). Drop them in once per clone.

## 1. SQLite amalgamation → `cpp/`

The NDK doesn't expose the platform's libsqlite to apps, so SQLite is compiled
straight into `libmotorguardvoice.so`. CMake fails with a clear message if it's
missing.

If you already have it from the standalone voice project:

```bash
cp /path/to/VoiceAssistantKotlin/app/src/main/cpp/sqlite3.{c,h} \
   app/src/main/cpp/
```

Otherwise download the amalgamation from sqlite.org and copy `sqlite3.c` +
`sqlite3.h` here (nothing else from the zip is needed).

## 2. Wake-word models → `app/src/main/assets/wakeword/`

Three `.onnx` files, all from openWakeWord:

| File | Source | Notes |
|------|--------|-------|
| `melspectrogram.onnx` | openWakeWord release | shared preprocessing |
| `embedding_model.onnx` | openWakeWord release | shared preprocessing |
| `hey_vega.onnx` | your trained model | the phrase-specific one |

If any are absent the app still builds and runs — `createWakeWordDetector()`
returns a no-op detector and the rail mic button remains the trigger.

To change the phrase, retrain and update `WAKE_MODEL_FILE` in
`ui/voice/WakeWord.kt`.

## 3. What's compiled

Only the portable reasoning files, shared byte-for-byte with the Linux build:

```
assistant-core/src/  FaultEvent · DiagnosticsEngine · IntentMatcher
                     ScoringIntentMatcher · Assistant
```

Left out on purpose: `Vad`, `VoiceLoop`, `HybridIntentMatcher`, `LlmPhrasing` and
all of `adapters/` except the header-only location provider — Android supplies
audio, STT and TTS, and there's no llama.cpp in this build.

The fault database (`assistant-core/data/dtc_seed.sql`) is embedded into
`seed_data.h` as a string literal, so there are no runtime file paths. **If you
edit the SQL, regenerate the header:**

```bash
cd app/src/main/cpp
{ echo '// GENERATED from assistant-core/data/dtc_seed.sql — do not edit by hand.'; \
  echo '#pragma once'; echo 'namespace seed {'; \
  echo 'inline const char* kDtcSeedSql = R"SQL('; \
  cat assistant-core/data/dtc_seed.sql; echo ')SQL";'; echo '}'; } > seed_data.h
```

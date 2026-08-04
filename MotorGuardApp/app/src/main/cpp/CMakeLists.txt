cmake_minimum_required(VERSION 3.22.1)
project("motorguardvoice")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CORE        ${CMAKE_SOURCE_DIR}/assistant-core)
set(WHISPER_DIR ${CMAKE_SOURCE_DIR}/whisper.cpp)
set(ESPEAK_DIR  ${CMAKE_SOURCE_DIR}/espeak-ng)

# SQLite is compiled straight into the library (the NDK does not expose the
# platform's libsqlite to apps). Drop the official amalgamation next to this
# file — see cpp/README-native.md for the one-line copy command.
if(NOT EXISTS ${CMAKE_SOURCE_DIR}/sqlite3.c)
    message(FATAL_ERROR
        "Missing cpp/sqlite3.c (+ sqlite3.h).\n"
        "Copy the SQLite amalgamation into app/src/main/cpp/ — see cpp/README-native.md.")
endif()

# Both third-party trees are OPTIONAL. If either is absent the library still
# builds and the corresponding Kotlin object fails cleanly at load time, so a
# fresh clone of this repo compiles without cloning anything else first.

# ---------------------------------------------------------------------------
# whisper.cpp — offline STT
# ---------------------------------------------------------------------------
#     cd app/src/main/cpp && git clone --depth 1 \
#         https://github.com/ggerganov/whisper.cpp
set(HAVE_WHISPER OFF)
if(EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    set(HAVE_WHISPER ON)

    # Trim to the library: the examples pull in SDL2 and a server target, none
    # of which cross-compile cleanly for Android.
    set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)

    # The NDK sysroot has no libomp for ggml to link against. Leaving this on is
    # the usual first failure when building ggml for Android.
    set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

    add_subdirectory(${WHISPER_DIR} EXCLUDE_FROM_ALL)
else()
    message(WARNING "whisper.cpp not found at ${WHISPER_DIR} — building without STT.")
endif()

# ---------------------------------------------------------------------------
# espeak-ng — phonemiser for Piper TTS
# ---------------------------------------------------------------------------
#     cd app/src/main/cpp && git clone --depth 1 \
#         https://github.com/espeak-ng/espeak-ng
#
# Used ONLY to turn text into IPA phonemes; synthesis is the Piper VITS model's
# job, in Kotlin. Hence every audio backend below is switched off -- espeak must
# never touch an audio device here.
#
# NOTE: espeak-ng normally generates espeak-ng-data at build time using host
# tools that do not exist in a cross-compile. Take the prebuilt data directory
# from a release tarball and push it to the device instead; see PiperTts.
set(HAVE_ESPEAK OFF)
if(EXISTS ${ESPEAK_DIR}/CMakeLists.txt)
    set(HAVE_ESPEAK ON)

    set(ESPEAK_BLD_EXECUTABLES OFF CACHE BOOL "" FORCE)
    set(USE_ASYNC              OFF CACHE BOOL "" FORCE)  # no pthread audio thread
    set(USE_MBROLA             OFF CACHE BOOL "" FORCE)
    set(USE_LIBSONIC           OFF CACHE BOOL "" FORCE)
    set(USE_LIBPCAUDIO         OFF CACHE BOOL "" FORCE)  # no audio output at all
    set(USE_KLATT              OFF CACHE BOOL "" FORCE)  # legacy synth, unused
    set(USE_SPEECHPLAYER       OFF CACHE BOOL "" FORCE)  # legacy synth, unused
    set(EXTRA_cmn              OFF CACHE BOOL "" FORCE)
    set(EXTRA_ru               OFF CACHE BOOL "" FORCE)

    add_subdirectory(${ESPEAK_DIR} EXCLUDE_FROM_ALL)
else()
    message(WARNING "espeak-ng not found at ${ESPEAK_DIR} — building without TTS.")
endif()

# Both subprojects default to shared libraries, which would ship three .so files
# instead of one. Force static AFTER add_subdirectory so the setting cannot leak
# into how this library itself is declared.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# ---------------------------------------------------------------------------
# The library
# ---------------------------------------------------------------------------
set(MG_SOURCES
    native-lib.cpp
    sqlite3.c
    ${CORE}/src/FaultEvent.cpp
    ${CORE}/src/DiagnosticsEngine.cpp
    ${CORE}/src/IntentMatcher.cpp
    ${CORE}/src/ScoringIntentMatcher.cpp
    ${CORE}/src/Assistant.cpp
)

if(HAVE_WHISPER)
    list(APPEND MG_SOURCES whisper_jni.cpp)
endif()

if(HAVE_ESPEAK)
    list(APPEND MG_SOURCES espeak_jni.cpp)
endif()

add_library(motorguardvoice SHARED ${MG_SOURCES})

target_include_directories(motorguardvoice PRIVATE
    ${CORE}/include          # assistant/*.hpp
    ${CMAKE_SOURCE_DIR}      # seed_data.h, sqlite3.h, assistant-core/adapters/...
)

# Keep the binary small: no loadable extensions, no unused SQLite features.
target_compile_definitions(motorguardvoice PRIVATE
    SQLITE_OMIT_LOAD_EXTENSION
    SQLITE_THREADSAFE=1
)

find_library(log-lib log)
target_link_libraries(motorguardvoice ${log-lib})

if(HAVE_WHISPER)
    target_include_directories(motorguardvoice PRIVATE
        ${WHISPER_DIR}/include
        ${WHISPER_DIR}/ggml/include
    )
    # Linking the static whisper target pulls ggml in with it.
    target_link_libraries(motorguardvoice whisper)
    target_compile_definitions(motorguardvoice PRIVATE MG_HAVE_WHISPER=1)
endif()

if(HAVE_ESPEAK)
    target_include_directories(motorguardvoice PRIVATE
        ${ESPEAK_DIR}/src/include
        ${ESPEAK_DIR}/src/include/espeak-ng
        ${ESPEAK_DIR}/src/libespeak-ng
    )
    # Target name varies by espeak-ng version: newer trees expose espeak-ng,
    # older ones espeak-ng-static. Prefer whichever exists.
    if(TARGET espeak-ng)
        target_link_libraries(motorguardvoice espeak-ng)
    elseif(TARGET espeak-ng-static)
        target_link_libraries(motorguardvoice espeak-ng-static)
    else()
        message(FATAL_ERROR
            "espeak-ng present but no espeak-ng / espeak-ng-static target was "
            "defined. Check the subproject's CMakeLists for the library name.")
    endif()
    target_compile_definitions(motorguardvoice PRIVATE MG_HAVE_ESPEAK=1)
endif()
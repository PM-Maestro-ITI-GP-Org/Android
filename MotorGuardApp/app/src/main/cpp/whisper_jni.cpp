// whisper_jni.cpp — owner D
// Thin JNI bridge over whisper.cpp. Deliberately minimal: load a model, run one
// utterance, return text. No streaming, because Whisper has no streaming mode --
// it processes a complete utterance in one pass over a padded 30 s window.
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>

#include "whisper.h"

#define TAG "MotorGuardVoice"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// One context for the process. Loading costs seconds and the platform creates
// and destroys the RecognitionService around every utterance, so a per-instance
// context would never be ready in time.
whisper_context *g_ctx = nullptr;
std::mutex g_mutex;

std::string jstr(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Whisper emits leading spaces and, on silence, bracketed non-speech markers
// like "[BLANK_AUDIO]" or "(wind blowing)". Neither is useful downstream.
std::string clean(const std::string &in) {
    std::string s = in;
    const auto first = s.find_first_not_of(" \t\n");
    if (first == std::string::npos) return {};
    const auto last = s.find_last_not_of(" \t\n");
    s = s.substr(first, last - first + 1);
    if (!s.empty() && (s.front() == '[' || s.front() == '(')) return {};
    return s;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_motorguard_ivi_ui_voice_WhisperStt_nativeInit(
        JNIEnv *env, jobject, jstring modelPath) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx != nullptr) return JNI_TRUE;

    const std::string path = jstr(env, modelPath);
    whisper_context_params cparams = whisper_context_default_params();
    // No GPU on this board; asking for one just logs noise.
    cparams.use_gpu = false;

    g_ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (g_ctx == nullptr) {
        LOGE("whisper: failed to load model from %s", path.c_str());
        return JNI_FALSE;
    }
    LOGI("whisper: model loaded from %s", path.c_str());
    LOGI("whisper: system = %s", whisper_print_system_info());
    return JNI_TRUE;
}

/**
 * @param pcm    mono 16 kHz float samples, normalised to [-1, 1]
 * @param prompt biases decoding; the single most useful knob here. Priming with
 *               real DTC codes ("P0217, P0300, B1000") makes Whisper far more
 *               willing to emit letter-digit sequences instead of fitting them
 *               to ordinary English words.
 */
JNIEXPORT jstring JNICALL
Java_com_motorguard_ivi_ui_voice_WhisperStt_nativeTranscribe(
        JNIEnv *env, jobject, jfloatArray pcm, jstring prompt, jint threads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx == nullptr) {
        LOGE("whisper: transcribe before init");
        return env->NewStringUTF("");
    }

    const jsize n = env->GetArrayLength(pcm);
    if (n <= 0) return env->NewStringUTF("");

    std::vector<float> samples(static_cast<size_t>(n));
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());

    const std::string primer = jstr(env, prompt);

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.language         = "en";
    wparams.translate        = false;
    wparams.n_threads        = threads > 0 ? threads : 4;
    wparams.no_timestamps    = true;
    wparams.single_segment   = true;   // one short utterance, not a transcript
    wparams.print_progress   = false;
    wparams.print_realtime   = false;
    wparams.print_special    = false;
    wparams.print_timestamps = false;
    wparams.audio_ctx = 512;
        // Suppress "(wind blowing)" style annotations.
    wparams.suppress_nst     = true;
    if (!primer.empty()) wparams.initial_prompt = primer.c_str();

    if (whisper_full(g_ctx, wparams, samples.data(), static_cast<int>(n)) != 0) {
        LOGE("whisper: inference failed");
        return env->NewStringUTF("");
    }

    std::string text;
    const int segments = whisper_full_n_segments(g_ctx);
    for (int i = 0; i < segments; ++i) {
        text += whisper_full_get_segment_text(g_ctx, i);
    }
    const std::string out = clean(text);
    LOGI("whisper: %d samples (%.1fs audio) -> \"%s\"",
         n, n / 16000.0f, out.c_str());
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_motorguard_ivi_ui_voice_WhisperStt_nativeRelease(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }
}

} // extern "C"
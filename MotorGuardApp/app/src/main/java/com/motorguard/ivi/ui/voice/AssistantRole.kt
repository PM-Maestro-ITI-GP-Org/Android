package com.motorguard.ivi.ui.voice

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Makes Motor Guard the selected voice interaction service, and keeps it that way.
 *
 * On a phone the driver picks an assistant in Settings. This head unit has no such
 * screen — Motor Guard *is* the assistant — and the selection lives in a secure setting
 * that is dropped whenever the app is reinstalled. After that the platform refuses to
 * open a session at all:
 *
 *     SecurityException: enforceIsCurrentVoiceInteractionService
 *       at VoiceInteractionManagerService.showSession
 *
 * and the rail mic button can only report "Voice assistant isn't the selected assistant".
 * That is a one-line adb command to fix and completely opaque to anyone who does not know
 * it, so the app claims the role itself instead.
 *
 * Writing here needs WRITE_SECURE_SETTINGS, which is signature|privileged: granted
 * because this app is platform-signed and allow-listed, and simply absent on a build that
 * is neither — in which case this reports the failure and changes nothing.
 */
object AssistantRole {

    private const val TAG = "MotorGuardVoice"

    private val SERVICE = ComponentName(
        "com.motorguard.ivi",
        "com.motorguard.ivi.ui.voice.VoiceOverlayService",
    )

    /**
     * Claim the role if something else holds it. Safe to call on every start.
     *
     * Deliberately does NOT touch Settings.Secure.ASSISTANT. That key names the assistant
     * *app* for the long-press gesture and is a different thing from the interaction
     * service; pointing it at VoiceOverlayService made the platform reject showSession
     * until it was cleared again. voice_interaction_service is the one that matters here.
     */
    fun claim(context: Context) {
        val current = runCatching {
            Settings.Secure.getString(context.contentResolver, KEY_VOICE_INTERACTION_SERVICE)
        }.getOrNull()

        val wanted = SERVICE.flattenToShortString()
        if (current == wanted || current == SERVICE.flattenToString()) return

        val ok = runCatching {
            Settings.Secure.putString(context.contentResolver, KEY_VOICE_INTERACTION_SERVICE, wanted)
        }.isSuccess

        if (ok) {
            // The recognizer is a separate selection, and an assistant that cannot
            // transcribe is not much of one.
            runCatching {
                Settings.Secure.putString(
                    context.contentResolver,
                    KEY_VOICE_RECOGNITION_SERVICE,
                    ComponentName(
                        "com.motorguard.ivi",
                        "com.motorguard.ivi.ui.voice.OfflineRecognitionService",
                    ).flattenToShortString(),
                )
            }
            Log.i(TAG, "claimed the voice interaction role (was ${current ?: "unset"})")
        } else {
            Log.w(TAG, "could not claim the voice interaction role — WRITE_SECURE_SETTINGS denied")
        }
    }

    private const val KEY_VOICE_INTERACTION_SERVICE = "voice_interaction_service"
    private const val KEY_VOICE_RECOGNITION_SERVICE = "voice_recognition_service"
}

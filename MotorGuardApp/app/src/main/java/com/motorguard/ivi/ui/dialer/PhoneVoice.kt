package com.motorguard.ivi.ui.dialer

import android.content.Context
import android.content.Intent
import android.util.Log
import com.motorguard.ivi.MainActivity
import com.motorguard.ivi.data.PhoneRepository

/**
 * Phone intents for the voice overlay. Handled here rather than in the C++ reasoning
 * core because dialling needs the contact list, which lives on the Android side — the
 * core reasons about faults, not about who "Mona" is.
 *
 * Wire it into `VoiceOverlaySession.answer()` before the core is consulted:
 *
 *     private fun answer(utterance: String) {
 *         PhoneVoice.handle(context, utterance)?.let { reply ->
 *             model = model.copy(state = VoiceState.SPEAKING, reply = reply)
 *             speak(reply); return
 *         }
 *         val reply = VoiceEngine.handle(utterance) ?: ...
 *     }
 *
 * Returns the line to speak, or null when the utterance is not about the phone — in
 * which case the caller falls through to the reasoning core untouched.
 */
object PhoneVoice {

    private const val TAG = "MotorGuardPhone"

    private val dialPrefixes = listOf("call ", "phone ", "dial ", "ring ")
    private val hangUpPhrases = listOf("hang up", "end the call", "end call")
    private val answerPhrases = listOf("answer", "pick up", "take the call")

    fun handle(context: Context, utterance: String): String? {
        val text = utterance.trim().lowercase().removeSuffix(".").removeSuffix("?")
        val repo = PhoneRepository.get(context)

        if (hangUpPhrases.any { text.contains(it) }) {
            if (repo.call.value == null) return "There's no call to end."
            repo.hangUp()
            return "Hanging up."
        }

        if (answerPhrases.any { text == it || text.startsWith("$it the") }) {
            if (repo.call.value == null) return "There's nothing to answer."
            repo.answer()
            return "Answering."
        }

        val target = dialPrefixes.firstNotNullOfOrNull { prefix ->
            text.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()
        } ?: return null

        if (target.isEmpty()) return "Who would you like to call?"

        // "call 0100 224 8871" — spoken digits go straight through.
        val spokenDigits = target.filter { it.isDigit() || it == '+' }
        if (spokenDigits.length >= 6 && target.none { it.isLetter() }) {
            return place(context, spokenDigits, null, "Calling that number.")
        }

        val contact = repo.lookup(target)
            ?: return "I couldn't find $target in your contacts."

        return place(context, contact.number, contact.name, "Calling ${contact.name}.")
    }

    private fun place(context: Context, number: String, name: String?, reply: String): String {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_TAB, MainActivity.Tab.PHONE.name)
            putExtra(MainActivity.EXTRA_DIAL_NUMBER, number)
            name?.let { putExtra(MainActivity.EXTRA_DIAL_NAME, it) }
        }
        return runCatching { context.startActivity(intent); reply }
            .getOrElse {
                Log.e(TAG, "could not open the phone tab", it)
                "I couldn't open the phone screen."
            }
    }
}

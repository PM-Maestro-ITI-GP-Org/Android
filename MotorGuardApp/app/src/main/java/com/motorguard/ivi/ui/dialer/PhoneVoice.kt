package com.motorguard.ivi.ui.dialer

import android.content.Context
import android.content.Intent
import android.util.Log
import com.motorguard.ivi.MainActivity
import com.motorguard.ivi.data.CallState
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

    /** Rejecting a ringing call is not the same request as ending a live one, even where the
     *  action underneath is. Saying "Declined" back is how the driver knows which happened. */
    private val declinePhrases = listOf("decline", "reject", "ignore the call", "ignore it", "don t answer", "dont answer")

    private val muteCallPhrases = listOf("mute the call", "mute me", "mute the phone", "mute")
    private val unmuteCallPhrases = listOf("unmute the call", "unmute me", "unmute the phone", "unmute")
    private val holdPhrases = listOf("put the call on hold", "hold the call", "put them on hold", "on hold")
    private val resumePhrases = listOf("take them off hold", "off hold", "resume the call", "unhold")

    /**
     * "Who is this" is deliberately absent — that is [com.motorguard.ivi.ui.voice.MediaVoice]'s
     * now-playing question, and this handler runs first, so claiming it would mean asking about a
     * song and being told about a phone call.
     */
    private val whoPhrases = listOf("who is calling", "whos calling", "who s calling", "who is it")

    /** Checked before [dialPrefixes], which would otherwise read "call back" as a contact
     *  named "back" and report that it could not find them. */
    private val redialPhrases = listOf(
        "call back", "call them back", "call him back", "call her back", "redial",
        "call the last number", "ring them back",
    )

    fun handle(context: Context, utterance: String): String? {
        val text = utterance.trim().lowercase().removeSuffix(".").removeSuffix("?")
        val repo = PhoneRepository.get(context)
        val call = repo.call.value

        // Everything in this block is gated on there being a call, so the words it shares with
        // other handlers — "mute" most of all — only belong to the phone while the phone is the
        // thing they could plausibly be about. With no call, "mute" falls through to the media
        // player, which is what it means the rest of the time.
        if (call != null) {
            if (unmuteCallPhrases.any { text.contains(it) }) {
                if (!call.muted) return "The call isn't muted."
                repo.setMuted(false)
                return "Unmuted."
            }
            if (muteCallPhrases.any { text.contains(it) }) {
                if (call.muted) return "The call is already muted."
                repo.setMuted(true)
                return "Call muted."
            }
            if (resumePhrases.any { text.contains(it) }) {
                repo.setOnHold(false)
                return "Back to the call."
            }
            if (holdPhrases.any { text.contains(it) }) {
                repo.setOnHold(true)
                return "On hold."
            }
        }

        if (whoPhrases.any { text.contains(it) }) {
            call ?: return "Nobody's calling."
            return when (call.state) {
                CallState.RINGING -> "${call.label} is calling."
                CallState.DIALING -> "Calling ${call.label}."
                else -> "You're on a call with ${call.label}."
            }
        }

        if (declinePhrases.any { text.contains(it) }) {
            if (call == null) return "There's no call to decline."
            // Reject and hang up are one action to the telephony layer and two different things
            // to the driver; the reply is what distinguishes them.
            val ringing = call.state == CallState.RINGING
            repo.hangUp()
            return if (ringing) "Declined." else "Hanging up."
        }

        if (redialPhrases.any { text.contains(it) }) {
            val last = repo.recents.value.firstOrNull() ?: return "There's nothing in your recent calls."
            return place(context, last.number, last.name, "Calling ${last.label} back.")
        }

        if (hangUpPhrases.any { text.contains(it) }) {
            if (call == null) return "There's no call to end."
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

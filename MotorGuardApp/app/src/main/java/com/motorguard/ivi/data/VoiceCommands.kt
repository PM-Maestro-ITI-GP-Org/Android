package com.motorguard.ivi.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phrases the driver taught the assistant, and what it should say back.
 *
 * The reasoning core in C++ knows about warning lights and faults; it is compiled in and
 * cannot be extended from the car. This is the part the owner controls: say a phrase, get
 * their answer. It sits in front of the core so a taught phrase always wins — the point of
 * teaching one is usually that the built-in answer was not what you wanted.
 *
 * Stored as JSON in [LocalStore] rather than a database: this is a handful of short
 * strings edited by hand, and a Room dependency to hold ten rows would be silly.
 */
object VoiceCommands {

    private const val TAG = "MotorGuardVoice"
    private const val KEY = "voice_commands"

    private val _commands = MutableStateFlow<List<VoiceCommand>>(emptyList())
    val commands: StateFlow<List<VoiceCommand>> = _commands.asStateFlow()

    @Volatile
    private var loaded = false

    /**
     * Load once, initialising the store if nobody has yet.
     *
     * Takes a Context because the assistant is not reached only through the launcher: the
     * platform binds VoiceOverlayService at boot, so a session can run before MainActivity
     * has ever started and done it. Relying on the activity would mean taught phrases
     * silently do nothing after a cold boot until someone opens the app.
     */
    fun ensureLoaded(context: android.content.Context) {
        if (loaded) return
        LocalStore.init(context)
        val raw = runCatching { LocalStore.getString(KEY) }.getOrNull()
        loaded = true
        if (raw.isNullOrBlank()) return
        _commands.value = runCatching { parse(raw) }.getOrElse {
            Log.w(TAG, "stored voice commands unreadable, starting empty", it)
            emptyList()
        }
    }

    /** @return the new command, or null when the trigger is blank or already taken. */
    fun add(trigger: String, reply: String): VoiceCommand? {
        val t = trigger.trim()
        val r = reply.trim()
        if (t.isEmpty() || r.isEmpty()) return null
        if (_commands.value.any { normalise(it.trigger) == normalise(t) }) return null

        val command = VoiceCommand(id = System.currentTimeMillis().toString(), trigger = t, reply = r)
        _commands.value = _commands.value + command
        persist()
        return command
    }

    fun update(id: String, trigger: String, reply: String) {
        val t = trigger.trim()
        val r = reply.trim()
        if (t.isEmpty() || r.isEmpty()) return
        _commands.value = _commands.value.map {
            if (it.id == id) it.copy(trigger = t, reply = r) else it
        }
        persist()
    }

    fun remove(id: String) {
        _commands.value = _commands.value.filterNot { it.id == id }
        persist()
    }

    /**
     * The answer for what was just said, or null to let the core handle it.
     *
     * Matches on containment, not equality: the driver taught "tyre pressure" and then
     * asks "what's my tyre pressure" — requiring the whole utterance to match would make
     * every taught phrase a password to be recited exactly. Longest trigger first, so a
     * specific phrase beats a general one that happens to be a substring of the same
     * sentence.
     */
    fun answer(utterance: String): String? {
        val said = normalise(utterance)
        if (said.isEmpty()) return null
        return _commands.value
            .sortedByDescending { it.trigger.length }
            .firstOrNull { said.contains(normalise(it.trigger)) }
            ?.reply
    }

    /** Case and punctuation are not something a driver should have to get right. */
    private fun normalise(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }.trim()

    private fun persist() {
        val json = JSONArray()
        _commands.value.forEach {
            json.put(
                JSONObject()
                    .put("id", it.id)
                    .put("trigger", it.trigger)
                    .put("reply", it.reply),
            )
        }
        runCatching { LocalStore.putString(KEY, json.toString()) }
            .onFailure { Log.w(TAG, "could not save voice commands", it) }
    }

    private fun parse(raw: String): List<VoiceCommand> {
        val array = JSONArray(raw)
        val out = ArrayList<VoiceCommand>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val trigger = o.optString("trigger").takeIf { it.isNotBlank() } ?: continue
            val reply = o.optString("reply").takeIf { it.isNotBlank() } ?: continue
            out += VoiceCommand(
                id = o.optString("id").takeIf { it.isNotBlank() } ?: i.toString(),
                trigger = trigger,
                reply = reply,
            )
        }
        return out
    }
}

/** One taught phrase and its answer. */
data class VoiceCommand(
    val id: String,
    val trigger: String,
    val reply: String,
)

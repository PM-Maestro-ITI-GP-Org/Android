package com.motorguard.ivi.data

/**
 * Domain types for the phone surface. Deliberately free of Android/Telecom types so
 * the UI never sees a `android.telecom.Call` and the mock backend can produce the
 * same shapes as the real one.
 */

/** HFP (hands-free) link to the driver's phone. The car is the HF, the phone is the AG. */
enum class PhoneLink { DISCONNECTED, CONNECTING, CONNECTED }

/** Lifecycle of the one call we support. AAOS HFP gives us a single active call. */
enum class CallState { DIALING, RINGING, ACTIVE, HOLDING, ENDING }

enum class CallDirection { INCOMING, OUTGOING, MISSED }

data class Contact(
    val id: Long,
    val name: String,
    val number: String,
    val favorite: Boolean = false,
) {
    /** Up to two letters for the avatar disc — no photo sync over PBAP. */
    val initials: String
        get() = name.split(' ')
            .filter { it.isNotBlank() }
            .take(2)
            .map { it.first().uppercaseChar() }
            .joinToString("")
            .ifEmpty { "?" }
}

data class CallLogEntry(
    val id: Long,
    val name: String?,
    val number: String,
    val direction: CallDirection,
    val timestampMillis: Long,
) {
    val label: String get() = name ?: number
}

/**
 * The live call. [startedAtElapsedMs] is `SystemClock.elapsedRealtime()` at answer time
 * so the UI can tick a duration without the repository emitting once per second.
 */
data class ActiveCall(
    val number: String,
    val name: String?,
    val state: CallState,
    val direction: CallDirection,
    val startedAtElapsedMs: Long = 0L,
    val muted: Boolean = false,
) {
    val label: String get() = name ?: number
    val connected: Boolean get() = state == CallState.ACTIVE || state == CallState.HOLDING
}

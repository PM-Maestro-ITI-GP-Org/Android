package com.motorguard.ivi.data

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * The phone-side twin of [CarDataRepository]: the **only** place in the app allowed to
 * touch `TelecomManager`, `InCallService`, `ContactsContract`, `CallLog` or the Bluetooth
 * HFP-client profile. Fragments observe StateFlows and never query a provider themselves.
 *
 * Two backends implement it:
 *  - [MockPhoneSource]    — seeded contacts/recents and a simulated call state machine.
 *                           Works on a bare Pi 5 image with no Bluetooth stack at all.
 *  - [TelecomPhoneSource] — real HFP: places calls through the Bluetooth PhoneAccount,
 *                           controls them via [com.motorguard.ivi.ui.dialer.MotorGuardInCallService],
 *                           reads PBAP-synced contacts and call log.
 *
 * Swap with [USE_MOCK], exactly as the car side runs on the mock VHAL until CAN is wired.
 */
interface PhoneRepository {

    val link: StateFlow<PhoneLink>

    /** Friendly name of the paired phone, or null when nothing is connected. */
    val deviceName: StateFlow<String?>

    /** The single live call, or null when idle. */
    val call: StateFlow<ActiveCall?>

    val contacts: StateFlow<List<Contact>>

    val recents: StateFlow<List<CallLogEntry>>

    fun dial(number: String, displayName: String? = null)

    fun answer()

    fun hangUp()

    fun setMuted(muted: Boolean)

    fun setOnHold(hold: Boolean)

    /** In-call DTMF (phone trees). Ignored when there is no connected call. */
    fun sendDtmf(digit: Char)

    /** Re-read contacts, call log and link state. Call from `onResume` and after a grant. */
    fun refresh()

    /**
     * Loose name match for the voice assistant: "call Mona" → the Mona contact.
     * Returns null when the query is ambiguous or unknown, so the caller can fall back
     * to opening the tab instead of dialling the wrong person.
     */
    fun lookup(nameQuery: String): Contact? {
        val q = nameQuery.trim().lowercase()
        if (q.length < 2) return null
        val list = contacts.value
        list.firstOrNull { it.name.equals(q, ignoreCase = true) }?.let { return it }
        val hits = list.filter { it.name.lowercase().startsWith(q) }
            .ifEmpty { list.filter { it.name.lowercase().contains(q) } }
        return hits.singleOrNull()
    }

    companion object {
        /**
         * true  — simulated phone, no permissions, no Bluetooth. Demo/bench default.
         * false — real HFP. Needs the DIALER role and the runtime permissions in
         *         `DialerFragment`; see docs/08-dialer.md.
         */
        const val USE_MOCK = false

        @Volatile
        private var instance: PhoneRepository? = null

        fun get(context: Context): PhoneRepository =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        private fun create(app: Context): PhoneRepository =
            if (USE_MOCK) MockPhoneSource() else TelecomPhoneSource(app)
    }
}

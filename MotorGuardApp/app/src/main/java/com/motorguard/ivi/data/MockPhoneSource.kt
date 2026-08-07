package com.motorguard.ivi.data

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Simulated phone. No Bluetooth, no permissions, no Telecom — so the dialer is
 * demonstrable on a bare Pi 5 image and in the emulator, and the UI can be developed
 * without a paired handset.
 *
 * Timings match a real HFP call closely enough that the UI is exercised properly:
 * ~2.2 s of DIALING before the far end picks up.
 */
class MockPhoneSource : PhoneRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var progression: Job? = null

    private val _link = MutableStateFlow(PhoneLink.CONNECTED)
    private val _deviceName = MutableStateFlow<String?>("Pixel 8 (simulated)")
    private val _call = MutableStateFlow<ActiveCall?>(null)
    private val _contacts = MutableStateFlow(seedContacts)
    private val _recents = MutableStateFlow(seedRecents())

    override val link: StateFlow<PhoneLink> = _link.asStateFlow()
    override val deviceName: StateFlow<String?> = _deviceName.asStateFlow()
    override val call: StateFlow<ActiveCall?> = _call.asStateFlow()
    override val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()
    override val recents: StateFlow<List<CallLogEntry>> = _recents.asStateFlow()

    override fun dial(number: String, displayName: String?) {
        if (_call.value != null) return
        val name = displayName ?: _contacts.value.firstOrNull { sameNumber(it.number, number) }?.name

        _call.value = ActiveCall(
            number = number,
            name = name,
            state = CallState.DIALING,
            direction = CallDirection.OUTGOING,
        )

        progression?.cancel()
        progression = scope.launch {
            delay(2_200)
            _call.value = _call.value
                ?.takeIf { it.state == CallState.DIALING }
                ?.copy(state = CallState.ACTIVE, startedAtElapsedMs = SystemClock.elapsedRealtime())
                ?: return@launch
        }
    }

    override fun answer() {
        val current = _call.value ?: return
        if (current.state != CallState.RINGING) return
        _call.value = current.copy(
            state = CallState.ACTIVE,
            startedAtElapsedMs = SystemClock.elapsedRealtime(),
        )
    }

    override fun hangUp() {
        val ended = _call.value ?: return
        progression?.cancel()
        _call.value = ended.copy(state = CallState.ENDING)
        progression = scope.launch {
            logCall(ended)
            delay(900)
            _call.value = null
        }
    }

    override fun setMuted(muted: Boolean) {
        _call.value = _call.value?.copy(muted = muted)
    }

    override fun setOnHold(hold: Boolean) {
        val current = _call.value ?: return
        if (!current.connected) return
        _call.value = current.copy(state = if (hold) CallState.HOLDING else CallState.ACTIVE)
    }

    override fun sendDtmf(digit: Char) = Unit

    override fun refresh() = Unit

    /** Push the finished call to the top of Recents so the list behaves like the real one. */
    private fun logCall(ended: ActiveCall) {
        val entry = CallLogEntry(
            id = System.currentTimeMillis(),
            name = ended.name,
            number = ended.number,
            direction = if (ended.connected) ended.direction else CallDirection.OUTGOING,
            timestampMillis = System.currentTimeMillis(),
        )
        _recents.value = (listOf(entry) + _recents.value).take(40)
    }

    private fun sameNumber(a: String, b: String): Boolean =
        a.filter(Char::isDigit).takeLast(9) == b.filter(Char::isDigit).takeLast(9)

    private companion object {

        val seedContacts = listOf(
            Contact(1, "Mona Farid", "+20 100 224 8871", favorite = true),
            Contact(2, "Karim Adel", "+20 122 908 4410", favorite = true),
            Contact(3, "Roadside Assistance", "+20 2 2555 0199", favorite = true),
            Contact(4, "Dina Hassan", "+20 111 337 2054"),
            Contact(5, "Hossam Nabil", "+20 128 640 7712"),
            Contact(6, "Service Centre", "+20 2 2670 4400"),
            Contact(7, "Nour El Din", "+20 101 553 8890"),
            Contact(8, "Yara Mostafa", "+20 106 214 3367"),
            Contact(9, "Tarek Sami", "+20 127 448 1902"),
        )

        fun seedRecents(): List<CallLogEntry> {
            val now = System.currentTimeMillis()
            val minute = 60_000L
            return listOf(
                CallLogEntry(1, "Mona Farid", "+20 100 224 8871", CallDirection.INCOMING, now - 14 * minute),
                CallLogEntry(2, "Service Centre", "+20 2 2670 4400", CallDirection.OUTGOING, now - 96 * minute),
                CallLogEntry(3, null, "+20 155 900 3321", CallDirection.MISSED, now - 300 * minute),
                CallLogEntry(4, "Karim Adel", "+20 122 908 4410", CallDirection.OUTGOING, now - 22 * 60 * minute),
                CallLogEntry(5, "Dina Hassan", "+20 111 337 2054", CallDirection.INCOMING, now - 30 * 60 * minute),
                CallLogEntry(6, "Yara Mostafa", "+20 106 214 3367", CallDirection.MISSED, now - 52 * 60 * minute),
            )
        }
    }
}

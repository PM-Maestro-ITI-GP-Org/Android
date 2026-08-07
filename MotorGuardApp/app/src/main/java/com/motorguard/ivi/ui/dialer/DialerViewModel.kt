package com.motorguard.ivi.ui.dialer

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.motorguard.ivi.data.PhoneRepository

/**
 * UI state for the phone tab. Scoped to the **activity** (`activityViewModels`), not the
 * fragment, because the rail uses `replace()` — a half-typed number and the open list tab
 * must survive a trip to Media and back.
 *
 * Call state itself is not held here; it lives in [PhoneRepository] so the voice overlay
 * and a future Home widget see the same call without going through this screen.
 */
class DialerViewModel(app: Application) : AndroidViewModel(app) {

    enum class ListTab(val label: String) { FAVOURITES("Favourites"), RECENTS("Recents"), CONTACTS("Contacts") }

    val repo: PhoneRepository = PhoneRepository.get(app)

    /** Digits typed on the dialpad, unformatted. */
    var digits by mutableStateOf("")
        private set

    var listTab by mutableStateOf(ListTab.RECENTS)

    /** In-call DTMF pad visibility. Reset whenever a call ends. */
    var inCallKeypad by mutableStateOf(false)

    fun press(digit: Char) {
        if (digits.length >= MAX_DIGITS) return
        digits += digit
    }

    fun backspace() {
        digits = digits.dropLast(1)
    }

    fun clearDigits() {
        digits = ""
    }

    /** Used when a `tel:` intent or the voice assistant prefills the pad. */
    fun prefillDigits(value: String) {
        digits = value.take(MAX_DIGITS)
    }

    fun dialTyped() {
        val number = digits.trim()
        if (number.isEmpty()) return
        repo.dial(number)
        clearDigits()
    }

    fun dial(number: String, name: String? = null) = repo.dial(number, name)

    fun endCall() {
        inCallKeypad = false
        repo.hangUp()
    }

    private companion object {
        const val MAX_DIGITS = 20
    }
}

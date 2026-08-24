package com.motorguard.ivi.data

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * When the driver last touched anything, anywhere in the app.
 *
 * [com.motorguard.ivi.MainActivity.dispatchTouchEvent] is the one place a touch is guaranteed to pass through
 * regardless of which fragment or `ComposeView` ends up handling it, so that is where this gets
 * poked. Elapsed-realtime rather than wall-clock: what matters is time actually passed, not the
 * clock reading, and the clock has been wrong before (see the timezone fix this same car needed).
 */
object UserActivity {
    private val _lastInteractionMs = MutableStateFlow(SystemClock.elapsedRealtime())
    val lastInteractionMs: StateFlow<Long> = _lastInteractionMs.asStateFlow()

    fun poke() {
        _lastInteractionMs.value = SystemClock.elapsedRealtime()
    }
}

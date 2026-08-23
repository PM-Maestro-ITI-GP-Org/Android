package com.motorguard.ivi.data.weather

import android.util.Log
import com.motorguard.ivi.data.nav.NavConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-lifetime weather for the Home card, refreshed on a timer rather than fetched fresh
 * every time the driver looks at Home — the same "outlives the fragment" reasoning
 * [com.motorguard.ivi.ui.diagnostics.VehicleData] and [com.motorguard.ivi.ui.nav.NavSession]
 * already apply, so leaving and returning to Home does not re-hit the API every time.
 *
 * Located at [NavConfig.defaultOrigin] — ITI — rather than a live GPS fix: this board has no
 * receiver on the bench, and a weather card that only ever reads "waiting for GPS" is worse
 * than one anchored at the place the app already treats as home.
 */
object WeatherRepository {

    private const val TAG = "MotorGuardWeather"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<Weather?>(null)
    val state: StateFlow<Weather?> = _state.asStateFlow()

    /** True once a fetch has failed and no reading has ever landed — lets the card say
     *  "Weather unavailable" instead of "Loading weather…" forever, which was indistinguishable
     *  from actually still loading and made a real failure invisible. */
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    private var started = false

    fun ensureStarted() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                val result = runCatching { WeatherService.current(NavConfig.defaultOrigin) }
                result.onSuccess {
                    _state.value = it
                    _failed.value = false
                }.onFailure {
                    // Was silently swallowed here before — a failed fetch and a slow one were
                    // indistinguishable on screen, and nothing ever showed up in logcat either.
                    Log.w(TAG, "weather fetch failed", it)
                    if (_state.value == null) _failed.value = true
                }
                // A failure is retried much sooner than a routine refresh — no reason to make
                // a driver wait a quarter-hour for a network blip to clear.
                delay(if (result.isSuccess) REFRESH_MS else RETRY_MS)
            }
        }
    }

    /** Slow enough to be a fair-use client of a free public API, fast enough that conditions
     *  shown on Home are never more than a quarter-hour stale. */
    private const val REFRESH_MS = 15 * 60 * 1000L
    private const val RETRY_MS = 30 * 1000L
}

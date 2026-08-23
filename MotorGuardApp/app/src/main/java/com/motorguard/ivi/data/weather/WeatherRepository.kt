package com.motorguard.ivi.data.weather

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<Weather?>(null)
    val state: StateFlow<Weather?> = _state.asStateFlow()

    private var started = false

    fun ensureStarted() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                runCatching { WeatherService.current(NavConfig.defaultOrigin) }
                    .onSuccess { _state.value = it }
                delay(REFRESH_MS)
            }
        }
    }

    /** Slow enough to be a fair-use client of a free public API, fast enough that conditions
     *  shown on Home are never more than a quarter-hour stale. */
    private const val REFRESH_MS = 15 * 60 * 1000L
}

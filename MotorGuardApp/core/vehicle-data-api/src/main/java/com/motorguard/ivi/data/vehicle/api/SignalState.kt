package com.motorguard.ivi.data.vehicle.api

/**
 * Wraps any vehicle signal so "we have no trustworthy value" is always
 * representable — the fragment never shows a fabricated number
 * (docs/05-diagnostics.md: offline -> "No data", grey dot).
 */
sealed interface SignalState<out T> {
    /** Subscribed but nothing received yet. */
    data object Loading : SignalState<Nothing>

    /** Fresh value; [timestampMs] is when it was captured. */
    data class Live<T>(val data: T, val timestampMs: Long) : SignalState<T>

    /** A previous value exists but stopped updating — shown distinctly, never as live. */
    data class Stale<T>(val lastData: T, val lastTimestampMs: Long) : SignalState<T>

    /** Source unreachable. */
    data object Offline : SignalState<Nothing>
}

/** Last value regardless of freshness; null for Loading/Offline. */
val <T> SignalState<T>.latestValueOrNull: T?
    get() = when (this) {
        is SignalState.Live -> data
        is SignalState.Stale -> lastData
        else -> null
    }

val SignalState<*>.isOfflineOrLoading: Boolean
    get() = this is SignalState.Offline || this is SignalState.Loading

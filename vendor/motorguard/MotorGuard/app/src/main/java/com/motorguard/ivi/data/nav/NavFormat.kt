package com.motorguard.ivi.data.nav

import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Distance / duration / arrival formatting, in one place so the maneuver card, the ETA bar
 * and the route-preview rows can never disagree with each other.
 *
 * Metric only for now — AAOS exposes the driver's unit preference via
 * `CarPropertyManager.DISTANCE_DISPLAY_UNITS`; wire that in here when CarDataRepository lands
 * and every call site follows automatically.
 */
object NavFormat {

    /** "600 m" · "1.2 km" · "62.3 km". Metres round to 10 so the number stops twitching. */
    fun distance(meters: Double): String = when {
        meters < 950 -> "${((meters / 10).roundToInt() * 10).coerceAtLeast(0)} m"
        meters < 10_000 -> String.format("%.1f km", meters / 1000.0)
        else -> String.format("%.1f km", meters / 1000.0)
    }

    /** Short form for the big maneuver readout: "600 m" · "1.2 km". */
    fun maneuverDistance(meters: Double): String = when {
        meters < 30 -> "now"
        meters < 950 -> "${((meters / 10).roundToInt() * 10)} m"
        else -> String.format("%.1f km", meters / 1000.0)
    }

    /** "1h 14m" · "14 min" · "< 1 min". */
    fun duration(seconds: Double): String {
        val totalMinutes = (seconds / 60.0).roundToLong()
        return when {
            totalMinutes < 1 -> "< 1 min"
            totalMinutes < 60 -> "$totalMinutes min"
            else -> {
                val h = totalMinutes / 60
                val m = totalMinutes % 60
                if (m == 0L) "${h}h" else "${h}h ${m}m"
            }
        }
    }

    /** Compact delta used for alternate-route labels: "+6 min". */
    fun durationDelta(seconds: Double): String {
        val minutes = (seconds / 60.0).roundToLong()
        return if (minutes <= 0) "same time" else "+$minutes min"
    }

    /** Clock time [seconds] from now, in the device's locale short format ("1:40 PM" / "13:40"). */
    fun arrivalTime(seconds: Double): String {
        val at = Date(System.currentTimeMillis() + (seconds * 1000).toLong())
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(at)
    }
}

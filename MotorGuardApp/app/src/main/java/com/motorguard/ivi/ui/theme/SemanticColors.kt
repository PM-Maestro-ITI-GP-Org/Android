package com.motorguard.ivi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.motorguard.ivi.data.vehicle.api.Severity

/**
 * Theme-aware semantic colors for diagnostics (success/caution/critical + the
 * "no-data" grey). Reads the project's [Tokens] day/night sets — never hardcode
 * a hex in the diagnostics UI; reach for these.
 */
object SemanticColors {

    val success: Color @Composable get() = color(Tokens.Day.success, Tokens.Night.success)
    val caution: Color @Composable get() = color(Tokens.Day.caution, Tokens.Night.caution)
    val critical: Color @Composable get() = color(Tokens.Day.critical, Tokens.Night.critical)

    /** Dot/border color for offline or not-yet-loaded signals. */
    val offline: Color @Composable get() = color(Color(0x662E3440), Color(0x66AFB9C5))

    @Composable
    fun forSeverity(sev: Severity?): Color = when (sev) {
        Severity.OK -> success
        Severity.CAUTION -> caution
        Severity.CRITICAL -> critical
        null -> offline
    }

    @Composable
    private fun color(day: Color, night: Color): Color =
        if (isSystemInDarkTheme()) night else day
}

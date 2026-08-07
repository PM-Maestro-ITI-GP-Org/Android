package com.motorguard.ivi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * The semantic trio from README §3 (🟢 ready · 🟡 caution · 🔴 critical), resolved for the
 * active Day/Night mode.
 *
 * `MaterialTheme.colorScheme` has no role for "success" or "caution", so without this
 * every screen ends up reaching straight for `Tokens.Night.success` and silently keeps
 * the night green in Day mode. Diagnostics severity, charge state and the call buttons
 * should all read from here.
 */
object Semantic {

    val success: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Tokens.Night.success else Tokens.Day.success

    val caution: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Tokens.Night.caution else Tokens.Day.caution

    val critical: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Tokens.Night.critical else Tokens.Day.critical

    /** Foreground for text/icons sitting on top of a filled semantic colour. */
    val onSemantic: Color
        @Composable @ReadOnlyComposable
        get() = Tokens.Night.base
}

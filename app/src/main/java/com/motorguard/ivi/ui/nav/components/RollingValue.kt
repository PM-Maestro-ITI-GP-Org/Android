package com.motorguard.ivi.ui.nav.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.Text
import com.motorguard.ivi.ui.nav.NavMotion

/**
 * A numeral that slides when it changes instead of hard-cutting — the "600 m" in the maneuver
 * card, the ETA, the remaining distance.
 *
 * Small thing, big effect: those values change every few hundred milliseconds while driving,
 * and an instant swap reads as a flicker. Falls back to a plain [Text] at
 * [com.motorguard.ivi.ui.nav.AnimationLevel.RESTRAINED], so the cost is opt-out.
 *
 * @param countsDown values that decrease (distance to a turn) slide the opposite way to values
 *        that increase, so the direction of travel is legible without reading the number.
 */
@Composable
fun RollingValue(
    value: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    countsDown: Boolean = true,
) {
    if (!NavMotion.rollingNumerals) {
        Text(text = value, style = style, modifier = modifier)
        return
    }

    AnimatedContent(
        targetState = value,
        transitionSpec = { rollTransform(countsDown) },
        label = "rolling-value",
        modifier = modifier,
    ) { shown ->
        Text(text = shown, style = style)
    }
}

private fun rollTransform(countsDown: Boolean): ContentTransform {
    val direction = if (countsDown) 1 else -1
    return (
        slideInVertically(NavMotion.swap()) { height -> direction * height / 2 } +
            fadeIn(NavMotion.swap())
        ) togetherWith (
        slideOutVertically(NavMotion.swap()) { height -> -direction * height / 2 } +
            fadeOut(NavMotion.swap())
        )
}

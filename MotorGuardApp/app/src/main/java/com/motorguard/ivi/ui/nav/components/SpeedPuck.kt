package com.motorguard.ivi.ui.nav.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorguard.ivi.ui.nav.NavMotion
import com.motorguard.ivi.ui.theme.MotorGuard

/**
 * Current road speed, top-right of the map. 70 dp circle, glass fill, coloured ring.
 *
 * The ring is semantic, not decorative: neutral at or below the limit, [caution] within the
 * tolerance, [critical] over it — the same green/amber/red language Diagnostics uses.
 *
 * @param limitKph the posted limit, when known. It is null today: Valhalla's `/route` response
 *        does not carry speed limits (that is the `trace_attributes` endpoint), so the ring
 *        stays neutral until that call is added. The colour logic is here so wiring it up is a
 *        one-argument change rather than a redesign.
 */
@Composable
fun SpeedPuck(
    speedKph: Int,
    modifier: Modifier = Modifier,
    limitKph: Int? = null,
) {
    val colors = MotorGuard.colors
    val ringTarget = when {
        limitKph == null -> colors.glassBorder
        speedKph > limitKph + OVER_LIMIT_TOLERANCE -> colors.critical
        speedKph > limitKph -> colors.caution
        else -> colors.success
    }
    val ring by animateColorAsState(ringTarget, NavMotion.settle(), label = "speed-ring")

    Box(
        modifier = modifier
            .size(70.dp)
            .clip(CircleShape)
            .background(colors.glass)
            .border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RollingValue(
                value = speedKph.toString(),
                countsDown = false,
                style = TextStyle(
                    fontSize = 23.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 23.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            )
            Text(text = "km/h", fontSize = 9.sp, color = colors.onBaseDim)
        }
    }
}

private const val OVER_LIMIT_TOLERANCE = 5

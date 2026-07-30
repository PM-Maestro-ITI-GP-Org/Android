package com.motorguard.ivi.ui.nav.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import com.motorguard.ivi.data.nav.NavFormat
import com.motorguard.ivi.data.nav.NavProgress
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.nav.NavMotion
import com.motorguard.ivi.ui.theme.MotorGuard

/**
 * The primary guidance readout, top-left of the map: turn glyph, distance to the maneuver,
 * and the instruction text. Mirrors the nav surface in MeowScreen.dc.html.
 *
 * Two animations, both earned:
 *  - the glyph **cross-scales** when the maneuver changes, so a new turn is felt rather than
 *    just read;
 *  - the icon swells slightly as the car closes on the turn (inside 150 m), which is the
 *    cheapest possible "act now" signal for a driver who is not looking at the text.
 */
@Composable
fun ManeuverCard(
    progress: NavProgress,
    modifier: Modifier = Modifier,
) {
    val colors = MotorGuard.colors
    val imminent = progress.distanceToManeuverMeters < IMMINENT_M

    val urgency by animateFloatAsState(
        targetValue = if (imminent && NavMotion.rollingNumerals) IMMINENT_SCALE else 1f,
        animationSpec = NavMotion.settle(),
        label = "maneuver-urgency",
    )

    GlassCard(
        modifier = modifier.widthIn(min = 300.dp, max = 460.dp),
        shape = RoundedCornerShape(24.dp),
        padding = PaddingValues(horizontal = 26.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = progress.currentStep.maneuver,
                transitionSpec = {
                    (scaleIn(NavMotion.swap(), initialScale = 0.7f) + fadeIn(NavMotion.swap()))
                        .togetherWith(
                            scaleOut(NavMotion.swap(), targetScale = 1.25f) + fadeOut(NavMotion.swap()),
                        )
                },
                label = "maneuver-glyph",
            ) { maneuver ->
                Icon(
                    imageVector = maneuverIcon(maneuver),
                    contentDescription = null,
                    tint = if (imminent) colors.accent2 else colors.accent,
                    modifier = Modifier
                        .size(46.dp)
                        .scale(urgency),
                )
            }

            Spacer(Modifier.width(18.dp))

            Column(verticalArrangement = Arrangement.Center) {
                RollingValue(
                    value = NavFormat.maneuverDistance(progress.distanceToManeuverMeters),
                    style = TextStyle(
                        fontSize = 34.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 34.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                )
                Spacer(Modifier.size(3.dp))
                Text(
                    text = progress.currentStep.instruction,
                    fontSize = 14.sp,
                    color = colors.onBaseDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The "then…" chip under the maneuver card. Only shown when the following turn arrives soon
 * after this one — that is when a driver actually needs it, and it stays out of the way
 * otherwise.
 */
@Composable
fun FollowingManeuverChip(
    progress: NavProgress,
    modifier: Modifier = Modifier,
) {
    val following = progress.followingStep ?: return
    if (progress.distanceToManeuverMeters > CHAIN_HINT_M) return
    val colors = MotorGuard.colors

    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        padding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        soft = true,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "then", fontSize = 13.sp, color = colors.onBaseDim)
            Spacer(Modifier.width(12.dp))
            Icon(
                imageVector = maneuverIcon(following.maneuver),
                contentDescription = null,
                tint = colors.onBaseDim,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = NavFormat.distance(following.distanceMeters),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private const val IMMINENT_M = 150.0
private const val IMMINENT_SCALE = 1.12f
private const val CHAIN_HINT_M = 400.0

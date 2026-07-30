package com.motorguard.ivi.ui.nav.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.motorguard.ivi.data.nav.Place
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.nav.NavMotion
import com.motorguard.ivi.ui.theme.MotorGuard

/**
 * The idle affordance: a glass pill that says where you could go. Tapping it opens
 * [SearchPanel].
 */
@Composable
fun SearchPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MotorGuard.colors
    GlassCard(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(28.dp),
        padding = PaddingValues(horizontal = 26.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = "Where to?",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Destination search: a text field and its results, as a left-hand column over the map.
 *
 * The panel takes a third of the width rather than covering the screen so the map stays
 * visible — picking a destination is a spatial decision, and hiding the map to make it is the
 * kind of thing that reads fine in a mockup and badly in a car.
 */
@Composable
fun SearchPanel(
    query: String,
    results: List<Place>,
    loading: Boolean,
    onQueryChange: (String) -> Unit,
    onPick: (Place) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MotorGuard.colors
    val focusRequester = remember { FocusRequester() }

    // Opening the panel should put the cursor in the field: one tap to search, not two.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        padding = PaddingValues(horizontal = 22.dp, vertical = 20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.width(14.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                text = "Search a place or address",
                                fontSize = 20.sp,
                                color = colors.onBaseDim,
                            )
                        }
                        inner()
                    },
                )
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(colors.chip)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close search",
                        tint = colors.onBaseDim,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.glassBorder),
            )

            AnimatedVisibility(visible = loading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        color = colors.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            if (!loading && results.isEmpty() && query.length >= 2) {
                Text(
                    text = "No matches",
                    fontSize = 14.sp,
                    color = colors.onBaseDim,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            }

            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(results, key = { "${it.name}-${it.point.lat}-${it.point.lon}" }) { place ->
                    ResultRow(place = place, onClick = { onPick(place) })
                }
            }
        }
    }
}

@Composable
private fun ResultRow(place: Place, onClick: () -> Unit) {
    val colors = MotorGuard.colors

    // Press feedback as a scale rather than only a ripple: at 76 dp touch targets in a moving
    // car, a shape that reacts is easier to confirm than a colour that does.
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = NavMotion.swap(),
        label = "result-press",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(vertical = 14.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(colors.chip),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = placeIcon(place.category),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = place.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (place.subtitle.isNotBlank()) {
                Text(
                    text = place.subtitle,
                    fontSize = 12.sp,
                    color = colors.onBaseDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

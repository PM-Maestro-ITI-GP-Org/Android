package com.motorguard.ivi.ui.nav.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import com.motorguard.ivi.ui.nav.SearchField
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
    origin: Place?,
    destination: Place?,
    active: SearchField,
    query: String,
    results: List<Place>,
    loading: Boolean,
    canUseCurrentLocation: Boolean,
    onQueryChange: (String) -> Unit,
    onActivate: (SearchField) -> Unit,
    onPick: (Place) -> Unit,
    onUseCurrentLocation: () -> Unit,
    onSwap: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MotorGuard.colors
    val focusRequester = remember { FocusRequester() }

    // Focus follows the active field, so switching endpoints needs one tap, not two. Keyed on
    // `active` so it re-runs when the caret moves between the two rows.
    LaunchedEffect(active) { runCatching { focusRequester.requestFocus() } }

    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        padding = PaddingValues(horizontal = 22.dp, vertical = 20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    EndpointField(
                        marker = { OriginMarker() },
                        active = active == SearchField.ORIGIN,
                        value = origin?.name ?: "Your location",
                        // "Your location" is a real value, not a prompt — it should not look
                        // like unfilled placeholder text.
                        muted = false,
                        placeholder = "Choose a starting point",
                        query = query,
                        focusRequester = focusRequester,
                        onQueryChange = onQueryChange,
                        onActivate = { onActivate(SearchField.ORIGIN) },
                    )

                    EndpointConnector()

                    EndpointField(
                        marker = { DestinationMarker() },
                        active = active == SearchField.DESTINATION,
                        value = destination?.name.orEmpty(),
                        muted = destination == null,
                        placeholder = "Search a place or address",
                        query = query,
                        focusRequester = focusRequester,
                        onQueryChange = onQueryChange,
                        onActivate = { onActivate(SearchField.DESTINATION) },
                    )
                }

                Spacer(Modifier.width(12.dp))
                RoundIconButton(
                    icon = Icons.Filled.SwapVert,
                    contentDescription = "Swap start and destination",
                    onClick = onSwap,
                )
                Spacer(Modifier.width(8.dp))
                RoundIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = "Close search",
                    onClick = onDismiss,
                )
            }

            Spacer(Modifier.height(10.dp))
            SearchActivityRail(loading = loading)

            if (!loading && results.isEmpty() && query.length >= 2) {
                Text(
                    text = "No matches",
                    fontSize = 14.sp,
                    color = colors.onBaseDim,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            }

            LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                // Pinned first when choosing a start: the answer is "here" far more often than
                // it is any of the search results below it.
                if (active == SearchField.ORIGIN && canUseCurrentLocation) {
                    item(key = "current-location") {
                        CurrentLocationRow(onClick = onUseCurrentLocation)
                    }
                }
                items(results, key = { "${it.name}-${it.point.lat}-${it.point.lon}" }) { place ->
                    ResultRow(place = place, onClick = { onPick(place) })
                }
            }
        }
    }
}

/**
 * One endpoint row: marker, then either a live text field (when it is the one being edited) or
 * the committed value (when it is not, tappable to take the caret).
 *
 * Only the active row hosts a `BasicTextField`. Two fields sharing one query string would
 * otherwise fight over focus and echo each other's text.
 */
@Composable
private fun EndpointField(
    marker: @Composable () -> Unit,
    active: Boolean,
    value: String,
    muted: Boolean,
    placeholder: String,
    query: String,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onActivate: () -> Unit,
) {
    val colors = MotorGuard.colors
    val background by animateColorAsState(
        targetValue = if (active) colors.accent.copy(alpha = 0.10f) else Color.Transparent,
        animationSpec = NavMotion.settle(),
        label = "endpoint-active",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .then(if (active) Modifier else Modifier.clickable(onClick = onActivate))
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(26.dp), contentAlignment = Alignment.Center) { marker() }
        Spacer(Modifier.width(14.dp))

        if (active) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 19.sp,
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
                        Text(text = placeholder, fontSize = 19.sp, color = colors.onBaseDim)
                    }
                    inner()
                },
            )
        } else {
            Text(
                text = value.ifBlank { placeholder },
                fontSize = 19.sp,
                fontWeight = FontWeight.Medium,
                color = if (muted || value.isBlank()) colors.onBaseDim else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Hollow accent ring — the "you are starting here" dot. */
@Composable
private fun OriginMarker() {
    val colors = MotorGuard.colors
    Canvas(modifier = Modifier.size(14.dp)) {
        drawCircle(color = colors.accent, radius = size.minDimension / 2f, style = Stroke(width = size.minDimension / 4f))
    }
}

@Composable
private fun DestinationMarker() {
    Icon(
        imageVector = Icons.Filled.Place,
        contentDescription = null,
        tint = MotorGuard.colors.accent,
        modifier = Modifier.size(22.dp),
    )
}

/** The dotted run between the two markers. Aligned to the 26 dp marker column. */
@Composable
private fun EndpointConnector() {
    val colors = MotorGuard.colors
    Row(modifier = Modifier.padding(start = 10.dp)) {
        Column(
            modifier = Modifier.size(width = 26.dp, height = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            repeat(3) {
                Box(
                    Modifier
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(colors.onBaseDim),
                )
            }
        }
    }
}

@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colors = MotorGuard.colors
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(colors.chip)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = colors.onBaseDim,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun CurrentLocationRow(onClick: () -> Unit) {
    val colors = MotorGuard.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(colors.accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.MyLocation,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = "Your location",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.accent,
        )
    }
}

/**
 * The divider under the search field, doubling as the busy indicator.
 *
 * A spinner that appears and disappears would add and remove 50 dp on every keystroke, shoving
 * the results list up and down the whole time you type. This is a fixed-height rail: it is the
 * divider when idle and a sweeping accent bar when a request is in flight, so nothing ever
 * relayouts. The sweep is a `translationX` on a fixed-width child — a transform, not a resize.
 */
@Composable
private fun SearchActivityRail(loading: Boolean) {
    val colors = MotorGuard.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(colors.glassBorder),
    ) {
        if (loading) {
            val transition = rememberInfiniteTransition(label = "search-rail")
            val progress by transition.animateFloat(
                initialValue = -RAIL_SWEEP_FRACTION,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
                label = "search-rail-sweep",
            )
            Box(
                Modifier
                    .fillMaxWidth(RAIL_SWEEP_FRACTION)
                    .height(2.dp)
                    .graphicsLayer {
                        // The layer's own width is RAIL_SWEEP_FRACTION of the parent, so the
                        // parent width is size.width / RAIL_SWEEP_FRACTION.
                        translationX = (size.width / RAIL_SWEEP_FRACTION) * progress
                    }
                    .clip(RoundedCornerShape(1.dp))
                    .background(colors.accent),
            )
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

/** Width of the sweeping bar in the activity rail, as a fraction of the panel. */
private const val RAIL_SWEEP_FRACTION = 0.35f

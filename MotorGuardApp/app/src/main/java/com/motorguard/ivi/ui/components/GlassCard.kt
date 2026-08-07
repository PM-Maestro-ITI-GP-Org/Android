package com.motorguard.ivi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The glass surface from README §4 — a translucent panel with a hairline highlight edge.
 *
 * Deliberately **not** a live `RenderEffect` blur: the perf budget allows one blurred
 * backdrop per screen, and on the Pi 5's VideoCore VII a blur behind every card in a
 * scrolling list is the first thing to drop frames. Translucency over the base gives the
 * same read at a fraction of the fill cost. Screens that want a real blur should apply it
 * once, to their own backdrop, behind these cards.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

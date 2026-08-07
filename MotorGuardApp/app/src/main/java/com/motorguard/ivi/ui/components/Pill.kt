package com.motorguard.ivi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Small status pill (e.g. "Caution" / "Critical" / "Open") matching the Motor Guard
 * prototype. Intended bg = a Tokens semantic color with ~14-20% alpha; text = full color.
 */
@Composable
fun Pill(
    text: String,
    bg: Color,
    modifier: Modifier = Modifier,
    fg: Color = bg,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(bg.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .heightIn(min = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (fg == bg) fg.copy(alpha = 1f) else fg,
        )
    }
}

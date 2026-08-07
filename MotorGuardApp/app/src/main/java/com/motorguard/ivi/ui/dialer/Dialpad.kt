package com.motorguard.ivi.ui.dialer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class Key(val digit: Char, val letters: String)

// A real space, not "" — an empty string measures shorter than a line of
// text, which is what threw digit '1' out of line with '2'/'3' in the first
// place. A space keeps every key's letters row the same height.
private val rows = listOf(
    listOf(Key('1', " "), Key('2', "ABC"), Key('3', "DEF")),
    listOf(Key('4', "GHI"), Key('5', "JKL"), Key('6', "MNO")),
    listOf(Key('7', "PQRS"), Key('8', "TUV"), Key('9', "WXYZ")),
    listOf(Key('*', " "), Key('0', "+"), Key('#', " ")),
)

/**
 * 3×4 pad. [keySize] never goes below the 76 dp touch minimum in README §2 — the caller
 * shrinks it to 76 dp on the 1024×600 reflow and no further.
 *
 * Long-pressing 0 emits `+` for international numbers, the convention every driver
 * already knows from their handset.
 */
@Composable
fun Dialpad(
    onPress: (Char) -> Unit,
    modifier: Modifier = Modifier,
    keySize: Dp = 88.dp,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { key ->
                    DialKey(
                        key = key,
                        size = keySize,
                        onPress = onPress,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialKey(
    key: Key,
    size: Dp,
    onPress: (Char) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.07f))
            .combinedClickable(
                onClick = { onPress(key.digit) },
                onLongClick = { if (key.digit == '0') onPress('+') },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = key.digit.toString(),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = key.letters,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                fontSize = 10.sp,
                letterSpacing = 1.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

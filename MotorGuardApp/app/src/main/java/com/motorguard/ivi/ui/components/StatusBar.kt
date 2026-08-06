package com.motorguard.ivi.ui.components

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorguard.ivi.data.Conn
import com.motorguard.ivi.ui.theme.MotorGuard
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Top status bar: live connectivity, driver, date and clock.
 *
 * This used to be four static glyphs — the icons were drawn whatever the radios were actually
 * doing, so the Wi-Fi symbol looked identical whether the car was online or not. Every indicator
 * here now reads real state from [Conn], the same source the Settings panes use, so the bar
 * cannot disagree with them.
 *
 * The cellular indicator is gone rather than faked. This board has no modem — the platform does
 * not even report `telephony.subscription` — and a permanently grey signal icon is worse than
 * no icon at all. It is drawn only where the hardware exists.
 *
 * Each live indicator carries its name (the SSID, the phone) beside it. On a dashboard the
 * useful question is "which network am I on", not "is there a network", and the answer is short
 * enough to fit.
 */
@Composable
fun StatusBar(modifier: Modifier = Modifier) {
    val colors = MotorGuard.colors
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val context = LocalContext.current
    val hasTelephony = remember(context) { context.hasTelephony() }

    val wifi = Conn.wifi
    val bt = Conn.bt

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        if (hasTelephony) {
            Indicator(
                icon = Icons.Filled.Wifi,
                label = "Signal",
                active = false,
                accent = colors.accent,
                dim = dim,
            )
            Spacer(Modifier.width(18.dp))
        }

        // --- Wi-Fi ---------------------------------------------------------------------------
        val ssid = wifi.connectedSsid
        Indicator(
            icon = when {
                !wifi.enabled -> Icons.Filled.WifiOff
                else -> Icons.Filled.Wifi
            },
            label = "Wi-Fi",
            active = wifi.enabled && ssid != null,
            accent = colors.accent,
            dim = dim,
            text = ssid.takeIf { wifi.enabled },
        )
        Spacer(Modifier.width(18.dp))

        // --- Bluetooth -----------------------------------------------------------------------
        val phone = bt.connectedName
        Indicator(
            icon = when {
                !bt.enabled -> Icons.Filled.BluetoothDisabled
                phone != null -> Icons.Filled.BluetoothConnected
                else -> Icons.Filled.Bluetooth
            },
            label = "Bluetooth",
            active = bt.enabled && phone != null,
            accent = colors.accent,
            dim = dim,
            text = phone,
        )

        Spacer(Modifier.width(20.dp))
        Divider(dim)
        Spacer(Modifier.width(20.dp))

        Icon(
            imageVector = Icons.Filled.Person,
            contentDescription = "Driver",
            tint = dim,
            modifier = Modifier.size(20.dp),
        )

        Spacer(Modifier.width(18.dp))
        Text(text = liveDate(), fontSize = 15.sp, color = dim)
        Spacer(Modifier.width(14.dp))
        Text(
            text = liveClock(),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * One indicator: icon, optionally followed by what it is connected to.
 *
 * The colour is the state. Accent means "this is doing something for you right now"; dim means
 * present but idle — a distinction that reads at a glance without needing the label.
 */
@Composable
private fun Indicator(
    icon: ImageVector,
    label: String,
    active: Boolean,
    accent: Color,
    dim: Color,
    text: String? = null,
) {
    val tint by animateColorAsState(if (active) accent else dim, label = "status-tint")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        if (!text.isNullOrBlank()) {
            Spacer(Modifier.width(7.dp))
            Text(
                text = text,
                fontSize = 14.sp,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Bounded so a long SSID cannot push the clock off the end of the bar.
                modifier = Modifier.widthIn(max = 150.dp),
            )
        }
    }
}

/** Hairline between the radios and the driver/clock cluster, so the bar reads as two groups. */
@Composable
private fun Divider(color: Color) {
    Box(
        Modifier
            .size(width = 1.dp, height = 18.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(color.copy(alpha = 0.35f)),
    )
}

private fun Context.hasTelephony(): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION)

/** Current date as "EEE, d MMM" (e.g. "Mon, 26 Jul"), refreshed each minute. */
@Composable
private fun liveDate(): String = liveFormatted("EEE, d MMM", 60_000)

/** Current time as HH:mm, refreshed every 10 s. */
@Composable
private fun liveClock(): String = liveFormatted("HH:mm", 10_000)

@Composable
private fun liveFormatted(pattern: String, everyMs: Long): String {
    val fmt = remember(pattern) { SimpleDateFormat(pattern, Locale.getDefault()) }
    val value by produceState(initialValue = fmt.format(Date()), fmt, everyMs) {
        while (true) {
            this.value = fmt.format(Date())
            delay(everyMs)
        }
    }
    return value
}

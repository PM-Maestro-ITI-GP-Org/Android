package com.motorguard.ivi.ui.dialer

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorguard.ivi.data.CallDirection
import com.motorguard.ivi.data.CallLogEntry
import com.motorguard.ivi.data.Contact
import com.motorguard.ivi.data.PhoneLink
import com.motorguard.ivi.ui.components.GlassCard
import com.motorguard.ivi.ui.theme.Semantic
import com.motorguard.ivi.ui.theme.Tokens

/**
 * Phone tab. Two panes on the 1920×720 dash — pad on the left, people on the right — so
 * neither ever scrolls out of reach while driving. Reflows to a narrower pad and 76 dp
 * keys under 1100 dp of width (the 1024×600 target).
 *
 * A live call is rendered by MainActivity's call_overlay, on top of every tab, not just
 * this one — so this screen only ever needs to draw the idle dialer/contacts surface.
 */
@Composable
fun DialerScreen(vm: DialerViewModel) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        DialerHome(vm)
    }
}

@Composable
private fun DialerHome(vm: DialerViewModel) {
    val link by vm.repo.link.collectAsState()
    val device by vm.repo.deviceName.collectAsState()
    val contacts by vm.repo.contacts.collectAsState()
    val recents by vm.repo.recents.collectAsState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 1100.dp
        val padWidth = if (compact) 396.dp else 520.dp
        val keySize = if (compact) 76.dp else 88.dp

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            GlassCard(
                modifier = Modifier
                    .width(padWidth)
                    .fillMaxHeight(),
            ) {
                PadPane(vm, keySize)
            }

            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinkChip(link, device)
                    Spacer(Modifier.weight(1f))
                    TabStrip(vm)
                }
                Spacer(Modifier.height(16.dp))
                SearchField(vm)
                Spacer(Modifier.height(12.dp))
                GlassCard(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    padding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    PeopleList(vm, link, contacts, recents)
                }
            }
        }
    }
}

// --- left pane ------------------------------------------------------------

@Composable
private fun PadPane(vm: DialerViewModel, keySize: Dp) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = vm.digits.ifEmpty { "Enter a number" },
                color = if (vm.digits.isEmpty()) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
                fontSize = if (vm.digits.length > 14) 26.sp else 34.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (vm.digits.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .clickable { vm.backspace() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Backspace,
                        contentDescription = "Delete last digit",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Dialpad(onPress = vm::press, keySize = keySize)
        Spacer(Modifier.height(20.dp))

        CallButton(
            enabled = vm.digits.isNotEmpty(),
            onClick = vm::dialTyped,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CallButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val green = Semantic.success
    Box(
        modifier = modifier
            .height(76.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (enabled) green else green.copy(alpha = 0.22f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Call,
            contentDescription = "Call",
            tint = if (enabled) Semantic.onSemantic else Tokens.Night.onBaseDim,
            modifier = Modifier.size(30.dp),
        )
    }
}

// --- right pane -----------------------------------------------------------

@Composable
private fun LinkChip(link: PhoneLink, device: String?) {
    val connected = link == PhoneLink.CONNECTED
    val tint = when (link) {
        PhoneLink.CONNECTED -> Semantic.success
        PhoneLink.CONNECTING -> Semantic.caution
        PhoneLink.DISCONNECTED -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (connected) Icons.Filled.Bluetooth else Icons.Filled.BluetoothDisabled,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = when (link) {
                PhoneLink.CONNECTED -> device ?: "Phone connected"
                PhoneLink.CONNECTING -> "Connecting…"
                PhoneLink.DISCONNECTED -> "No phone connected"
            },
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun TabStrip(vm: DialerViewModel) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DialerViewModel.ListTab.entries.forEach { tab ->
            val selected = vm.listTab == tab
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        else Color.Transparent,
                    )
                    .clickable { vm.listTab = tab }
                    .height(68.dp)
                    .width(150.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tab.label,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    },
                    fontSize = 16.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun SearchField(vm: DialerViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (vm.searchQuery.isEmpty()) {
                Text(
                    text = "Search contacts",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    fontSize = 17.sp,
                )
            }
            BasicTextField(
                value = vm.searchQuery,
                onValueChange = { vm.searchQuery = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 17.sp,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (vm.searchQuery.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable { vm.searchQuery = "" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Clear search",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun PeopleList(
    vm: DialerViewModel,
    link: PhoneLink,
    contacts: List<Contact>,
    recents: List<CallLogEntry>,
) {
    val query = vm.searchQuery.trim()

    val empty: String = when {
        link == PhoneLink.DISCONNECTED -> "No phone connected. Pair one in Settings › Bluetooth."
        query.isNotEmpty() -> "No matches for \"$query\"."
        vm.listTab == DialerViewModel.ListTab.FAVOURITES -> "Star someone on your phone and they show up here."
        vm.listTab == DialerViewModel.ListTab.RECENTS -> "No calls yet."
        else -> "Contacts sync from your phone once it is paired."
    }

    val rows: List<Any> = when (vm.listTab) {
        DialerViewModel.ListTab.FAVOURITES -> contacts.filter { it.favorite }
        DialerViewModel.ListTab.RECENTS -> recents
        DialerViewModel.ListTab.CONTACTS -> contacts
    }.filter { row ->
        if (query.isEmpty()) {
            true
        } else {
            when (row) {
                is Contact -> row.name.contains(query, ignoreCase = true) || row.number.contains(query)
                is CallLogEntry -> (row.name?.contains(query, ignoreCase = true) ?: false) ||
                    row.number.contains(query)
                else -> true
            }
        }
    }

    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = empty,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                fontSize = 17.sp,
            )
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        items(rows) { row ->
            when (row) {
                is Contact -> PersonRow(
                    initials = row.initials,
                    title = row.name,
                    subtitle = row.number,
                    icon = null,
                    onClick = { vm.dial(row.number, row.name) },
                )

                is CallLogEntry -> PersonRow(
                    initials = row.name?.let { Contact(0, it, row.number).initials } ?: "#",
                    title = row.label,
                    subtitle = "${row.number}  ·  " + DateUtils.getRelativeTimeSpanString(
                        row.timestampMillis,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    ),
                    icon = when (row.direction) {
                        CallDirection.INCOMING -> Icons.Filled.CallReceived
                        CallDirection.OUTGOING -> Icons.Filled.CallMade
                        CallDirection.MISSED -> Icons.Filled.CallMissed
                    },
                    iconTint = if (row.direction == CallDirection.MISSED) Semantic.critical else null,
                    onClick = { vm.dial(row.number, row.name) },
                )
            }
        }
    }
}

@Composable
private fun PersonRow(
    initials: String,
    title: String,
    subtitle: String,
    icon: ImageVector?,
    iconTint: Color? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initials,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    fontSize = 14.sp,
                    maxLines = 1,
                )
            }
        }

        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Semantic.success.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Call,
                contentDescription = "Call $title",
                tint = Semantic.success,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

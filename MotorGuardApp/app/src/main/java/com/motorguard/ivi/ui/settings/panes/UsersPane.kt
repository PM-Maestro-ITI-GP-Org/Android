package com.motorguard.ivi.ui.settings.panes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorguard.ivi.data.Conn
import com.motorguard.ivi.data.UserProfile
import com.motorguard.ivi.ui.components.RowDivider
import com.motorguard.ivi.ui.components.SectionCard
import com.motorguard.ivi.ui.components.SettingRow

@Composable
fun UsersPane() {
    val repo = Conn.users
    var menuFor by remember { mutableStateOf<UserProfile?>(null) }
    var addUser by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        SectionCard(title = "Driver profiles") {
            repo.users.forEachIndexed { i, user ->
                UserRow(
                    user = user,
                    onClick = { if (!user.isActive) repo.switchTo(user.id) },
                    onLongClick = { menuFor = user },
                )
                if (i < repo.users.lastIndex) RowDivider()
            }
        }

        SectionCard {
            SettingRow(
                title = "Add driver",
                subtitle = "Create a new profile",
                leading = Icons.Filled.PersonAdd,
                onClick = { addUser = true },
            )
            RowDivider()
            SettingRow(
                title = "Add guest",
                subtitle = "A temporary profile",
                leading = Icons.Filled.PersonOutline,
                onClick = { repo.addGuest() },
            )
        }
    }

    // Long-press: switch · remove.
    menuFor?.let { user ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(user.name) },
            text = { Text(if (user.isActive) "Active profile" else if (user.isGuest) "Guest" else "Driver profile") },
            confirmButton = {
                Column {
                    if (!user.isActive) {
                        TextButton(onClick = { repo.switchTo(user.id); menuFor = null }) { Text("Switch to") }
                    }
                    TextButton(onClick = { repo.removeUser(user.id); menuFor = null }) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { menuFor = null }) { Text("Cancel") }
            },
        )
    }

    // Add-driver dialog.
    if (addUser) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addUser = false },
            title = { Text("Add driver") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        repo.addUser(name.trim())
                        addUser = false
                    },
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { addUser = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * A driver-profile row: colored avatar · name/status · active check. Mirrors [SettingRow]'s
 * layout but swaps the leading icon for a per-profile avatar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserRow(
    user: UserProfile,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(user)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = user.name,
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = when {
                    user.isActive -> "Active"
                    user.isGuest -> "Guest"
                    else -> "Tap to switch"
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
        if (user.isActive) {
            Spacer(Modifier.width(12.dp))
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Active",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Small circular avatar with the driver's initial, tinted from the theme accent set. */
@Composable
private fun Avatar(user: UserProfile) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(avatarColor(user.color)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = user.initial,
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Rotates through theme colors so profiles are visually distinguishable (no hardcoded hex). */
@Composable
private fun avatarColor(index: Int): Color {
    val scheme = MaterialTheme.colorScheme
    val palette = listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.error)
    return palette[index % palette.size]
}

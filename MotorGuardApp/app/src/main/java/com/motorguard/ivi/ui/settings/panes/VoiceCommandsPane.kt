package com.motorguard.ivi.ui.settings.panes

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorguard.ivi.data.VoiceCommands
import com.motorguard.ivi.ui.theme.MotorGuard

/** One thing you can say, and what it does. */
private data class VoiceExample(val phrase: String, val does: String)

/** A group of related commands, shown as a collapsible card in the reference list below. */
private data class VoiceCategory(val title: String, val examples: List<VoiceExample>)

/**
 * The reference list of everything Vega already understands, grouped the way the handlers
 * themselves are organised (MediaVoice, NavVoice, PhoneVoice, MotorVoice, ThemeVoice, and the
 * tab-opening fallback in IntentMatcher's BUILT_IN map) so this stays a description of the real
 * dispatch order rather than a wishlist that drifts from it.
 */
private val VOICE_CATEGORIES = listOf(
    VoiceCategory(
        title = "Music & media",
        examples = listOf(
            VoiceExample("Play music", "Resumes whatever's loaded, or tries Library, then Bluetooth, then Radio, then YouTube Music, in that order."),
            VoiceExample("Pause / Play", "Pauses or resumes the current track."),
            VoiceExample("Next song / Previous song", "Skips forward or back."),
            VoiceExample("Shuffle", "Toggles shuffle."),
            VoiceExample("Repeat", "Cycles repeat: off → all → one."),
            VoiceExample("Turn it up / Turn it down", "Adjusts volume."),
            VoiceExample("Mute / Unmute", "Mutes or unmutes."),
            VoiceExample("What's playing?", "Reads back the track and artist."),
            VoiceExample("Play from my library / USB / Bluetooth / the radio / YouTube Music", "Switches to that source and starts it, if it has anything to play."),
        ),
    ),
    VoiceCategory(
        title = "Navigation",
        examples = listOf(
            VoiceExample("Take me to <place>", "Searches, routes, and starts guidance to the first result — the name is always read back so you can catch a wrong match."),
            VoiceExample("How far is it? / How long until we arrive?", "Reads the remaining distance or ETA of the active route."),
            VoiceExample("Cancel the route", "Ends guidance."),
        ),
    ),
    VoiceCategory(
        title = "Phone",
        examples = listOf(
            VoiceExample("Call <name or number>", "Looks the contact up and dials, or dials spoken digits directly."),
            VoiceExample("Call them back / Redial", "Calls the most recent recent-call entry."),
            VoiceExample("Answer / Decline", "Answers a ringing call, or declines it."),
            VoiceExample("Hang up", "Ends the current call."),
            VoiceExample("Who's calling?", "Reads back the caller's name."),
            VoiceExample("Mute the call / Unmute the call", "Mutes or unmutes your mic on the call."),
            VoiceExample("Put the call on hold / Take them off hold", "Holds or resumes the call."),
        ),
    ),
    VoiceCategory(
        title = "Vehicle & motor",
        examples = listOf(
            VoiceExample("Is the motor okay? / Is anything wrong with the motor?", "Reads the diagnostics unit's current fault summary."),
            VoiceExample("Is it electrical or mechanical?", "Names the fault's classification."),
            VoiceExample("How bad is it? / Is it safe to keep driving?", "Reads the fault's severity and advice."),
            VoiceExample("How long has the motor got left?", "Reads the estimated remaining useful life."),
            VoiceExample("Is anything wrong with the vehicle? / Show me the warning lights", "Opens the Diagnostics tab."),
        ),
    ),
    VoiceCategory(
        title = "Display",
        examples = listOf(
            VoiceExample("Night mode / Day mode", "Switches the theme."),
            VoiceExample("Switch automatically", "Lets the theme follow the time of day."),
        ),
    ),
    VoiceCategory(
        title = "Open a screen",
        examples = listOf(
            VoiceExample("Open settings / Connect to Wi-Fi / Pair my phone", "Opens the Settings tab."),
            VoiceExample("Watch a video / Open YouTube", "Opens the Video tab."),
            VoiceExample("Put some music on / Play my playlist", "Opens the Media tab to browse."),
        ),
    ),
)

/**
 * Teach the assistant a phrase and what to answer, plus a reference list of everything it
 * already understands without being taught.
 *
 * The reasoning core is compiled in and cannot learn anything from the car, so without
 * the teaching section the only way to change what Vega says is a rebuild. Everything taught here
 * is matched ahead of that core, which means a phrase added on this screen also *overrides* a
 * built-in answer the owner did not like.
 */
@Composable
fun VoiceCommandsPane() {
    val colors = MotorGuard.colors
    val commands by VoiceCommands.commands.collectAsState()

    var trigger by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val expanded = remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = "Voice commands",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Say the phrase and Vega replies with your answer. " +
                    "Matching ignores case and punctuation, and the phrase only has to appear " +
                    "somewhere in what you say.",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = colors.onBaseDim,
            )

            Spacer(Modifier.height(18.dp))

            // --- add ---------------------------------------------------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .padding(16.dp),
            ) {
                OutlinedTextField(
                    value = trigger,
                    onValueChange = { trigger = it; error = null },
                    label = { Text("When I say") },
                    placeholder = { Text("tyre pressure") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = reply,
                    onValueChange = { reply = it; error = null },
                    label = { Text("Vega answers") },
                    placeholder = { Text("All four tyres were last checked on Tuesday.") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(text = it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.accent.copy(alpha = 0.16f))
                        .clickable {
                            error = when {
                                trigger.isBlank() || reply.isBlank() ->
                                    "Both a phrase and an answer are needed."
                                VoiceCommands.add(trigger, reply) == null ->
                                    "That phrase is already taught."
                                else -> {
                                    trigger = ""; reply = ""
                                    null
                                }
                            }
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add command", color = colors.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(Modifier.height(24.dp))

            // --- reference guide ----------------------------------------------
            Text(
                text = "What can I say?",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Everything below already works, without teaching it anything.",
                fontSize = 13.sp,
                color = colors.onBaseDim,
            )
            Spacer(Modifier.height(10.dp))
        }

        items(VOICE_CATEGORIES, key = { it.title }) { category ->
            val isOpen = expanded.value == category.title
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .animateContentSize(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded.value = if (isOpen) null else category.title }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = category.title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${category.examples.size} commands",
                            fontSize = 12.sp,
                            color = colors.onBaseDim,
                        )
                    }
                    Icon(
                        if (isOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = colors.onBaseDim,
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (isOpen) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        category.examples.forEach { example ->
                            Column {
                                Text(
                                    text = "“${example.phrase}”",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.accent,
                                )
                                Text(
                                    text = example.does,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    color = colors.onBaseDim,
                                )
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }

        // --- taught list ---------------------------------------------------
        item {
            if (commands.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.RecordVoiceOver,
                            contentDescription = null,
                            tint = colors.onBaseDim,
                            modifier = Modifier.size(34.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("Nothing taught yet", color = colors.onBaseDim, fontSize = 15.sp)
                    }
                }
            } else {
                Text(
                    text = "${commands.size} taught",
                    fontSize = 13.sp,
                    color = colors.onBaseDim,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        items(commands, key = { it.id }) { command ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "“${command.trigger}”",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = command.reply,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = colors.onBaseDim,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable { VoiceCommands.remove(command.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete ${command.trigger}",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

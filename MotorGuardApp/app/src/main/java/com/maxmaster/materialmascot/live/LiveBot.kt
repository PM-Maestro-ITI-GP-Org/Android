/*
 * Material Mascot — a living bot avatar for Android apps.
 * Copyright (C) 2026 Max Master
 * SPDX-License-Identifier: MIT
 */

package com.maxmaster.materialmascot.live

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxmaster.materialmascot.config.MascotConfig
import com.maxmaster.materialmascot.engine.BotState
import com.maxmaster.materialmascot.ui.MaterialBot

/**
 * THE companion bot of an app. Wrap [MaterialBot] once and render this
 * everywhere instead, so exactly one place decides what the bot is feeling:
 *
 *  1. A shake hard enough to dizzy it always wins (Silly face + wobble).
 *  2. An explicit [state] override from a screen that knows better
 *     (Thinking while refreshing, Narrating in the player, ...).
 *  3. Otherwise the shared status bus ([LocalMascotStatus]) drives the mood,
 *     so one begin() call moves every instance on screen at once.
 *
 * The wobble modifier is applied here unconditionally, which is what makes
 * even bots that never heard of the sensor react to vibrations.
 *
 * This is the recommended entry point for apps that want the full "living
 * companion" behavior with shake reactions and status-bus-driven mood.
 * For full control, use [MaterialBot] directly with [MascotConfig].
 *
 * @param config Host-owned immutable configuration.
 * @param modifier Layout modifier.
 * @param state Optional explicit state override (takes precedence over status bus).
 * @param contentDescription Accessibility label; null omits the semantics node.
 */
@Composable
public fun LiveBot(
    config: MascotConfig,
    modifier: Modifier = Modifier,
    state: BotState? = null,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null
) {
    // App-wide operations speak through the status bus; screens may shout
    // over it with an explicit state, but nobody escapes being dizzy.
    val bus = LocalMascotStatus.current
    val busState = bus?.state?.collectAsStateWithLifecycle()?.value
    val statusDriven = if (busState == null || busState.status == MascotStatus.IDLE) {
        config.state
    } else {
        when (busState.status) {
            MascotStatus.SUCCESS -> BotState.Happy
            MascotStatus.ERROR -> BotState.Alert
            MascotStatus.SLOW -> BotState.Sleepy
            MascotStatus.WORKING -> BotState.Working
            MascotStatus.IDLE -> config.state
        }
    }

    val dizzy = LocalShakeDizzy.current
    val shown =
        if (dizzy != null && dizzy.level.floatValue > DIZZY_FACE_THRESHOLD) BotState.Silly
        else state ?: statusDriven

    MaterialBot(
        modifier = modifier.dizzyWobble(dizzy, enabled = config.motion),
        config = config.copy(
            enabled = true,
            state = shown,
            size = config.size
        ),
        contentDescription = contentDescription,
        onClick = onClick
    )
}

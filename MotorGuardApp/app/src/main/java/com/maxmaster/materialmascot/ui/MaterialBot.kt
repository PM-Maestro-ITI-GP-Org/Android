/*
 * Material Mascot — a living bot avatar for Android apps.
 * Copyright (C) 2026 Max Master
 * SPDX-License-Identifier: MIT
 */

package com.maxmaster.materialmascot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.maxmaster.materialmascot.config.MascotConfig
import com.maxmaster.materialmascot.config.MascotFinish
import com.maxmaster.materialmascot.engine.BotEngine
import com.maxmaster.materialmascot.engine.BotState
import com.maxmaster.materialmascot.engine.DotSpec
import com.maxmaster.materialmascot.engine.NOTIF_BLUE
import com.maxmaster.materialmascot.engine.RenderedEye
import com.maxmaster.materialmascot.engine.TEAR_POINTS
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders the bot mascot using Compose Canvas.
 *
 * This is the main entry point for displaying the mascot in your UI.
 * Create a [MascotConfig] with your desired settings and pass it here.
 *
 * The bot animates autonomously based on time — no manual frame advancement
 * needed. State changes are smooth morph transitions.
 *
 * @param config Host-owned immutable configuration. See [MascotConfig].
 * @param modifier Layout modifier for positioning, sizing, etc.
 * @param contentDescription Accessibility label; null omits the semantics node.
 *                           Provide a description like "Bot mascot" for accessibility.
 * @param onClick Optional host action run when the bot is tapped, in addition
 *                to its own poke reaction. Null leaves the bot decorative.
 *
 * @sample com.maxmaster.materialmascot.sample.SampleUsage.ChatWithBot
 * @sample com.maxmaster.materialmascot.sample.SampleUsage.AccessibleBot
 */
@Composable
public fun MaterialBot(
    config: MascotConfig,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null
) {
    if (!config.enabled) return

    val sizePx = with(LocalDensity.current) { config.size.toPx() }

    var frameMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frameMs = it / 1_000_000L }
        }
    }

    val engine = remember { BotEngine(config.initialState) }
    // Read the ticker's CURRENT value when a state change lands: mixing clocks
    // here (epoch vs uptime nanos) would make every morph complete instantly.
    val latestFrameMs by rememberUpdatedState(frameMs)

    // Poke reaction: a tap makes the bot wink at you for a moment, whatever
    // state it is in. Pure delight, no configuration needed.
    var pokes by remember { mutableIntStateOf(0) }
    var celebrating by remember { mutableStateOf(false) }
    LaunchedEffect(pokes) {
        if (pokes == 0) return@LaunchedEffect
        celebrating = true
        delay(1100)
        celebrating = false
    }
    val shownState = if (celebrating && config.state != BotState.Happy) BotState.Happy else config.state

    LaunchedEffect(shownState) {
        if (engine.state != shownState) {
            engine.setState(shownState, latestFrameMs)
        }
    }

    val canvasModifier = if (contentDescription != null) {
        modifier
            .size(config.size)
            .semantics { this.contentDescription = contentDescription }
    } else {
        modifier.size(config.size)
    }

    // Reused across frames: allocating paths and matrices at 60 fps makes the
    // garbage collector stutter on device.
    // Every gradient is derived from the configured colors and the canvas size,
    // so it is built once per palette change — never per frame, which is what
    // drawing at display refresh rate demands.
    val chrome = remember(config.color, config.eyeColor, sizePx, config.finish) {
        if (config.finish == MascotFinish.CHROME) {
            ChromeBrushes(config.color, config.eyeColor, sizePx)
        } else null
    }

    val bodyPath = remember { Path() }
    val eyeSrcPath = remember { Path() }
    val eyeDstPath = remember { Path() }
    val eyeMatrix = remember { android.graphics.Matrix() }

    Canvas(
        // The poke reaction always fires; a host action rides along with it,
        // so the bot can be a real button on screens that give it a job
        // without losing the delight of prodding it.
        modifier = canvasModifier.pointerInput(onClick) {
            detectTapGestures {
                pokes++
                onClick?.invoke()
            }
        }
    ) {
        // Sample in the draw phase: the ticker state is read here, so a new
        // frame invalidates only this canvas' draw — not recomposition.
        // Sampling during composition made every visible bot rebuild its
        // whole composable at display refresh rate.
        val frame = engine.sample(
            nowMs = frameMs,
            scalePx = sizePx,
            reducedMotion = !config.motion,
            energy = config.energy,
            eyeStyle = config.eyeStyle
        )
        val outline = config.finish == MascotFinish.OUTLINE
        // With no fill behind them, eyes in the configured eye color would sit
        // on the page rather than in the bot — so line art draws them in the
        // same ink as the stroke.
        val inkColor = if (outline) config.color else config.eyeColor
        translate(this.center.x, this.center.y) {
            when {
                chrome != null -> drawChromeBody(frame.bodyPoints, chrome, bodyPath)
                outline -> drawOutlineBody(frame.bodyPoints, config.color, sizePx, bodyPath)
                else -> drawBody(frame.bodyPoints, config.color, bodyPath)
            }
            // Swirl and Orbit build these rings, but nothing ever painted
            // them — both states rendered as a plain resting ball. The engine
            // scales arc centres to pixels and leaves radius and stroke in
            // ball-radius units, so they are converted here.
            if (frame.arcs.isNotEmpty()) {
                val ballR = sizePx * BotEngine.RADIUS_FRACTION
                frame.arcs.forEach { arc ->
                    drawCircle(
                        color = inkColor,
                        radius = arc.r * ballR,
                        center = Offset(arc.x, arc.y),
                        alpha = arc.alpha,
                        style = Stroke(width = arc.stroke * ballR)
                    )
                }
            }
            frame.dots.forEach { drawDot(it, config.color, inkColor, sizePx, bodyPath) }
            frame.eyes.forEach {
                drawEye(
                    it, inkColor, eyeSrcPath, eyeDstPath, eyeMatrix,
                    glow = chrome?.eyesGlow == true
                )
            }
            frame.notif?.let {
                drawCircle(
                    color = Color(NOTIF_BLUE),
                    radius = it.rPx,
                    center = Offset(it.x, it.y)
                )
            }
        }
    }
}

/**
 * The gradients behind [MascotFinish.CHROME], all expressed in the canvas'
 * centre-origin space so they can be built once and reused every frame.
 *
 * Each tone is derived from the host's body color rather than hardcoded, so a
 * mint or lavender bot gets the same lit-metal treatment as a near-black one.
 */
private class ChromeBrushes(color: Color, eyeColor: Color, sizePx: Float) {

    /**
     * Bloom is light spilling out of the eye, so it only makes sense when the
     * eye is brighter than the body. Blooming a dark eye on a pale body just
     * paints grey rings around it.
     */
    val eyesGlow: Boolean = eyeColor.luminance() > color.luminance() + 0.15f

    val ballR: Float = sizePx * BotEngine.RADIUS_FRACTION

    private val top = lerp(color, Color.White, 0.18f)
    private val bottom = lerp(color, Color.Black, 0.35f)
    private val sheen = lerp(color, Color.White, 0.55f)

    /** Lit from above, falling into shadow at the base. */
    val body: Brush = Brush.verticalGradient(
        colors = listOf(top, color, bottom),
        startY = -ballR,
        endY = ballR
    )

    /** The edge catch-light that does most of the work of reading as metal. */
    val rim: Brush = Brush.verticalGradient(
        colors = listOf(sheen.copy(alpha = 0.9f), sheen.copy(alpha = 0f)),
        startY = -ballR,
        endY = ballR * 0.25f
    )
    val rimWidth: Float = ballR * 0.022f

    /** One soft highlight, upper-left, clipped to the body. */
    val specular: Brush = Brush.radialGradient(
        colors = listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0f)),
        center = Offset(-ballR * 0.34f, -ballR * 0.46f),
        radius = ballR * 0.62f
    )
    val specularTopLeft = Offset(-ballR * 0.72f, -ballR * 0.84f)
    val specularSize = Size(ballR * 0.78f, ballR * 0.58f)

    /** Contact shadow, so the bot sits in space instead of floating on it. */
    val shadow: Brush = Brush.radialGradient(
        colors = listOf(Color.Black.copy(alpha = 0.26f), Color.Black.copy(alpha = 0f)),
        center = Offset(0f, ballR * 0.99f),
        radius = ballR * 0.95f
    )
    val shadowTopLeft = Offset(-ballR * 0.95f, ballR * 0.72f)
    val shadowSize = Size(ballR * 1.9f, ballR * 0.54f)
}

/** Same silhouette as [drawBody], painted with depth instead of one flat fill. */
private fun DrawScope.drawChromeBody(points: List<Offset>, chrome: ChromeBrushes, path: Path) {
    if (points.size < 3) return
    buildBodyPath(points, path)
    drawOval(brush = chrome.shadow, topLeft = chrome.shadowTopLeft, size = chrome.shadowSize)
    drawPath(path = path, brush = chrome.body)
    drawPath(path = path, brush = chrome.rim, style = Stroke(width = chrome.rimWidth))
    clipPath(path) {
        drawOval(brush = chrome.specular, topLeft = chrome.specularTopLeft, size = chrome.specularSize)
    }
}

/** [MascotFinish.OUTLINE]: the same silhouette as a stroke, with no fill. */
private fun DrawScope.drawOutlineBody(
    points: List<Offset>,
    color: Color,
    sizePx: Float,
    path: Path
) {
    if (points.size < 3) return
    buildBodyPath(points, path)
    drawPath(
        path = path,
        color = color,
        // Scaled off the bot's radius so the line keeps its weight at any size.
        style = Stroke(width = sizePx * BotEngine.RADIUS_FRACTION * 0.06f)
    )
}

private fun DrawScope.drawBody(points: List<Offset>, color: Color, path: Path) {
    if (points.size < 3) return
    buildBodyPath(points, path)
    drawPath(path = path, color = color)
}

/** Catmull-Rom style closed spline through the sampled silhouette points. */
private fun buildBodyPath(points: List<Offset>, path: Path) {
    val n = points.size
    path.reset()
    path.moveTo(points[0].x, points[0].y)
    for (i in 0 until n) {
        val p0 = points[(i - 1 + n) % n]
        val p1 = points[i]
        val p2 = points[(i + 1) % n]
        val p3 = points[(i + 2) % n]
        path.cubicTo(
            p1.x + (p2.x - p0.x) / 6f,
            p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f,
            p2.y - (p3.y - p1.y) / 6f,
            p2.x,
            p2.y
        )
    }
    path.close()
}

private fun DrawScope.drawDot(
    dot: DotSpec,
    color: Color,
    onBodyColor: Color,
    sizePx: Float,
    path: Path
) {
    if (!dot.tear) {
        // Dots painted "on" the body belong to face-less states and take the
        // eye color so they read as features, not holes.
        drawCircle(
            color = if (dot.onBody) onBodyColor else color,
            radius = dot.r,
            center = Offset(dot.x, dot.y),
            alpha = dot.alpha
        )
        return
    }
    val ballR = sizePx * BotEngine.RADIUS_FRACTION
    val rad = dot.rotDeg * PI.toFloat() / 180f
    val cr = cos(rad)
    val sr = sin(rad)
    path.reset()
    TEAR_POINTS.forEachIndexed { i, p ->
        val rx = p.x * cr - p.y * sr
        val ry = p.x * sr + p.y * cr
        val point = Offset(dot.x + rx * ballR, dot.y + ry * ballR)
        if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    path.close()
    drawPath(path = path, color = color, alpha = dot.alpha)
}

private fun DrawScope.drawEye(
    eye: RenderedEye,
    color: Color,
    src: Path,
    dst: Path,
    matrix: android.graphics.Matrix,
    glow: Boolean = false
) {
    val corner = minOf(eye.wPx, eye.hPx) / 2f
    src.rewind()
    src.addRoundRect(RoundRect(0f, 0f, eye.wPx, eye.hPx, CornerRadius(corner, corner)))
    matrix.reset()
    matrix.setValues(
        floatArrayOf(
            eye.a, eye.c, eye.tx,
            eye.b * eye.blinkK, eye.d * eye.blinkK, eye.ty,
            0f, 0f, 1f
        )
    )
    src.asAndroidPath().transform(matrix, dst.asAndroidPath())
    if (glow && eye.alpha > 0f) {
        // Two expanding copies at falling alpha read as bloom without a blur
        // mask filter, which fights hardware acceleration on some devices.
        val pivot = dst.getBounds().center
        scale(scaleX = 1.55f, scaleY = 1.55f, pivot = pivot) {
            drawPath(path = dst, color = color, alpha = eye.alpha * 0.14f)
        }
        scale(scaleX = 1.25f, scaleY = 1.25f, pivot = pivot) {
            drawPath(path = dst, color = color, alpha = eye.alpha * 0.22f)
        }
    }
    drawPath(path = dst, color = color, alpha = eye.alpha)
}

// --- Previews ---

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0D)
@Composable
private fun PreviewChromeIdle() {
    MaterialBot(
        config = MascotConfig(
            state = BotState.Idle,
            color = Color(0xFF14161A),
            size = 120.dp,
            finish = MascotFinish.CHROME
        )
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0D)
@Composable
private fun PreviewChromeHappy() {
    MaterialBot(
        config = MascotConfig(
            state = BotState.Happy,
            color = Color(0xFF14161A),
            size = 120.dp,
            finish = MascotFinish.CHROME
        )
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0B0D)
@Composable
private fun PreviewChromeNarrating() {
    MaterialBot(
        config = MascotConfig(
            state = BotState.Narrating,
            color = Color(0xFF14161A),
            size = 120.dp,
            finish = MascotFinish.CHROME
        )
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun PreviewIdle() {
    MaterialBot(
        config = MascotConfig(
            state = BotState.Idle,
            color = Color(0xFF0A0A0C),
            size = 120.dp
        )
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun PreviewHappy() {
    MaterialBot(
        config = MascotConfig(
            state = BotState.Happy,
            color = Color(0xFF0A0A0C),
            size = 120.dp
        )
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun PreviewAlert() {
    MaterialBot(
        config = MascotConfig(
            state = BotState.Alert,
            color = Color(0xFF0A0A0C),
            size = 120.dp
        )
    )
}
package com.motorguard.ivi.ui.diagnostics.render

import android.util.Log
import android.view.MotionEvent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.google.android.filament.Renderer
import com.google.android.filament.View as FilamentView
import com.motorguard.ivi.data.vehicle.api.Hotspot
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.lookAt
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.Scene
import io.github.sceneview.SceneView
import io.github.sceneview.collision.Vector3
import io.github.sceneview.model.renderableNames
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberView
import kotlin.coroutines.coroutineContext
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

private const val TAG = "Car3dRenderer"

/**
 * Note on `io.github.sceneview.collision.Vector3` vs [Float3]: two different vector types are
 * on the classpath. This file only ever uses kotlin-math's [Float3]. The collision-package
 * `Vector3` shows up starting Step 2, where the (deprecated) `worldToScreenPoint` overload
 * that projects a world point to screen space is declared in terms of it.
 */

/**
 * Every value that may need adjusting after looking at the first render. Nothing else in this
 * file hardcodes a magnitude that is not derived from the model's own bounding box.
 */
object Car3dTuning {
    const val MODEL_ASSET = "car_model.glb"

    // --- Orientation. The GLB is a Sketchfab FBX export; up-axis and facing are NOT assumed.
    /** Applied about world X when the bbox says the model is Z-up. Flip sign if upside down. */
    const val UP_AXIS_PITCH_DEG = -90f

    /** Added to the auto-derived yaw. Set to 180f if the car's rear faces the camera. */
    const val YAW_TRIM_DEG = 0f

    // --- Framing. Car is normalised so its longest dimension == 1.0 world unit.
    const val UNIT_SIZE = 1f

    /** Degrees around world Y from the car's side. ~35-40 gives the classic 3/4 hero. */
    const val HERO_AZIMUTH_DEG = 38f

    /** Degrees above the horizon. Low = heroic; high = topdown-ish. */
    const val HERO_ELEVATION_DEG = 13f

    /**
     * Eye distance = bounding-sphere radius * this. The bounding *sphere* is a loose fit for a
     * car (long and flat, so the sphere is much bigger than the silhouette), which is why this
     * is well above 1 — measured against the real stage, 2.9 clipped the front bumper.
     */
    const val HERO_DISTANCE_FACTOR = 3.9f

    /**
     * Look-at point lifted above the bbox centre, as a fraction of the bounding radius. Positive
     * pushes the car *down* in frame. Kept at 0 so it sits centred; the stage's bottom vignette
     * already supplies the visual weight this was meant to give.
     */
    const val HERO_TARGET_LIFT = 0f

    // --- Entrance
    const val ENTRANCE_DISTANCE_FACTOR = 1.20f
    const val ENTRANCE_MILLIS = 900

    // --- Camera
    const val FOCAL_LENGTH_MM = 45.0
    const val NEAR = 0.05f
    const val FAR = 100f

    // --- Lighting
    const val KEY_LIGHT_INTENSITY_LUX = 90_000f
    val KEY_LIGHT_DIRECTION = Float3(0.35f, -0.85f, -0.45f)
    const val IBL_INTENSITY_LUX = 40_000f
    const val CAST_SHADOWS = false

    // --- Quality
    const val POST_PROCESSING = true
    const val FXAA = true

    /**
     * Bundled inside the SceneView AAR (merged into this app's assets at build time). Loaded
     * with no `skyboxAssetFile` so the environment carries indirect lighting but no skybox —
     * see the comment on `environment` in [Car3dRenderer.Render] for why a skybox is unwanted.
     */
    const val NEUTRAL_IBL_ASSET = "environments/neutral/neutral_ibl.ktx"
}

/**
 * SceneView/Filament implementation of [CarRenderer]. This is the only file in the app that
 * knows the car is a 3D model — everything upstream of [CarRenderer] stays agnostic.
 *
 * Two load-bearing, non-obvious facts (decompiled from sceneview-2.3.0 / filament-1.56.0;
 * see the Step 1 design doc §0 for the full trail):
 *
 * 1. `isOpaque = false` would Z-order this Scene's `SurfaceView` **above the whole window**
 *    (`SceneView` -> `UiHelper.attachTo` -> `setZOrderOnTop(!isOpaque)`), which would hide every
 *    Compose sibling drawn after it (the stage border in `DiagnosticsScreen`, later the Step 2
 *    hotspot dots). So this renders `isOpaque = true` and reconstructs the "glass" look as a
 *    Compose overlay drawn *on top* of the SurfaceView instead.
 * 2. Because `isOpaque = true`, `SceneView` never touches `Renderer.ClearOptions` (that block
 *    is guarded by `if (!isOpaque)`). With no skybox and Filament's default `clear = false`,
 *    the background would be whatever garbage was already in the buffer. [applyStageColor]
 *    sets the clear colour explicitly, both on view creation and whenever the theme flips.
 */
class Car3dRenderer internal constructor(
    private val modelAsset: String,
) : CarRenderer {

    override var state: CarRenderState by mutableStateOf(CarRenderState.Loading)
        private set

    /** Opaque colour Filament clears to. Kept in sync with the theme by the screen. */
    internal var stageColor: Color by mutableStateOf(Color.Black)

    /** Set once the Scene exists. Step 2 projects hotspots through this. */
    private var cameraNode: CameraNode? by mutableStateOf(null)
    private var modelNode: ModelNode? by mutableStateOf(null)

    /**
     * Resolved once per model load (see the `LaunchedEffect` in [Render]). Plain `var`, not
     * `mutableStateOf`: [screenPositionOf] is read from a frame loop during the layout/draw
     * phases (see [HotspotOverlay][com.motorguard.ivi.ui.diagnostics.component.HotspotOverlay]),
     * never from composition, so there is nothing for Compose to observe here — wrapping it in
     * snapshot state would only add a write-during-layout/read-during-layout hazard for free.
     */
    private var geometry: HotspotGeometry? = null

    /** Compose-layout size of the Scene in px, to rescale `worldToScreenPoint`'s viewport-space
     *  px into the Compose px the overlay draws in (see [screenPositionOf]). Same non-observable
     *  reasoning as [geometry]. */
    private var renderSizePx: IntSize = IntSize.Zero

    @Composable
    override fun Render(
        // Step 3: if (focus != null) animate camera to focusPose(focus) else heroPose().
        // Deliberately ignored in Step 1 — see the CarRenderer KDoc for why the parameter
        // exists already even though nothing reads it yet.
        @Suppress("UNUSED_PARAMETER") focus: Hotspot?,
        onBackgroundTap: () -> Unit,
        modifier: Modifier,
    ) {
        val engine = rememberEngine()
        val modelLoader = rememberModelLoader(engine)
        val materialLoader = rememberMaterialLoader(engine)
        val environmentLoader = rememberEnvironmentLoader(engine)
        val filamentView = rememberView(engine)
        // rememberEnvironment's default creator (SceneView.createEnvironment) always builds a
        // solid-colour Skybox alongside the IBL. A Skybox paints over every pixel geometry
        // doesn't touch, which would defeat applyStageColor's whole point (matching the
        // surrounding GlassCard colour). Loading the bundled neutral IBL through
        // createKTX1Environment with no skyboxAssetFile keeps indirect lighting without a
        // skybox, so our clear colour is what actually shows through.
        val environment = rememberEnvironment(environmentLoader) {
            environmentLoader.createKTX1Environment(iblAssetFile = Car3dTuning.NEUTRAL_IBL_ASSET)
        }
        val mainLight = rememberMainLightNode(engine)
        val camera = rememberCameraNode(engine) {
            focalLength = Car3dTuning.FOCAL_LENGTH_MM
            near = Car3dTuning.NEAR
            far = Car3dTuning.FAR
        }
        val childNodes = rememberNodes()
        var sceneView by remember { mutableStateOf<SceneView?>(null) }

        // 1. Lighting. Idempotent and cheap, safe to re-run on every recomposition.
        SideEffect {
            mainLight.intensity = Car3dTuning.KEY_LIGHT_INTENSITY_LUX
            mainLight.lightDirection = normalize(Car3dTuning.KEY_LIGHT_DIRECTION)
            mainLight.isShadowCaster = Car3dTuning.CAST_SHADOWS
            environment.indirectLight?.intensity = Car3dTuning.IBL_INTENSITY_LUX
        }

        // 2. Stage colour -> Filament clear colour, re-applied whenever the theme flips.
        val currentStage = stageColor
        SideEffect { sceneView?.let { applyStageColor(it, currentStage) } }

        // 3. Load the model, once per (loader, asset) pair.
        LaunchedEffect(modelLoader, modelAsset) {
            state = CarRenderState.Loading
            try {
                val instance = modelLoader.loadModelInstance(modelAsset)
                    ?: error("loadModelInstance returned null for $modelAsset")
                coroutineContext.ensureActive()

                val node = ModelNode(
                    modelInstance = instance,
                    autoAnimate = false,
                    scaleToUnits = Car3dTuning.UNIT_SIZE,
                    centerOrigin = Float3(0f, 0f, 0f),
                ).apply {
                    isShadowCaster = Car3dTuning.CAST_SHADOWS
                    isShadowReceiver = false
                    name = "car"
                }
                applyOrientation(node)
                modelNode = node
                childNodes += node
                val geo = HotspotGeometry.resolve(node)
                geometry = geo
                geo.report.forEach { Log.i(TAG, "hotspot geometry: $it") }
                frameHero(camera, node, distanceScale = 1f)
                Log.i(TAG, "car renderables: " + node.model.renderableNames.joinToString())
                state = CarRenderState.Ready
                playEntrance(camera, node)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "car model load failed", t)
                state = CarRenderState.Failed(t.message ?: t::class.java.simpleName, t)
            }
        }

        // Every Filament object below is created by a remember* helper and destroyed by its
        // matching onDispose — that is the entire lifecycle-safety story for this file (see
        // the design doc §5). cameraNode/modelNode/geometry/renderSizePx are the one exception
        // allowed to outlive a single recomposition, so they are nulled out explicitly when the
        // Scene leaves composition: screenPositionOf can be read from a frame callback
        // (HotspotOverlay's LaunchedEffect) that outlives the Scene by a frame, and a stale
        // native pointer there is a crash, not an exception. Registered here, immediately before
        // `Scene(...)`, rather than at the top of `Render`: Compose forgets remembered objects in
        // REVERSE declaration order, so this DisposableEffect's onDispose — which only nulls out
        // plain fields, touching no native object — runs FIRST, before rememberNodes/
        // rememberEngine tear down the Filament objects those fields point into.
        DisposableEffect(Unit) {
            onDispose {
                cameraNode = null
                modelNode = null
                geometry = null
                renderSizePx = IntSize.Zero
            }
        }

        Scene(
            modifier = modifier.onSizeChanged { renderSizePx = it },
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            view = filamentView,
            isOpaque = true,
            environment = environment,
            mainLightNode = mainLight,
            cameraNode = camera,
            childNodes = childNodes,
            cameraManipulator = null, // no user orbit; Step 3 drives the camera in code
            onTouchEvent = { event, hit ->
                if (event.actionMasked == MotionEvent.ACTION_UP && hit == null) onBackgroundTap()
                false
            },
            onViewCreated = {
                sceneView = this
                // `this` here is the SceneView receiver, which has its own (read-only)
                // `cameraNode` property, so the field write must be qualified or it would try
                // (and fail to compile) to reassign that val instead of this renderer's field.
                this@Car3dRenderer.cameraNode = camera
                setZOrderOnTop(false)
                setZOrderMediaOverlay(false)
                applyStageColor(this, currentStage)
                view.isPostProcessingEnabled = Car3dTuning.POST_PROCESSING
                view.antiAliasing = if (Car3dTuning.FXAA) {
                    FilamentView.AntiAliasing.FXAA
                } else {
                    FilamentView.AntiAliasing.NONE
                }
            },
        )
    }

    override fun screenPositionOf(hotspot: Hotspot): Offset? {
        val cam = cameraNode ?: return null
        val model = modelNode ?: return null
        val local = geometry?.anchorOf(hotspot) ?: return null

        val w4 = model.worldTransform * Float4(local, 1f)

        // worldToScreenPoint has NO behind-camera rejection: for w < 0 it silently returns a
        // point mirrored through the screen centre. Filament view space looks down -Z, so a
        // visible point has viewZ < -near. This test is the rejection (Step 2 design doc §0.1).
        val v4 = cam.viewTransform * Float4(w4.x, w4.y, w4.z, 1f)
        if (-v4.z <= cam.near) return null

        val vp = cam.viewport ?: return null // null until the Scene attaches its View
        if (vp.width <= 0 || vp.height <= 0) return null
        val size = renderSizePx
        if (size.width == 0 || size.height == 0) return null

        // Returns TOP-LEFT origin already: SceneView applies `y = viewport.height - y`
        // internally. DO NOT flip again. `.z` of the result is always 0 and must not be read.
        val p = cam.worldToScreenPoint(Vector3(w4.x, w4.y, w4.z))
        return Offset(p.x * size.width / vp.width, p.y * size.height / vp.height)
    }
}

/** Remembers a [Car3dRenderer], re-created only if [modelAsset] changes. */
@Composable
fun rememberCar3dRenderer(
    stageColor: Color,
    modelAsset: String = Car3dTuning.MODEL_ASSET,
): Car3dRenderer {
    val renderer = remember(modelAsset) { Car3dRenderer(modelAsset) }
    renderer.stageColor = stageColor
    return renderer
}

/**
 * Derives the model's up-axis and facing from its bounding box and rotates it into the
 * canonical pose (length along world X, upright). Sketchfab FBX exports carry no reliable
 * orientation metadata, so this has to be inferred rather than assumed.
 */
private fun applyOrientation(node: ModelNode) {
    val h = node.boundingBox.halfExtent // FloatArray(3), asset-local
    val hx = h[0]
    val hy = h[1]
    val hz = h[2]

    // A car's smallest dimension is always its height. If the Z half-extent is smaller than
    // the Y half-extent, Z is the up axis (typical Sketchfab FBX export) and needs correcting.
    val zIsUp = hz < hy
    val pitchDeg = if (zIsUp) Car3dTuning.UP_AXIS_PITCH_DEG else 0f

    // Horizontal footprint AFTER the pitch correction:
    //   zIsUp  -> world X = model X, world Z = model Y
    //   else   -> world X = model X, world Z = model Z
    val worldHalfX = hx
    val worldHalfZ = if (zIsUp) hy else hz

    // Canonical pose: the car's LENGTH runs along world X.
    val autoYawDeg = if (worldHalfZ > worldHalfX) 90f else 0f
    val yawDeg = autoYawDeg + Car3dTuning.YAW_TRIM_DEG

    // Compose explicitly rather than going through Node.rotation: that setter runs through
    // Quaternion.fromEuler with kotlin-math's default ZYX ordering, which is easy to get
    // backwards. q = yaw * pitch means "pitch first, then yaw", unambiguously.
    node.quaternion =
        Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), yawDeg) *
            Quaternion.fromAxisAngle(Float3(1f, 0f, 0f), pitchDeg)
}

/** Eye and look-at point for the hero camera pose, in world space. */
private data class CameraPose(val eye: Float3, val target: Float3)

/** Radius of the bounding sphere of the SCALED, oriented model, in world units. */
private fun boundingRadius(node: ModelNode): Float {
    val h = node.boundingBox.halfExtent
    val s = node.scale.x // uniform after scaleToUnits
    return sqrt(h[0] * h[0] + h[1] * h[1] + h[2] * h[2]) * s
}

private fun heroPose(radius: Float, distanceScale: Float): CameraPose {
    val d = radius * Car3dTuning.HERO_DISTANCE_FACTOR * distanceScale
    val az = Math.toRadians(Car3dTuning.HERO_AZIMUTH_DEG.toDouble()).toFloat()
    val el = Math.toRadians(Car3dTuning.HERO_ELEVATION_DEG.toDouble()).toFloat()
    val target = Float3(0f, radius * Car3dTuning.HERO_TARGET_LIFT, 0f)
    val eye = Float3(
        target.x + d * cos(el) * sin(az),
        target.y + d * sin(el),
        target.z + d * cos(el) * cos(az),
    )
    return CameraPose(eye, target)
}

/**
 * Points [camera] at the hero pose derived from [node]'s current bounding box.
 * [distanceScale] > 1 pulls the eye back along the same ray, which is all [playEntrance]
 * needs to dolly in.
 *
 * Neither `Node` nor `CameraNode` has a `lookAt(eye:, target:, up:)` overload — the only
 * member `lookAt` rotates a node in place around its *current* position to face a target, it
 * cannot move the node. `dev.romainguy.kotlin.math.lookAt` builds the full eye+orientation
 * transform directly, which is what an eye/target camera pose actually needs; assigning it to
 * `worldTransform` sets position and rotation in one step.
 */
private fun frameHero(camera: CameraNode, node: ModelNode, distanceScale: Float) {
    val pose = heroPose(boundingRadius(node), distanceScale)
    camera.worldTransform = lookAt(eye = pose.eye, target = pose.target, up = Float3(0f, 1f, 0f))
}

/**
 * A slow dolly-in on load. Deliberately not `Modifier.graphicsLayer { alpha / scale }` on the
 * Scene: alpha and scale on a SurfaceView are composited by SurfaceFlinger and behave
 * inconsistently across drivers. The fade half of the entrance is a Compose scrim instead,
 * layered over the Scene in `DiagnosticsScreen`'s `CarStage`.
 */
private suspend fun playEntrance(camera: CameraNode, node: ModelNode) {
    animate(
        initialValue = Car3dTuning.ENTRANCE_DISTANCE_FACTOR,
        targetValue = 1f,
        animationSpec = tween(Car3dTuning.ENTRANCE_MILLIS, easing = FastOutSlowInEasing),
    ) { value, _ -> frameHero(camera, node, distanceScale = value) }
}

private fun srgbToLinear(c: Float): Float =
    if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

/**
 * SceneView only touches [Renderer.ClearOptions] when `isOpaque = false` (see the class KDoc).
 * This sets it explicitly so an opaque stage clears to the theme colour instead of whatever
 * was already in the buffer. `clearColor` is linear sRGB, not gamma-encoded — skipping the
 * conversion makes the stage visibly too bright and mismatched with the surrounding panels.
 */
private fun applyStageColor(sceneView: SceneView, color: Color) {
    sceneView.renderer.clearOptions = Renderer.ClearOptions().apply {
        clear = true
        discard = false
        clearColor = floatArrayOf(
            srgbToLinear(color.red),
            srgbToLinear(color.green),
            srgbToLinear(color.blue),
            1f,
        )
    }
}

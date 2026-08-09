package com.motorguard.ivi.ui.diagnostics.render

import android.util.Log
import android.view.MotionEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import com.google.android.filament.Renderer
import com.google.android.filament.View as FilamentView
import com.motorguard.ivi.data.vehicle.api.Hotspot
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.clamp
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.lookAt
import dev.romainguy.kotlin.math.mix
import dev.romainguy.kotlin.math.normalize
import dev.romainguy.kotlin.math.scale
import io.github.sceneview.Scene
import io.github.sceneview.SceneView
import io.github.sceneview.collision.Vector3
import io.github.sceneview.model.renderableNames
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.ImageNode
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
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

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

    /**
     * Degrees above the horizon. Low = heroic; high = topdown-ish.
     *
     * Raised from 13: at that height the car was seen almost from its own waistline, which hides
     * the roof and the shoulders and makes it hard to tell one end from the other at a glance. A
     * little more elevation reads as looking *at* a vehicle rather than standing beside it, and it
     * opens up the flank the hotspot dots live on.
     */
    const val HERO_ELEVATION_DEG = 22f

    /**
     * Eye distance = bounding-sphere radius * this. The bounding *sphere* is a loose fit for a
     * car (long and flat, so the sphere is much bigger than the silhouette), which is why this
     * is well above 1 — measured against the real stage, 2.9 clipped the front bumper.
     *
     * Pulled in from 3.9 when the stage grew to 80% of the row: the extra width is all horizontal,
     * and framing is driven by the vertical field of view, so a wider stage on its own makes the
     * car no bigger — it only buys the headroom to come closer without the ends leaving frame.
     */
    const val HERO_DISTANCE_FACTOR = 3.2f

    /**
     * Look-at point lifted above the bbox centre, as a fraction of the bounding radius. Positive
     * pushes the car *down* in frame. Kept at 0 so it sits centred; the stage's bottom vignette
     * already supplies the visual weight this was meant to give.
     */
    const val HERO_TARGET_LIFT = 0f

    // --- Entrance. Step 3 repurposes these: distanceScale for the pulled-back START pose the
    // load effect sets, and millis for the very first camera move only (see `hasFramed`). The
    // magnitudes are unchanged, only who reads them.
    const val ENTRANCE_DISTANCE_FACTOR = 1.20f
    const val ENTRANCE_MILLIS = 900

    // --- Focus (spec §7)
    /** Spec §7 mandates 250 ms FastOutSlowIn for the focus transition. Do not retune. */
    const val FOCUS_MILLIS = 250

    /**
     * How one component is framed when focused.
     *
     * @param azimuthDeg swing from pure side-on toward the anchor's own end of the car. Small
     *   values are side-on and flatten the part against the bodywork behind it; large values are
     *   three-quarter and give depth.
     * @param elevationDeg height above the horizon. Negative looks slightly UP at the vehicle,
     *   which is the only honest way to present something mounted under the floor.
     * @param distanceFactor eye distance from the anchor, as a multiple of the car's bounding
     *   radius. Small parts want a small number; parts that span the vehicle want a large one or
     *   the camera ends up inside them.
     * @param orbitLimitDeg how far the user may drag either side of this framing while the
     *   component is focused. A focused view is a framing, not a free turntable: keep dragging
     *   past the bodywork and the eye ends up inside the cabin looking out through the seats,
     *   which is unreadable and impossible to recover from without unfocusing. Wider for parts
     *   readable from a range of angles, tighter for ones with exactly one honest viewpoint.
     */
    data class FocusFraming(
        val azimuthDeg: Float,
        val elevationDeg: Float,
        val distanceFactor: Float,
        val orbitLimitDeg: Float,
    )

    /**
     * Per-component framing, tuned against the real render rather than shared.
     *
     * A single set of angles cannot serve all eight: a wheel is a small object best read almost
     * side-on and near eye level, while the battery pack spans the whole floor and is invisible
     * from anywhere above the sill. The earlier one-size framing put the battery camera on the
     * door skin and cropped the doors down to glass and roof.
     */
    val FOCUS_FRAMING: Map<Hotspot, FocusFraming> = mapOf(
        // Nearly side-on and low, so the sidewall and the wheel face read rather than the arch.
        // A wheel is an object in the round and stays legible from anywhere on its own flank.
        Hotspot.TIRE_FL to FocusFraming(16f, 5f, 1.15f, 55f),
        Hotspot.TIRE_FR to FocusFraming(16f, 5f, 1.15f, 55f),
        Hotspot.TIRE_RL to FocusFraming(16f, 5f, 1.15f, 55f),
        Hotspot.TIRE_RR to FocusFraming(16f, 5f, 1.15f, 55f),
        // Pulled back enough to hold a whole wheel, since the disc itself is behind the spokes.
        Hotspot.BRAKES to FocusFraming(24f, 7f, 1.75f, 45f),
        // Looking DOWN onto the floor pan through the cutaway. This used to sit below the
        // horizon, on the reasoning that a pack under the floor cannot honestly be shown from
        // above — true while the bodywork was opaque, and false since the cutaway started
        // opening: the sill and floor fade, and looking down through them shows the pack laid out
        // along the car, which is the shape a skateboard pack actually has. From below it was
        // only ever an edge.
        Hotspot.BATTERY to FocusFraming(16f, 24f, 2.15f, 25f),
        // Rear three-quarter, where the drive unit sits. Same reasoning as the battery, with a
        // little more room because the motor's window reaches further around the rear quarter.
        Hotspot.MOTOR to FocusFraming(42f, 9f, 1.7f, 30f),
        // Side-on and wide: the subject is the whole flank and its four openings, not one panel.
        Hotspot.DOORS to FocusFraming(6f, 6f, 2.3f, 45f),
    )

    /** Used if a hotspot is ever added without a framing entry. */
    val FOCUS_FRAMING_DEFAULT = FocusFraming(30f, 14f, 1.35f, 35f)

    /** |lateral| below this is a centreline component: the camera keeps the side it is already on. */
    const val FOCUS_SIDE_THRESHOLD = 0.20f

    /**
     * Degrees of orbit per pixel dragged. At this rate a drag across the full ~1000 dp car stage
     * sweeps a half turn, so one deliberate gesture brings the opposite flank into view, while a
     * short 200 px flick moves ~36 degrees — enough to feel responsive, not enough for a stray
     * touch to lose the driver's orientation.
     */
    const val ROTATE_DEG_PER_PX = 0.18f

    /**
     * Degrees of tilt per pixel dragged vertically. Lower than [ROTATE_DEG_PER_PX] because the
     * travel it maps onto is bounded: yaw has a full turn to spend a long drag on, pitch has
     * [PITCH_LIMIT_DEG], and at the yaw rate the whole range would be used up in ~250 px — the
     * camera would slam into its stop before the gesture felt like it had started.
     */
    const val PITCH_DEG_PER_PX = 0.10f

    /**
     * How far the user may tilt either side of the framing the camera opened at. Applies to the
     * overview and, additionally clamped by the component's own `orbitLimitDeg`, to a focused
     * view — a focused framing is no freer vertically than it is horizontally.
     */
    const val PITCH_LIMIT_DEG = 45f

    /**
     * Absolute elevation the camera may never leave, whatever [PITCH_LIMIT_DEG] would otherwise
     * allow. The low end stops the eye dropping under the ground plane, where the car is seen
     * through a floor the scene does not model. The high end stays clear of straight overhead,
     * where the view direction becomes parallel to the world-Y up vector `applyPose` passes to
     * `lookAt` and the camera basis is undefined.
     *
     * The clamp is applied to the accumulated offset, not to the resulting angle, so a drag that
     * runs into a limit does not build up slack the user has to wind back off before the camera
     * moves again.
     */
    const val MIN_ELEVATION_DEG = -10f
    const val MAX_ELEVATION_DEG = 72f

    // --- Far-side occlusion. All three compared against `lateral * viewLat`.
    /** |lateral| at or below this is a centreline anchor, never occluded. */
    const val OCCLUSION_DEADBAND = 0.12f

    /** -facing at which the fade STARTS. Equal to the deadband so the two agree exactly. */
    const val OCCLUSION_START = 0.12f

    /** -facing at which the dot is fully occluded. */
    const val OCCLUSION_FULL = 0.45f

    // --- Camera
    const val FOCAL_LENGTH_MM = 45.0
    const val NEAR = 0.05f
    const val FAR = 100f

    // --- Lighting
    // Halved from 90k after the first coloured paint went on. 90k lux suited a white car, whose
    // clear coat has little to blow out; on a saturated body it flared the bonnet and shoulders to
    // pure white and flattened every crease under the highlight. Lower key plus a rougher clear
    // coat (PAINT_ROUGHNESS) keeps the specular as a shaped sheen instead of a hole in the paint.
    const val KEY_LIGHT_INTENSITY_LUX = 45_000f
    val KEY_LIGHT_DIRECTION = Float3(0.35f, -0.85f, -0.45f)
    /** Ambient fill. Lowered with the key so the two stay in proportion and the shadow side of
     *  the car keeps some depth instead of washing flat. */
    const val IBL_INTENSITY_LUX = 26_000f
    const val CAST_SHADOWS = false

    // --- Quality
    //
    // These two are coupled: Filament runs FXAA inside the post-process pass, so disabling
    // POST_PROCESSING disables anti-aliasing with it. They are the first lever to pull if the
    // target hardware cannot keep up.
    //
    // Measured on the dev emulator (AMD RENOIR via Mesa, GLES 3.1, 1920x1080, 202k-vertex model),
    // sampled across four consecutive focus transitions — i.e. continuous camera animation, the
    // worst case rather than idle:
    //
    //     on   ->  43.5% janky frames, p50 48 ms, p90 65 ms
    //     off  ->   7.9% janky frames, p50 25 ms, p90 48 ms
    //
    // Kept ON: without it the panel-gap and door-seam lines visibly stair-step, and the car is the
    // centrepiece of this screen. The emulator's integrated GPU is also not the RPi 5's VideoCore
    // VII, so this number is a signal rather than a verdict — re-measure on target before deciding
    // (spec T13), and flip these two if the real hardware disagrees.
    const val POST_PROCESSING = true
    const val FXAA = true

    /**
     * Bundled inside the SceneView AAR (merged into this app's assets at build time). Loaded
     * with no `skyboxAssetFile` so the environment carries indirect lighting but no skybox —
     * see the comment on `environment` in [Car3dRenderer.Render] for why a skybox is unwanted.
     */
    const val NEUTRAL_IBL_ASSET = "environments/neutral/neutral_ibl.ktx"

    /**
     * The donor car's body-panel materials — everything painted in the vehicle's own colour.
     *
     * `PORLQW` is the paint proper. The other three are the untextured trim pieces that are
     * colour-matched on a real car: the bumper inserts and the panel beside the headlights. They
     * were left out of the first repaint and stood out as grey patches on a coloured car, which is
     * exactly how they were found. Glass, carbon, lights, brakes and interior are NOT in this set:
     * they carry their own textures and are not body-coloured on any car.
     *
     * Matched by suffix as well as by name, because the cutaway zones carry copies —
     * `Zone_Battery_PORLQW` and friends — and missing those would leave the doors and rear quarter
     * the old colour while the rest of the car changed.
     */
    val PAINT_MATERIALS = setOf(
        "PORLQW", "PORMBB", "PORPMB", "Material_55", "Glow_Front", "Doorsill",
    )

    /**
     * The wheel face: the spoke star AND the fine turbine blades between the spokes. One material
     * in the donor model, so those two can only ever be one colour.
     */
    val RIM_MATERIALS = setOf("Ghostrim")

    /**
     * The barrel — the ring inside the tyre, behind the spokes. Its own material as of the v2
     * model; before the split it was painted with `PORLQW`, the body material, so recolouring the
     * car recoloured the wheels with it and a red car got red wheels.
     */
    val RIM_INNER_MATERIALS = setOf("Wheel_Barrel")

    /** The ventilated rotor. Split from the caliper in the v2 model — they shared one material
     *  before, which made a silver disc behind a coloured caliper unreachable. */
    val DISC_MATERIALS = setOf("Brakes_Disc")

    /** The caliper clamped over the rotor: the one part of a wheel that carries a colour on a
     *  real car, which is why it gets a slot of its own. */
    val CALIPER_MATERIALS = setOf("Brakes_Caliper")

    /**
     * Every colour of the vehicle, in one value. Grouped rather than left as five loose colours
     * because they are only ever chosen against each other — a caliper colour is an accent on a
     * body colour or it is a mistake.
     *
     * Not hex literals, and not theme colours either: this is paint on a *vehicle*, not a UI
     * surface, and it has to hold up against both the near-black night stage and the near-white
     * day one. The extremes are what fail — pure white vanishes into the day stage, near-black
     * into the night one.
     */
    data class Livery(
        val body: Color,
        val rim: Color,
        val rimInner: Color,
        val disc: Color,
        val caliper: Color,
    ) {
        /** The colour this material should take, or null to leave it as the model authored it.
         *  The single place that knows the mapping, so the initial repaint and [CutawayZones]
         *  — which re-sends colours on every frame of a fade — cannot drift apart. */
        fun colorFor(name: String?): Color? = when {
            name == null -> null
            matches(name, PAINT_MATERIALS) -> body
            matches(name, RIM_MATERIALS) -> rim
            matches(name, RIM_INNER_MATERIALS) -> rimInner
            matches(name, DISC_MATERIALS) -> disc
            matches(name, CALIPER_MATERIALS) -> caliper
            else -> null
        }

        /** The finish that goes with [colorFor]'s slot: metallic, then roughness. */
        fun finishFor(name: String?): Pair<Float, Float>? = when {
            name == null -> null
            matches(name, PAINT_MATERIALS) -> PAINT_METALLIC to PAINT_ROUGHNESS
            matches(name, RIM_MATERIALS) -> RIM_METALLIC to RIM_ROUGHNESS
            matches(name, RIM_INNER_MATERIALS) -> RIM_INNER_METALLIC to RIM_INNER_ROUGHNESS
            matches(name, DISC_MATERIALS) -> DISC_METALLIC to DISC_ROUGHNESS
            matches(name, CALIPER_MATERIALS) -> CALIPER_METALLIC to CALIPER_ROUGHNESS
            else -> null
        }
    }

    /** Name match that also catches a cutaway zone's copy, e.g. `Zone_Motor_Brakes_Rear`. */
    private fun matches(name: String, materials: Set<String>) =
        materials.any { name == it || name.endsWith("_$it") }

    /** True for a body-panel material, or a cutaway zone's copy of one. */
    fun isPaintMaterial(name: String?): Boolean = name != null && matches(name, PAINT_MATERIALS)

    /**
     * What the car wears at startup. Change it here for a new default, or drive it live from the
     * debug panel (long-press the health ring) — that panel writes straight to
     * [Car3dRenderer.livery], so a colour can be tried without a rebuild.
     */
    val DEFAULT_LIVERY = Livery(
        body = Color(red = 0.26f, green = 0.44f, blue = 0.52f),
        rim = Color(red = 0.045f, green = 0.045f, blue = 0.05f),
        rimInner = Color(red = 0.13f, green = 0.14f, blue = 0.17f),
        disc = Color(red = 0.62f, green = 0.63f, blue = 0.65f),
        caliper = Color(red = 0.95f, green = 0.78f, blue = 0.05f),
    )

    /**
     * Nodes whose materials keep their backface culling. Everything else in the model is forced
     * double-sided at load — see [showBothFaces] for the full story. Glass is excluded because it
     * is genuinely transparent and authored to be seen from one side: drawing its far pane through
     * its near pane doubles the tint and darkens every window.
     */
    val KEEP_SINGLE_SIDED = setOf("Glass")

    /**
     * Whether to reverse the model's backface culling at load.
     *
     * **On, and it has to be.** `prep_car.py` recalculates normals outward at export, which fixed
     * the worst of it, but `recalc_face_normals` can only agree a winding *within* a connected
     * island — an island that is closed and entirely inverted, or an open surface with no inside
     * to speak of, comes out backwards anyway. Enough of the donor Porsche is built that way that
     * culling leaves holes: with this off, the rear wheels showed the motor straight through their
     * spokes. Turning it off again needs a model whose winding is right everywhere, not just
     * mostly, and the check is to look at a rear wheel.
     */
    const val FORCE_DOUBLE_SIDED = true

    /**
     * Renderables whose material colour the app drives from severity, and the parameter it drives.
     * These two are the only parts `prep_car.py` gives materials of its own (`MatMotor`,
     * `MatBattery`, both emissive) precisely so the app can recolour them; every other material
     * belongs to the donor car and is left alone.
     */
    val TINTED_COMPONENTS: Map<Hotspot, String> = mapOf(
        Hotspot.MOTOR to "Comp_Motor",
        Hotspot.BATTERY to "Comp_Battery",
    )

    /**
     * How much of the severity colour the component's own emissive glow carries. The base colour
     * alone reads as "a grey part in coloured light"; pushing the emissive as well makes the part
     * itself the thing that is red, which is the point — it has to be legible through a faded
     * panel, where ambient light barely reaches it.
     */
    const val COMPONENT_EMISSIVE = 0.55f

    // --- Stage backdrop
    /**
     * Perforated backdrop drawn behind the car, one per theme. Generated by
     * `vehicle3dModel/tools/make_backdrop.py`, which is also where every constant that shapes
     * them lives.
     *
     * It is a quad *inside the scene* rather than a Compose background, and that is forced: the
     * stage is an opaque SurfaceView, which punches through the window wherever it is drawn, so
     * Compose content behind it is erased (class KDoc, note 1). Filament's own alternative, a
     * skybox, needs a KTX cubemap and would also feed the image back into the lighting.
     */
    const val BACKDROP_NIGHT_ASSET = "stage_backdrop_night.png"
    const val BACKDROP_DAY_ASSET = "stage_backdrop_day.png"

    /**
     * Distance from the eye to the backdrop, as a multiple of the car's bounding radius. Far
     * enough behind the car that no camera move can push the car through it — the focus poses get
     * as close as 1.15 radii to their anchor — and near enough to stay well inside [FAR].
     */
    const val BACKDROP_DISTANCE_FACTOR = 9f

    /** Slack on the fitted size, so the quad's vignetted edge stays outside the viewport. */
    const val BACKDROP_OVERSCAN = 1.15f

    // --- Paint finish
    /**
     * Metallic and roughness for the repainted body. The donor material is a plain diffuse white;
     * left as-is, a saturated colour on it reads as matte plastic. These two are what make it
     * read as car paint — a tight specular roll-off over a coloured base, which is also what
     * carries the body's shape in a render this dark.
     */
    const val PAINT_METALLIC = 0.80f
    const val PAINT_ROUGHNESS = 0.42f

    /**
     * Gloss-black lacquered alloy. Rougher than the body's clear coat but glossier than a matte
     * wheel, which is what keeps a near-black rim from turning into a silhouette: almost all of
     * what you see of it is reflected highlight along the spoke edges, not its base colour.
     */
    const val RIM_METALLIC = 0.45f
    const val RIM_ROUGHNESS = 0.44f

    /**
     * Near-matte on purpose, and this is the important one. The barrel is a wide, gently curved
     * ring sitting right beside the bodywork, so at any reflective setting it mirrors the body and
     * reads as though the wheel had been painted in the car's colour — the exact effect that made
     * a red car look like it had red wheels. Rough and barely metallic, it reads as the dark metal
     * it is, and stops following the paint around.
     */
    const val RIM_INNER_METALLIC = 0.15f
    const val RIM_INNER_ROUGHNESS = 0.78f

    /** Machined metal: the disc is bare steel, so it wants a real metallic response rather than
     *  the flat one a painted caliper would take. */
    /** Bare machined steel. */
    const val DISC_METALLIC = 0.90f
    const val DISC_ROUGHNESS = 0.28f

    /** Painted metal, and the dullest thing on the wheel — a glossy caliper reads as chrome
     *  jewellery rather than as a brake. */
    const val CALIPER_METALLIC = 0.20f
    const val CALIPER_ROUGHNESS = 0.52f

    /**
     * Attribution for the installed model, displayed on the car stage.
     *
     * The Porsche Mission E asset is CC-BY-4.0, which requires credit wherever the work appears —
     * a licence obligation, not a courtesy, so this string is rendered rather than merely recorded
     * in a file nobody ships. Change it together with `vehicle3dModel/MODEL_LICENSE.md` whenever
     * `scripts/select-car-model.sh` installs a different model.
     */
    const val MODEL_CREDIT = "3D model: Porsche Mission E by kevin · CC BY 4.0"
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

    /**
     * The colours the vehicle is wearing. Writable at runtime — the debug panel drives it — and
     * applied on the spot, so a colour can be judged on the real render instead of rebuilt for.
     * Snapshot state so the panel's own swatches recompose against it.
     */
    var livery: Car3dTuning.Livery by mutableStateOf(Car3dTuning.DEFAULT_LIVERY)

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

    /** Resolved next to [geometry], and for the same reasons kept off snapshot state: it is
     *  written and read from coroutines, never from composition. */
    private var zones: CutawayZones? = null

    /** Compose-layout size of the Scene in px, to rescale `worldToScreenPoint`'s viewport-space
     *  px into the Compose px the overlay draws in (see [screenPositionOf]). Same non-observable
     *  reasoning as [geometry]. */
    private var renderSizePx: IntSize = IntSize.Zero

    /** Last pose actually written to the camera. The retarget-from-current source (§4.3). */
    private var currentPose: CameraPose? = null
    private var animFrom: CameraPose? = null
    private var animTo: CameraPose? = null
    private var animPivot: Float3 = Float3(0f, 0f, 0f)

    /** False until the first camera move is scheduled, so the first transition is the slow
     *  entrance dolly rather than a 250 ms snap. Reset on disposal. */
    private var hasFramed = false

    /** ONE animation drives every camera move — entrance, focus and return. */
    private val cameraProgress = Animatable(1f)

    /**
     * User-applied orbit of the OVERVIEW, accumulated from drags. Unbounded: with nothing focused
     * the car is a turntable and every angle is a legitimate thing to want to look at. Kept here
     * rather than in the ViewModel because it is camera state, not vehicle state: it must not
     * survive a process restart, and nothing outside this renderer has any use for it.
     */
    private var heroAzimuthDeg = 0f

    /**
     * User-applied orbit WHILE FOCUSED, kept separate from [heroAzimuthDeg] and clamped to the
     * focused component's own `orbitLimitDeg`.
     *
     * Separate rather than shared for two reasons. The overview's orbit would otherwise be carried
     * into the focus pose, so the same component would be framed differently depending on where
     * the car happened to be turned when it was tapped. And unfocusing would lose the angle the
     * user had chosen for the overview. Reset to 0 on every focus change, so each component always
     * opens at the framing it was tuned for.
     */
    private var focusAzimuthDeg = 0f

    /**
     * User-applied tilt of the OVERVIEW and of the FOCUSED view, the vertical counterparts of
     * [heroAzimuthDeg] and [focusAzimuthDeg] and kept apart for the same reasons.
     *
     * Both are bounded, which yaw is not: see [Car3dTuning.MIN_ELEVATION_DEG].
     */
    private var heroElevationDeg = 0f
    private var focusElevationDeg = 0f

    /** True while a finger is driving the camera, which suspends animated writes (see [rotateBy]). */
    private var isDragging = false

    /** The focus the camera is currently framing, so [rotateBy] can re-derive the right pose. */
    private var currentFocus: Hotspot? = null

    /**
     * Severity colours per component, last set by the screen. Held rather than applied straight
     * through, because they arrive before the model finishes loading — the screen has severities
     * from the first frame, the GLB takes a second — and would otherwise be dropped on the floor.
     * Re-applied once the model is ready.
     */
    private val componentColors = mutableMapOf<Hotspot, Color>()

    /** The backdrop quad, re-aimed at the camera on every pose write. Null until the Scene and
     *  the model exist, and nulled on disposal along with the rest. */
    private var backdropNode: ImageNode? = null

    /** How far behind the look-at point the backdrop is parked, in world units. Derived from the
     *  model's bounding radius once it is known, so it scales with the car rather than assuming
     *  one. */
    private var backdropOffset = 0f

    /** Half-width and half-height of the backdrop quad's own geometry, measured once when it is
     *  built. [fitBackdrop] divides by it, so the fit holds whatever size SceneView gave it. */
    private var backdropHalfExtent: Float3? = null

    @Composable
    override fun Render(
        focus: Hotspot?,
        onBackgroundTap: () -> Unit,
        modifier: Modifier,
    ) {
        // Only use: reading the GLB's own JSON chunk back for the cutaway's authored colours
        // (see CutawayZones.readZoneBaseColors). The model itself is loaded by SceneView.
        val context = LocalContext.current
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
                showBothFaces(node)
                Log.i(TAG, "livery: ${applyLivery(node, livery)} material instances repainted")
                modelNode = node
                childNodes += node
                val geo = HotspotGeometry.resolve(node)
                geometry = geo
                geo.report.forEach { Log.i(TAG, "hotspot geometry: $it") }
                componentColors.forEach { (h, c) -> applyComponentColor(node, h, c) }
                val cutaway = CutawayZones.resolve(node, context.assets, modelAsset) { livery }
                zones = cutaway
                cutaway.report.forEach { Log.i(TAG, "cutaway: $it") }
                backdropOffset = boundingRadius(node) * Car3dTuning.BACKDROP_DISTANCE_FACTOR
                // Start pulled back; the focus effect below dollies in as the FIRST camera move.
                frameHero(camera, node, distanceScale = Car3dTuning.ENTRANCE_DISTANCE_FACTOR)
                Log.i(TAG, "car renderables: " + node.model.renderableNames.joinToString())
                state = CarRenderState.Ready
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "car model load failed", t)
                state = CarRenderState.Failed(t.message ?: t::class.java.simpleName, t)
            }
        }

        // The backdrop is a scene object, so it is created and destroyed like one. Keyed on the
        // theme: a flip swaps the whole node rather than repainting a texture, which is both
        // simpler and rare enough not to matter.
        val darkStage = currentStage.luminance() < 0.5f
        DisposableEffect(materialLoader, darkStage) {
            val backdrop = ImageNode(
                materialLoader = materialLoader,
                imageFileLocation = if (darkStage) {
                    Car3dTuning.BACKDROP_NIGHT_ASSET
                } else {
                    Car3dTuning.BACKDROP_DAY_ASSET
                },
            ).apply {
                name = "backdrop"
                isShadowCaster = false
                isShadowReceiver = false
                isTouchable = false
            }
            backdropNode = backdrop
            backdrop.axisAlignedBoundingBox.halfExtent.let { h ->
                backdropHalfExtent = if (h[0] > 1e-6f && h[1] > 1e-6f) Float3(h[0], h[1], 1f) else null
            }
            childNodes += backdrop
            currentPose?.let { fitBackdrop(it) }
            onDispose {
                childNodes -= backdrop
                backdropNode = null
                backdropHalfExtent = null
                backdrop.destroy()
            }
        }

        // The ONLY writer of camera.worldTransform after load. Keyed on (node, focus, camera) so
        // it restarts exactly when the target pose changes and on nothing else: `camera` is
        // remembered, `node` flips null->non-null once per model load, and `focus` is the
        // ViewModel's single nullable focusedHotspot. An unrelated recomposition re-runs Render
        // but leaves all three keys identical, so the running animation is untouched.
        val node = modelNode
        LaunchedEffect(node, focus, camera) {
            val n = node ?: return@LaunchedEffect
            val geo = geometry ?: return@LaunchedEffect
            val radius = boundingRadius(n)
            currentFocus = focus
            // Every focus change starts from the tuned framing, including a return to overview.
            focusAzimuthDeg = 0f
            focusElevationDeg = 0f
            val target = poseFor(focus, n, geo)
            val pivot = (n.worldTransform * Float4(geo.centerLocal, 1f)).xyz
            val millis = if (hasFramed) Car3dTuning.FOCUS_MILLIS else Car3dTuning.ENTRANCE_MILLIS
            hasFramed = true
            // The cutaway runs alongside the camera rather than after it, so the window is open
            // by the time the camera arrives. Launched rather than awaited because both are
            // cancelled together when focus changes again, and each retargets from where it is.
            zones?.let { launch { it.animateTo(focus) } }
            animateCameraTo(camera, target, pivot, millis)
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
                // release() first: it is what stops an in-flight fade from writing into a
                // MaterialInstance whose Engine is about to be destroyed.
                backdropOffset = 0f
                backdropHalfExtent = null
                zones?.release()
                zones = null
                cameraNode = null
                modelNode = null
                geometry = null
                renderSizePx = IntSize.Zero
                currentPose = null
                animFrom = null
                animTo = null
                hasFramed = false
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

    override fun occlusionOf(hotspot: Hotspot): Float {
        val cam = cameraNode ?: return 0f
        val model = modelNode ?: return 0f
        val geo = geometry ?: return 0f
        val local = geo.anchorOf(hotspot) ?: return 0f

        // Car-frame constant: which flank this anchor sits on, and how far out along it.
        val s = geo.lateralOf(hotspot)
        if (abs(s) <= Car3dTuning.OCCLUSION_DEADBAND) return 0f // centreline: never occluded

        // Everything below is read from the LIVE transforms, every frame. Nothing here is baked,
        // which is precisely what lets this survive the user-rotatable car planned for a later
        // phase: a static "these anchors are on the left" table would be wrong the moment the
        // model turns, and wrong in a way that looks plausible.
        val m = model.worldTransform
        val anchor = (m * Float4(local, 1f)).xyz
        // w = 0 -> direction, so the model's translation is not picked up.
        val right = normalize((m * Float4(geo.lateralAxis, 0f)).xyz)
        val eye = cam.worldTransform.position

        val toEye = eye - anchor
        val len = length(toEye)
        if (len < 1e-4f) return 0f

        // s * viewLat > 0 means the flank this anchor sits on is the one facing the camera.
        val facing = s * dot(toEye / len, right)
        val t = ((-facing - Car3dTuning.OCCLUSION_START) /
            (Car3dTuning.OCCLUSION_FULL - Car3dTuning.OCCLUSION_START)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t) // smoothstep: C1, so no kink as the car turns
    }

    /**
     * Repaints the car now. Both halves are needed: [applyLivery] covers the shell and the wheels,
     * and the cutaway zones hold their own copies of those same materials, which only refresh when
     * asked — otherwise the doors and rear quarter would keep the previous colour until the next
     * fade.
     */
    fun applyLivery(newLivery: Car3dTuning.Livery) {
        livery = newLivery
        val node = modelNode ?: return
        applyLivery(node, newLivery)
        zones?.refresh()
    }

    override fun setComponentColor(hotspot: Hotspot, color: Color) {
        if (componentColors[hotspot] == color) return
        componentColors[hotspot] = color
        modelNode?.let { applyComponentColor(it, hotspot, color) }
    }

    /** Writes one component's severity colour into its material. No-op if the model has no such
     *  part, which is every hotspot but the motor and the battery. */
    private fun applyComponentColor(node: ModelNode, hotspot: Hotspot, color: Color) {
        val nodeName = Car3dTuning.TINTED_COMPONENTS[hotspot] ?: return
        val renderable = node.renderableNodes.firstOrNull { it.name == nodeName } ?: return
        val r = srgbToLinear(color.red)
        val g = srgbToLinear(color.green)
        val b = srgbToLinear(color.blue)
        val e = Car3dTuning.COMPONENT_EMISSIVE
        renderable.materialInstances.forEach { m ->
            m.setParameter("baseColorFactor", r, g, b, 1f)
            m.setParameter("emissiveFactor", r * e, g * e, b * e)
        }
    }

    override fun rotateBy(deltaDegrees: Float) {
        val focus = currentFocus
        if (focus == null) {
            heroAzimuthDeg += deltaDegrees
        } else {
            // Clamped rather than ignored: the view still follows the finger up to the limit and
            // then stops, which reads as an edge to lean against. Refusing the drag outright
            // would read as the car having stopped responding.
            val limit = (Car3dTuning.FOCUS_FRAMING[focus] ?: Car3dTuning.FOCUS_FRAMING_DEFAULT).orbitLimitDeg
            focusAzimuthDeg = (focusAzimuthDeg + deltaDegrees).coerceIn(-limit, limit)
        }
        val cam = cameraNode ?: return
        val node = modelNode ?: return
        val geo = geometry ?: return
        // Written straight to the camera, not animated: a rotation the finger is driving has to
        // track the finger. applyPose also records it as currentPose, so if a focus transition
        // starts afterwards it begins from exactly where the user left the view.
        applyPose(cam, poseFor(currentFocus, node, geo))
    }

    override fun pitchBy(deltaDegrees: Float) {
        val focus = currentFocus
        if (focus == null) {
            heroElevationDeg = clampElevationOffset(
                current = heroElevationDeg,
                delta = deltaDegrees,
                baseDeg = Car3dTuning.HERO_ELEVATION_DEG,
                limitDeg = Car3dTuning.PITCH_LIMIT_DEG,
            )
        } else {
            val framing = Car3dTuning.FOCUS_FRAMING[focus] ?: Car3dTuning.FOCUS_FRAMING_DEFAULT
            focusElevationDeg = clampElevationOffset(
                current = focusElevationDeg,
                delta = deltaDegrees,
                baseDeg = framing.elevationDeg,
                // A focused framing already declares how far it may be swung horizontally; there
                // is no reason for it to be freer vertically, and one number per component beats
                // eight more constants that would only ever be tuned to the same value.
                limitDeg = minOf(Car3dTuning.PITCH_LIMIT_DEG, framing.orbitLimitDeg),
            )
        }
        val cam = cameraNode ?: return
        val node = modelNode ?: return
        val geo = geometry ?: return
        applyPose(cam, poseFor(currentFocus, node, geo))
    }

    /**
     * Re-fits the backdrop to the camera: parked [Car3dTuning.BACKDROP_DISTANCE_FACTOR] radii
     * behind the look-at point, square-on to the eye, and scaled to exactly fill the frustum at
     * that distance. Called from [applyPose], the single place the camera is written, so the
     * backdrop can never lag a frame behind the view it is backing.
     *
     * Fitting rather than picking a fixed size is what keeps the dot grid a constant size on
     * screen: the quad grows with distance at the same rate the projection shrinks it.
     */
    private fun fitBackdrop(pose: CameraPose) {
        val node = backdropNode ?: return
        val cam = cameraNode ?: return

        val forward = pose.target - pose.eye
        val len = length(forward)
        if (len < 1e-4f) return
        val dir = forward / len
        val distance = len + backdropOffset
        val center = pose.eye + dir * distance

        // Read the frustum straight off the live projection instead of re-deriving it from focal
        // length and viewport: a perspective matrix holds 1/tan(fov/2) in x.x and y.y, so the
        // visible half-extents at any distance are just that distance over those. Deriving it by
        // hand needs Filament's sensor size and the exact aspect handling, both of which are
        // assumptions that were already wrong once — the first attempt fitted the quad to 70% of
        // the stage and left a visible rectangle floating in the middle of it.
        val projection = cam.projectionTransform
        if (projection.x.x <= 0f || projection.y.y <= 0f) return
        val halfHeight = distance / projection.y.y * Car3dTuning.BACKDROP_OVERSCAN
        val halfWidth = distance / projection.x.x * Car3dTuning.BACKDROP_OVERSCAN

        // lookAt(eye = center, target = center + dir) points the quad's -Z along the view
        // direction, i.e. its front face straight back at the camera. Scale is composed into the
        // same matrix rather than set through Node.scale, so one write leaves no intermediate
        // frame where the quad is oriented but not yet sized.
        // Scaled against the quad's OWN measured half-extents rather than an assumed unit size:
        // ImageNode's default plane is not 1x1, and taking it on faith fitted the backdrop to 81%
        // of the stage and left a rectangle floating in the middle of it.
        val base = backdropHalfExtent ?: return
        node.worldTransform = lookAt(eye = center, target = center + dir, up = Float3(0f, 1f, 0f)) *
            scale(Float3(halfWidth / base.x, halfHeight / base.y, 1f))
    }

    /** Set by the drag gesture so an in-flight animation stops writing under the finger. */
    internal fun onDragStateChange(dragging: Boolean) {
        isDragging = dragging
    }

    /**
     * The pose the camera should hold right now: the focused component's framing if something is
     * focused, otherwise the hero framing — both with the user's accumulated orbit applied.
     *
     * Shared by the animation driver and by [rotateBy], so a drag can never derive its pose by a
     * different route than the transition it interrupts.
     */
    private fun poseFor(focus: Hotspot?, node: ModelNode, geo: HotspotGeometry): CameraPose {
        val hero = heroPose(boundingRadius(node), 1f, heroAzimuthDeg, heroElevationDeg)
        return if (focus == null) {
            hero
        } else {
            // heroEye still carries the overview's orbit, which is what decides the side a
            // centreline component is approached from — the focus opens on the flank the user
            // was already looking at, even though its own orbit starts from zero.
            focusPose(focus, node, geo, hero.eye, focusAzimuthDeg, focusElevationDeg) ?: hero
        }
    }

    /**
     * Writes [pose] to the camera and records it as the retarget origin.
     *
     * `up` is deliberately world-Y rather than the car's own up axis: [applyOrientation] leaves
     * the model upright by construction, and a fixed up vector removes a whole class of roll
     * artefact when two poses are interpolated. Revisit only if a later phase pitches or rolls
     * the model itself.
     */
    private fun applyPose(camera: CameraNode, pose: CameraPose) {
        camera.worldTransform = lookAt(eye = pose.eye, target = pose.target, up = Float3(0f, 1f, 0f))
        currentPose = pose
        fitBackdrop(pose)
    }

    /** Snaps [camera] to the hero framing derived from [node]'s current bounding box. */
    private fun frameHero(camera: CameraNode, node: ModelNode, distanceScale: Float) {
        applyPose(camera, heroPose(boundingRadius(node), distanceScale))
    }

    /**
     * Interruptible camera move. **Interruption is the point**: [currentPose] holds the pose
     * written on the last rendered frame, so a call that lands mid-flight restarts from exactly
     * where the camera currently is, never from the abandoned target. `snapTo` and `animateTo`
     * share one `MutatorMutex`, so the in-flight animation is cancelled before this one's first
     * frame runs — that cancellation IS the retarget mechanism, which is why nothing here
     * catches `CancellationException`.
     *
     * Velocity is deliberately not carried across: the spec mandates a fixed 250 ms tween, which
     * has no velocity continuity to preserve. Position continuity is what removes the visible
     * snap, and that comes from [currentPose].
     */
    private suspend fun animateCameraTo(
        camera: CameraNode,
        target: CameraPose,
        pivot: Float3,
        millis: Int,
    ) {
        val from = currentPose
        if (from == null) {
            applyPose(camera, target)
            return
        }
        animFrom = from
        animTo = target
        animPivot = pivot
        cameraProgress.snapTo(0f)
        cameraProgress.animateTo(1f, tween(millis, easing = FastOutSlowInEasing)) {
            // Same null-guard discipline as screenPositionOf: the nulling DisposableEffect runs
            // BEFORE the remember* helpers destroy anything, so a null here means "Filament is
            // going away" and skipping the write is the correct, crash-free response.
            val cam = cameraNode
            val a = animFrom
            val b = animTo
            // A drag that started mid-transition owns the camera: writing an interpolated pose
            // underneath the finger would fight it.
            if (!isDragging && cam != null && a != null && b != null) {
                applyPose(cam, lerpPose(a, b, value, animPivot))
            }
        }
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

/**
 * Turns backface culling off for the whole model, Glass excepted.
 *
 * **Kept only as a fallback for the v1 model.** The donor Porsche was authored for double-sided
 * materials and much of it — near door skins, front fender, mirrors, tyre sidewalls — was wound
 * inwards; culled, those panels did not draw at all, and which of them vanished changed as the car
 * turned. `prep_car.py` now recalculates normals outward before export, so the v2 model needs none
 * of this, and leaving it on costs a little fill and lets a faded cutaway show the car's far side
 * through its near side. [Car3dTuning.FORCE_DOUBLE_SIDED] is the switch; turn it back on if a
 * model without the normals fix is ever installed.
 */
private fun showBothFaces(node: ModelNode) {
    if (!Car3dTuning.FORCE_DOUBLE_SIDED) return
    node.renderableNodes
        .filterNot { it.name in Car3dTuning.KEEP_SINGLE_SIDED }
        .forEach { rn -> rn.materialInstances.forEach { it.setDoubleSided(true) } }
}

/**
 * Applies a [Car3dTuning.Livery] to every material it recognises. Returns how many material
 * instances it touched, which the caller logs: zero means the name match failed and the car is
 * still the colour the model shipped with, an outcome that is otherwise silent.
 *
 * Each slot carries its own finish, not just its own colour. A wheel face is lacquered alloy, a
 * disc is bare machined steel, a body panel is clear-coated paint — give them all one finish and
 * whichever is wrong turns to chrome and reads as jewellery rather than as a part.
 *
 * Alpha is left at 1 even for the zone copies: [CutawayZones] owns their alpha and overwrites it
 * immediately afterwards.
 */
private fun applyLivery(node: ModelNode, livery: Car3dTuning.Livery): Int {
    var painted = 0
    node.renderableNodes.forEach { rn ->
        rn.materialInstances.forEach { m ->
            val color = livery.colorFor(m.name)
            val finish = livery.finishFor(m.name)
            if (color != null && finish != null) {
                m.setParameter(
                    "baseColorFactor",
                    srgbToLinear(color.red), srgbToLinear(color.green), srgbToLinear(color.blue), 1f,
                )
                m.setParameter("metallicFactor", finish.first)
                m.setParameter("roughnessFactor", finish.second)
                painted++
            }
        }
    }
    return painted
}

/**
 * Rotates [v] by unit quaternion [q]. kotlin-math offers no direct quaternion-times-vector
 * operator, so this is the standard v + 2*cross(q.xyz, cross(q.xyz, v) + q.w*v) form, which avoids
 * building a matrix for a single vector.
 */
private fun rotateVector(q: Quaternion, v: Float3): Float3 {
    val u = Float3(q.x, q.y, q.z)
    val t = cross(u, v) + v * q.w
    return v + cross(u, t) * 2f
}

/** Eye and look-at point for the hero camera pose, in world space. */
private data class CameraPose(val eye: Float3, val target: Float3)

/** Radius of the bounding sphere of the SCALED, oriented model, in world units. */
private fun boundingRadius(node: ModelNode): Float {
    val h = node.boundingBox.halfExtent
    val s = node.scale.x // uniform after scaleToUnits
    return sqrt(h[0] * h[0] + h[1] * h[1] + h[2] * h[2]) * s
}

/**
 * Adds [delta] to an accumulated elevation offset and clamps the result so that
 * `baseDeg + offset` stays inside [Car3dTuning.MIN_ELEVATION_DEG]..[Car3dTuning.MAX_ELEVATION_DEG]
 * and the offset itself stays inside +/-[limitDeg].
 *
 * Clamping the OFFSET rather than the final angle is what stops a drag held against a limit from
 * banking up travel that the user then has to unwind before the camera responds again.
 */
private fun clampElevationOffset(
    current: Float,
    delta: Float,
    baseDeg: Float,
    limitDeg: Float,
): Float {
    val lo = maxOf(-limitDeg, Car3dTuning.MIN_ELEVATION_DEG - baseDeg)
    val hi = minOf(limitDeg, Car3dTuning.MAX_ELEVATION_DEG - baseDeg)
    // A framing already outside the band (none is today) would give lo > hi and make coerceIn
    // throw, so the band is collapsed to its nearest end rather than trusted to be ordered.
    return if (lo > hi) hi else (current + delta).coerceIn(lo, hi)
}

private fun heroPose(
    radius: Float,
    distanceScale: Float,
    azimuthOffsetDeg: Float = 0f,
    elevationOffsetDeg: Float = 0f,
): CameraPose {
    val d = radius * Car3dTuning.HERO_DISTANCE_FACTOR * distanceScale
    val az = Math.toRadians((Car3dTuning.HERO_AZIMUTH_DEG + azimuthOffsetDeg).toDouble()).toFloat()
    val el = Math.toRadians(
        (Car3dTuning.HERO_ELEVATION_DEG + elevationOffsetDeg).toDouble(),
    ).toFloat()
    val target = Float3(0f, radius * Car3dTuning.HERO_TARGET_LIFT, 0f)
    val eye = Float3(
        target.x + d * cos(el) * sin(az),
        target.y + d * sin(el),
        target.z + d * cos(el) * cos(az),
    )
    return CameraPose(eye, target)
}

/**
 * Camera pose that frames [hotspot]'s component. Every magnitude derives from the anchor and the
 * model's own bounding radius — not one world-space constant appears below — so this survives a
 * model swap and any future rotation of the car.
 *
 * The camera approaches from whichever side the anchor sits on, or the component would end up
 * behind the bodywork. [heroEye] supplies the fallback side for centreline components (BATTERY,
 * MOTOR, DOORS, BRAKES), which have no side of their own: keeping the camera on the side it is
 * already on avoids a pointless 180-degree swing around the car to show something visible from
 * both sides.
 *
 * INVARIANT, and the reason the focus camera and [Car3dRenderer.occlusionOf] can never disagree:
 * at this pose `dot(normalize(eye - anchor), right)` carries the sign of the anchor's own lateral
 * coordinate, so `occlusionOf` returns 0 for the focused hotspot. The camera physically cannot
 * hide the thing it was asked to focus on.
 */
private fun focusPose(
    hotspot: Hotspot,
    node: ModelNode,
    geo: HotspotGeometry,
    heroEye: Float3,
    azimuthOffsetDeg: Float = 0f,
    elevationOffsetDeg: Float = 0f,
): CameraPose? {
    val local = geo.anchorOf(hotspot) ?: return null
    val m = node.worldTransform
    val anchor = (m * Float4(local, 1f)).xyz
    val center = (m * Float4(geo.centerLocal, 1f)).xyz
    // w = 0 for the axes: directions must not pick up the model's translation.
    val right = normalize((m * Float4(geo.lateralAxis, 0f)).xyz)
    val fwd = normalize((m * Float4(geo.forwardAxis, 0f)).xyz)
    val up = normalize((m * Float4(geo.upAxis, 0f)).xyz)

    val s = geo.lateralOf(hotspot)
    val heroSide = if (dot(heroEye - center, right) >= 0f) 1f else -1f
    val sideSign = if (abs(s) > Car3dTuning.FOCUS_SIDE_THRESHOLD) {
        if (s >= 0f) 1f else -1f
    } else {
        heroSide
    }
    // Explicit comparison rather than sign(): sign(0f) is 0f, which would silently drop the
    // forward term for a dead-centre anchor and quietly change the framing.
    val lonSign = if (geo.longitudinalOf(hotspot) >= 0f) 1f else -1f

    val framing = Car3dTuning.FOCUS_FRAMING[hotspot] ?: Car3dTuning.FOCUS_FRAMING_DEFAULT
    val az = Math.toRadians(framing.azimuthDeg.toDouble()).toFloat()
    val el = Math.toRadians((framing.elevationDeg + elevationOffsetDeg).toDouble()).toFloat()
    val dir = normalize(
        right * (sideSign * cos(el) * cos(az)) +
            fwd * (lonSign * cos(el) * sin(az)) +
            up * sin(el),
    )
    // The user's orbit is applied about the car's OWN up axis, so dragging behaves identically
    // whether the view is framing the whole car or one component.
    val orbited = Quaternion.fromAxisAngle(up, azimuthOffsetDeg).let { q -> rotateVector(q, dir) }
    val d = boundingRadius(node) * framing.distanceFactor
    return CameraPose(eye = anchor + orbited * d, target = anchor)
}

/**
 * Interpolates two camera poses at [t], orbiting the eye around [pivot] on a spherical arc rather
 * than sliding along the straight chord between them.
 *
 * The arc is not decoration. A straight lerp between two poses on OPPOSITE sides of the car flies
 * the eye through the cabin — a few frames of upholstery mid-transition. That is unreachable
 * today only because nothing can focus a far-side component yet; it becomes reachable the moment
 * the Step 5 alert list can jump to a far-side wheel, and permanently once the user can rotate
 * the car.
 *
 * Radius is lerped linearly, so a pure dolly (same direction, different distance) degenerates to
 * exactly the entrance behaviour this replaced.
 */
private fun lerpPose(a: CameraPose, b: CameraPose, t: Float, pivot: Float3): CameraPose {
    val target = mix(a.target, b.target, t)
    val va = a.eye - pivot
    val vb = b.eye - pivot
    val ra = length(va)
    val rb = length(vb)
    if (ra < 1e-5f || rb < 1e-5f) return CameraPose(mix(a.eye, b.eye, t), target) // eye at pivot

    val da = va / ra
    val db = vb / rb
    val ang = acos(clamp(dot(da, db), -1f, 1f))
    val r = ra + (rb - ra) * t

    // sin(ang) -> 0 at BOTH ends of the range: 0 (a pure dolly, directions identical) and PI
    // (exactly antipodal, where no unique arc exists). Both fall back to a normalised linear
    // blend — exactly right for the first, arbitrary but stable for the second.
    val sinAng = sin(ang)
    val dir = if (sinAng < 1e-4f) {
        normalize(mix(da, db, t))
    } else {
        (da * sin((1f - t) * ang) + db * sin(t * ang)) / sinAng
    }
    return CameraPose(eye = pivot + dir * r, target = target)
}

internal fun srgbToLinear(c: Float): Float =
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

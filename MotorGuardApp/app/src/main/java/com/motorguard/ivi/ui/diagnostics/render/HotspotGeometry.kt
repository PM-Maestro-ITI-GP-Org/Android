package com.motorguard.ivi.ui.diagnostics.render

import com.motorguard.ivi.data.vehicle.api.Hotspot
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.inverse
import io.github.sceneview.node.ModelNode
import kotlin.math.sign

/**
 * Position in the car's own frame, independent of model units and of which axis happens to be
 * length/up/lateral in the source asset.
 * @param t 0 = rear extreme, 1 = front extreme
 * @param h 0 = bottom, 1 = top
 * @param s -1 = full left, +1 = full right
 */
data class CarFrame(val t: Float, val h: Float, val s: Float)

/** A named mesh's centre, in ModelNode-LOCAL space. Intermediate value only — see [HotspotGeometry.resolve]. */
private data class Part(val name: String, val p: Float3)

/**
 * Resolves the 8 hotspot anchors once, at model load, from the glTF's named renderables, into
 * ModelNode-LOCAL space. Local rather than world space because it must stay valid across
 * [Car3dTuning]'s own `applyOrientation`/`scaleToUnits`/`centerOrigin` fixups and across every
 * camera move Step 3 makes — none of those touch a child renderable's transform relative to its
 * ModelNode parent (Step 2 design doc §0.2).
 *
 * Knows nothing about severity, telemetry, colour or Compose: a pure geometry lookup table that
 * [Car3dRenderer.screenPositionOf] re-projects through the live camera every frame.
 */
class HotspotGeometry private constructor(
    private val anchors: Map<Hotspot, Float3>,
    private val laterals: Map<Hotspot, Float>,
    private val longitudinals: Map<Hotspot, Float>,
    /** Bounding-box centre, ModelNode-LOCAL. Pivot for camera arcs and origin for the occlusion test. */
    val centerLocal: Float3,
    /** Unit, ModelNode-LOCAL, points to the car's right. */
    val lateralAxis: Float3,
    /** Unit, ModelNode-LOCAL, points to the car's nose. */
    val forwardAxis: Float3,
    /** Unit, ModelNode-LOCAL, points up out of the roof. */
    val upAxis: Float3,
    /** One line per resolution decision. Logged by the caller (`Car3dRenderer`'s `LaunchedEffect`)
     *  — it is the entire debugging story for "why is this dot in the wrong place". */
    val report: List<String>,
) {
    /** Anchor in ModelNode-LOCAL space. Never null after [resolve] — every hotspot always gets
     *  *some* anchor, real or [Tuning.FALLBACK]. */
    fun anchorOf(hotspot: Hotspot): Float3? = anchors[hotspot]

    /**
     * Normalised lateral coordinate: -1 = full left flank, 0 = centreline, +1 = full right.
     * A property of the CAR, in the car's own frame — constant under every camera move and under
     * any future rotation. The view-dependent half of the occlusion test lives in
     * Car3dRenderer.occlusionOf, which combines this with the live camera pose.
     */
    fun lateralOf(hotspot: Hotspot): Float = laterals[hotspot] ?: 0f

    /** Normalised longitudinal coordinate: -1 = tail, 0 = middle, +1 = nose. */
    fun longitudinalOf(hotspot: Hotspot): Float = longitudinals[hotspot] ?: 0f

    object Tuning {
        /** Set true only if the model is mirrored and FL/FR come out swapped. */
        const val MIRROR_LATERAL = false

        /** Fraction of car height the BRAKES anchor is lifted above the front-axle centre, so
         *  the dot sits clear of the wheel well instead of buried in it. */
        /**
         * Near zero on purpose. The front-brake meshes average to a point BETWEEN the wheels at
         * axle height; lifting it from there walks the dot up onto the door skin, which reads as
         * the brakes being somewhere they are not. Kept level with the discs themselves.
         */
        const val BRAKES_LIFT = 0.01f

        /** Used when a name-based resolution is unavailable. MOTOR and BATTERY always use
         *  these — see [resolve] step (7) for why neither has a real mesh to anchor to. */
        val FALLBACK: Map<Hotspot, CarFrame> = mapOf(
            Hotspot.BATTERY to CarFrame(t = 0.50f, h = 0.26f, s = 0.00f),
            Hotspot.MOTOR to CarFrame(t = 0.14f, h = 0.34f, s = 0.00f),
            Hotspot.BRAKES to CarFrame(t = 0.78f, h = 0.30f, s = 0.00f),
            Hotspot.DOORS to CarFrame(t = 0.46f, h = 0.62f, s = 0.00f),
            Hotspot.TIRE_FL to CarFrame(t = 0.80f, h = 0.20f, s = -0.86f),
            Hotspot.TIRE_FR to CarFrame(t = 0.80f, h = 0.20f, s = 0.86f),
            Hotspot.TIRE_RL to CarFrame(t = 0.20f, h = 0.20f, s = -0.86f),
            Hotspot.TIRE_RR to CarFrame(t = 0.20f, h = 0.20f, s = 0.86f),
        )

        /** Additive car-frame nudge applied after resolution. THE one place to move a dot that
         *  lands wrong on the real render. Leave empty until you have seen a screenshot. */
        val NUDGE: Map<Hotspot, CarFrame> = emptyMap()
    }

    companion object {
        /** Never throws. Every hotspot always gets an anchor, from a name or from [Tuning.FALLBACK]. */
        fun resolve(modelNode: ModelNode): HotspotGeometry {
            val report = mutableListOf<String>()

            // (1) Collect every named renderable's centre in ModelNode-local space.
            val invModel = inverse(modelNode.worldTransform)
            val parts = buildList {
                for (rn in modelNode.renderableNodes) {
                    val box = rn.axisAlignedBoundingBox
                    val he = box.halfExtent
                    if (he[0] == 0f && he[1] == 0f && he[2] == 0f) continue // degenerate mesh
                    val c = box.center // LOCAL to rn
                    val w4 = rn.worldTransform * Float4(c[0], c[1], c[2], 1f)
                    val l4 = invModel * w4
                    add(Part(rn.name ?: "", Float3(l4.x, l4.y, l4.z)))
                }
            }

            // (2) Derive the car basis from modelNode.boundingBox — asset-local, same space as
            // `parts`, and unaffected by applyOrientation/scaleToUnits/centerOrigin (those move
            // the ModelNode itself, not a child's coordinates relative to it). A car's smallest
            // dimension is always height, its largest always length.
            val bbox = modelNode.boundingBox
            val he = bbox.halfExtent
            val ct = bbox.center
            val center = Float3(ct[0], ct[1], ct[2])
            val lengthAxisIndex = (0..2).maxBy { he[it] }
            val upAxisIndex = (0..2).minBy { he[it] }
            val lateralAxisIndex = 3 - lengthAxisIndex - upAxisIndex
            report += "basis: lengthAxis=$lengthAxisIndex upAxis=$upAxisIndex lateralAxis=$lateralAxisIndex " +
                "halfExtent=(${he[0]}, ${he[1]}, ${he[2]})"

            fun axis(i: Int) = Float3(if (i == 0) 1f else 0f, if (i == 1) 1f else 0f, if (i == 2) 1f else 0f)

            // (3) Front direction — from the brakes, the only name-carrying front/rear pair.
            // Deliberately not a shortcut on axis index: real basis vectors make left/right
            // correct regardless of which physical axis turned out to be length/up/lateral.
            val frontBrakes = parts.filter { it.name.startsWith("geo_brakes_front") }
            val rearBrakes = parts.filter { it.name.startsWith("geo_brakes_rear") }
            val lengthAxisVec = axis(lengthAxisIndex)
            val frontSign = if (frontBrakes.isNotEmpty() && rearBrakes.isNotEmpty()) {
                val meanFront = frontBrakes.map { dot(it.p, lengthAxisVec) }.average().toFloat()
                val meanRear = rearBrakes.map { dot(it.p, lengthAxisVec) }.average().toFloat()
                sign(meanFront - meanRear)
            } else {
                report += "FRONT UNRESOLVED, assuming +length"
                1f
            }
            val fwd = lengthAxisVec * frontSign
            val up = axis(upAxisIndex)
            val left = cross(up, fwd) // right-handed; glTF is right-handed
            val right = -left * (if (Tuning.MIRROR_LATERAL) -1f else 1f)

            /** Maps a [CarFrame] fraction back into ModelNode-local space using the basis just derived. */
            fun fromCarFrame(f: CarFrame): Float3 =
                center +
                    fwd * ((f.t - 0.5f) * 2f * he[lengthAxisIndex]) +
                    up * ((f.h - 0.5f) * 2f * he[upAxisIndex]) +
                    right * (f.s * he[lateralAxisIndex])

            val anchors = mutableMapOf<Hotspot, Float3>()

            // (4) Tire corners — split by sign against the tire centroid using the derived
            // basis; tire mesh names carry no L/R/F/R identity of their own.
            val tires = parts.filter { it.name.startsWith("geo_tire") } // rims are "geo_rim_*", no collision
            if (tires.size == 4) {
                val axleCenter = tires.map { it.p }.reduce { a, b -> a + b } / 4f
                val classified = tires.map { part ->
                    val isFront = dot(part.p - axleCenter, fwd) > 0f
                    val isRight = dot(part.p - axleCenter, right) > 0f
                    val corner = when {
                        isFront && isRight -> Hotspot.TIRE_FR
                        isFront && !isRight -> Hotspot.TIRE_FL
                        !isFront && isRight -> Hotspot.TIRE_RR
                        else -> Hotspot.TIRE_RL
                    }
                    part to corner
                }
                val distinctCorners = classified.map { it.second }.toSet()
                if (distinctCorners.size == 4) {
                    classified.forEach { (part, corner) -> anchors[corner] = part.p }
                    report += "tires: resolved from mesh names"
                } else {
                    report += "TIRES: corner collision (${classified.map { it.second }}), using FALLBACK for all 4"
                    Hotspot.tireCorners.forEach { anchors[it] = fromCarFrame(Tuning.FALLBACK.getValue(it)) }
                }
            } else {
                report += "TIRES: expected 4, got ${tires.size}, using FALLBACK for all 4"
                Hotspot.tireCorners.forEach { anchors[it] = fromCarFrame(Tuning.FALLBACK.getValue(it)) }
            }

            // (5) BRAKES — front calipers' centroid, else all named brake parts' centroid, else
            // FALLBACK; then lifted clear of the wheel well regardless of source.
            val allBrakes = parts.filter { it.name.startsWith("geo_brakes") }
            var brakesAnchor = when {
                frontBrakes.isNotEmpty() -> frontBrakes.map { it.p }.reduce { a, b -> a + b } / frontBrakes.size.toFloat()
                allBrakes.isNotEmpty() -> allBrakes.map { it.p }.reduce { a, b -> a + b } / allBrakes.size.toFloat()
                else -> {
                    report += "BRAKES: no geo_brakes_* mesh, using FALLBACK"
                    fromCarFrame(Tuning.FALLBACK.getValue(Hotspot.BRAKES))
                }
            }
            brakesAnchor += up * (Tuning.BRAKES_LIFT * 2f * he[upAxisIndex])
            anchors[Hotspot.BRAKES] = brakesAnchor

            // (6) DOORS — centroid of every door mesh, else FALLBACK.
            val doors = parts.filter { it.name.startsWith("geo_doors") }
            anchors[Hotspot.DOORS] = if (doors.isNotEmpty()) {
                doors.map { it.p }.reduce { a, b -> a + b } / doors.size.toFloat()
            } else {
                report += "DOORS: no geo_doors_* mesh, using FALLBACK"
                fromCarFrame(Tuning.FALLBACK.getValue(Hotspot.DOORS))
            }

            // (7) BATTERY and MOTOR always use FALLBACK — neither has a corresponding mesh:
            // there is no motor/engine geometry in the GLB, and the only charging-related mesh
            // (`geo_charging`) is the charge port on the fender, not the floor-mounted pack.
            // Labelling the charge port "Battery" would put the dot in the wrong place.
            report += "MOTOR: no motor/engine mesh in the GLB, using FALLBACK"
            anchors[Hotspot.MOTOR] = fromCarFrame(Tuning.FALLBACK.getValue(Hotspot.MOTOR))
            report += "BATTERY: geo_charging is the charge port, not the pack; using FALLBACK"
            anchors[Hotspot.BATTERY] = fromCarFrame(Tuning.FALLBACK.getValue(Hotspot.BATTERY))

            // (8) Apply the manual nudge, expressed as a car-frame-fraction delta.
            val neutral = fromCarFrame(CarFrame(0.5f, 0.5f, 0f))
            Tuning.NUDGE.forEach { (hotspot, nudge) ->
                anchors[hotspot] = anchors.getValue(hotspot) + (fromCarFrame(nudge) - neutral)
            }

            // (9) Car-frame coordinates of the final anchors. Step 3's occlusion test and focus-pose
            // derivation both need these; both must stay valid if the model is later rotated, which is
            // why they are stored in the car's own frame and never in world space.
            val halfLat = he[lateralAxisIndex]
            val halfLon = he[lengthAxisIndex]
            val laterals = anchors.mapValues { (_, p) ->
                if (halfLat > 1e-6f) (dot(p - center, right) / halfLat).coerceIn(-1f, 1f) else 0f
            }
            val longitudinals = anchors.mapValues { (_, p) ->
                if (halfLon > 1e-6f) (dot(p - center, fwd) / halfLon).coerceIn(-1f, 1f) else 0f
            }
            report += "laterals: " + laterals.entries.joinToString { "${it.key}=${"%.2f".format(it.value)}" }

            // (10) All 8 keys are always present by construction.
            return HotspotGeometry(
                anchors = anchors,
                laterals = laterals,
                longitudinals = longitudinals,
                centerLocal = center,
                lateralAxis = right,
                forwardAxis = fwd,
                upAxis = up,
                report = report,
            )
        }
    }
}

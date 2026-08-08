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

        /**
         * Node names in the diagnostics model (`prep_car.py` output). The Blender merge that
         * builds it collapses the source car's ~123 parts into one shell, destroying the
         * `geo_*` names the original Sketchfab export carried, and re-emits the four wheels
         * under names that state their corner outright — so no centroid classification is
         * needed here, unlike the legacy path below.
         */
        val WHEEL_MESHES: Map<Hotspot, String> = mapOf(
            Hotspot.TIRE_FL to "Wheel_FL",
            Hotspot.TIRE_FR to "Wheel_FR",
            Hotspot.TIRE_RL to "Wheel_RL",
            Hotspot.TIRE_RR to "Wheel_RR",
        )

        /** The two components the merge adds inside the bodywork. Real geometry, unlike the
         *  estimates these two used to be stuck with. */
        val COMPONENT_MESHES: Map<Hotspot, String> = mapOf(
            Hotspot.MOTOR to "Comp_Motor",
            Hotspot.BATTERY to "Comp_Battery",
        )

        /** Used when a name-based resolution is unavailable — which, in the diagnostics model,
         *  is BRAKES and DOORS: see [resolve] steps (5) and (6). */
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

            /** Exact name match. Node names are unique in both supported models. */
            fun named(name: String): Part? = parts.firstOrNull { it.name == name }

            val namedWheels = Tuning.WHEEL_MESHES.mapNotNull { (h, n) -> named(n)?.let { h to it } }.toMap()

            // (3) Front direction — from whichever name-carrying front/rear pair the model has:
            // the wheels in the diagnostics model, the brakes in the legacy one.
            // Deliberately not a shortcut on axis index: real basis vectors make left/right
            // correct regardless of which physical axis turned out to be length/up/lateral.
            val frontBrakes = parts.filter { it.name.startsWith("geo_brakes_front") }
            val rearBrakes = parts.filter { it.name.startsWith("geo_brakes_rear") }
            val lengthAxisVec = axis(lengthAxisIndex)
            fun meanAlongLength(ps: List<Part>) = ps.map { dot(it.p, lengthAxisVec) }.average().toFloat()
            val frontPair: Pair<List<Part>, List<Part>>? = when {
                namedWheels.size == 4 -> listOf(Hotspot.TIRE_FL, Hotspot.TIRE_FR).map { namedWheels.getValue(it) } to
                    listOf(Hotspot.TIRE_RL, Hotspot.TIRE_RR).map { namedWheels.getValue(it) }
                frontBrakes.isNotEmpty() && rearBrakes.isNotEmpty() -> frontBrakes to rearBrakes
                else -> null
            }
            val frontSign = if (frontPair != null) {
                sign(meanAlongLength(frontPair.first) - meanAlongLength(frontPair.second))
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

            // (4) Tire corners. The diagnostics model names each wheel by its corner, so those
            // are taken at their word — and used to check the derived left/right, since a
            // mirrored model would put every dot on the wrong flank in a way that still looks
            // plausible. Reported rather than auto-corrected: MIRROR_LATERAL stays the one lever.
            val legacyTires = parts.filter { it.name.startsWith("geo_tire") } // rims are "geo_rim_*", no collision
            if (namedWheels.size == 4) {
                namedWheels.forEach { (corner, part) -> anchors[corner] = part.p }
                val fl = namedWheels.getValue(Hotspot.TIRE_FL).p
                val fr = namedWheels.getValue(Hotspot.TIRE_FR).p
                report += if (dot(fr - fl, right) > 0f) {
                    "tires: resolved from Wheel_* node names"
                } else {
                    "tires: resolved from Wheel_* node names, but FR is LEFT of FL — " +
                        "model looks mirrored, consider MIRROR_LATERAL"
                }
            } else if (legacyTires.size == 4) {
                // Legacy path, for the pre-merge model: split by sign against the tire centroid
                // using the derived basis, since those tire mesh names carry no L/R/F/R identity.
                val axleCenter = legacyTires.map { it.p }.reduce { a, b -> a + b } / 4f
                val classified = legacyTires.map { part ->
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
                    report += "tires: resolved from geo_tire* mesh names"
                } else {
                    report += "TIRES: corner collision (${classified.map { it.second }}), using FALLBACK for all 4"
                    Hotspot.tireCorners.forEach { anchors[it] = fromCarFrame(Tuning.FALLBACK.getValue(it)) }
                }
            } else {
                report += "TIRES: no Wheel_* nodes and ${legacyTires.size} geo_tire* meshes, " +
                    "using FALLBACK for all 4"
                Hotspot.tireCorners.forEach { anchors[it] = fromCarFrame(Tuning.FALLBACK.getValue(it)) }
            }

            // (5) BRAKES — front calipers' centroid, else all named brake parts' centroid, else
            // FALLBACK; then lifted clear of the wheel well regardless of source.
            val allBrakes = parts.filter { it.name.startsWith("geo_brakes") }
            var brakesAnchor = when {
                frontBrakes.isNotEmpty() -> frontBrakes.map { it.p }.reduce { a, b -> a + b } / frontBrakes.size.toFloat()
                allBrakes.isNotEmpty() -> allBrakes.map { it.p }.reduce { a, b -> a + b } / allBrakes.size.toFloat()
                else -> {
                    // The diagnostics model has no brake geometry at all: the merge dissolved
                    // the calipers and discs into the shell, and the components it adds are the
                    // motor and the pack. There is nothing to anchor to, so this stays on the
                    // estimate table — an invented anchor would be a guess wearing the costume
                    // of a measurement.
                    report += "BRAKES: no brake mesh in this model, using FALLBACK"
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
                // Same story as the brakes: the doors are part of the single merged shell here,
                // with no separable mesh of their own. FALLBACK, and said out loud.
                report += "DOORS: no door mesh in this model, using FALLBACK"
                fromCarFrame(Tuning.FALLBACK.getValue(Hotspot.DOORS))
            }

            // (7) MOTOR and BATTERY — the two components the diagnostics model plants inside the
            // bodywork, and the first real geometry these two have ever had. Before the merge
            // there was no motor mesh at all, and the only battery-adjacent mesh (`geo_charging`)
            // was the charge port on the fender rather than the floor-mounted pack, so both were
            // permanently estimated. `Comp_Motor` and `Comp_Battery` are the parts themselves.
            Tuning.COMPONENT_MESHES.forEach { (hotspot, meshName) ->
                val part = named(meshName)
                anchors[hotspot] = if (part != null) {
                    report += "$hotspot: resolved from $meshName"
                    part.p
                } else {
                    report += "$hotspot: no $meshName mesh, using FALLBACK"
                    fromCarFrame(Tuning.FALLBACK.getValue(hotspot))
                }
            }

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

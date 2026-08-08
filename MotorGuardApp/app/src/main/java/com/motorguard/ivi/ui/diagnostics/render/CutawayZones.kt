package com.motorguard.ivi.ui.diagnostics.render

import android.content.res.AssetManager
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import com.google.android.filament.MaterialInstance
import com.motorguard.ivi.data.vehicle.api.Hotspot
import io.github.sceneview.node.ModelNode
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val TAG = "CutawayZones"

/**
 * Drives the cutaway: the patch of bodywork over a component fades out when that component is
 * focused, opening a window onto it, and fades back when focus leaves. Everything else about the
 * car — paint, glass, wheels, the rest of the shell — is never touched.
 *
 * The zones are baked by `vehicle3dModel/tools/prep_car.py`, which carves the faces within a
 * radius of each component out of the shell into their own node (`Zone_Battery`, `Zone_Motor`)
 * and gives them BLEND copies of whatever materials they inherited. Two consequences shape this
 * class:
 *
 * 1. **A zone has many materials, not one.** It is a patch of real bodywork, so it carries every
 *    material the shell used in that region — currently 16 for the battery and 17 for the motor.
 *    They must move together or the window opens in stripes, so this animates one `openness`
 *    value per zone and writes it to every material instance of that zone.
 * 2. **Only alpha may change.** `baseColorFactor` is a float4 and Filament exposes no getter for
 *    it, so writing alpha means writing the colour too. The authored RGB is therefore read back
 *    out of the GLB at load ([readZoneBaseColors]) and re-sent unchanged on every frame of the
 *    fade — forcing white instead would repaint the black roof-liner and floor patches the moment
 *    a component was focused.
 *
 * Nothing here knows about severity or telemetry: it is told which hotspot is focused and fades
 * accordingly.
 */
class CutawayZones private constructor(
    private val zones: Map<Hotspot, Zone>,
    /** Read on every write rather than captured once, so a livery changed at runtime reaches the
     *  zone copies of the body, wheel and brake materials without reloading the model. */
    private val livery: () -> Car3dTuning.Livery,
    /** One line per zone resolved or missed. Logged by the caller — the debugging story for
     *  "why did nothing fade". */
    val report: List<String>,
) {

    object Tuning {
        /** glTF node name per hotspot. Zones exist only for the two components buried inside the
         *  bodywork; a wheel needs no window cut for it. */
        val ZONE_NODES: Map<Hotspot, String> = mapOf(
            Hotspot.BATTERY to "Zone_Battery",
            Hotspot.MOTOR to "Zone_Motor",
        )

        /**
         * Alpha the zone's materials fall to when focused. Deliberately not 0: a fully erased
         * patch reads as a hole punched in the car, while a faint ghost of the panel keeps the
         * component located in a vehicle the driver still recognises.
         *
         * Halved to compensate for double-sided rendering (`Car3dTuning.FORCE_DOUBLE_SIDED`):
         * both the near and far faces of a zone contribute, so what reaches the eye is nearer
         * 1-(1-a)² than a. Raise it towards 0.16 if that flag ever goes off.
         */
        const val OPEN_ALPHA = 0.08f

        /**
         * Alpha a zone rests at — **1.0, overriding the 0.99 the GLB was authored with**.
         *
         * The 0.99 exists only to make Blender's exporter write `alphaMode: BLEND`, since an
         * OPAQUE material can never be faded at runtime. It is not meant to be seen. Left at 0.99
         * it is very much seen: a blended surface writes no depth, and Filament sorts the
         * transparent pass per renderable rather than per triangle, so the far side of a zone gets
         * painted over the near side and the doors flicker between solid and see-through as the
         * car turns. At 1.0 the blend is an identity operation, and [DEPTH_WRITE_BELOW] settles
         * the ordering within the patch itself.
         */
        const val CLOSED_ALPHA = 1f

        /**
         * Below this openness the zone writes depth, so its own near faces occlude its far ones
         * and the patch reads as solid bodywork. Above it depth writing is turned off, which is
         * what lets the component inside show through the fading panel at all.
         */
        const val DEPTH_WRITE_BELOW = 0.02f

        /** Matches the camera transition, so the window finishes opening exactly as the camera
         *  arrives rather than either one trailing the other. */
        const val FADE_MILLIS = Car3dTuning.FOCUS_MILLIS
    }

    /**
     * Set on disposal. Every write checks it: a `MaterialInstance` whose Engine has been destroyed
     * is a dangling native pointer, and the fade coroutine can outlive the Scene by a frame — the
     * same hazard, and the same guard, as `Car3dRenderer.screenPositionOf`.
     */
    private var released = false

    /** Hotspots that actually have a window to open. Empty if the installed model has no zones. */
    val zonedHotspots: Set<Hotspot> get() = zones.keys

    /**
     * Fades [focus]'s zone open and every other zone shut, concurrently. Suspends until all of
     * them settle. Safe to call again mid-fade: each zone has its own `Animatable`, so the new
     * call retargets from wherever that zone currently sits rather than snapping.
     */
    suspend fun animateTo(focus: Hotspot?) = coroutineScope {
        zones.forEach { (hotspot, zone) ->
            launch {
                zone.openness.animateTo(
                    targetValue = if (hotspot == focus) 1f else 0f,
                    animationSpec = tween(Tuning.FADE_MILLIS, easing = FastOutSlowInEasing),
                ) { write(zone, value) }
            }
        }
    }

    /** Re-sends every zone's colour at its current openness. Call after changing the livery:
     *  a zone that is not mid-fade would otherwise keep the colours of the previous one. */
    fun refresh() = zones.values.forEach { write(it, it.openness.value) }

    /** Call from the Scene's `onDispose`, before Filament tears the model down. */
    fun release() {
        released = true
    }

    /**
     * Writes one zone's state: how transparent the patch is, and whether it still behaves like
     * solid bodywork for depth purposes.
     */
    private fun write(zone: Zone, openness: Float) {
        if (released) return
        val alpha = Tuning.CLOSED_ALPHA + (Tuning.OPEN_ALPHA - Tuning.CLOSED_ALPHA) * openness
        val depthWrite = openness < Tuning.DEPTH_WRITE_BELOW
        zone.materials.forEach { m ->
            // A zone's copy of a liveried material follows the livery; anything else keeps the
            // colour the GLB authored, which is why both are carried on ZoneMaterial.
            val c = livery().colorFor(m.name)
            if (c == null) {
                m.instance.setParameter("baseColorFactor", m.red, m.green, m.blue, alpha)
            } else {
                m.instance.setParameter(
                    "baseColorFactor",
                    srgbToLinear(c.red), srgbToLinear(c.green), srgbToLinear(c.blue), alpha,
                )
            }
            m.instance.setDepthWrite(depthWrite)
        }
    }

    /** One primitive's material: its name, so the livery can claim it, and the colour the GLB
     *  authored, for when the livery does not. */
    private class ZoneMaterial(
        val instance: MaterialInstance,
        val name: String?,
        val red: Float,
        val green: Float,
        val blue: Float,
    )

    private class Zone(
        val materials: List<ZoneMaterial>,
        /** 0 = resting (patch indistinguishable from the shell), 1 = fully open. */
        val openness: Animatable<Float, *> = Animatable(0f),
    )

    companion object {
        /** Never throws. A model with no zones yields an empty instance that fades nothing. */
        fun resolve(
            modelNode: ModelNode,
            assets: AssetManager,
            modelAsset: String,
            livery: () -> Car3dTuning.Livery,
        ): CutawayZones {
            val report = mutableListOf<String>()

            val authored = try {
                readZoneBaseColors(assets, modelAsset, Tuning.ZONE_NODES.values.toSet())
            } catch (t: Throwable) {
                // Not fatal: without the authored colours the fade would have to invent them, so
                // the honest failure is no cutaway rather than a repainted car.
                report += "GLB read failed (${t.message}); no cutaway"
                Log.w(TAG, "could not read zone base colours from $modelAsset", t)
                emptyMap()
            }

            val zones = mutableMapOf<Hotspot, Zone>()
            Tuning.ZONE_NODES.forEach { (hotspot, nodeName) ->
                val node = modelNode.renderableNodes.firstOrNull { it.name == nodeName }
                if (node == null) {
                    report += "$nodeName: no such node in the model; $hotspot will not open"
                    return@forEach
                }
                val instances = node.materialInstances
                val colors = authored[nodeName].orEmpty()
                if (colors.size != instances.size) {
                    // Zipped by primitive index, which is the order gltfio builds them in. A
                    // mismatch means that assumption no longer holds, and guessing which material
                    // is which would tint the bodywork at random.
                    report += "$nodeName: ${instances.size} materials but ${colors.size} authored " +
                        "colours; not fading it rather than guessing"
                    return@forEach
                }
                zones[hotspot] = Zone(
                    materials = instances.mapIndexed { i, instance ->
                        val c = colors[i]
                        ZoneMaterial(instance, instance.name, c[0], c[1], c[2])
                    },
                )
                report += "$nodeName -> $hotspot, ${instances.size} materials, " +
                    "authored alpha ${colors.first()[3]} overridden to ${Tuning.CLOSED_ALPHA}"
            }

            if (zones.isEmpty()) report += "no cutaway zones resolved; the car will never open"
            return CutawayZones(zones, livery, report).apply {
                // Write the closed state once, immediately: until something is focused nothing
                // else would, and the car would sit at the authored 0.99 showing its own interior
                // through the doors (see Tuning.CLOSED_ALPHA).
                zones.values.forEach { write(it, 0f) }
            }
        }

        /**
         * Per-primitive `baseColorFactor` (r, g, b, a) of each named node's mesh, in primitive
         * order, read straight from the GLB's JSON chunk.
         *
         * Filament's `MaterialInstance` can set a parameter but not read one, and gltfio keeps no
         * record of what the glTF authored — so the only place the resting colours still exist at
         * runtime is the file itself. Only the JSON chunk is read (~80 KB of a 15 MB asset); the
         * binary chunk behind it is never touched.
         */
        private fun readZoneBaseColors(
            assets: AssetManager,
            modelAsset: String,
            nodeNames: Set<String>,
        ): Map<String, List<FloatArray>> {
            val json = assets.open(modelAsset).use { stream ->
                val input = DataInputStream(stream)
                val header = ByteArray(12).also { input.readFully(it) }
                val magic = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
                require(magic == GLB_MAGIC) { "$modelAsset is not a binary glTF" }

                val chunkHeader = ByteArray(8).also { input.readFully(it) }
                val cb = ByteBuffer.wrap(chunkHeader).order(ByteOrder.LITTLE_ENDIAN)
                val length = cb.int
                require(cb.int == GLB_CHUNK_JSON) { "first GLB chunk of $modelAsset is not JSON" }

                JSONObject(String(ByteArray(length).also { input.readFully(it) }, Charsets.UTF_8))
            }

            val nodes = json.optJSONArray("nodes") ?: return emptyMap()
            val meshes = json.optJSONArray("meshes") ?: return emptyMap()
            val materials = json.optJSONArray("materials") ?: return emptyMap()

            val result = mutableMapOf<String, List<FloatArray>>()
            for (i in 0 until nodes.length()) {
                val node = nodes.getJSONObject(i)
                val name = node.optString("name")
                if (name !in nodeNames || !node.has("mesh")) continue
                val primitives = meshes.getJSONObject(node.getInt("mesh"))
                    .optJSONArray("primitives") ?: continue
                result[name] = (0 until primitives.length()).map { p ->
                    val index = primitives.getJSONObject(p).optInt("material", -1)
                    val factor = materials.optJSONObject(index)
                        ?.optJSONObject("pbrMetallicRoughness")
                        ?.optJSONArray("baseColorFactor")
                    // glTF's own default when the factor is omitted: opaque white.
                    FloatArray(4) { c -> factor?.optDouble(c, 1.0)?.toFloat() ?: 1f }
                }
            }
            return result
        }

        /** "glTF", little-endian. */
        private const val GLB_MAGIC = 0x46546C67

        /** "JSON", little-endian. */
        private const val GLB_CHUNK_JSON = 0x4E4F534A
    }
}

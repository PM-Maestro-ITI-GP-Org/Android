package com.motorguard.ivi.ui.nav.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.motorguard.ivi.data.nav.NavConfig
import com.motorguard.ivi.ui.theme.Tokens
import java.util.Locale

/**
 * The map's own stylesheet, generated from [Tokens] instead of shipped as a static asset.
 *
 * Two reasons it is Kotlin and not `assets/map_style.json`:
 *  1. the design rule is that colour has exactly one source of truth — a hand-written JSON
 *     file would be a second copy of the palette, guaranteed to drift;
 *  2. Day and Night need two styles, and deriving the light one from [Tokens.Day] is a few
 *     lerps rather than a second 200-line file to keep in sync.
 *
 * Data is OpenFreeMap's planet tiles in the OpenMapTiles schema (no key, no quota). The layer
 * ids and `source-layer` names below are that schema's, so pointing [NavConfig.tileJsonUrl] at
 * any other OpenMapTiles server — including a self-hosted one — works unchanged.
 */
internal object MapStyle {

    /** Ids the route layers are inserted above, so the route never hides under a road. */
    const val TOP_LAYER_ID = "mg-place-label"

    fun json(dark: Boolean): String {
        val p = if (dark) nightPalette() else dayPalette()
        return """
        {
          "version": 8,
          "name": "MotorGuard ${if (dark) "Night" else "Day"}",
          "glyphs": "${NavConfig.glyphsUrl}",
          "sources": {
            "omt": { "type": "vector", "url": "${NavConfig.tileJsonUrl}" }
          },
          "layers": [
            ${background(p)},
            ${landcover(p)},
            ${park(p)},
            ${water(p)},
            ${waterway(p)},
            ${building(p)},
            ${roadCasing("mg-road-minor-casing", MINOR, p.roadCasing, MINOR_WIDTHS, 12.0)},
            ${roadCasing("mg-road-major-casing", MAJOR, p.roadCasing, MAJOR_WIDTHS, 8.0)},
            ${roadCasing("mg-road-trunk-casing", TRUNK, p.roadCasing, TRUNK_WIDTHS, 5.0)},
            ${roadFill("mg-road-minor", MINOR, p.roadMinor, MINOR_WIDTHS, 12.0)},
            ${roadFill("mg-road-major", MAJOR, p.roadMajor, MAJOR_WIDTHS, 8.0)},
            ${roadFill("mg-road-trunk", TRUNK, p.roadTrunk, TRUNK_WIDTHS, 5.0)},
            ${roadLabel(p)},
            ${placeLabel(p)}
          ]
        }
        """.trimIndent()
    }

    // ---------------------------------------------------------------- palette

    /**
     * Map colours are *derived* from the tokens, never new hexes: everything is a blend of the
     * rail background with an existing token, so a palette change propagates into the map.
     */
    private class Palette(
        val background: Color,
        val land: Color,
        val park: Color,
        val water: Color,
        val building: Color,
        val buildingOpacity: Double,
        val roadCasing: Color,
        val roadMinor: Color,
        val roadMajor: Color,
        val roadTrunk: Color,
        val label: Color,
        val labelHalo: Color,
    )

    private fun nightPalette(): Palette {
        val base = Tokens.Night.railBg
        val t = Tokens.Night
        return Palette(
            background = base,
            land = lerp(base, t.panel, 0.35f),
            park = lerp(base, t.success, 0.10f),
            water = lerp(base, t.accent, 0.16f),
            building = lerp(base, t.panel, 0.85f),
            buildingOpacity = 0.9,
            roadCasing = lerp(base, Color.Black, 0.45f),
            roadMinor = lerp(base, t.onBaseDim, 0.22f),
            roadMajor = lerp(base, t.onBaseDim, 0.38f),
            roadTrunk = lerp(base, t.accent, 0.26f),
            label = t.onBaseDim,
            labelHalo = lerp(base, Color.Black, 0.5f),
        )
    }

    private fun dayPalette(): Palette {
        val base = Tokens.Day.base
        val t = Tokens.Day
        return Palette(
            background = base,
            land = lerp(base, t.onBaseDim, 0.06f),
            park = lerp(base, t.success, 0.16f),
            water = lerp(base, t.accent, 0.30f),
            building = lerp(base, t.onBaseDim, 0.14f),
            buildingOpacity = 0.8,
            roadCasing = lerp(base, t.onBaseDim, 0.28f),
            roadMinor = Color.White,
            roadMajor = Color.White,
            roadTrunk = lerp(Color.White, t.caution, 0.22f),
            label = t.onBaseDim,
            labelHalo = Color.White,
        )
    }

    // ---------------------------------------------------------------- layers

    private fun background(p: Palette) = """
        { "id": "mg-background", "type": "background",
          "paint": { "background-color": "${p.background.hex()}" } }
    """.trimIndent()

    private fun landcover(p: Palette) = """
        { "id": "mg-landcover", "type": "fill", "source": "omt", "source-layer": "landcover",
          "paint": { "fill-color": "${p.land.hex()}", "fill-opacity": 0.5 } }
    """.trimIndent()

    private fun park(p: Palette) = """
        { "id": "mg-park", "type": "fill", "source": "omt", "source-layer": "park",
          "paint": { "fill-color": "${p.park.hex()}", "fill-opacity": 0.7 } }
    """.trimIndent()

    private fun water(p: Palette) = """
        { "id": "mg-water", "type": "fill", "source": "omt", "source-layer": "water",
          "paint": { "fill-color": "${p.water.hex()}" } }
    """.trimIndent()

    private fun waterway(p: Palette) = """
        { "id": "mg-waterway", "type": "line", "source": "omt", "source-layer": "waterway",
          "minzoom": 8,
          "paint": { "line-color": "${p.water.hex()}",
                     "line-width": ["interpolate", ["linear"], ["zoom"], 8, 0.8, 16, 3.5] } }
    """.trimIndent()

    /** Buildings fade in rather than popping at the minzoom boundary. */
    private fun building(p: Palette) = """
        { "id": "mg-building", "type": "fill", "source": "omt", "source-layer": "building",
          "minzoom": 13,
          "paint": { "fill-color": "${p.building.hex()}",
                     "fill-opacity": ["interpolate", ["linear"], ["zoom"], 13, 0, 14.5, ${p.buildingOpacity}] } }
    """.trimIndent()

    private fun roadCasing(id: String, classes: String, color: Color, widths: String, minZoom: Double) = """
        { "id": "$id", "type": "line", "source": "omt", "source-layer": "transportation",
          "minzoom": $minZoom,
          "filter": ["match", ["get", "class"], $classes, true, false],
          "layout": { "line-cap": "round", "line-join": "round" },
          "paint": { "line-color": "${color.hex()}", "line-width": $widths, "line-gap-width": 0 } }
    """.trimIndent()

    private fun roadFill(id: String, classes: String, color: Color, widths: String, minZoom: Double) = """
        { "id": "$id", "type": "line", "source": "omt", "source-layer": "transportation",
          "minzoom": $minZoom,
          "filter": ["match", ["get", "class"], $classes, true, false],
          "layout": { "line-cap": "round", "line-join": "round" },
          "paint": { "line-color": "${color.hex()}", "line-width": $widths } }
    """.trimIndent()

    private fun roadLabel(p: Palette) = """
        { "id": "mg-road-label", "type": "symbol", "source": "omt",
          "source-layer": "transportation_name", "minzoom": 13,
          "filter": ["match", ["get", "class"],
                     ["motorway", "trunk", "primary", "secondary", "tertiary"], true, false],
          "layout": { "symbol-placement": "line",
                      "text-field": ["coalesce", ["get", "name_en"], ["get", "name"]],
                      "text-font": ["Noto Sans Regular"],
                      "text-rotation-alignment": "map",
                      "text-size": ["interpolate", ["linear"], ["zoom"], 13, 11, 17, 14] },
          "paint": { "text-color": "${p.label.hex()}",
                     "text-halo-color": "${p.labelHalo.hex()}", "text-halo-width": 1.2 } }
    """.trimIndent()

    private fun placeLabel(p: Palette) = """
        { "id": "$TOP_LAYER_ID", "type": "symbol", "source": "omt", "source-layer": "place",
          "filter": ["match", ["get", "class"],
                     ["city", "town", "village", "suburb"], true, false],
          "layout": { "text-field": ["coalesce", ["get", "name_en"], ["get", "name"]],
                      "text-font": ["Noto Sans Bold"],
                      "text-size": ["interpolate", ["linear"], ["zoom"], 4, 11, 12, 17],
                      "text-max-width": 8 },
          "paint": { "text-color": "${p.label.hex()}",
                     "text-halo-color": "${p.labelHalo.hex()}", "text-halo-width": 1.4 } }
    """.trimIndent()

    // OpenMapTiles `transportation.class` groupings, widest road first in visual weight.
    private const val MINOR = """["minor", "service", "track"]"""
    private const val MAJOR = """["secondary", "tertiary"]"""
    private const val TRUNK = """["motorway", "trunk", "primary"]"""

    // Casing and fill share a width ramp; the casing sits under a slightly narrower fill,
    // which is what produces the outlined-road look without a second geometry.
    private const val MINOR_WIDTHS =
        """["interpolate", ["linear"], ["zoom"], 12, 0.5, 15, 2.0, 18, 8.0]"""
    private const val MAJOR_WIDTHS =
        """["interpolate", ["linear"], ["zoom"], 8, 0.6, 12, 1.6, 15, 4.0, 18, 14.0]"""
    private const val TRUNK_WIDTHS =
        """["interpolate", ["linear"], ["zoom"], 5, 0.6, 10, 2.0, 14, 5.0, 18, 20.0]"""

    /**
     * MapLibre style JSON wants `#RRGGBB`; alpha is expressed with the `*-opacity` paints.
     *
     * [Locale.ROOT] is not optional here. `%X` honours the default locale's digits, so on an
     * `ar-EG` device — entirely plausible for this project — the default-locale overload would
     * emit Arabic-Indic numerals and every colour in the style would fail to parse.
     */
    private fun Color.hex(): String = String.format(
        Locale.ROOT,
        "#%02X%02X%02X",
        (red * 255f).toInt().coerceIn(0, 255),
        (green * 255f).toInt().coerceIn(0, 255),
        (blue * 255f).toInt().coerceIn(0, 255),
    )
}

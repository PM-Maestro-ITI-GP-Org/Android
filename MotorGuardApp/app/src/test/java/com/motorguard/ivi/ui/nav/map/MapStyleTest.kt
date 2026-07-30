package com.motorguard.ivi.ui.nav.map

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MapStyle] builds its JSON by string interpolation, which is fast to read and very easy to
 * break with one misplaced comma. A malformed style does not crash — MapLibre just quietly
 * renders nothing — so this is the check that would otherwise have to happen on the Pi.
 */
class MapStyleTest {

    @Test
    fun `night style is valid json with the expected shape`() = assertValidStyle(dark = true)

    @Test
    fun `day style is valid json with the expected shape`() = assertValidStyle(dark = false)

    private fun assertValidStyle(dark: Boolean) {
        val root = JsonParser.parseString(MapStyle.json(dark)).asJsonObject

        assertEquals(8, root["version"].asInt)
        assertTrue(root["glyphs"].asString.contains("{fontstack}"))

        val source = root["sources"].asJsonObject["omt"].asJsonObject
        assertEquals("vector", source["type"].asString)
        assertTrue(source["url"].asString.startsWith("https://"))

        val layers = root["layers"].asJsonArray
        assertTrue("expected a full basemap, got ${layers.size()} layers", layers.size() >= 12)

        val ids = layers.map { it.asJsonObject["id"].asString }
        assertEquals("layer ids must be unique", ids.size, ids.toSet().size)
        assertTrue(ids.contains(MapStyle.TOP_LAYER_ID))

        // Every non-background layer has to name the source it reads, or it draws nothing.
        layers.map { it.asJsonObject }
            .filter { it["type"].asString != "background" }
            .forEach { layer ->
                assertEquals("omt", layer["source"].asString)
                assertTrue("${layer["id"].asString} has no source-layer", layer.has("source-layer"))
            }
    }

    @Test
    fun `colours are ascii hex regardless of default locale`() {
        // String.format honours the default locale's digits: on ar-EG the un-rooted overload
        // emits Arabic-Indic numerals and every colour in the style fails to parse.
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG-u-nu-arab"))
            val json = MapStyle.json(dark = true)
            val hexes = Regex("#[0-9A-Fa-f]{6}").findAll(json).count()
            assertTrue("expected ASCII hex colours, found $hexes", hexes >= 10)
            // Nothing that looks like a colour may contain a non-ASCII digit.
            assertTrue(
                "style contains non-ASCII characters",
                json.none { it.code > 127 && it != '·' },
            )
            JsonParser.parseString(json)
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}

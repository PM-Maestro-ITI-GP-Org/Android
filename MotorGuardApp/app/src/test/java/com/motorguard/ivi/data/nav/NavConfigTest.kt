package com.motorguard.ivi.data.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards against the endpoint mistake that is genuinely hard to debug.
 *
 * `valhalla.openstreetmap.de` and `valhalla1.openstreetmap.de` differ by one character, and the
 * wrong one does not fail like a wrong URL: it serves the demo *web app*, answering `/route`
 * with an HTML page and HTTP 200. That sails past a status check and only surfaces much later,
 * inside a JSON parser, as "String cannot be converted to JSONObject" — an error that points
 * nowhere near the actual cause.
 */
class NavConfigTest {

    @Test
    fun `valhalla base url is an api host, not the demo web app`() {
        val url = NavConfig.valhallaBaseUrl
        assertTrue("must be https: $url", url.startsWith("https://"))
        assertFalse("must not end in a slash (paths are appended raw): $url", url.endsWith("/"))
        assertTrue(
            "must be a numbered API host — the bare host serves HTML: $url",
            Regex("""^https://valhalla[1-9]\.""").containsMatchIn(url) ||
                !url.contains("openstreetmap.de"), // a self-hosted instance is exempt
        )
    }

    @Test
    fun `photon base url has no trailing slash`() {
        assertTrue(NavConfig.photonBaseUrl.startsWith("https://"))
        assertFalse(NavConfig.photonBaseUrl.endsWith("/"))
    }

    @Test
    fun `public endpoints are identified, as their fair-use policies ask`() {
        assertTrue(NavConfig.clientId.isNotBlank())
        assertTrue(NavConfig.userAgent.isNotBlank())
    }
}

package com.motorguard.ivi.data.nav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The whole HTTP layer for navigation: one GET that returns a string.
 *
 * Photon and Valhalla are both single-shot JSON GETs, so pulling in OkHttp/Retrofit/Moshi
 * would add three dependencies to save about thirty lines. `HttpURLConnection` + the
 * platform's `org.json` keeps the APK (and the AOSP prebuilt list) unchanged.
 */
internal object NavHttp {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000

    /**
     * @throws IOException on a non-2xx status or transport failure. Callers let it propagate;
     *         the ViewModel is the single place that turns a failure into user-visible text.
     */
    suspend fun getString(url: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", NavConfig.userAgent)
            setRequestProperty("X-Client-Id", NavConfig.clientId)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("HTTP $code from ${URL(url).host}${detail.take(200).prefixedOrEmpty()}")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun String.prefixedOrEmpty(): String = if (isBlank()) "" else ": $this"
}

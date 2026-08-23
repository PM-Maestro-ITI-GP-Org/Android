package com.motorguard.ivi.data.weather

import com.motorguard.ivi.data.nav.GeoPoint
import com.motorguard.ivi.data.nav.NavHttp
import org.json.JSONObject

data class Weather(val tempC: Float, val code: Int) {
    val description: String get() = descriptionOf(code)
}

/**
 * Current conditions from Open-Meteo — no key, no quota, the same public fair-use instance
 * pattern [com.motorguard.ivi.data.nav.NavConfig] already uses for tiles, routing and
 * geocoding. [NavHttp] is reused rather than duplicated: it is a plain `HttpURLConnection` GET
 * with no navigation-specific behaviour baked in.
 */
object WeatherService {
    suspend fun current(point: GeoPoint): Weather {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${point.lat}&longitude=${point.lon}" +
            "&current=temperature_2m,weather_code&timezone=auto"
        val current = JSONObject(NavHttp.getString(url)).getJSONObject("current")
        return Weather(
            tempC = current.getDouble("temperature_2m").toFloat(),
            code = current.getInt("weather_code"),
        )
    }
}

/** WMO weather codes, the small subset Open-Meteo's `current` actually returns. */
private fun descriptionOf(code: Int): String = when (code) {
    0 -> "Clear sky"
    1, 2 -> "Partly cloudy"
    3 -> "Overcast"
    45, 48 -> "Fog"
    51, 53, 55, 56, 57 -> "Drizzle"
    61, 63, 65, 66, 67 -> "Rain"
    71, 73, 75, 77 -> "Snow"
    80, 81, 82 -> "Rain showers"
    85, 86 -> "Snow showers"
    95, 96, 99 -> "Thunderstorm"
    else -> "—"
}

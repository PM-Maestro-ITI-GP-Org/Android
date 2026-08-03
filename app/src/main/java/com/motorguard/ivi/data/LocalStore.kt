package com.motorguard.ivi.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Everything the driver chose that should still be true after a reboot: theme and accent.
 *
 * SharedPreferences rather than DataStore on purpose. These are a handful of scalars written on
 * a tap and read once at startup; DataStore would add a dependency and make every read a
 * suspend/Flow for no benefit here. The writes are `apply()` (async, atomic), so nothing blocks
 * the UI thread — the read at startup is the only synchronous access and it happens before the
 * first frame.
 *
 * Call [init] once, from MainActivity, next to `Conn.init`.
 */
object LocalStore {

    private const val FILE = "motorguard.prefs"

    private lateinit var prefs: SharedPreferences

    /** False until [init] runs, so a call from a preview or a test cannot crash. */
    val ready: Boolean get() = ::prefs.isInitialized

    fun init(context: Context) {
        if (ready) return
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    fun getString(key: String, default: String? = null): String? =
        if (ready) prefs.getString(key, default) else default

    fun putString(key: String, value: String?) {
        if (ready) prefs.edit().putString(key, value).apply()
    }

    fun getInt(key: String, default: Int): Int =
        if (ready) prefs.getInt(key, default) else default

    fun putInt(key: String, value: Int) {
        if (ready) prefs.edit().putInt(key, value).apply()
    }

    fun getBoolean(key: String, default: Boolean): Boolean =
        if (ready) prefs.getBoolean(key, default) else default

    fun putBoolean(key: String, value: Boolean) {
        if (ready) prefs.edit().putBoolean(key, value).apply()
    }

    // ---------------------------------------------------------------- keys

    object Keys {
        const val THEME_MODE = "theme.mode"
        const val THEME_ACCENT = "theme.accent"
        const val THEME_DYNAMIC = "theme.dynamic"
        const val WIFI_KNOWN = "wifi.known"
        const val USE_REAL = "conn.useReal"
        const val DIRECT_CONTROL = "conn.directOnly"
    }
}

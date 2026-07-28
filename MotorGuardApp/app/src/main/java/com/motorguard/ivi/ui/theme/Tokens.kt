package com.motorguard.ivi.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Single source of truth for color, mirrored from the README "Modern Tech / Sleek"
 * palette. Two sets (day/night); [MotorGuardTheme] picks one. Never hardcode a hex
 * in a screen — reach for these (or MaterialTheme.colorScheme, which is built from them).
 */
object Tokens {

    object Night {
        val base = Color(0xFF121212)
        val panel = Color(0xFF161B24)
        val railBg = Color(0xFF0E1219)
        val onBase = Color(0xFFF2F5F8)
        val onBaseDim = Color(0xFF8A97A6)
        val accent = Color(0xFF56C9EF)
        val accent2 = Color(0xFF80DCF8)
        val success = Color(0xFF38D17F)
        val caution = Color(0xFFF5B942)
        val critical = Color(0xFFF46C64)
    }

    object Day {
        val base = Color(0xFFF4F6F8)
        val panel = Color(0xFFFFFFFF)
        val railBg = Color(0xFFE9EDF2)
        val onBase = Color(0xFF121417)
        val onBaseDim = Color(0xFF5A6473)
        val accent = Color(0xFF0FA8D8)
        val accent2 = Color(0xFF3FC3EC)
        val success = Color(0xFF1FB56A)
        val caution = Color(0xFFD89A1E)
        val critical = Color(0xFFE24B43)
    }
}

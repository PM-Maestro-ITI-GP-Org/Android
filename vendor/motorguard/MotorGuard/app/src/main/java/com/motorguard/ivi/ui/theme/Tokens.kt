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

        // Glassmorphism set, mirrored from the --glass* CSS variables in MeowScreen.dc.html.
        val glass = Color(0x0FFFFFFF)        // --glass       rgba(255,255,255,.06)
        val glassSoft = Color(0x09FFFFFF)    // --glass2      rgba(255,255,255,.035)
        val glassBorder = Color(0x1CFFFFFF)  // --glass-brd   rgba(255,255,255,.11)
        val chip = Color(0x0FFFFFFF)         // --chip        rgba(255,255,255,.06)
        val highlight = Color(0x12FFFFFF)    // --hi          rgba(255,255,255,.07)
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

        val glass = Color(0xC2FFFFFF)        // --glass       rgba(255,255,255,.76)
        val glassSoft = Color(0x8FFFFFFF)    // --glass2      rgba(255,255,255,.56)
        val glassBorder = Color(0xEBFFFFFF)  // --glass-brd   rgba(255,255,255,.92)
        val chip = Color(0xB3FFFFFF)         // --chip        rgba(255,255,255,.7)
        val highlight = Color(0xE6FFFFFF)    // --hi          rgba(255,255,255,.9)
    }
}

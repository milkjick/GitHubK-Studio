package com.example.myempty.githubk.ui

import com.example.myempty.githubk.R

/**
 * GitHubK Studio theme system — Material3 minimalist.
 *
 * Brand palette: primary purple #7950F2, accent/brand green #22C55E.
 * Kept dependency-light (AppCompat + AndroidX) for AIDE/offline builds.
 */
object ThemeManager {
    enum class ThemeType(val id: String, val label: String) {
        LIGHT("light", "浅色"),
        DARK("dark", "深色"),
        AMOLED("amoled", "纯黑"),
        OCEAN("ocean", "海洋"),
        FOREST("forest", "森林"),
        SUNSET("sunset", "日落")
    }

    data class ThemeColors(
        val surface: Int,
        val surfaceAlt: Int,
        val surfaceElevated: Int,
        val divider: Int,
        val primary: Int,
        val primaryDark: Int,
        val gradientStart: Int,
        val gradientEnd: Int,
        val accent: Int,
        val onSurface: Int,
        val muted: Int,
        val success: Int,
        val error: Int,
        val warning: Int
    )

    // Brand purple / green tokens
    private const val PURPLE = 0xFF7950F2.toInt()
    private const val PURPLE_DARK = 0xFF5B3FD1.toInt()
    private const val PURPLE_LIGHT = 0xFF9E79FF.toInt()
    private const val GREEN = 0xFF22C55E.toInt()
    private const val GREEN_DARK = 0xFF16A34A.toInt()

    private val light = ThemeColors(
        0xFFFFFBFE.toInt(), 0xFFF3F1F5.toInt(), 0xFFFFFFFF.toInt(), 0xFFE3E0E6.toInt(),
        PURPLE, PURPLE_DARK, PURPLE, PURPLE_LIGHT,
        GREEN, 0xFF1D1B20.toInt(), 0xFF6F6A73.toInt(), GREEN_DARK,
        0xFFBA1A1A.toInt(), 0xFF8A6500.toInt()
    )
    private val dark = ThemeColors(
        0xFF17141D.toInt(), 0xFF211D2B.toInt(), 0xFF2A2536.toInt(), 0xFF3A3448.toInt(),
        PURPLE_LIGHT, 0xFFB7A0FF.toInt(), 0xFF7950F2.toInt(), 0xFF9E79FF.toInt(),
        GREEN, 0xFFEAE5F2.toInt(), 0xFFA79FB8.toInt(), GREEN,
        0xFFFF8A80.toInt(), 0xFFFFC400.toInt()
    )
    private val amoled = dark.copy(surface = 0xFF000000.toInt(), surfaceAlt = 0xFF0F0F0F.toInt(), surfaceElevated = 0xFF171717.toInt())
    private val ocean = ThemeColors(
        0xFFF7FAFF.toInt(), 0xFFEAF2FF.toInt(), 0xFFFFFFFF.toInt(), 0xFFD7E2F3.toInt(),
        PURPLE, PURPLE_DARK, PURPLE, PURPLE_LIGHT,
        GREEN, 0xFF161B22.toInt(), 0xFF5F6874.toInt(), GREEN_DARK,
        0xFFBA1A1A.toInt(), 0xFF8A6500.toInt()
    )
    private val forest = ThemeColors(
        0xFFF9FBF7.toInt(), 0xFFEDF4E9.toInt(), 0xFFFFFFFF.toInt(), 0xFFD9E2D4.toInt(),
        PURPLE, PURPLE_DARK, PURPLE, PURPLE_LIGHT,
        GREEN, 0xFF1A1C19.toInt(), 0xFF687064.toInt(), GREEN_DARK,
        0xFFBA1A1A.toInt(), 0xFF806000.toInt()
    )
    private val sunset = ThemeColors(
        0xFFFFF8F6.toInt(), 0xFFFFECE7.toInt(), 0xFFFFFFFF.toInt(), 0xFFF1D9D1.toInt(),
        PURPLE, PURPLE_DARK, PURPLE, PURPLE_LIGHT,
        GREEN, 0xFF201A18.toInt(), 0xFF766864.toInt(), GREEN_DARK,
        0xFFBA1A1A.toInt(), 0xFF856000.toInt()
    )

    @Volatile
    var current: ThemeType = ThemeType.DARK
        private set

    val colors: ThemeColors
        get() = when (current) {
            ThemeType.LIGHT -> light
            ThemeType.DARK -> dark
            ThemeType.AMOLED -> amoled
            ThemeType.OCEAN -> ocean
            ThemeType.FOREST -> forest
            ThemeType.SUNSET -> sunset
        }

    fun apply(id: String) {
        current = ThemeType.entries.firstOrNull { it.id == id } ?: ThemeType.DARK
    }

    fun c(colorRes: Int): Int = when (colorRes) {
        R.color.surface -> colors.surface
        R.color.surface_alt -> colors.surfaceAlt
        R.color.surface_elevated -> colors.surfaceElevated
        R.color.divider -> colors.divider
        R.color.primary -> colors.primary
        R.color.primary_dark -> colors.primaryDark
        R.color.gradient_start -> colors.gradientStart
        R.color.gradient_end -> colors.gradientEnd
        R.color.accent -> colors.accent
        R.color.on_surface -> colors.onSurface
        R.color.muted -> colors.muted
        R.color.success -> colors.success
        R.color.error -> colors.error
        R.color.warning -> colors.warning
        else -> colorRes
    }
}

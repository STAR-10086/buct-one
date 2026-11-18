package com.buct.one.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 色板。深色为主态，浅色为日间态。
 * 品牌主色取 iOS systemBlue #0A84FF：在深色玻璃与多彩壁纸上都保持鲜明。
 */
object BuctColors {
    val BrandPrimary = Color(0xFF0A84FF)
    val BrandPrimaryStrong = Color(0xFF0069D9)
    val BrandPrimarySoft = Color(0x330A84FF)

    val AccentRed = Color(0xFFFF453A)
    val AccentOrange = Color(0xFFFF9F0A)
    val AccentGreen = Color(0xFF30D158)
    val AccentTeal = Color(0xFF40C8E0)

    // 深色态
    val CanvasDark = Color(0xFF0B1220)
    val SurfaceDark = Color(0xFF171B22)
    val InkDark = Color(0xFFFFFFFF)
    val InkDarkSecondary = Color(0xB3FFFFFF)
    val InkDarkTertiary = Color(0x80FFFFFF)

    // 浅色态
    val CanvasLight = Color(0xFFE8EEF7)
    val SurfaceLight = Color(0xFFFFFFFF)
    val InkLight = Color(0xFF0A1628)
    val InkLightSecondary = Color(0xB30A1628)
    val InkLightTertiary = Color(0x800A1628)

    // 玻璃层
    val GlassFillDark = 0.10f
    val GlassFillDarkStrong = 0.16f
    val GlassBorderDark = Color(0x3DFFFFFF)
    val GlassFillLight = 0.62f
    val GlassFillLightStrong = 0.78f
    val GlassBorderLight = Color(0x66FFFFFF)

    val DividerDark = Color(0x1FFFFFFF)
    val DividerLight = Color(0x1A0A1628)
}

/** 课程块配色，按课程名 hash 取色，保证同一门课每次同色。 */
object CoursePalette {
    private val blocks = listOf(
        Color(0xFF4F6BED), Color(0xFF0F9D84), Color(0xFF1F88C8), Color(0xFF5965D8),
        Color(0xFF855AE0), Color(0xFFE57A1F), Color(0xFFD9487E), Color(0xFF1C8F8F),
    )

    fun colorFor(key: String): Color =
        blocks[(key.hashCode() and 0x7FFFFFFF) % blocks.size]
}

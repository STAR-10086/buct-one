package com.buct.one.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 色板。取自上游同校工作台真机截图的像素采样，浅色为主态。
 *
 * 关键判断：上游不是"深色玻璃 + 高对比"，而是**浅色淡彩 + 低对比**。
 * 背景亮度 > 88%，玻璃靠透明度与高光边区分层次，不靠色阶深浅。
 */
object BuctColors {
    // 品牌
    val BrandPrimary = Color(0xFF0A84FF)
    val BrandPrimaryStrong = Color(0xFF0A6BFF)
    val BrandPrimarySoft = Color(0x1A0A84FF)

    // 语义
    val Danger = Color(0xFFFF453A)
    val DangerDeep = Color(0xFFE8322A)
    val Success = Color(0xFF2FBF71)
    val Warning = Color(0xFFFF9F0A)

    // 文字（浅色）
    val Ink = Color(0xFF10151F)
    val InkSecondary = Color(0xB810151F)
    val InkTertiary = Color(0x8A10151F)
    val InkDisabled = Color(0x5C10151F)

    // 画布：截图中背景是蓝→紫→青的高亮渐变
    val CanvasTop = Color(0xFFEEF2FA)
    val CanvasMid = Color(0xFFE2E7F5)
    val CanvasWarm = Color(0xFFECE2EF)
    val CanvasBottom = Color(0xFFE4EDF5)

    // 玻璃（浅色）：低 alpha + 近白描边
    const val GlassAlpha = 0.46f
    const val GlassAlphaStrong = 0.62f
    val GlassBorder = Color(0xF0FFFFFF)
    val GlassDivider = Color(0x1A10151F)

    // 深色态（跟随系统时用，非主态）
    val CanvasDark = Color(0xFF0B1220)
    val InkDark = Color(0xFFFFFFFF)
    val InkDarkSecondary = Color(0xB8FFFFFF)
    val InkDarkTertiary = Color(0x8AFFFFFF)
    const val GlassAlphaDark = 0.10f
    val GlassBorderDark = Color(0x3DFFFFFF)
}

/**
 * 课程块配色。取截图里那五类：粉红 / 淡紫 / 淡蓝 / 蓝紫 / 卡其。
 * 块本身是半透明淡彩，文字用同色系深色，靠色相区分课程而不是靠高饱和。
 */
data class CourseBlock(val fill: Color, val ink: Color)

object CoursePalette {
    private val blocks = listOf(
        CourseBlock(Color(0xC7FAD4D1), Color(0xFF8C3B34)),  // 粉红
        CourseBlock(Color(0xC7E8D0EA), Color(0xFF6E3A73)),  // 淡紫
        CourseBlock(Color(0xC7CFE4F7), Color(0xFF1F5273)),  // 淡蓝
        CourseBlock(Color(0xC7D0D6F8), Color(0xFF3A4280)),  // 蓝紫
        CourseBlock(Color(0xCCE6DFCF), Color(0xFF6B5E3F)),  // 卡其
    )

    fun blockFor(key: String): CourseBlock =
        blocks[(key.hashCode() and 0x7FFFFFFF) % blocks.size]
}

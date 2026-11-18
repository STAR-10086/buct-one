package com.buct.one.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 玻璃材质在当前主题下的取值。
 * 组件里不要直接写 Color.White.copy(alpha = …)，一律从这里取。
 */
data class GlassTokens(
    val fill: Color,
    val fillStrong: Color,
    val border: Color,
    val divider: Color,
    val ink: Color,
    val inkSecondary: Color,
    val inkTertiary: Color,
)

val LocalGlass = staticCompositionLocalOf {
    GlassTokens(
        fill = Color.White.copy(alpha = BuctColors.GlassAlpha),
        fillStrong = Color.White.copy(alpha = BuctColors.GlassAlphaStrong),
        border = BuctColors.GlassBorder,
        divider = BuctColors.GlassDivider,
        ink = BuctColors.Ink,
        inkSecondary = BuctColors.InkSecondary,
        inkTertiary = BuctColors.InkTertiary,
    )
}

private val LightScheme = lightColorScheme(
    primary = BuctColors.BrandPrimary,
    onPrimary = Color.White,
    secondary = BuctColors.BrandPrimaryStrong,
    background = BuctColors.CanvasTop,
    onBackground = BuctColors.Ink,
    surface = Color.White,
    onSurface = BuctColors.Ink,
    error = BuctColors.Danger,
)

private val DarkScheme = darkColorScheme(
    primary = BuctColors.BrandPrimary,
    onPrimary = Color.White,
    background = BuctColors.CanvasDark,
    onBackground = BuctColors.InkDark,
    surface = BuctColors.CanvasDark,
    onSurface = BuctColors.InkDark,
    error = BuctColors.Danger,
)

private val BuctShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

@Composable
fun BuctTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val glass = if (darkTheme) {
        GlassTokens(
            fill = Color.White.copy(alpha = BuctColors.GlassAlphaDark),
            fillStrong = Color.White.copy(alpha = BuctColors.GlassAlphaDark + 0.06f),
            border = BuctColors.GlassBorderDark,
            divider = Color(0x1FFFFFFF),
            ink = BuctColors.InkDark,
            inkSecondary = BuctColors.InkDarkSecondary,
            inkTertiary = BuctColors.InkDarkTertiary,
        )
    } else {
        GlassTokens(
            fill = Color.White.copy(alpha = BuctColors.GlassAlpha),
            fillStrong = Color.White.copy(alpha = BuctColors.GlassAlphaStrong),
            border = BuctColors.GlassBorder,
            divider = BuctColors.GlassDivider,
            ink = BuctColors.Ink,
            inkSecondary = BuctColors.InkSecondary,
            inkTertiary = BuctColors.InkTertiary,
        )
    }

    CompositionLocalProvider(LocalGlass provides glass) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = BuctTypography,
            shapes = BuctShapes,
            content = content,
        )
    }
}

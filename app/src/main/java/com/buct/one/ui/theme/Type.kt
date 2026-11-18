package com.buct.one.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * 中文优先的排版阶梯。
 * 中文不用负字距（会让字挤在一起），只在纯数字/英文标题上用 -0.2sp ~ -0.6sp。
 */
val BuctTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-0.6).sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium,
    ),
)

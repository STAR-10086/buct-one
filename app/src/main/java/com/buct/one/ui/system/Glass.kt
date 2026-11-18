package com.buct.one.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.buct.one.ui.theme.LocalGlass

/**
 * 玻璃面板。
 *
 * M1 用「半透明底 + 0.7dp 高光边」模拟玻璃，不依赖 RenderEffect——
 * Modifier.blur 只能模糊自身内容而非背景，真背景模糊要 GraphicsLayer 抓帧，
 * 成本高且 API 24-30 无硬件加速保障。等 M2 再评估 backdrop 方案。
 *
 * 没有那条 0.7dp 高光边，半透明卡片会糊成一团灰，边框是玻璃的骨架。
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    radius: Dp = 20.dp,
    strong: Boolean = false,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = LocalGlass.current
    Column(
        modifier
            .clip(RoundedCornerShape(radius))
            .background(if (strong) glass.fillStrong else glass.fill)
            .border(0.5.dp, glass.border, RoundedCornerShape(radius))
            .padding(contentPadding),
        content = content,
    )
}

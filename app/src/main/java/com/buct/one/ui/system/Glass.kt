package com.buct.one.ui.system

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 玻璃面板（旧接口，已由 [GlassCapsule] 承接）。
 *
 * M1 用「半透明底 + 0.5dp 高光边」模拟玻璃：Compose 的 Modifier.blur 只能模糊自身内容，
 * 模糊背景要抓帧，成本高且 API 24-30 无硬件加速保障。真折射路线见
 * docs/ui-capsule-system.md §5（API 33+ 走 Cloudy liquidGlass）。
 *
 * 没有那条 0.5dp 高光边，半透明卡片会糊成一团灰，边框是玻璃的骨架。
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    radius: Dp = 20.dp,
    strong: Boolean = false,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCapsule(
        modifier = modifier,
        spec = Capsules.Card.copy(radius = radius, padH = contentPadding, padV = contentPadding),
        strong = strong,
        content = content,
    )
}

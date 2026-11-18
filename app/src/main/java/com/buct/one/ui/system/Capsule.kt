package com.buct.one.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.buct.one.ui.theme.LocalGlass

/**
 * 胶囊规格。高度一半即圆角，「全圆」胶囊永不出现直角。
 * 规范见 docs/ui-capsule-system.md §1。
 */
data class CapsuleSpec(
    val height: Dp?,
    val radius: Dp,
    val padH: Dp,
    val padV: Dp,
)

object Capsules {
    /** 底部悬浮导航坞 */
    val Dock = CapsuleSpec(68.dp, 34.dp, 6.dp, 8.dp)

    /** 首页信息卡、课表卡（高度自适应，软圆角） */
    val Card = CapsuleSpec(null, 28.dp, 20.dp, 18.dp)

    /** 大号主卡 */
    val CardHero = CapsuleSpec(null, 30.dp, 20.dp, 20.dp)

    /** 筛选器、周次切换 */
    val Chip = CapsuleSpec(36.dp, 18.dp, 12.dp, 0.dp)

    /** 搜索 / 登录输入框 */
    val Field = CapsuleSpec(52.dp, 26.dp, 16.dp, 0.dp)

    /** 主 / 次按钮 */
    val Pill = CapsuleSpec(44.dp, 22.dp, 18.dp, 0.dp)

    /** 分段控件 */
    val Segment = CapsuleSpec(40.dp, 20.dp, 8.dp, 0.dp)

    /** 提示条、Snackbar */
    val Toast = CapsuleSpec(48.dp, 24.dp, 16.dp, 0.dp)
}

/** 胶囊表面：底色 + 描边 + 圆角。所有胶囊容器共用，别各写各的。 */
fun Modifier.capsuleSurface(
    spec: CapsuleSpec,
    fill: Color,
    borderColor: Color?,
): Modifier {
    var m = this
        .then(if (spec.height != null) Modifier.height(spec.height) else Modifier)
        .clip(RoundedCornerShape(spec.radius))
        .background(fill)
    if (borderColor != null) {
        m = m.border(0.5.dp, borderColor, RoundedCornerShape(spec.radius))
    }
    return m.padding(horizontal = spec.padH, vertical = spec.padV)
}

/** 纵向胶囊（卡片）。 */
@Composable
fun GlassCapsule(
    modifier: Modifier = Modifier,
    spec: CapsuleSpec = Capsules.Card,
    strong: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val glass = LocalGlass.current
    Column(
        modifier.capsuleSurface(
            spec,
            fill = if (strong) glass.fillStrong else glass.fill,
            borderColor = glass.border,
        ),
        content = content,
    )
}

/** 横向胶囊（Chip / Pill / Field / Toast）。 */
@Composable
fun GlassCapsuleRow(
    modifier: Modifier = Modifier,
    spec: CapsuleSpec = Capsules.Chip,
    strong: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val glass = LocalGlass.current
    Row(
        modifier
            .then(if (spec.height != null) Modifier.defaultMinSize(minHeight = spec.height) else Modifier)
            .capsuleSurface(
                spec = spec.copy(height = null),
                fill = if (strong) glass.fillStrong else glass.fill,
                borderColor = glass.border,
            ),
        content = content,
    )
}

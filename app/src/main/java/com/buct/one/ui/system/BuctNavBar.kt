package com.buct.one.ui.system

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.buct.one.ui.theme.BuctColors
import com.buct.one.ui.theme.LocalGlass
import com.buct.one.ui.theme.MotionDuration
import com.buct.one.ui.theme.MotionEasing

/**
 * 一栏。
 * [center] = true 的那一项是工作台：凸起、方块底、图标自绘（四格），不参与常规排布。
 * 中心项 [icon] 可为 null——它用 [Win11Mark] 而不是图标字体。
 */
data class NavTab(
    val label: String,
    val icon: ImageVector? = null,
    val center: Boolean = false,
)

/** Win11 风格的四格标记：2×2 方块，缝隙 2dp，圆角 1dp。 */
@Composable
fun Win11Mark(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    cell: Int = 5,
    gap: Int = 2,
) {
    Column(modifier) {
        repeat(2) { row ->
            Row {
                repeat(2) { col ->
                    Box(
                        Modifier
                            .size(cell.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(color)
                    )
                    if (col == 0) Spacer(Modifier.width(gap.dp))
                }
            }
            if (row == 0) Spacer(Modifier.height(gap.dp))
        }
    }
}

/**
 * 底部导航：五栏，中心为凸起的工作台。
 *
 * 凸起用 offset + 阴影色块实现，不用真阴影（API 24-30 的 elevation 渲染差异大）。
 * 中心项始终处于选中态外观——它是「回到工作台」的锚点，不跟随其它 tab 变化。
 */
@Composable
fun BuctNavBar(
    tabs: List<NavTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalGlass.current
    Row(
        modifier
            .fillMaxWidth()
            .background(glass.fillStrong)
            .height(64.dp)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            if (tab.center) {
                CenterTab(
                    label = tab.label,
                    selected = index == selected,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                SideTab(
                    tab = tab,
                    selected = index == selected,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SideTab(
    tab: NavTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.48f),
        animationSpec = tween(MotionDuration.NavLabel, easing = MotionEasing.Standard),
        label = "sideTabColor",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.94f,
        animationSpec = tween(MotionDuration.Fast, easing = MotionEasing.Standard),
        label = "sideTabScale",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 6.dp),
    ) {
        tab.icon?.let {
            Icon(
                imageVector = it,
                contentDescription = tab.label,
                tint = color,
                modifier = Modifier.size(21.dp).scale(scale),
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun CenterTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lift by animateDpAsState(
        targetValue = if (selected) (-18).dp else (-12).dp,
        animationSpec = tween(MotionDuration.NavIndicator, easing = MotionEasing.Emphasized),
        label = "centerLift",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.offset(y = lift),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(BuctColors.BrandPrimary)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
        ) {
            Win11Mark(color = Color.White, cell = 7, gap = 3)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
        )
    }
}

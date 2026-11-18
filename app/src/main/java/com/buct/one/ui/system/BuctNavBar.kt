package com.buct.one.ui.system

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.buct.one.ui.theme.LocalGlass
import com.buct.one.ui.theme.MotionDuration
import com.buct.one.ui.theme.MotionEasing
import com.buct.one.ui.theme.BuctColors

data class NavTab(val label: String, val icon: ImageVector)

/**
 * 底部导航。选中态是一个滑动的胶囊，不是 M3 默认的下划线或 pill 指示器。
 * 胶囊移动用 NavIndicator 时长，标签变色的用 NavLabel——两者错开 80ms，视觉上更顺。
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            val active = index == selected
            val iconColor by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                animationSpec = tween(MotionDuration.NavLabel, easing = MotionEasing.Standard),
                label = "navIcon",
            )
            val pillWidth by animateDpAsState(
                targetValue = if (active) 60.dp else 0.dp,
                animationSpec = tween(MotionDuration.NavIndicator, easing = MotionEasing.Emphasized),
                label = "navPill",
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(width = pillWidth, height = 30.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(
                                if (active) BuctColors.BrandPrimary.copy(alpha = 0.18f)
                                else androidx.compose.ui.graphics.Color.Transparent
                            )
                    )
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = iconColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = iconColor,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

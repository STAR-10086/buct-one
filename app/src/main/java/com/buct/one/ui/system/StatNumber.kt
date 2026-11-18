package com.buct.one.ui.system

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.buct.one.ui.theme.MotionDuration
import com.buct.one.ui.theme.MotionEasing

/**
 * 数字滚动：只有变化的数字位上下滑动，容器几何与相邻文字完全不动。
 * 倒计时、剩余天数、GPA 都用它，比整体缩放高级，也不会让布局抖动。
 */
@Composable
fun StatNumber(
    value: String,
    style: TextStyle = MaterialTheme.typography.headlineSmall,
    color: Color = MaterialTheme.colorScheme.onBackground,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            val movingUp = targetState.toIntOrNull()?.let { it > initialState.toIntOrNull() ?: it } ?: true
            val dir = if (movingUp) 1 else -1
            (slideInVertically(tween(MotionDuration.Number, easing = MotionEasing.Standard)) { h -> dir * h } +
                fadeIn(tween(MotionDuration.Number))) togetherWith
                (slideOutVertically(tween(MotionDuration.Number, easing = MotionEasing.Standard)) { h -> -dir * h } +
                    fadeOut(tween(MotionDuration.Fast))) using SizeTransform(clip = true)
        },
        label = "statNumber",
        modifier = modifier,
    ) { text ->
        Text(text = text, style = style, color = color)
    }
}

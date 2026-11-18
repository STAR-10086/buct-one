package com.buct.one.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/** 动效时长。数值与上游保持一致，跨版本不要随手改。 */
object MotionDuration {
    const val Fast = 120
    const val Medium = 220
    const val Slow = 400
    const val Number = 180
    const val TabTransition = 220
    const val DialogEnter = 400
    const val ExpandCollapse = 250
    const val NavIndicator = 260
    const val NavLabel = 180
    const val EmphasisPulse = 1400
}

object MotionEasing {
    val Standard = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val FastOutSlowIn = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    val Accelerate = CubicBezierEasing(0.4f, 0f, 1f, 1f)
}

object MotionSpring {
    fun <T> gentle() = spring<T>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessLow,
    )

    fun <T> snappy() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )
}

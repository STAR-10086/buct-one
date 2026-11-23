package com.buct.course.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle

/** 小组件当前占用的桌面尺寸（dp）。 */
internal data class WidgetSize(val widthDp: Int, val heightDp: Int)

/**
 * 小组件排版模式：
 * - [COMPACT]：2×1，只放一堂课（左右滑动看当天其它课程）
 * - [FULL]：4×2 及以上，放当天完整课程列表
 */
internal enum class WidgetLayoutMode { COMPACT, FULL }

/**
 * 尺寸解析与排版模式判定。
 *
 * 「适配某些 UI 的小部件缩放」的落点就在这里：不同桌面写进 AppWidgetOptions 的
 * min/max 宽高并不一致（有的只写 min、有的只写 max，横屏下含义还会互换），
 * 所以先按方向挑有效值，取不到就回落到上一次记下的尺寸，最后才用保守默认值。
 * 每次刷新都会把解析结果存下来，供 RemoteViewsService 在没有 options 的时候复用。
 */
internal object WidgetSizePolicy {

    /** 4×2 的门槛：再小就退回单节卡片，保证内容永远放得下。 */
    private const val FULL_MIN_WIDTH_DP = 240
    private const val FULL_MIN_HEIGHT_DP = 110

    /** 完全没有尺寸信息时的保守默认值（约等于 2×1）。 */
    private const val FALLBACK_WIDTH_DP = 150
    private const val FALLBACK_HEIGHT_DP = 70

    fun resolve(context: Context, options: Bundle?, appWidgetId: Int): WidgetSize {
        val stored = stored(context, appWidgetId)
        val measured = options?.let { measure(context, it) }
        val width = measured?.first?.takeIf { it > 0 }
            ?: stored.widthDp.takeIf { it > 0 }
            ?: FALLBACK_WIDTH_DP
        val height = measured?.second?.takeIf { it > 0 }
            ?: stored.heightDp.takeIf { it > 0 }
            ?: FALLBACK_HEIGHT_DP
        WidgetConfigStore.setWidgetSize(context, appWidgetId, width, height)
        return WidgetSize(width, height)
    }

    /** 只读已记录的尺寸（RemoteViewsService 拿不到 options 时用）；没记录过就按 2×1 保守估算。 */
    fun stored(context: Context, appWidgetId: Int): WidgetSize = WidgetSize(
        WidgetConfigStore.widgetWidthDp(context, appWidgetId).takeIf { it > 0 } ?: FALLBACK_WIDTH_DP,
        WidgetConfigStore.widgetHeightDp(context, appWidgetId).takeIf { it > 0 } ?: FALLBACK_HEIGHT_DP
    )

    fun mode(size: WidgetSize): WidgetLayoutMode =
        if (size.widthDp >= FULL_MIN_WIDTH_DP && size.heightDp >= FULL_MIN_HEIGHT_DP) {
            WidgetLayoutMode.FULL
        } else {
            WidgetLayoutMode.COMPACT
        }

    /**
     * 竖屏时可用宽度是 MIN_WIDTH、可用高度是 MAX_HEIGHT；横屏反过来。
     * 两边都为 0 时返回 0，交给上层回落。
     */
    private fun measure(context: Context, options: Bundle): Pair<Int, Int> {
        val landscape =
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        val width = if (landscape) larger(minWidth, maxWidth) else smaller(minWidth, maxWidth)
        val height = if (landscape) smaller(minHeight, maxHeight) else larger(minHeight, maxHeight)
        return width to height
    }

    private fun larger(first: Int, second: Int): Int = when {
        first > 0 && second > 0 -> maxOf(first, second)
        first > 0 -> first
        else -> second
    }

    private fun smaller(first: Int, second: Int): Int = when {
        first > 0 && second > 0 -> minOf(first, second)
        first > 0 -> first
        else -> second
    }
}

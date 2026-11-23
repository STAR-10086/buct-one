package com.buct.course.widget

import android.content.Context
import android.widget.RemoteViews
import com.buct.course.R
import com.buct.course.manager.ScheduleSettingsManager

/**
 * 小组件字号缩放。
 *
 * RemoteViews 不能改样式表，只能在构建视图时对每个 TextView 显式调
 * setTextSize。这些 id 和基准字号是从 res/layout 的 widget_*.xml 生成的
 * （tools/widget_text_maps.txt），布局改字号时记得同步重新生成。
 */
object WidgetTextSizes {

    /** 今日课表·卡片：2×1 单节卡片的基准字号（还会再乘桌面高度系数，见 TodayWidget）。 */
    const val TODAY_COMPACT_NAME_SP = 11.5f
    const val TODAY_COMPACT_DETAIL_SP = 9.5f

    /** 今日课表·卡片：4×2 课程行每行的基准字号（行矮时用 TIGHT 一档）。 */
    const val TODAY_ROW_NAME_SP = 13.5f
    const val TODAY_ROW_NAME_TIGHT_SP = 12f
    const val TODAY_ROW_DETAIL_SP = 10.5f
    const val TODAY_ROW_DETAIL_TIGHT_SP = 9.5f

    /** 待办事项每行的基准字号。 */
    const val TODO_ROW_NAME_SP = 12.5f
    const val TODO_ROW_NAME_TIGHT_SP = 11f
    const val TODO_ROW_COUNT_SP = 10f
    const val TODO_ROW_COUNT_TIGHT_SP = 9f

    fun scale(context: Context): Float =
        ScheduleSettingsManager.getInstance().apply { init(context) }.widgetFontScale

    fun apply(views: RemoteViews, sizes: Map<Int, Float>, scale: Float) {
        // 不能在 scale==1f 时跳过：小组件走局部更新，视图树里可能还留着上一次
        // 放大后的字号，只有显式 setTextSize 才能把它们改回默认，否则「恢复默认」不生效
        sizes.forEach { (id, base) ->
            runCatching { views.setFloat(id, "setTextSize", base * scale) }
        }
    }

    /** widget_schedule_day.xml（旧版今日课表，可翻天） */
    val DAY_ROOT: Map<Int, Float> = mapOf(
        R.id.widget_title to 14.0f,
        R.id.widget_day_info to 11.0f,
        R.id.widget_back_today to 11.0f,
    )
    /** widget_schedule_day_notitle.xml */
    val DAY_ROOT_NO_TITLE: Map<Int, Float> = mapOf(
        R.id.widget_day_info to 11.0f,
        R.id.widget_back_today to 11.0f,
    )
    /** widget_schedule_day_row.xml */
    val DAY_ROW: Map<Int, Float> = mapOf(
        R.id.item_name to 14.0f,
        R.id.item_time to 11.0f,
        R.id.item_loc to 11.0f,
    )
    /** widget_schedule_exam_row.xml */
    val DAY_EXAM_ROW: Map<Int, Float> = mapOf(
        R.id.exam_name to 14.0f,
        R.id.exam_time to 12.0f,
        R.id.exam_loc to 11.0f,
    )
    /** widget_today.xml（今日课表·卡片，4×2 当天列表） */
    val TODAY_ROOT: Map<Int, Float> = mapOf(
        R.id.today_title to 13.5f,
        R.id.today_day_info to 10.5f,
    )
    /** widget_today_notitle.xml */
    val TODAY_ROOT_NO_TITLE: Map<Int, Float> = mapOf(
        R.id.today_day_info to 10.5f,
    )
    /** widget_todo.xml */
    val TODO_ROOT: Map<Int, Float> = mapOf(
        R.id.widget_title to 13f,
        R.id.widget_todo_count to 10f,
    )
    /** widget_todo.xml（隐藏标题栏） */
    val TODO_ROOT_NO_TITLE: Map<Int, Float> = emptyMap()
    /** widget_exam_countdown.xml */
    val EXAM_ROOT: Map<Int, Float> = mapOf(
        R.id.exam_widget_title to 12.0f,
        R.id.exam_widget_badge to 11.0f,
        R.id.exam_widget_name to 13.5f,
        R.id.exam_widget_detail to 10.5f,
    )
    /** widget_balance.xml */
    val BALANCE_ROOT: Map<Int, Float> = mapOf(
        R.id.balance_widget_title to 11.5f,
        R.id.balance_card_label to 10.0f,
        R.id.balance_card_value to 14.0f,
        R.id.balance_ele_label to 10.0f,
        R.id.balance_ele_value to 14.0f,
    )
    /** widget_schedule.xml */
    val WEEK_ROOT: Map<Int, Float> = mapOf(
        R.id.widget_title to 16.0f,
        R.id.widget_monday_header to 10.0f,
        R.id.widget_tuesday_header to 10.0f,
        R.id.widget_wednesday_header to 10.0f,
        R.id.widget_thursday_header to 10.0f,
        R.id.widget_friday_header to 10.0f,
        R.id.widget_saturday_header to 10.0f,
        R.id.widget_sunday_header to 10.0f,
        R.id.widget_week_info to 12.0f,
    )
    /** widget_schedule_notitle.xml */
    val WEEK_ROOT_NO_TITLE: Map<Int, Float> = WEEK_ROOT - R.id.widget_title
    /** widget_schedule_row.xml */
    val WEEK_ROW: Map<Int, Float> = mapOf(
        R.id.item_period to 10.0f,
        R.id.item_monday to 9.0f,
        R.id.item_tuesday to 9.0f,
        R.id.item_wednesday to 9.0f,
        R.id.item_thursday to 9.0f,
        R.id.item_friday to 9.0f,
        R.id.item_saturday to 9.0f,
        R.id.item_sunday to 9.0f,
    )
    /** widget_schedule_row_cell.xml */
    val WEEK_CELL: Map<Int, Float> = mapOf(
        R.id.item_period to 10.0f,
        R.id.item_monday to 9.0f,
        R.id.item_tuesday to 9.0f,
        R.id.item_wednesday to 9.0f,
        R.id.item_thursday to 9.0f,
        R.id.item_friday to 9.0f,
        R.id.item_saturday to 9.0f,
        R.id.item_sunday to 9.0f,
    )
    /** widget_schedule_row_top.xml */
    val WEEK_ROW_TOP: Map<Int, Float> = mapOf(
        R.id.item_period to 10.0f,
        R.id.item_monday to 9.0f,
        R.id.item_tuesday to 9.0f,
        R.id.item_wednesday to 9.0f,
        R.id.item_thursday to 9.0f,
        R.id.item_friday to 9.0f,
        R.id.item_saturday to 9.0f,
        R.id.item_sunday to 9.0f,
    )
    /** widget_schedule_row_bottom.xml */
    val WEEK_ROW_BOTTOM: Map<Int, Float> = mapOf(
        R.id.item_period to 10.0f,
        R.id.item_monday to 9.0f,
        R.id.item_tuesday to 9.0f,
        R.id.item_wednesday to 9.0f,
        R.id.item_thursday to 9.0f,
        R.id.item_friday to 9.0f,
        R.id.item_saturday to 9.0f,
        R.id.item_sunday to 9.0f,
    )
}

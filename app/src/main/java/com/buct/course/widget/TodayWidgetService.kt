package com.buct.course.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.buct.course.R

/**
 * 桌面「今日课表·卡片」4×2 模式的 RemoteViewsService：当天完整课程列表。
 *
 * 每行只显示课程名称、上课时间、上课教室；桌面高度不够时行高压到 40dp，
 * 时间与教室并成一行，仍然放不下就让 ListView 上下滑动。
 */
class TodayWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return TodayRemoteViewsFactory(applicationContext, intent)
    }
}

class TodayRemoteViewsFactory(
    private val context: Context,
    intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private val appWidgetId: Int = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID,
        AppWidgetManager.INVALID_APPWIDGET_ID
    )

    private var courses: List<TodayCourse> = emptyList()
    private var nowMinutes: Int = 0
    private var emptyText: String = "今天没有课"
    private var rowHeightDp: Int = 56
    private var threeLines: Boolean = true
    private var isDarkMode: Boolean = false

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val size = WidgetSizePolicy.stored(context, appWidgetId)
        val hideTitle = WidgetConfigStore.hideTitle(context, appWidgetId)
        val fontScale = WidgetTextSizes.scale(context)
        val rowSpec = TodayWidget.fullRowSpec(size, hideTitle, fontScale)
        rowHeightDp = rowSpec.heightDp
        threeLines = rowSpec.threeLines
        isDarkMode = WidgetAppearance.isDarkMode(context)

        val data = runCatching { TodayCourseSource.load(context, appWidgetId) }.getOrNull()
        courses = data?.courses.orEmpty()
        nowMinutes = data?.nowMinutes ?: 0
        emptyText = when {
            data == null || !data.hasScheduleData -> "暂无课表，打开 App 同步"
            else -> "今天没有课"
        }
    }

    override fun onDestroy() {}

    override fun getCount(): Int = if (courses.isEmpty()) 1 else courses.size

    override fun getViewAt(position: Int): RemoteViews {
        if (courses.isEmpty()) {
            return buildEmptyView()
        }
        val course = courses.getOrNull(position) ?: return buildEmptyView()
        val views = RemoteViews(context.packageName, R.layout.widget_today_row)
        val ended = course.hasEnded(nowMinutes)
        val location = course.location
        val timeText = if (threeLines) course.timeText else mergeText(course.timeText, location)

        views.setTextViewText(R.id.today_row_name, course.name)
        views.setTextViewText(R.id.today_row_time, timeText)
        views.setTextViewText(R.id.today_row_loc, location)
        views.setViewVisibility(R.id.today_row_time, if (timeText.isBlank()) View.GONE else View.VISIBLE)
        views.setViewVisibility(
            R.id.today_row_loc,
            if (threeLines && location.isNotBlank()) View.VISIBLE else View.GONE
        )
        views.setInt(R.id.today_row_root, "setBackgroundResource", WidgetAppearance.courseCardRes(course.name))
        views.setTextColor(R.id.today_row_name, if (ended) 0x99FFFFFF.toInt() else Color.WHITE)
        views.setTextColor(R.id.today_row_time, if (ended) 0x8CFFFFFF.toInt() else 0xE6FFFFFF.toInt())
        views.setTextColor(R.id.today_row_loc, if (ended) 0x8CFFFFFF.toInt() else 0xD9FFFFFF.toInt())
        applyRowSizing(views)
        views.setOnClickFillInIntent(R.id.today_row_root, Intent())
        return views
    }

    private fun buildEmptyView(): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_today_row)
        views.setTextViewText(R.id.today_row_name, emptyText)
        views.setTextViewText(R.id.today_row_time, "")
        views.setTextViewText(R.id.today_row_loc, "")
        views.setViewVisibility(R.id.today_row_time, View.GONE)
        views.setViewVisibility(R.id.today_row_loc, View.GONE)
        views.setInt(R.id.today_row_root, "setBackgroundColor", Color.TRANSPARENT)
        views.setTextColor(
            R.id.today_row_name,
            if (isDarkMode) 0xCCFFFFFF.toInt() else 0xFF6E6E73.toInt()
        )
        views.setInt(R.id.today_row_name, "setGravity", Gravity.CENTER)
        val emptySize = if (rowHeightDp <= 44) {
            WidgetTextSizes.TODAY_ROW_NAME_TIGHT_SP
        } else {
            WidgetTextSizes.TODAY_ROW_NAME_SP
        }
        views.setFloat(R.id.today_row_name, "setTextSize", emptySize * WidgetTextSizes.scale(context))
        return views
    }

    private fun applyRowSizing(views: RemoteViews) {
        val density = context.resources.displayMetrics.density
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setViewLayoutHeight(
                R.id.today_row_root,
                rowHeightDp.toFloat(),
                TypedValue.COMPLEX_UNIT_DIP
            )
        } else {
            views.setInt(R.id.today_row_root, "setMinimumHeight", (rowHeightDp * density).toInt())
        }
        val scale = WidgetTextSizes.scale(context)
        val nameSize = if (rowHeightDp <= 44) {
            WidgetTextSizes.TODAY_ROW_NAME_TIGHT_SP
        } else {
            WidgetTextSizes.TODAY_ROW_NAME_SP
        }
        val detailSize = if (rowHeightDp <= 44) {
            WidgetTextSizes.TODAY_ROW_DETAIL_TIGHT_SP
        } else {
            WidgetTextSizes.TODAY_ROW_DETAIL_SP
        }
        views.setFloat(R.id.today_row_name, "setTextSize", nameSize * scale)
        views.setFloat(R.id.today_row_time, "setTextSize", detailSize * scale)
        views.setFloat(R.id.today_row_loc, "setTextSize", detailSize * scale)
        views.setInt(R.id.today_row_name, "setMaxLines", 1)
        views.setInt(R.id.today_row_time, "setMaxLines", 1)
        views.setInt(R.id.today_row_loc, "setMaxLines", 1)
        val horizontalPadding = (10 * density).toInt()
        val verticalPadding = ((if (rowHeightDp <= 44) 2 else 4) * density).toInt()
        views.setViewPadding(
            R.id.today_row_root,
            horizontalPadding,
            verticalPadding,
            horizontalPadding,
            verticalPadding
        )
    }

    private fun mergeText(time: String, location: String): String = when {
        location.isBlank() -> time
        time.isBlank() -> location
        else -> "$time · $location"
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false
}
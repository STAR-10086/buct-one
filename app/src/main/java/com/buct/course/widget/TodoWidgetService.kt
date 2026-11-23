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
 * 「待办事项」小组件的列表数据：一门课一行，行数超出一屏时上下滑动。
 */
class TodoWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return TodoRemoteViewsFactory(applicationContext, intent)
    }
}

class TodoRemoteViewsFactory(
    private val context: Context,
    intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private val appWidgetId: Int = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID,
        AppWidgetManager.INVALID_APPWIDGET_ID
    )

    private var items: List<PendingCourse> = emptyList()
    private var emptyText: String = "没有待办，全都做完了"
    private var rowHeightDp: Int = 28
    private var isDarkMode: Boolean = false

    private companion object {
        /** widget_todo.xml 里 ListView 的 dividerHeight。 */
        const val TODO_ROW_DIVIDER_DP = 2
    }

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val size = WidgetSizePolicy.stored(context, appWidgetId)
        val hideTitle = WidgetConfigStore.hideTitle(context, appWidgetId)
        val fontScale = WidgetTextSizes.scale(context)
        val headerDp = TodoWidget.headerHeightDp(size, hideTitle)
        val listHeightDp = (size.heightDp - headerDp).coerceAtLeast(32)
        val targetRows = when {
            listHeightDp >= 180 -> 5
            listHeightDp >= 120 -> 4
            listHeightDp >= 52 -> 3
            else -> 2
        }
        // 扣掉 2dp 分隔线；字号可放大到 150%，行高要跟着长，宁可少显示一门课
        val slotHeightDp = (listHeightDp / targetRows) - TODO_ROW_DIVIDER_DP
        val contentHeightDp = (22 * fontScale).toInt()
        rowHeightDp = maxOf(slotHeightDp, contentHeightDp).coerceIn(22, 72)
        isDarkMode = WidgetAppearance.isDarkMode(context)

        val data = runCatching { TodoWidgetSource.load(context) }.getOrNull()
        items = data?.items.orEmpty()
        emptyText = when {
            data?.neverSynced == true -> "登录北化在线后显示待办"
            else -> "没有待办，全都做完了"
        }
    }

    override fun onDestroy() {}

    override fun getCount(): Int = if (items.isEmpty()) 1 else items.size

    override fun getViewAt(position: Int): RemoteViews {
        if (items.isEmpty()) {
            return buildEmptyView()
        }
        val item = items.getOrNull(position) ?: return buildEmptyView()
        val views = RemoteViews(context.packageName, R.layout.widget_todo_row)
        views.setTextViewText(R.id.todo_name, item.courseName)
        views.setTextViewText(R.id.todo_count, TodoWidgetSource.describeCount(item))
        views.setTextColor(
            R.id.todo_name,
            if (isDarkMode) Color.WHITE else 0xFF1C1C1E.toInt()
        )
        views.setTextColor(
            R.id.todo_count,
            if (isDarkMode) 0xCCFFFFFF.toInt() else 0xFF6E6E73.toInt()
        )
        applyRowSizing(views)
        views.setOnClickFillInIntent(R.id.todo_root, Intent())
        return views
    }

    private fun buildEmptyView(): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_todo_row)
        views.setTextViewText(R.id.todo_name, emptyText)
        views.setTextViewText(R.id.todo_count, "")
        views.setViewVisibility(R.id.todo_count, View.GONE)
        views.setInt(R.id.todo_name, "setGravity", Gravity.CENTER)
        views.setTextColor(
            R.id.todo_name,
            if (isDarkMode) 0xCCFFFFFF.toInt() else 0xFF6E6E73.toInt()
        )
        applyRowSizing(views)
        return views
    }

    private fun applyRowSizing(views: RemoteViews) {
        val density = context.resources.displayMetrics.density
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setViewLayoutHeight(
                R.id.todo_root,
                rowHeightDp.toFloat(),
                TypedValue.COMPLEX_UNIT_DIP
            )
        } else {
            views.setInt(R.id.todo_root, "setMinimumHeight", (rowHeightDp * density).toInt())
        }
        val scale = WidgetTextSizes.scale(context)
        val tight = rowHeightDp <= 28
        views.setFloat(
            R.id.todo_name,
            "setTextSize",
            (if (tight) WidgetTextSizes.TODO_ROW_NAME_TIGHT_SP else WidgetTextSizes.TODO_ROW_NAME_SP) * scale
        )
        views.setFloat(
            R.id.todo_count,
            "setTextSize",
            (if (tight) WidgetTextSizes.TODO_ROW_COUNT_TIGHT_SP else WidgetTextSizes.TODO_ROW_COUNT_SP) * scale
        )
        views.setInt(R.id.todo_name, "setMaxLines", 1)
        views.setInt(R.id.todo_count, "setMaxLines", 1)
        val horizontalPadding = (10 * density).toInt()
        val verticalPadding = ((if (rowHeightDp <= 28) 1 else 3) * density).toInt()
        views.setViewPadding(
            R.id.todo_root,
            horizontalPadding,
            verticalPadding,
            horizontalPadding,
            verticalPadding
        )
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false
}

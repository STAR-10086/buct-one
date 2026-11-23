package com.buct.course.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.buct.course.R

/**
 * 桌面「待办事项」小组件（最小 2×1）。
 *
 * 数据来自 [TodoWidgetSource]：北化在线的待交作业 + 未完成测验，按课程名汇总。
 * 一行一门课，课程多于一屏时上下滑动（ListView 自带滚动）。
 * 桌面高度有限，2×1 时默认不显示标题栏，把空间全留给课程名。
 */
class TodoWidget : AppWidgetProvider() {

    companion object {
        /** 低于这个高度不显示标题栏，否则 2×1 只剩不到一行课程。 */
        private const val HEADER_MIN_HEIGHT_DP = 90
        private const val HEADER_HEIGHT_DP = 30

        /** 标题栏是否显示：用户关掉了、或者小部件太矮，都不显示。 */
        internal fun isHeaderVisible(size: WidgetSize, hideTitle: Boolean): Boolean =
            !hideTitle && size.heightDp >= HEADER_MIN_HEIGHT_DP

        internal fun headerHeightDp(size: WidgetSize, hideTitle: Boolean): Int =
            if (isHeaderVisible(size, hideTitle)) HEADER_HEIGHT_DP else 8

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val size = WidgetSizePolicy.resolve(
                context,
                appWidgetManager.getAppWidgetOptions(appWidgetId),
                appWidgetId
            )
            appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, size, appWidgetId))
        }

        private fun buildViews(
            context: Context,
            size: WidgetSize,
            appWidgetId: Int
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_todo)
            views.setInt(R.id.widget_root, "setBackgroundResource", WidgetAppearance.backgroundRes(context))

            val hideTitle = WidgetConfigStore.hideTitle(context, appWidgetId)
            val headerVisible = isHeaderVisible(size, hideTitle)
            views.setViewVisibility(R.id.widget_header, if (headerVisible) View.VISIBLE else View.GONE)
            if (headerVisible) {
                val todoData = TodoWidgetSource.load(context)
                views.setTextViewText(
                    R.id.widget_title,
                    WidgetConfigStore.resolveTitle(context, appWidgetId, WidgetKind.TODO)
                )
                views.setTextColor(R.id.widget_title, WidgetAppearance.textColor(context))
                views.setTextViewText(
                    R.id.widget_todo_count,
                    when {
                        todoData.neverSynced -> "未同步"
                        else -> "${todoData.items.size} 门"
                    }
                )
                views.setTextColor(
                    R.id.widget_todo_count,
                    WidgetAppearance.secondaryTextColor(context)
                )
                val configIntent = Intent(
                    context,
                    com.buct.course.ui.widget.WidgetConfigActivity::class.java
                ).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    putExtra(
                        com.buct.course.ui.widget.WidgetConfigActivity.EXTRA_WIDGET_KIND,
                        WidgetKind.TODO.name
                    )
                    data = Uri.parse("buctwidget://config/todo/$appWidgetId")
                }
                views.setOnClickPendingIntent(
                    R.id.widget_header,
                    PendingIntent.getActivity(
                        context,
                        appWidgetId + 3000,
                        configIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }

            val serviceIntent = Intent(context, TodoWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_todo_list, serviceIntent)
            val openIntent = Intent(context, com.buct.course.MainActivity::class.java)
                .setData(Uri.parse("buctwidget://todo/open/$appWidgetId"))
                .putExtra(
                    com.buct.course.MainActivity.EXTRA_TARGET_TAB,
                    com.buct.course.manager.StartupPage.Homework.name
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            views.setPendingIntentTemplate(
                R.id.widget_todo_list,
                PendingIntent.getActivity(
                    context,
                    0,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            WidgetTextSizes.apply(
                views,
                if (headerVisible) WidgetTextSizes.TODO_ROOT else WidgetTextSizes.TODO_ROOT_NO_TITLE,
                WidgetTextSizes.scale(context)
            )
            return views
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
        if (appWidgetIds.isNotEmpty()) {
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_todo_list)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateAppWidget(context, appWidgetManager, appWidgetId)
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_todo_list)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetProviders.ACTION_REFRESH) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            onUpdate(context, appWidgetManager, WidgetProviders.todoIds(context, appWidgetManager))
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        for (appWidgetId in appWidgetIds) {
            WidgetConfigStore.clear(context, appWidgetId)
        }
    }
}

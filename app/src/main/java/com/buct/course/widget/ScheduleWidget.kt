package com.buct.course.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.buct.course.R

/**
 * 桌面课表小组件
 */
open class ScheduleWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action == ACTION_PREV_WEEK || intent.action == ACTION_NEXT_WEEK) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val targetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
            val allIds = WidgetProviders.weekIds(context, appWidgetManager)
            val ids = if (targetId != AppWidgetManager.INVALID_APPWIDGET_ID) intArrayOf(targetId) else allIds
            if (ids.isEmpty()) return

            val primaryId = ids.first()
            val nextWeek = synchronized(WEEK_LOCK) {
                val widgetPrefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
                val baseWeek = readWeek(context, widgetPrefs, primaryId)
                val computed = if (intent.action == ACTION_PREV_WEEK) {
                    (baseWeek - 1).coerceAtLeast(1)
                } else {
                    (baseWeek + 1).coerceAtMost(com.buct.course.schedule.ScheduleMaxWeeks)
                }
                val now = System.currentTimeMillis()
                widgetPrefs.edit().apply {
                    ids.forEach { id ->
                        putInt("current_week_$id", computed)
                        putLong("last_week_nav_$id", now)
                    }
                    if (targetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                        putInt("current_week", computed)
                        putLong("last_week_nav", now)
                    }
                }.commit()
                computed
            }

            // 增量刷新：按实例是否隐藏标题匹配对应布局做局部更新
            for (id in ids) {
                val startMillis = WidgetScheduleSource.resolveSemesterStartMillis(context, id)
                val hideTitle = WidgetConfigStore.hideTitle(context, id)
                val layoutId = if (hideTitle) R.layout.widget_schedule_notitle else R.layout.widget_schedule
                val views = RemoteViews(context.packageName, layoutId)
                views.setTextViewText(R.id.widget_week_info, weekInfoText(nextWeek, startMillis))
                bindWeekHeader(views, nextWeek, startMillis)
                WidgetTextSizes.apply(
                    views,
                    if (hideTitle) WidgetTextSizes.WEEK_ROOT_NO_TITLE else WidgetTextSizes.WEEK_ROOT,
                    WidgetTextSizes.scale(context)
                )
                appWidgetManager.partiallyUpdateAppWidget(intArrayOf(id), views)
            }
            appWidgetManager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
        } else if (intent.action == ACTION_REFRESH) {
            // 主题变化，刷新所有小组件
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = WidgetProviders.weekIds(context, appWidgetManager)
            onUpdate(context, appWidgetManager, ids)

            // 通知 ListView 的 Service 刷新数据
            appWidgetManager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val widgetPrefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
        val editor = widgetPrefs.edit()
        for (id in appWidgetIds) {
            WidgetConfigStore.clear(context, id)
            editor.remove("current_week_$id").remove("last_week_nav_$id")
        }
        editor.commit()
    }

    companion object {
        const val ACTION_PREV_WEEK = "com.buct.course.widget.PREV_WEEK"
        const val ACTION_NEXT_WEEK = "com.buct.course.widget.NEXT_WEEK"
        const val ACTION_REFRESH = WidgetProviders.ACTION_REFRESH
        private val WEEK_LOCK = Any()

        internal fun readWeek(
            context: Context,
            widgetPrefs: android.content.SharedPreferences,
            appWidgetId: Int
        ): Int {
            val defaultWeek = getDefaultWeek(context, appWidgetId)
            val navKey = "last_week_nav_$appWidgetId"
            val weekKey = "current_week_$appWidgetId"
            val lastNav = if (widgetPrefs.contains(navKey)) {
                widgetPrefs.getLong(navKey, 0L)
            } else {
                widgetPrefs.getLong("last_week_nav", 0L)
            }
            val freshNav = System.currentTimeMillis() - lastNav < 86_400_000L
            return if (freshNav) {
                if (widgetPrefs.contains(weekKey)) widgetPrefs.getInt(weekKey, defaultWeek)
                else widgetPrefs.getInt("current_week", defaultWeek)
            } else {
                defaultWeek
            }
        }

        /** 通知桌面所有课表小组件重读数据（朋友课表保存/删除后调用）。 */
        fun refreshAllWidgets(context: Context) {
            // 必须广播到「旧包名兼容壳 + 新包名」全部组件，
            // 否则桌面上那些老实例收不到刷新（组件名没变，一直收不到）。
            WidgetProviders.refreshAll(context)
        }

        private fun weekMonday(week: Int, startMillis: Long): java.util.Calendar =
            com.buct.course.schedule.ScheduleDates.mondayOfWeek(startMillis).apply {
                add(java.util.Calendar.DAY_OF_YEAR, (week - 1) * 7)
            }

        private fun weekInfoText(week: Int, startMillis: Long): String {
            val monday = weekMonday(week, startMillis)
            val sunday = (monday.clone() as java.util.Calendar).apply {
                add(java.util.Calendar.DAY_OF_YEAR, 6)
            }
            val firstMonth = monday.get(java.util.Calendar.MONTH) + 1
            val lastMonth = sunday.get(java.util.Calendar.MONTH) + 1
            val monthText = if (firstMonth == lastMonth) "${firstMonth}月" else "${firstMonth}月/${lastMonth}月"
            return "第${week}周 · $monthText"
        }

        private fun bindWeekHeader(views: RemoteViews, week: Int, startMillis: Long) {
            val monday = weekMonday(week, startMillis)
            val weekdayLabels = listOf("一", "二", "三", "四", "五", "六", "日")
            val headerIds = listOf(
                R.id.widget_monday_header, R.id.widget_tuesday_header, R.id.widget_wednesday_header,
                R.id.widget_thursday_header, R.id.widget_friday_header, R.id.widget_saturday_header,
                R.id.widget_sunday_header
            )
            for (i in 0..6) {
                val dayCal = (monday.clone() as java.util.Calendar).apply {
                    add(java.util.Calendar.DAY_OF_YEAR, i)
                }
                views.setTextViewText(
                    headerIds[i],
                    "${weekdayLabels[i]}\n${dayCal.get(java.util.Calendar.MONTH) + 1}/${dayCal.get(java.util.Calendar.DAY_OF_MONTH)}"
                )
            }
        }

        private fun getDefaultWeek(context: Context, appWidgetId: Int): Int {
            val startMillis = WidgetScheduleSource.resolveSemesterStartMillis(context, appWidgetId)
            return WidgetScheduleSource.weekForDate(startMillis)
        }

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val hideTitle = WidgetConfigStore.hideTitle(context, appWidgetId)
            val layoutId = if (hideTitle) R.layout.widget_schedule_notitle else R.layout.widget_schedule
            val views = RemoteViews(context.packageName, layoutId)

            val isDarkMode = WidgetAppearance.isDarkMode(context)
            val textColor = WidgetAppearance.textColor(context)
            if (!hideTitle) {
                views.setTextViewText(
                    R.id.widget_title,
                    WidgetConfigStore.resolveTitle(context, appWidgetId, WidgetKind.WEEK)
                )
                views.setTextColor(R.id.widget_title, textColor)
            }

            // 根据模式设置文字颜色、圆角背景和矢量箭头
            views.setInt(R.id.widget_root, "setBackgroundResource", WidgetAppearance.backgroundRes(context))
            views.setTextColor(R.id.widget_week_info, textColor)
            views.setImageViewResource(
                R.id.widget_prev_week,
                if (isDarkMode) R.drawable.ic_widget_chevron_left_light else R.drawable.ic_widget_chevron_left_dark
            )
            views.setImageViewResource(
                R.id.widget_next_week,
                if (isDarkMode) R.drawable.ic_widget_chevron_right_light else R.drawable.ic_widget_chevron_right_dark
            )

            // 设置星期表头的文字颜色
            views.setTextColor(R.id.widget_monday_header, textColor)
            views.setTextColor(R.id.widget_tuesday_header, textColor)
            views.setTextColor(R.id.widget_wednesday_header, textColor)
            views.setTextColor(R.id.widget_thursday_header, textColor)
            views.setTextColor(R.id.widget_friday_header, textColor)
            views.setTextColor(R.id.widget_saturday_header, textColor)
            views.setTextColor(R.id.widget_sunday_header, textColor)

            // 读取当前周数（每实例独立）
            val startMillis = WidgetScheduleSource.resolveSemesterStartMillis(context, appWidgetId)
            val widgetPrefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            val defaultWeek = getDefaultWeek(context, appWidgetId)
            val currentWeek = readWeek(context, widgetPrefs, appWidgetId)
            if (currentWeek == defaultWeek && !widgetPrefs.contains("current_week_$appWidgetId")) {
                widgetPrefs.edit()
                    .putInt("current_week_$appWidgetId", defaultWeek)
                    .putLong("last_week_nav_$appWidgetId", 0L)
                    .apply()
            }
            views.setTextViewText(R.id.widget_week_info, weekInfoText(currentWeek, startMillis))

            // 星期表头带上月份和日期，跨月时每一天仍然能看懂属于哪个月。
            bindWeekHeader(views, currentWeek, startMillis)

            // 设置上一周按钮点击事件：按 appWidgetId 隔离 PendingIntent
            val prevIntent = Intent(context, com.tyust.course.widget.ScheduleWidget::class.java).apply {
                action = ACTION_PREV_WEEK
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = android.net.Uri.parse("buctwidget://week/$appWidgetId/$ACTION_PREV_WEEK")
            }
            val prevPendingIntent = PendingIntent.getBroadcast(
                context, appWidgetId.coerceAtLeast(0) * 10 + 1, prevIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_prev_week, prevPendingIntent)

            // 设置下一周按钮点击事件
            val nextIntent = Intent(context, com.tyust.course.widget.ScheduleWidget::class.java).apply {
                action = ACTION_NEXT_WEEK
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = android.net.Uri.parse("buctwidget://week/$appWidgetId/$ACTION_NEXT_WEEK")
            }
            val nextPendingIntent = PendingIntent.getBroadcast(
                context, appWidgetId.coerceAtLeast(0) * 10 + 2, nextIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_next_week, nextPendingIntent)

            // 设置 ListView 的 RemoteViewsService
            val serviceIntent = Intent(context, ScheduleWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_list, serviceIntent)

            // 点击标题/周次进「小组件配置」，点击列表项跳转到 app
            val configIntent = Intent(context, com.buct.course.ui.widget.WidgetConfigActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(com.buct.course.ui.widget.WidgetConfigActivity.EXTRA_IS_DAY, false)
                data = android.net.Uri.parse("buctwidget://config/week/$appWidgetId")
            }
            val titleClickPendingIntent = PendingIntent.getActivity(
                context, appWidgetId + 2000, configIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (!hideTitle) {
                views.setOnClickPendingIntent(R.id.widget_title, titleClickPendingIntent)
            }
            views.setOnClickPendingIntent(R.id.widget_week_info, titleClickPendingIntent)

            // 点击列表项跳转到 app
            val clickIntent = Intent(context, com.buct.course.MainActivity::class.java)
            val clickPendingIntent = PendingIntent.getActivity(
                context, 0, clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_list, clickPendingIntent)
            WidgetTextSizes.apply(
                views,
                if (hideTitle) WidgetTextSizes.WEEK_ROOT_NO_TITLE else WidgetTextSizes.WEEK_ROOT,
                WidgetTextSizes.scale(context)
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

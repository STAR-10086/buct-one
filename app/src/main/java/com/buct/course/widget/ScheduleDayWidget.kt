package com.buct.course.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.buct.course.R
import com.buct.course.manager.UserManager
import java.util.Calendar

/**
 * 桌面「今日课表」小组件：可左右切换查看任意一天（含考试），一键回到今天。
 * 采用 ListView + RemoteViewsService，按桌面当前高度（行数）约束每屏显示的课程节数（2 行最多 2 节，3 行最多 3 节），
 * 当天课程超出时支持手动上下滑动。
 */
open class ScheduleDayWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_DAY_PREV = "com.buct.course.widget.DAY_PREV"
        const val ACTION_DAY_NEXT = "com.buct.course.widget.DAY_NEXT"
        const val ACTION_DAY_TODAY = "com.buct.course.widget.DAY_TODAY"
        private const val PREFS = "day_widget_prefs"
        private const val KEY_OFFSET = "day_offset"

        fun getDayOffset(context: Context): Int {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return p.getInt(KEY_OFFSET, 0)
        }

        private fun resolveAndSaveHeightDp(
            context: Context,
            options: Bundle?,
            appWidgetId: Int
        ): Int {
            if (options == null) return WidgetConfigStore.widgetHeightDp(context, appWidgetId)
            val isLandscape =
                context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
            val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            val h = if (isLandscape) {
                if (minH > 0) minH else maxH
            } else {
                if (maxH > 0) maxH else minH
            }
            if (h > 0) {
                WidgetConfigStore.setWidgetHeightDp(context, appWidgetId, h)
            }
            return h
        }

        /** 构建完整视图（主题 + 日期 + 可滑动列表） */
        private fun buildViews(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ): RemoteViews {
            resolveAndSaveHeightDp(
                context,
                appWidgetManager.getAppWidgetOptions(appWidgetId),
                appWidgetId
            )

            val views = RemoteViews(context.packageName, R.layout.widget_schedule_day)

            val themePrefs = context.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
            val themeMode = themePrefs.getString("theme_mode", "system")
            val nightModeFlags =
                context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            val systemDark = nightModeFlags == Configuration.UI_MODE_NIGHT_YES
            val isDarkMode = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }

            UserManager.getInstance().init(context)
            val hideTitle = WidgetConfigStore.hideTitle(context, appWidgetId)
            views.setViewVisibility(R.id.widget_title, if (hideTitle) View.GONE else View.VISIBLE)
            views.setTextViewText(
                R.id.widget_title,
                WidgetConfigStore.resolveTitle(context, appWidgetId, isDay = true)
            )

            val textColor = if (isDarkMode) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            val bgColor = if (isDarkMode) {
                android.graphics.Color.argb(180, 30, 30, 46)
            } else {
                android.graphics.Color.argb(180, 255, 255, 255)
            }
            views.setInt(R.id.widget_root, "setBackgroundColor", bgColor)
            views.setTextColor(R.id.widget_title, textColor)
            views.setTextColor(R.id.widget_day_info, textColor)
            views.setTextColor(R.id.widget_prev_day, textColor)
            views.setTextColor(R.id.widget_next_day, textColor)
            views.setTextColor(R.id.widget_back_today, textColor)

            // 所选日期：今天 + offset（无限前后切换）
            val offset = getDayOffset(context)
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, offset)
            val month = cal.get(Calendar.MONTH) + 1
            val day = cal.get(Calendar.DAY_OF_MONTH)
            val weekdayIndex = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
            val weekdayText = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[weekdayIndex - 1]
            val startMillis = WidgetScheduleSource.resolveSemesterStartMillis(context, appWidgetId)
            val week = WidgetScheduleSource.weekForDate(startMillis, cal)
            views.setTextViewText(R.id.widget_day_info, "${month}月${day}日 · $weekdayText · 第${week}周")

            views.setTextViewText(R.id.widget_back_today, if (offset == 0) "今天" else "回到今天")

            // 导航按钮：指向 AndroidManifest 实际注册的 receiver（com.tyust.course.widget.ScheduleDayWidget）
            fun pending(action: String, requestCode: Int): PendingIntent {
                val intent = Intent(context, com.tyust.course.widget.ScheduleDayWidget::class.java).setAction(action)
                return PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            views.setOnClickPendingIntent(R.id.widget_prev_day, pending(ACTION_DAY_PREV, 11))
            views.setOnClickPendingIntent(R.id.widget_next_day, pending(ACTION_DAY_NEXT, 12))
            views.setOnClickPendingIntent(R.id.widget_back_today, pending(ACTION_DAY_TODAY, 13))

            // 绑定可上下滑动的列表
            val serviceIntent = Intent(context, ScheduleDayWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_day_list, serviceIntent)

            val clickIntent = Intent(context, com.buct.course.MainActivity::class.java)
            val clickPendingIntent = PendingIntent.getActivity(
                context,
                0,
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_day_list, clickPendingIntent)

            val configIntent = Intent(context, com.buct.course.ui.widget.WidgetConfigActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(com.buct.course.ui.widget.WidgetConfigActivity.EXTRA_IS_DAY, true)
            }
            val configPendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId + 2000,
                configIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_title, configPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_day_info, configPendingIntent)

            WidgetTextSizes.apply(views, WidgetTextSizes.DAY_ROOT, WidgetTextSizes.scale(context))
            return views
        }

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = buildViews(context, appWidgetManager, appWidgetId)
            appWidgetManager.updateAppWidget(appWidgetId, views)
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
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_day_list)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        resolveAndSaveHeightDp(context, newOptions, appWidgetId)
        updateAppWidget(context, appWidgetManager, appWidgetId)
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_day_list)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_DAY_PREV, ACTION_DAY_NEXT, ACTION_DAY_TODAY -> {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val current = prefs.getInt(KEY_OFFSET, 0)
                val next = when (intent.action) {
                    ACTION_DAY_PREV -> current - 1
                    ACTION_DAY_NEXT -> current + 1
                    else -> 0
                }
                prefs.edit().putInt(KEY_OFFSET, next).apply()
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val ids = WidgetProviders.dayIds(context, appWidgetManager)
                for (id in ids) {
                    updateAppWidget(context, appWidgetManager, id)
                }
                if (ids.isNotEmpty()) {
                    appWidgetManager.notifyAppWidgetViewDataChanged(ids, R.id.widget_day_list)
                }
            }
            "com.buct.course.widget.REFRESH_WIDGET" -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val ids = WidgetProviders.dayIds(context, appWidgetManager)
                onUpdate(context, appWidgetManager, ids)
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        for (id in appWidgetIds) {
            WidgetConfigStore.clear(context, id)
        }
    }
}

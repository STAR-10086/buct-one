package com.buct.course.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.buct.course.R
import com.buct.course.manager.ScheduleSettingsManager
import com.buct.course.manager.UserManager
import com.buct.course.schedule.ScheduleJson
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
        private const val ACTION_REFRESH_WIDGET = "com.buct.course.widget.REFRESH_WIDGET"
        private const val ALARM_REQUEST_CODE = 4102
        private const val PREFS = "day_widget_prefs"
        private const val KEY_OFFSET = "day_offset"
        private val OFFSET_LOCK = Any()

        fun getDayOffset(
            context: Context,
            appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
        ): Int {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                val perKey = "${KEY_OFFSET}_$appWidgetId"
                if (p.contains(perKey)) return p.getInt(perKey, 0)
            }
            return p.getInt(KEY_OFFSET, 0)
        }

        private fun resolveDayLabels(
            context: Context,
            appWidgetId: Int,
            offset: Int
        ): Pair<String, String> {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, offset)
            val month = cal.get(Calendar.MONTH) + 1
            val day = cal.get(Calendar.DAY_OF_MONTH)
            val weekdayIndex = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
            val weekdayText = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[weekdayIndex - 1]
            val startMillis = WidgetScheduleSource.resolveSemesterStartMillis(context, appWidgetId)
            val week = WidgetScheduleSource.weekForDate(startMillis, cal)
            val dayInfo = "${month}月${day}日 · $weekdayText · 第${week}周"
            val backToday = if (offset == 0) "今天" else "回到今天"
            return dayInfo to backToday
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

            val hideTitle = WidgetConfigStore.hideTitle(context, appWidgetId)
            val layoutId = if (hideTitle) R.layout.widget_schedule_day_notitle else R.layout.widget_schedule_day
            val views = RemoteViews(context.packageName, layoutId)

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
            val textColor = if (isDarkMode) android.graphics.Color.WHITE else 0xFF1C1C1E.toInt()
            if (!hideTitle) {
                views.setTextViewText(
                    R.id.widget_title,
                    WidgetConfigStore.resolveTitle(context, appWidgetId, WidgetKind.DAY)
                )
                views.setTextColor(R.id.widget_title, textColor)
            }

            views.setInt(
                R.id.widget_root,
                "setBackgroundResource",
                if (isDarkMode) R.drawable.widget_bg_dark else R.drawable.widget_bg_light
            )
            views.setTextColor(R.id.widget_day_info, textColor)
            views.setImageViewResource(
                R.id.widget_prev_day,
                if (isDarkMode) R.drawable.ic_widget_chevron_left_light else R.drawable.ic_widget_chevron_left_dark
            )
            views.setImageViewResource(
                R.id.widget_next_day,
                if (isDarkMode) R.drawable.ic_widget_chevron_right_light else R.drawable.ic_widget_chevron_right_dark
            )
            views.setTextColor(R.id.widget_back_today, textColor)

            // 所选日期：今天 + offset（每实例独立）
            val offset = getDayOffset(context, appWidgetId)
            val (dayInfo, backToday) = resolveDayLabels(context, appWidgetId, offset)
            views.setTextViewText(R.id.widget_day_info, dayInfo)
            views.setTextViewText(R.id.widget_back_today, backToday)

            // 导航按钮：按 appWidgetId 隔离 PendingIntent，避免多实例与 HyperOS 桌面合并/重放点击
            fun pending(action: String, slot: Int): PendingIntent {
                val intent = Intent(context, com.tyust.course.widget.ScheduleDayWidget::class.java)
                    .setAction(action)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    .setData(Uri.parse("buctwidget://day/$appWidgetId/$action"))
                val requestCode = (appWidgetId.coerceAtLeast(0) * 10) + slot
                return PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            views.setOnClickPendingIntent(R.id.widget_prev_day, pending(ACTION_DAY_PREV, 1))
            views.setOnClickPendingIntent(R.id.widget_next_day, pending(ACTION_DAY_NEXT, 2))
            views.setOnClickPendingIntent(R.id.widget_back_today, pending(ACTION_DAY_TODAY, 3))

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
                data = Uri.parse("buctwidget://config/day/$appWidgetId")
            }
            val configPendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId + 2000,
                configIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (!hideTitle) {
                views.setOnClickPendingIntent(R.id.widget_title, configPendingIntent)
            }
            views.setOnClickPendingIntent(R.id.widget_day_info, configPendingIntent)

            WidgetTextSizes.apply(
                views,
                if (hideTitle) WidgetTextSizes.DAY_ROOT_NO_TITLE else WidgetTextSizes.DAY_ROOT,
                WidgetTextSizes.scale(context)
            )
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

        private fun refreshAlarmPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, com.tyust.course.widget.ScheduleDayWidget::class.java)
                .setAction(ACTION_REFRESH_WIDGET)
            return PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun cancelScheduledRefresh(context: Context) {
            val appContext = context.applicationContext
            runCatching {
                val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                alarmManager?.cancel(refreshAlarmPendingIntent(appContext))
            }
        }

        /** 预约今日最近一节待结束课程的下课时间自动刷新小组件（若今日已无后续课程则预约次日 00:01）。 */
        fun scheduleNextClassEndRefresh(context: Context) {
            val appContext = context.applicationContext
            val manager = runCatching { AppWidgetManager.getInstance(appContext) }.getOrNull() ?: return
            val dayIds = WidgetProviders.dayIds(appContext, manager)
            if (dayIds.isEmpty()) {
                cancelScheduledRefresh(appContext)
                return
            }
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

            val nowCal = Calendar.getInstance()
            val nowMillis = nowCal.timeInMillis
            val nowMinutes = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)
            val todayDay = ((nowCal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
            val settingsManager = ScheduleSettingsManager.getInstance().apply { init(appContext) }
            val periodTimes = settingsManager.getPeriodTimes()
            val defaultPeriodTimes = settingsManager.getDefaultPeriodTimes()

            var earliestEndMin: Int? = null
            for (appWidgetId in dayIds) {
                val json = WidgetScheduleSource.loadOwnScheduleJson(appContext, appWidgetId)
                    ?: continue
                val courses = runCatching { ScheduleJson.parse(json) }.getOrNull().orEmpty()
                if (courses.isEmpty()) continue
                val startMillis = WidgetScheduleSource.resolveSemesterStartMillis(appContext, appWidgetId)
                val week = WidgetScheduleSource.weekForDate(startMillis, nowCal)
                val merged = resolveMergedCoursesForDay(courses, todayDay, week)
                for (c in merged) {
                    val endMin = resolvePeriodTime(c.endPeriod, periodTimes, defaultPeriodTimes)
                        ?.endTime
                        ?.let(::parseClockMinutes)
                        ?: continue
                    if (endMin > nowMinutes && (earliestEndMin == null || endMin < earliestEndMin)) {
                        earliestEndMin = endMin
                    }
                }
            }

            val nextEndMin = earliestEndMin
            val triggerCal = (nowCal.clone() as Calendar).apply {
                if (nextEndMin != null) {
                    set(Calendar.HOUR_OF_DAY, nextEndMin / 60)
                    set(Calendar.MINUTE, nextEndMin % 60)
                    set(Calendar.SECOND, 2)
                    set(Calendar.MILLISECOND, 0)
                } else {
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 1)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
            }
            val triggerAt = maxOf(triggerCal.timeInMillis, nowMillis + 5_000L)
            val pi = refreshAlarmPendingIntent(appContext)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC, triggerAt, pi)
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC, triggerAt, pi)
                }
            }.onFailure {
                runCatching { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC, triggerAt, pi) }
            }
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
            scheduleNextClassEndRefresh(context)
        } else {
            cancelScheduledRefresh(context)
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
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val targetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                val allIds = WidgetProviders.dayIds(context, appWidgetManager)
                val ids = if (targetId != AppWidgetManager.INVALID_APPWIDGET_ID) intArrayOf(targetId) else allIds
                if (ids.isEmpty()) return

                val next = synchronized(OFFSET_LOCK) {
                    val primaryId = ids.first()
                    val current = getDayOffset(context, primaryId)
                    val computed = when (intent.action) {
                        ACTION_DAY_PREV -> current - 1
                        ACTION_DAY_NEXT -> current + 1
                        else -> 0
                    }
                    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    prefs.edit().apply {
                        ids.forEach { putInt("${KEY_OFFSET}_$it", computed) }
                        if (targetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                            putInt(KEY_OFFSET, computed)
                        }
                    }.commit()
                    computed
                }

                // 增量局部刷新日期栏，避免整卡重建引发 HyperOS 桌面 RemoteViews/PendingIntent 错乱回跳
                for (id in ids) {
                    val hideTitle = WidgetConfigStore.hideTitle(context, id)
                    val layoutId = if (hideTitle) R.layout.widget_schedule_day_notitle else R.layout.widget_schedule_day
                    val partial = RemoteViews(context.packageName, layoutId)
                    val (dayInfo, backToday) = resolveDayLabels(context, id, next)
                    partial.setTextViewText(R.id.widget_day_info, dayInfo)
                    partial.setTextViewText(R.id.widget_back_today, backToday)
                    WidgetTextSizes.apply(
                        partial,
                        if (hideTitle) WidgetTextSizes.DAY_ROOT_NO_TITLE else WidgetTextSizes.DAY_ROOT,
                        WidgetTextSizes.scale(context)
                    )
                    appWidgetManager.partiallyUpdateAppWidget(intArrayOf(id), partial)
                }
                appWidgetManager.notifyAppWidgetViewDataChanged(ids, R.id.widget_day_list)
                if (next == 0) {
                    scheduleNextClassEndRefresh(context)
                }
            }
            ACTION_REFRESH_WIDGET -> {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val ids = WidgetProviders.dayIds(context, appWidgetManager)
                onUpdate(context, appWidgetManager, ids)
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for (id in appWidgetIds) {
            WidgetConfigStore.clear(context, id)
            editor.remove("${KEY_OFFSET}_$id")
        }
        editor.commit()
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        cancelScheduledRefresh(context)
    }
}

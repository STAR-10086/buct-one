package com.buct.course.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.buct.course.R
import java.util.Calendar

/** 4×2 列表一行的排版规格（行高 dp、是否排三行）。 */
internal data class TodayRowSpec(val heightDp: Int, val threeLines: Boolean)

/**
 * 桌面「今日课表·卡片」小组件：与按天翻页的旧「今日课表」并存，两者互不影响。
 *
 * 两种尺寸自适应（尺寸判定见 [WidgetSizePolicy]）：
 * - 最小 2×1 [WidgetLayoutMode.COMPACT]：只放一堂课，默认「下一节课」，
 *   点卡片左右两端的箭头看当天前后课程；
 * - 最小 4×2 [WidgetLayoutMode.FULL]：当天完整课程列表，放不下时上下滑动。
 *
 * 每节课只显示课程名称、上课教室、上课时间三样信息。
 *
 * 关于「左右滑动」：桌面小组件的视图树由 RemoteViews 还原，平台只允许
 * FrameLayout / LinearLayout / RelativeLayout / GridLayout 这几种容器，
 * ScrollView 与 HorizontalScrollView 根本 inflate 不出来，触摸拖动事件也不会
 * 回传给 App，所以原生桌面小组件无法实现真正的滑动手势翻页。
 * 这里用左右两端箭头点击翻页 —— 平台允许的唯一等效交互。
 */
class TodayWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_TODAY_PREV = "com.buct.course.widget.TODAY_PREV"
        const val ACTION_TODAY_NEXT = "com.buct.course.widget.TODAY_NEXT"
        private const val ALARM_REQUEST_CODE = 4202
        private const val PREFS = "today_widget_prefs"
        private const val KEY_SELECTED = "selected_course"

        /** 4×2 列表顶部日期栏高度（用于算每行高度）。 */
        private const val FULL_HEADER_DP = 44
        /** ListView 的分隔线高度（widget_today*.xml 里 dividerHeight）。 */
        private const val ROW_DIVIDER_DP = 4
        /** 行高够不够排三行（会再乘用户的小组件字号缩放）。 */
        private const val THREE_LINE_THRESHOLD_DP = 50
        private const val THREE_LINE_HEIGHT_DP = 56
        private const val TWO_LINE_HEIGHT_DP = 38

        /** 用户翻到的那一节；数据变了或换了天就自然失效，回到「下一节课」。 */
        private fun storedSelection(context: Context, appWidgetId: Int): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("${KEY_SELECTED}_$appWidgetId", null)

        private fun storeSelection(context: Context, appWidgetId: Int, key: String?) {
            val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            if (key.isNullOrBlank()) {
                editor.remove("${KEY_SELECTED}_$appWidgetId")
            } else {
                editor.putString("${KEY_SELECTED}_$appWidgetId", key)
            }
            editor.commit()
        }

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
            val views = when (WidgetSizePolicy.mode(size)) {
                WidgetLayoutMode.COMPACT -> buildCompact(context, size, appWidgetId)
                WidgetLayoutMode.FULL -> buildFull(context, appWidgetId)
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /**
         * 选中课程：优先用用户上次翻到的那一节（同一天且课程还在），
         * 否则回到默认 —— 下一节课（正在上的也算）。
         */
        private fun resolveSelectedCourse(
            context: Context,
            appWidgetId: Int,
            data: TodayCourses
        ): TodayCourse? {
            if (data.courses.isEmpty() || data.nextIndex < 0) return null
            val stored = storedSelection(context, appWidgetId)
            if (!stored.isNullOrBlank()) {
                data.courses.firstOrNull { it.key == stored }?.let { return it }
            }
            return data.courses.getOrNull(data.nextIndex)
        }

        private fun buildCompact(
            context: Context,
            size: WidgetSize,
            appWidgetId: Int
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_today_compact)
            views.setInt(R.id.today_root, "setBackgroundResource", WidgetAppearance.backgroundRes(context))

            val todayData = TodayCourseSource.load(context, appWidgetId)
            val selected = resolveSelectedCourse(context, appWidgetId, todayData)
            val twoLines = size.heightDp < 54
            val fontScale = WidgetTextSizes.scale(context)
            // 小部件被桌面缩放后高度差异很大，字号跟着高度走才不会溢出
            val heightFactor = (size.heightDp / 64f).coerceIn(0.82f, 1.12f)

            val placeholder = when {
                selected != null -> null
                !todayData.hasScheduleData -> "暂无课表"
                todayData.courses.isEmpty() -> "今天没有课"
                else -> "今天的课已上完"
            }

            val nameText = selected?.name ?: placeholder.orEmpty()
            val timeText = when {
                selected == null -> ""
                twoLines -> mergeTimeAndLocation(selected.timeText, selected.location)
                else -> selected.timeText
            }
            val locationText = if (selected == null || twoLines) "" else selected.location

            views.setTextViewText(R.id.today_name, nameText)
            views.setTextViewText(R.id.today_time, timeText)
            views.setTextViewText(R.id.today_loc, locationText)
            views.setViewVisibility(R.id.today_time, if (timeText.isBlank()) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.today_loc, if (locationText.isBlank()) View.GONE else View.VISIBLE)
            views.setFloat(R.id.today_name, "setTextSize", WidgetTextSizes.TODAY_COMPACT_NAME_SP * fontScale * heightFactor)
            views.setFloat(R.id.today_time, "setTextSize", WidgetTextSizes.TODAY_COMPACT_DETAIL_SP * fontScale * heightFactor)
            views.setFloat(R.id.today_loc, "setTextSize", WidgetTextSizes.TODAY_COMPACT_DETAIL_SP * fontScale * heightFactor)

            if (selected == null) {
                views.setInt(R.id.today_card, "setBackgroundColor", Color.TRANSPARENT)
                views.setTextColor(R.id.today_name, WidgetAppearance.secondaryTextColor(context))
            } else {
                views.setInt(R.id.today_card, "setBackgroundResource", WidgetAppearance.courseCardRes(selected.name))
                views.setTextColor(R.id.today_name, Color.WHITE)
                views.setTextColor(R.id.today_time, 0xE6FFFFFF.toInt())
                views.setTextColor(R.id.today_loc, 0xD9FFFFFF.toInt())
            }
            views.setOnClickPendingIntent(
                R.id.today_card,
                openAppPendingIntent(context, appWidgetId)
            )

            // 当天只有一节课（或没有课）时不显示翻页箭头，避免点了没反应
            val browsable = todayData.courses.size > 1 && selected != null
            views.setViewVisibility(R.id.today_prev_course, if (browsable) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.today_next_course, if (browsable) View.VISIBLE else View.GONE)
            if (browsable) {
                val isDark = WidgetAppearance.isDarkMode(context)
                views.setImageViewResource(
                    R.id.today_prev_course,
                    if (isDark) R.drawable.ic_widget_chevron_left_light else R.drawable.ic_widget_chevron_left_dark
                )
                views.setImageViewResource(
                    R.id.today_next_course,
                    if (isDark) R.drawable.ic_widget_chevron_right_light else R.drawable.ic_widget_chevron_right_dark
                )
                views.setOnClickPendingIntent(R.id.today_prev_course, pagingPendingIntent(context, appWidgetId, ACTION_TODAY_PREV, 1))
                views.setOnClickPendingIntent(R.id.today_next_course, pagingPendingIntent(context, appWidgetId, ACTION_TODAY_NEXT, 2))
            }
            return views
        }

        private fun buildFull(context: Context, appWidgetId: Int): RemoteViews {
            val hideTitle = WidgetConfigStore.hideTitle(context, appWidgetId)
            val layoutId =
                if (hideTitle) R.layout.widget_today_notitle else R.layout.widget_today
            val views = RemoteViews(context.packageName, layoutId)
            val todayData = TodayCourseSource.load(context, appWidgetId)
            views.setInt(R.id.today_root, "setBackgroundResource", WidgetAppearance.backgroundRes(context))

            if (!hideTitle) {
                views.setTextViewText(
                    R.id.today_title,
                    WidgetConfigStore.resolveTitle(context, appWidgetId, WidgetKind.TODAY)
                )
                views.setTextColor(R.id.today_title, WidgetAppearance.textColor(context))
            }
            views.setTextViewText(R.id.today_day_info, todayData.dayInfo)
            views.setTextColor(R.id.today_day_info, WidgetAppearance.secondaryTextColor(context))

            val serviceIntent = Intent(context, TodayWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.today_list, serviceIntent)
            views.setPendingIntentTemplate(
                R.id.today_list,
                openAppPendingIntent(context, appWidgetId)
            )

            val configPendingIntent = configPendingIntent(context, appWidgetId)
            if (!hideTitle) {
                views.setOnClickPendingIntent(R.id.today_title, configPendingIntent)
            }
            views.setOnClickPendingIntent(R.id.today_day_info, configPendingIntent)

            WidgetTextSizes.apply(
                views,
                if (hideTitle) WidgetTextSizes.TODAY_ROOT_NO_TITLE else WidgetTextSizes.TODAY_ROOT,
                WidgetTextSizes.scale(context)
            )
            return views
        }

        /** 2×1 翻页：按轮播顺序前进/后退一格，把结果记下来再整卡重建。 */
        private fun pageSelection(context: Context, appWidgetId: Int, forward: Boolean) {
            val data = TodayCourseSource.load(context, appWidgetId)
            if (data.courses.isEmpty() || data.nextIndex < 0) return
            val order = TodayCourseSource.carouselOrder(data.courses, data.nextIndex)
            if (order.size <= 1) return
            val current = resolveSelectedCourse(context, appWidgetId, data) ?: return
            val index = order.indexOfFirst { it.key == current.key }.coerceAtLeast(0)
            val target = if (forward) (index + 1) % order.size else (index - 1 + order.size) % order.size
            storeSelection(context, appWidgetId, order[target].key)
        }

        private fun mergeTimeAndLocation(time: String, location: String): String = when {
            location.isBlank() -> time
            time.isBlank() -> location
            else -> "$time · $location"
        }

        private fun openAppPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, com.buct.course.MainActivity::class.java)
                .setData(Uri.parse("buctwidget://today/open/$appWidgetId"))
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun configPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, com.buct.course.ui.widget.WidgetConfigActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(
                    com.buct.course.ui.widget.WidgetConfigActivity.EXTRA_WIDGET_KIND,
                    WidgetKind.TODAY.name
                )
                data = Uri.parse("buctwidget://config/today/$appWidgetId")
            }
            return PendingIntent.getActivity(
                context,
                appWidgetId + 2000,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun pagingPendingIntent(
            context: Context,
            appWidgetId: Int,
            action: String,
            slot: Int
        ): PendingIntent {
            val intent = Intent(context, TodayWidget::class.java)
                .setAction(action)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .setData(Uri.parse("buctwidget://today/$appWidgetId/$action"))
            return PendingIntent.getBroadcast(
                context,
                appWidgetId * 10 + slot,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun refreshAlarmPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, TodayWidget::class.java)
                .setAction(WidgetProviders.ACTION_REFRESH)
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

        /**
         * 预约下一次需要换内容的时间点：当天最近一次「上课开始」或「下课」，
         * 因为「下一节课」会在这两个时刻变化；当天没有课了则预约次日 00:01 换日期。
         */
        fun scheduleNextRefresh(context: Context) {
            val appContext = context.applicationContext
            val manager = runCatching { AppWidgetManager.getInstance(appContext) }.getOrNull() ?: return
            val todayIds = WidgetProviders.todayIds(appContext, manager)
            if (todayIds.isEmpty()) {
                cancelScheduledRefresh(appContext)
                return
            }
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

            val nowCal = Calendar.getInstance()
            val nowMillis = nowCal.timeInMillis
            val nowMinutes = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)

            var nextBoundary: Int? = null
            for (appWidgetId in todayIds) {
                val data = runCatching { TodayCourseSource.load(appContext, appWidgetId) }.getOrNull()
                    ?: continue
                for (course in data.courses) {
                    for (boundary in listOfNotNull(course.startMinutes, course.endMinutes)) {
                        val current = nextBoundary
                        if (boundary > nowMinutes && (current == null || boundary < current)) {
                            nextBoundary = boundary
                        }
                    }
                }
            }

            val triggerCal = (nowCal.clone() as Calendar).apply {
                val boundary = nextBoundary
                if (boundary != null) {
                    set(Calendar.HOUR_OF_DAY, boundary / 60)
                    set(Calendar.MINUTE, boundary % 60)
                } else {
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 1)
                }
                set(Calendar.SECOND, 2)
                set(Calendar.MILLISECOND, 0)
            }
            val triggerAt = maxOf(triggerCal.timeInMillis, nowMillis + 5_000L)
            val pendingIntent = refreshAlarmPendingIntent(appContext)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC, triggerAt, pendingIntent)
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC, triggerAt, pendingIntent)
                }
            }.onFailure {
                runCatching { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC, triggerAt, pendingIntent) }
            }
        }

        /**
         * 4×2 列表每行怎么排：按桌面实际高度分档，放不下就交给 ListView 上下滑动。
         *
         * 两点必须一起算：
         * 1. 扣掉 ListView 分隔线，否则最后一行会被切掉几个 dp；
         * 2. 用户可以把小组件字号放大到 150%，行高要跟着长 —— 宁可一屏少显示几行也不能裁字。
         */
        internal fun fullRowSpec(
            size: WidgetSize,
            hideTitle: Boolean,
            fontScale: Float
        ): TodayRowSpec {
            val headerDp = if (hideTitle) (FULL_HEADER_DP - 18) else FULL_HEADER_DP
            val listHeightDp = (size.heightDp - headerDp).coerceAtLeast(72)
            val targetRows = when {
                listHeightDp >= 300 -> 6
                listHeightDp >= 220 -> 5
                listHeightDp >= 160 -> 4
                listHeightDp >= 100 -> 3
                else -> 2
            }
            val slotHeightDp = (listHeightDp / targetRows) - ROW_DIVIDER_DP
            val threeLines = slotHeightDp >= (THREE_LINE_THRESHOLD_DP * fontScale).toInt()
            val contentHeightDp =
                ((if (threeLines) THREE_LINE_HEIGHT_DP else TWO_LINE_HEIGHT_DP) * fontScale).toInt()
            return TodayRowSpec(
                heightDp = maxOf(slotHeightDp, contentHeightDp).coerceIn(38, 170),
                threeLines = threeLines
            )
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
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.today_list)
            scheduleNextRefresh(context)
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
        // 桌面拖动改变尺寸：重新判排版模式、记下新尺寸，列表数据也要重算行高
        updateAppWidget(context, appWidgetManager, appWidgetId)
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.today_list)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val appWidgetManager = AppWidgetManager.getInstance(context)
        when (intent.action) {
            ACTION_TODAY_PREV, ACTION_TODAY_NEXT -> {
                val targetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
                val ids = if (targetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    intArrayOf(targetId)
                } else {
                    WidgetProviders.todayIds(context, appWidgetManager)
                }
                for (appWidgetId in ids) {
                    pageSelection(context, appWidgetId, intent.action == ACTION_TODAY_NEXT)
                    updateAppWidget(context, appWidgetManager, appWidgetId)
                }
            }
            WidgetProviders.ACTION_REFRESH -> {
                onUpdate(context, appWidgetManager, WidgetProviders.todayIds(context, appWidgetManager))
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for (appWidgetId in appWidgetIds) {
            WidgetConfigStore.clear(context, appWidgetId)
            editor.remove("${KEY_SELECTED}_$appWidgetId")
        }
        editor.commit()
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        cancelScheduledRefresh(context)
    }
}
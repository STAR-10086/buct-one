package com.buct.course.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.buct.course.R
import com.buct.course.manager.ScheduleSettingsManager
import com.buct.course.manager.UserManager
import com.buct.course.schedule.NetworkScheduleCourse
import com.buct.course.schedule.ScheduleJson
import com.buct.course.schedule.ScheduleWeeks
import org.json.JSONArray
import java.util.Calendar

internal data class MergedDayCourse(
    val name: String,
    val location: String,
    val teacher: String,
    val startPeriod: Int,
    val endPeriod: Int
)

private val EXAM_TIME_RANGE_REGEX = Regex("""(\d{1,2}):(\d{2})\s*[-~–至]\s*(\d{1,2}):(\d{2})""")

internal fun parseClockMinutes(rawTime: String): Int? {
    val parts = rawTime.trim().split(':')
    if (parts.size < 2) return null
    val h = parts[0].trim().toIntOrNull() ?: return null
    val m = parts[1].trim().toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

internal fun resolvePeriodTime(
    period: Int,
    periodTimes: List<ScheduleSettingsManager.PeriodTime>,
    defaultPeriodTimes: List<ScheduleSettingsManager.PeriodTime>
): ScheduleSettingsManager.PeriodTime? =
    periodTimes.firstOrNull { it.period == period }
        ?: defaultPeriodTimes.firstOrNull { it.period == period }

internal fun parseExamEndMinutes(rawExamTime: String): Int? {
    val match = EXAM_TIME_RANGE_REGEX.find(rawExamTime) ?: return null
    val h = match.groupValues[3].toIntOrNull() ?: return null
    val m = match.groupValues[4].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

internal fun resolveMergedCoursesForDay(
    courses: List<NetworkScheduleCourse>,
    day: Int,
    week: Int
): List<MergedDayCourse> {
    val dayCourses = courses
        .filter {
            it.course.day == day &&
                ScheduleWeeks.parse(it.course.weeks).visibleIn(week)
        }
        .map { it.course }
        .sortedBy { it.startPeriod }

    return buildList {
        for (c in dayCourses) {
            val last = lastOrNull()
            if (last != null && last.name == c.name && last.location == c.location &&
                last.teacher == c.teacher && c.startPeriod == last.endPeriod + 1
            ) {
                this[lastIndex] = last.copy(endPeriod = c.endPeriod)
            } else {
                add(MergedDayCourse(c.name, c.location, c.teacher, c.startPeriod, c.endPeriod))
            }
        }
    }
}

/**
 * 桌面「今日课表」小组件的 RemoteViewsService。
 * 按实例当前的桌面高度（行数）动态固定每张卡片高度（2 行最多每屏 2 节，3 行最多 3 节…），
 * 查看当天时自动隐藏已过下课时间的课程，当日未结束课程超出视图时可手动上下滑动查看。
 */
class ScheduleDayWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return ScheduleDayRemoteViewsFactory(this.applicationContext, intent)
    }
}

class ScheduleDayRemoteViewsFactory(
    private val context: Context,
    intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private val appWidgetId: Int = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID,
        AppWidgetManager.INVALID_APPWIDGET_ID
    )

    private var courses: List<NetworkScheduleCourse> = emptyList()
    private var courseColorMap = mutableMapOf<String, Int>()
    private var selectedDay = 1
    private var selectedDate = ""
    private var dayOffset = 0
    private var hadScheduledItems = false
    private var dayExams: List<ExamRow> = emptyList()
    private var merged: List<MergedDayCourse> = emptyList()
    private var periodTimes: List<ScheduleSettingsManager.PeriodTime> = emptyList()
    private var defaultPeriodTimes: List<ScheduleSettingsManager.PeriodTime> = emptyList()
    private var cardHeightDp: Int = 56
    private var isCompact: Boolean = true

    private data class ExamRow(
        val name: String,
        val time: String,
        val loc: String,
        val endMinutes: Int? = null
    )

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val rawHeight = WidgetConfigStore.widgetHeightDp(context, appWidgetId).let { if (it > 0) it else 160 }
        val hideTitle = WidgetConfigStore.hideTitle(context, appWidgetId)
        val slots = WidgetConfigStore.visibleCourseSlots(rawHeight)
        val headerDp = if (hideTitle) 52 else 74
        val listHeightDp = (rawHeight - headerDp).coerceAtLeast(96)
        val dividerTotal = 4 * (slots - 1)
        cardHeightDp = ((listHeightDp - dividerTotal) / slots).coerceIn(46, 115)
        isCompact = cardHeightDp <= 60

        try {
            UserManager.getInstance().init(context)
            val settingsManager = ScheduleSettingsManager.getInstance().apply { init(context) }
            periodTimes = settingsManager.getPeriodTimes()
            defaultPeriodTimes = settingsManager.getDefaultPeriodTimes()

            val friendJson = WidgetScheduleSource.loadFriendJson(context, appWidgetId)
            val scheduleJson = friendJson ?: WidgetScheduleSource.loadOwnScheduleJson(context, appWidgetId)

            if (scheduleJson != null) {
                courses = ScheduleJson.parse(scheduleJson) ?: emptyList()
                val courseNames = courses.map { it.course.name }.distinct()
                courseColorMap.clear()
                courseNames.forEachIndexed { index, name ->
                    courseColorMap[name] = index
                }
            } else {
                courses = emptyList()
            }

            dayOffset = ScheduleDayWidget.getDayOffset(context)
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, dayOffset)
            selectedDay = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
            selectedDate = String.format(
                java.util.Locale.US, "%04d-%02d-%02d",
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
            )

            val startMillis = WidgetScheduleSource.resolveSemesterStartMillis(context, appWidgetId)
            val currentWeek = WidgetScheduleSource.weekForDate(startMillis, cal)
            val allMerged = resolveMergedCoursesForDay(courses, selectedDay, currentWeek)

            var allExams: List<ExamRow> = emptyList()
            val examPrefs = context.getSharedPreferences("exam_cache", Context.MODE_PRIVATE)
            val examJson = if (friendJson == null) examPrefs.getString("exams", null) else null
            if (examJson != null) {
                val arr = JSONArray(examJson)
                allExams = buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val time = o.optString("examTime", "")
                        if (time.startsWith(selectedDate)) {
                            val name = o.optString("courseName", "考试")
                            val timeText = formatExamTime(time)
                            val loc = buildString {
                                val location = o.optString("location", "")
                                val seat = o.optString("seatNumber", "")
                                val examName = o.optString("examName", "")
                                if (examName.isNotBlank()) {
                                    if (location.isNotBlank() || seat.isNotBlank()) append("$examName · ")
                                    else append(examName)
                                }
                                if (location.isNotBlank()) append(location)
                                if (seat.isNotBlank()) append(" · $seat")
                            }
                            add(ExamRow(name, timeText, loc, parseExamEndMinutes(timeText)))
                        }
                    }
                }.sortedBy { it.time }
            }

            hadScheduledItems = allMerged.isNotEmpty() || allExams.isNotEmpty()
            if (dayOffset == 0) {
                val nowCal = Calendar.getInstance()
                val nowMinutes = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)
                merged = allMerged.filter { c ->
                    val endMin = resolvePeriodTime(c.endPeriod, periodTimes, defaultPeriodTimes)
                        ?.endTime
                        ?.let(::parseClockMinutes)
                    endMin == null || endMin > nowMinutes
                }
                dayExams = allExams.filter { e ->
                    e.endMinutes == null || e.endMinutes > nowMinutes
                }
            } else {
                merged = allMerged
                dayExams = allExams
            }
        } catch (_: Exception) {
            courses = emptyList()
            merged = emptyList()
            dayExams = emptyList()
            hadScheduledItems = false
        }
    }

    private fun formatExamTime(time: String): String {
        val idx = time.indexOf(' ')
        return if (idx > 0) time.substring(idx + 1) else time
    }

    override fun onDestroy() {}

    override fun getCount(): Int {
        val total = dayExams.size + merged.size
        return if (total == 0) 1 else total
    }

    override fun getViewAt(position: Int): RemoteViews {
        val themePrefs = context.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
        val themeMode = themePrefs.getString("theme_mode", "system")
        val nightModeFlags =
            context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val systemDark = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val isDarkMode = when (themeMode) {
            "light" -> false
            "dark" -> true
            else -> systemDark
        }

        if (dayExams.isEmpty() && merged.isEmpty()) {
            val views = RemoteViews(context.packageName, R.layout.widget_schedule_day_row)
            val emptyText = if (dayOffset == 0 && hadScheduledItems) {
                "今日课程已全部上完"
            } else {
                "当天没有课程安排"
            }
            views.setTextViewText(R.id.item_name, emptyText)
            views.setTextViewText(R.id.item_time, "")
            views.setTextViewText(R.id.item_loc, "")
            views.setViewVisibility(R.id.item_time, View.GONE)
            views.setViewVisibility(R.id.item_loc, View.GONE)
            views.setInt(R.id.item_root, "setBackgroundColor", Color.TRANSPARENT)
            views.setTextColor(R.id.item_name, if (isDarkMode) Color.WHITE else Color.BLACK)
            applyCardSizing(views, R.id.item_root, R.id.item_name, R.id.item_time, R.id.item_loc)
            return views
        }

        if (position < dayExams.size) {
            val e = dayExams[position]
            val views = RemoteViews(context.packageName, R.layout.widget_schedule_exam_row)
            views.setTextViewText(R.id.exam_name, e.name)
            views.setTextViewText(R.id.exam_time, e.time)
            views.setTextViewText(R.id.exam_loc, e.loc)
            views.setViewVisibility(R.id.exam_time, if (e.time.isBlank()) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.exam_loc, if (e.loc.isBlank()) View.GONE else View.VISIBLE)
            views.setInt(R.id.exam_root, "setBackgroundResource", R.drawable.widget_exam_bg)
            applyCardSizing(views, R.id.exam_root, R.id.exam_name, R.id.exam_time, R.id.exam_loc)
            return views
        }

        val views = RemoteViews(context.packageName, R.layout.widget_schedule_day_row)
        val c = merged[position - dayExams.size]

        val startTime = resolvePeriodTime(c.startPeriod, periodTimes, defaultPeriodTimes)?.startTime ?: ""
        val endTime = resolvePeriodTime(c.endPeriod, periodTimes, defaultPeriodTimes)?.endTime ?: ""
        val span = if (c.endPeriod > c.startPeriod) "第${c.startPeriod}-${c.endPeriod}节" else "第${c.startPeriod}节"
        val timeText = if (startTime.isNotBlank() || endTime.isNotBlank()) "$span  $startTime-$endTime" else span
        val locText = buildString {
            if (c.location.isNotBlank()) append(c.location)
            if (c.teacher.isNotBlank()) append(" · ${c.teacher}")
        }

        views.setTextViewText(R.id.item_name, c.name)
        views.setTextColor(R.id.item_name, Color.WHITE)
        views.setTextViewText(R.id.item_time, timeText)
        views.setViewVisibility(R.id.item_time, View.VISIBLE)
        views.setTextViewText(R.id.item_loc, locText)
        views.setViewVisibility(R.id.item_loc, if (locText.isBlank()) View.GONE else View.VISIBLE)

        val colorIdx = courseColorMap[c.name] ?: 0
        val bgRes = when (colorIdx % 8) {
            0 -> R.drawable.widget_course_card_indigo
            1 -> R.drawable.widget_course_card_blue
            2 -> R.drawable.widget_course_card_green
            3 -> R.drawable.widget_course_card_orange
            4 -> R.drawable.widget_course_card_purple
            5 -> R.drawable.widget_course_card_red
            6 -> R.drawable.widget_course_card_cyan
            else -> R.drawable.widget_course_card_brown
        }
        views.setInt(R.id.item_root, "setBackgroundResource", bgRes)
        applyCardSizing(views, R.id.item_root, R.id.item_name, R.id.item_time, R.id.item_loc)

        return views
    }

    private fun applyCardSizing(
        views: RemoteViews,
        rootId: Int,
        nameId: Int,
        timeId: Int,
        locId: Int
    ) {
        val density = context.resources.displayMetrics.density
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setViewLayoutHeight(rootId, cardHeightDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
        } else {
            views.setInt(rootId, "setMinimumHeight", (cardHeightDp * density).toInt())
        }
        val scale = WidgetTextSizes.scale(context)
        val nameSize = (if (isCompact) 12.5f else 14f) * scale
        val timeSize = (if (isCompact) 10.5f else 11.5f) * scale
        val locSize = (if (isCompact) 10f else 11f) * scale
        views.setFloat(nameId, "setTextSize", nameSize)
        views.setFloat(timeId, "setTextSize", timeSize)
        views.setFloat(locId, "setTextSize", locSize)
        views.setInt(nameId, "setMaxLines", if (isCompact) 1 else 2)
        views.setInt(timeId, "setMaxLines", 1)
        views.setInt(locId, "setMaxLines", 1)
        val hPad = ((if (isCompact) 8 else 10) * density).toInt()
        val vPad = ((if (isCompact) 4 else 6) * density).toInt()
        views.setViewPadding(rootId, hPad, vPad, hPad, vPad)
        views.setOnClickFillInIntent(rootId, Intent())
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 2

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = true
}

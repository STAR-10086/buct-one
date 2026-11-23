package com.buct.course.widget

import android.content.Context
import com.buct.course.manager.ScheduleSettingsManager
import com.buct.course.schedule.ScheduleJson
import java.util.Calendar

/**
 * 「今日课表·卡片」小组件要显示的一节课。
 *
 * 只保留三样信息：课程名称 [name]、上课教室 [location]、上课时间 [timeText]。
 * 老师、学分等一律不进小组件（桌面上放不下，也不是一眼要看的东西）。
 */
internal data class TodayCourse(
    val name: String,
    val location: String,
    val startPeriod: Int,
    val endPeriod: Int,
    /** 已排好版的「第1-2节  08:00-09:35」；取不到节次时间时退化成「第1-2节」。 */
    val timeText: String,
    val startMinutes: Int?,
    val endMinutes: Int?,
    /** 用于在两次刷新之间记住用户翻到的是哪一节：日期 + 起始节次 + 课程名。 */
    val key: String
) {
    fun hasEnded(nowMinutes: Int): Boolean = endMinutes != null && endMinutes <= nowMinutes
}

internal data class TodayCourses(
    /** 当天全部课程，按节次升序（含已经上完的，4×2 要显示完整列表）。 */
    val courses: List<TodayCourse>,
    /** 「9月23日 · 周三 · 第4周」 */
    val dayInfo: String,
    val nowMinutes: Int,
    /**
     * 是否读到了真实的课表快照。
     * false 表示「还没同步过 / 解析失败」，此时课程列表为空也不能说「今天没有课」
     * —— 数据层铁律 2：NEVER ≠ EMPTY_CONFIRMED。
     */
    val hasScheduleData: Boolean
) {
    /** 下一节课（正在上的也算）在 [courses] 中的下标；当天课程全部结束时为 -1。 */
    val nextIndex: Int
        get() = courses.indexOfFirst { !it.hasEnded(nowMinutes) }
}

/**
 * 「今日课表·卡片」的数据来源：读本机账号当前学期课表 → 取今天 → 合并连堂课 → 补上节次时间。
 * 与周课表小组件、旧「今日课表」小组件同源（WidgetScheduleSource），
 * 连堂合并与节次时间辅助函数复用 ScheduleDayWidgetService 里的那份（同包 internal）。
 */
internal object TodayCourseSource {

    private val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun load(context: Context, appWidgetId: Int): TodayCourses {
        val cal = Calendar.getInstance()
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val dateKey = String.format(
            java.util.Locale.US,
            "%04d%02d%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
        val weekdayIndex = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val startMillis = runCatching {
            WidgetScheduleSource.resolveSemesterStartMillis(context, appWidgetId)
        }.getOrDefault(0L)
        val week = runCatching { WidgetScheduleSource.weekForDate(startMillis, cal) }.getOrDefault(1)
        val dayInfo = "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日 · " +
            "${WEEKDAY_LABELS[weekdayIndex]} · 第${week}周"

        val json = runCatching {
            WidgetScheduleSource.loadOwnScheduleJson(context, appWidgetId)
        }.getOrNull() ?: return TodayCourses(emptyList(), dayInfo, nowMinutes, hasScheduleData = false)
        val courses = runCatching { ScheduleJson.parse(json) }.getOrNull()
            ?: return TodayCourses(emptyList(), dayInfo, nowMinutes, hasScheduleData = false)

        val settings = runCatching {
            ScheduleSettingsManager.getInstance().apply { init(context) }
        }.getOrNull()
        val periodTimes = settings?.getPeriodTimes().orEmpty()
        val defaultPeriodTimes = settings?.getDefaultPeriodTimes().orEmpty()

        val merged = resolveMergedCoursesForDay(courses, weekdayIndex + 1, week)
        val list = merged.map { course ->
            val start = resolvePeriodTime(course.startPeriod, periodTimes, defaultPeriodTimes)
            val end = resolvePeriodTime(course.endPeriod, periodTimes, defaultPeriodTimes)
            TodayCourse(
                name = course.name,
                location = course.location,
                startPeriod = course.startPeriod,
                endPeriod = course.endPeriod,
                timeText = buildTimeText(course.startPeriod, course.endPeriod, start?.startTime, end?.endTime),
                startMinutes = start?.startTime?.let(::parseClockMinutes),
                endMinutes = end?.endTime?.let(::parseClockMinutes),
                key = "$dateKey#${course.startPeriod}#${course.name}"
            )
        }
        return TodayCourses(list, dayInfo, nowMinutes, hasScheduleData = true)
    }

    /**
     * 2×1 单节卡片的翻页顺序：从下一节课开始，往后排完再回到当天最早的课程。
     * 这样默认停在第一张就是「下一节课」，一路点下去也能翻到当天每一节课。
     */
    fun carouselOrder(courses: List<TodayCourse>, nextIndex: Int): List<TodayCourse> {
        if (courses.isEmpty()) return emptyList()
        if (nextIndex !in 1 until courses.size) return courses
        return courses.subList(nextIndex, courses.size) + courses.subList(0, nextIndex)
    }

    private fun buildTimeText(
        startPeriod: Int,
        endPeriod: Int,
        startTime: String?,
        endTime: String?
    ): String {
        val span = if (endPeriod > startPeriod) "第${startPeriod}-${endPeriod}节" else "第${startPeriod}节"
        val clock = when {
            !startTime.isNullOrBlank() && !endTime.isNullOrBlank() -> "$startTime-$endTime"
            !startTime.isNullOrBlank() -> startTime
            !endTime.isNullOrBlank() -> endTime
            else -> ""
        }
        return if (clock.isBlank()) span else "$span  $clock"
    }
}

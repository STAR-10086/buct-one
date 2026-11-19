package com.buct.course.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import com.buct.course.academic.AcademicStudyParser
import com.buct.course.academic.AcademicTerm
import com.buct.course.manager.ScheduleSettingsManager
import com.buct.course.manager.UserManager
import com.buct.course.schedule.ScheduleCacheStore
import com.buct.course.schedule.ScheduleCalendarStore
import com.buct.course.schedule.ScheduleDates
import com.buct.course.schedule.ScheduleMaxWeeks
import com.buct.course.social.FriendScheduleStore
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * 小组件实例的数据来源解析（Week 课表 / 今日课表共用）。
 *
 * 实例配置里的 source 字段取值：
 * - 空 / null      → 本机账号课表（再用 accountKey 决定是哪个账号）
 * - "friend:<id>"  → 好友传过来、已保存在固定目录里的课表
 *
 * 朋友课表被删掉（或文件丢失）时这里返回 null，调用方自动回落到本机账号，
 * 不会出现「小组件空白但标题还写着别人名字」的情况。
 */
object WidgetScheduleSource {
    private const val PREFIX = "friend:"

    fun encodeFriend(id: String): String = PREFIX + id

    /** 解析该小组件实例对应的本机账号存储 key 与学校 id。 */
    fun resolveAccountAndSchool(context: Context, appWidgetId: Int): Pair<String, String> {
        UserManager.getInstance().init(context)
        val instanceAccount = WidgetConfigStore.accountKey(context, appWidgetId)
        val account = instanceAccount ?: UserManager.getInstance().currentAccountStorageKey
        val school = UserManager.getInstance().currentSchool?.id ?: "buct"
        return account to school
    }

    /** 解析小组件应展示的当前学期：优先当前日历/教务学期，若尚未同步则仅当旧缓存存在数据时回退。 */
    fun resolveTerm(context: Context, appWidgetId: Int): AcademicTerm {
        val (account, school) = resolveAccountAndSchool(context, appWidgetId)
        val prefs = context.getSharedPreferences("schedule_cache", Context.MODE_PRIVATE)
        val store = ScheduleCacheStore(context, prefs)
        val current = store.currentTerm(account, school)
        if (store.read(account, school, current) != null) return current

        val rawCurrent = prefs.getString("schedule_${account}_${school}_current", null)
        val cachedTerm = rawCurrent?.let { raw ->
            runCatching {
                JSONObject(raw).optString("term", "")
                    .takeIf { it.isNotBlank() }
                    ?.let(AcademicStudyParser::term)
            }.getOrNull()
        }
        if (cachedTerm != null && store.read(account, school, cachedTerm) != null) {
            return cachedTerm
        }
        return current
    }

    /** 读取本机账号当前学期课表 JSON（兼容文件存储、SharedPreferences 与旧键名）。 */
    fun loadOwnScheduleJson(context: Context, appWidgetId: Int): String? {
        val (account, school) = resolveAccountAndSchool(context, appWidgetId)
        val prefs = context.getSharedPreferences("schedule_cache", Context.MODE_PRIVATE)
        val store = ScheduleCacheStore(context, prefs)
        val term = resolveTerm(context, appWidgetId)
        return store.read(account, school, term)
    }

    /**
     * 解析当前学期第 1 周周一时间戳（与 App 课表同源）：
     * 1. ScheduleCalendarStore 中当前学期的 firstWeekDate
     * 2. 用户在 ScheduleSettingsManager 显式保存过的 semesterStartDate
     * 3. 按当前学期动态推算的默认第 1 周周一
     */
    fun resolveSemesterStartMillis(
        context: Context,
        appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    ): Long {
        val (account, _) = resolveAccountAndSchool(context, appWidgetId)
        val term = resolveTerm(context, appWidgetId)
        val reminderPrefs = context.getSharedPreferences("course_reminders", Context.MODE_PRIVATE)
        val calendarFirstWeek = ScheduleCalendarStore(reminderPrefs).read(account, term.id)?.firstWeekDate
        ScheduleDates.firstMonday(calendarFirstWeek)?.let { return it.timeInMillis }

        val settings = ScheduleSettingsManager.getInstance().apply { init(context) }
        settings.savedSemesterStartDateFor(account)?.let { saved ->
            return ScheduleDates.mondayOfWeek(saved).timeInMillis
        }

        return ScheduleSettingsManager.defaultSemesterStartMillis(term)
    }

    /** 以 UTC 自然日计算 targetCal 相对开学第 1 周周一的教学周次（1..ScheduleMaxWeeks）。 */
    fun weekForDate(startMillis: Long, targetCal: Calendar = Calendar.getInstance()): Int {
        val startMonday = ScheduleDates.mondayOfWeek(startMillis)
        val utc = TimeZone.getTimeZone("UTC")
        fun utcCivilMillis(cal: Calendar): Long = Calendar.getInstance(utc).apply {
            clear()
            set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis

        val diffDays = (utcCivilMillis(targetCal) - utcCivilMillis(startMonday)) / 86_400_000L
        return (Math.floorDiv(diffDays, 7L) + 1L).toInt().coerceIn(1, ScheduleMaxWeeks)
    }

    /** 当前实例配置的朋友课表条目；没配置/已删除则为 null。 */
    fun friendEntry(context: Context, appWidgetId: Int): FriendScheduleStore.Entry? {
        val source = WidgetConfigStore.source(context, appWidgetId) ?: return null
        if (!source.startsWith(PREFIX)) return null
        val id = source.removePrefix(PREFIX).takeIf { it.isNotBlank() } ?: return null
        return runCatching { FriendScheduleStore.find(context, id) }.getOrNull()
    }

    /** 朋友课表的原始 JSON；无来源或内容为空返回 null。 */
    fun loadFriendJson(context: Context, appWidgetId: Int): String? {
        val entry = friendEntry(context, appWidgetId) ?: return null
        return FriendScheduleStore.readJson(entry)?.takeIf { it.isNotBlank() }
    }

    /** 小组件标题兜底：显示朋友课表时用「XX的课表」，否则交回原有默认值。 */
    fun friendTitle(context: Context, appWidgetId: Int): String? =
        friendEntry(context, appWidgetId)?.label
            ?.takeIf { it.isNotBlank() }
            ?.let { "${it}的课表" }
}

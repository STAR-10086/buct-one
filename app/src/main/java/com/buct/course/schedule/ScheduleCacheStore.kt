package com.buct.course.schedule

import android.content.Context
import android.content.SharedPreferences
import com.buct.course.academic.AcademicStudyAdapter
import com.buct.course.academic.AcademicStudyBridge
import com.buct.course.academic.AcademicStudyParser
import com.buct.course.academic.AcademicStudyReader
import com.buct.course.academic.AcademicTerm
import com.buct.course.manager.LocalStore
import org.json.JSONObject

internal data class CachedSchedule(
    val currentTerm: AcademicTerm,
    val term: AcademicTerm,
    val json: String,
    val fromCache: Boolean
)

/**
 * Account/term data survives login sessions; only an explicit sync bypasses a valid cache.
 * 数据同时落盘到 App 私有目录文件（filesDir/local_data），关机重启/清理缓存后仍可离线使用。
 */
internal class ScheduleCacheStore(
    private val context: Context,
    private val preferences: SharedPreferences,
    private val calendarTerm: () -> AcademicTerm = { AcademicStudyReader.calendarTerm() }
) {
    private fun prefix(account: String, school: String) = "schedule_${account}_${school}"

    private fun fileKey(account: String, school: String, termId: String) = "${prefix(account, school)}_${termId}.json"

    fun currentTerm(account: String, school: String): AcademicTerm {
        val calendar = calendarTerm()
        val known = runCatching {
            JSONObject(preferences.getString("${prefix(account, school)}_current", null) ?: return@runCatching null)
        }.getOrNull()
        // Resolve the school's term again when the local academic half-year changes.
        // A school can legitimately start later than the calendar fallback.
        return known?.takeIf { it.optString("calendar") == calendar.id }
            ?.optString("term")?.let(AcademicStudyParser::term) ?: calendar
    }

    fun read(account: String, school: String, term: AcademicTerm): String? {
        val base = prefix(account, school)
        val keys = buildList {
            add("${base}_${term.id}")
            if (term.semester in 1..2) add("${base}_${term.year}_${if (term.semester == 1) 3 else 12}")
        }
        return keys.firstNotNullOfOrNull { key ->
            // 优先读本地文件（离线可用），再回退 prefs
            val fromFile = LocalStore.load(context, fileKey(account, school, term.id))
            val raw = fromFile ?: runCatching { preferences.getString(key, null) }.getOrNull()
            raw?.takeIf { ScheduleJson.parse(it) != null }
        }
    }

    fun selected(account: String, school: String, term: AcademicTerm): CachedSchedule? {
        val current = currentTerm(account, school)
        return read(account, school, term)?.let { CachedSchedule(current, term, it, true) }
    }

    suspend fun load(
        account: String,
        school: String,
        term: AcademicTerm,
        followServerCurrent: Boolean,
        forceRefresh: Boolean,
        reader: () -> AcademicStudyAdapter
    ): CachedSchedule {
        if (!forceRefresh && !followServerCurrent) {
            selected(account, school, term)?.let { return it }
        }
        if (!forceRefresh && followServerCurrent) {
            selected(account, school, currentTerm(account, school))?.let { return it }
        }
        val remote = reader()
        val current = remote.catalog().currentTerm
        val target = if (followServerCurrent) current else term
        // Existing installations may have data but no persisted current-term metadata yet.
        if (!forceRefresh) read(account, school, target)?.let { return CachedSchedule(current, target, it, true) }
        return CachedSchedule(current, target, AcademicStudyBridge.scheduleJson(remote.schedule(target)), false)
    }

    fun save(account: String, school: String, schedule: CachedSchedule) {
        require(ScheduleJson.parse(schedule.json) != null) { "Invalid timetable must not replace the cache" }
        val base = prefix(account, school)
        preferences.edit()
            .putString("${base}_${schedule.term.id}", schedule.json)
            .putLong("${base}_${schedule.term.id}_time", System.currentTimeMillis())
            .putString("${base}_current", JSONObject().put("term", schedule.currentTerm.id)
                .put("calendar", calendarTerm().id).toString())
            .apply()
        // 同步落盘到本地文件
        LocalStore.save(context, fileKey(account, school, schedule.term.id), schedule.json)
    }
}

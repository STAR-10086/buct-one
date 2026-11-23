package com.buct.one.data.store

import android.content.Context
import com.buct.one.data.model.ReminderRuleEngine
import com.buct.one.data.model.ReminderRunSummary
import com.buct.one.data.model.ReminderSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * 智能提醒设置、去重记录、低余额状态跳变与巡检摘要持久化存储。
 * 包结构约束：store -> model；禁止依赖 source / sync / ui。
 */
class ReminderStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun readSettings(): ReminderSettings {
        val rawOffsets = prefs.getString(KEY_THEOL_OFFSETS, null)
        val offsets = if (rawOffsets.isNullOrBlank()) {
            listOf(24, 3)
        } else {
            runCatching {
                val arr = JSONArray(rawOffsets)
                buildList {
                    for (i in 0 until arr.length()) {
                        val h = arr.optInt(i)
                        if (h in ReminderRuleEngine.VALID_DDL_OFFSETS_HOURS) add(h)
                    }
                }.distinct().sortedDescending().ifEmpty { listOf(24, 3) }
            }.getOrElse { listOf(24, 3) }
        }
        val interval = prefs.getInt(KEY_SYNC_INTERVAL, 4)
            .let { if (it in ReminderRuleEngine.VALID_SYNC_INTERVALS_HOURS) it else 4 }
        val cardThreshold = prefs.getInt(KEY_CARD_THRESHOLD, 20)
            .let { if (it in ReminderRuleEngine.VALID_CARD_THRESHOLDS_YUAN) it else 20 }

        return ReminderSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            theolDdlEnabled = prefs.getBoolean(KEY_THEOL_DDL, true),
            theolOffsetsHours = offsets,
            libraryDueEnabled = prefs.getBoolean(KEY_LIBRARY_DUE, true),
            backgroundSyncEnabled = prefs.getBoolean(KEY_BG_SYNC, true),
            syncIntervalHours = interval,
            jwNoticeEnabled = prefs.getBoolean(KEY_JW_NOTICE, true),
            electricityLowEnabled = prefs.getBoolean(KEY_ELE_LOW, true),
            cardLowEnabled = prefs.getBoolean(KEY_CARD_LOW, true),
            cardLowThresholdYuan = cardThreshold,
            quietHoursEnabled = prefs.getBoolean(KEY_QUIET_HOURS, true)
        )
    }

    @Synchronized
    fun writeSettings(settings: ReminderSettings) {
        val cleanOffsets = settings.theolOffsetsHours
            .filter { it in ReminderRuleEngine.VALID_DDL_OFFSETS_HOURS }
            .distinct()
            .sortedDescending()
        val cleanInterval = if (settings.syncIntervalHours in ReminderRuleEngine.VALID_SYNC_INTERVALS_HOURS) {
            settings.syncIntervalHours
        } else {
            4
        }
        val cleanCardThreshold = if (settings.cardLowThresholdYuan in ReminderRuleEngine.VALID_CARD_THRESHOLDS_YUAN) {
            settings.cardLowThresholdYuan
        } else {
            20
        }
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putBoolean(KEY_THEOL_DDL, settings.theolDdlEnabled)
            .putString(KEY_THEOL_OFFSETS, JSONArray(cleanOffsets).toString())
            .putBoolean(KEY_LIBRARY_DUE, settings.libraryDueEnabled)
            .putBoolean(KEY_BG_SYNC, settings.backgroundSyncEnabled)
            .putInt(KEY_SYNC_INTERVAL, cleanInterval)
            .putBoolean(KEY_JW_NOTICE, settings.jwNoticeEnabled)
            .putBoolean(KEY_ELE_LOW, settings.electricityLowEnabled)
            .putBoolean(KEY_CARD_LOW, settings.cardLowEnabled)
            .putInt(KEY_CARD_THRESHOLD, cleanCardThreshold)
            .putBoolean(KEY_QUIET_HOURS, settings.quietHoursEnabled)
            .commit()
    }

    @Synchronized
    fun isNotified(key: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val map = readDedupMap()
        val ts = map.optLong(key, 0L)
        return ts > 0L && (nowMillis - ts) < DEDUP_TTL_MILLIS
    }

    @Synchronized
    fun markNotified(key: String, nowMillis: Long = System.currentTimeMillis()) {
        val map = pruneDedupMap(readDedupMap(), nowMillis)
        map.put(key, nowMillis)
        prefs.edit().putString(KEY_DEDUP_MAP, map.toString()).commit()
    }

    @Synchronized
    fun seedNotified(keys: Collection<String>, nowMillis: Long = System.currentTimeMillis()) {
        if (keys.isEmpty()) return
        val map = pruneDedupMap(readDedupMap(), nowMillis)
        for (key in keys) {
            if (!map.has(key)) map.put(key, nowMillis)
        }
        prefs.edit().putString(KEY_DEDUP_MAP, map.toString()).commit()
    }

    @Synchronized
    fun hasSeededDomain(domain: String): Boolean =
        prefs.getBoolean("${KEY_SEEDED_PREFIX}$domain", false)

    @Synchronized
    fun markDomainSeeded(domain: String) {
        prefs.edit().putBoolean("${KEY_SEEDED_PREFIX}$domain", true).commit()
    }

    @Synchronized
    fun updateLowBalanceState(domain: String, targetKey: String, isLowNow: Boolean): Boolean {
        val prefKey = "${KEY_LOW_STATE_PREFIX}${domain}_$targetKey"
        val wasLowBefore = prefs.getBoolean(prefKey, false)
        if (wasLowBefore != isLowNow) {
            prefs.edit().putBoolean(prefKey, isLowNow).commit()
        }
        return ReminderRuleEngine.shouldTriggerLowBalance(isLowNow, wasLowBefore)
    }

    @Synchronized
    fun readScheduledAlarmCount(): Int = prefs.getInt(KEY_ALARM_COUNT, 0)

    @Synchronized
    fun writeScheduledAlarmCount(count: Int) {
        prefs.edit().putInt(KEY_ALARM_COUNT, count.coerceAtLeast(0)).apply()
    }

    @Synchronized
    fun readRunSummary(): ReminderRunSummary = ReminderRunSummary(
        lastRunAtMillis = prefs.getLong(KEY_LAST_RUN_AT, 0L),
        lastResult = prefs.getString(KEY_LAST_RUN_RESULT, "").orEmpty(),
        notifiedCount = prefs.getInt(KEY_LAST_RUN_NOTIFIED, 0)
    )

    @Synchronized
    fun writeRunSummary(summary: ReminderRunSummary) {
        prefs.edit()
            .putLong(KEY_LAST_RUN_AT, summary.lastRunAtMillis)
            .putString(KEY_LAST_RUN_RESULT, summary.lastResult)
            .putInt(KEY_LAST_RUN_NOTIFIED, summary.notifiedCount)
            .apply()
    }

    private fun readDedupMap(): JSONObject {
        val raw = prefs.getString(KEY_DEDUP_MAP, null) ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    private fun pruneDedupMap(source: JSONObject, nowMillis: Long): JSONObject {
        val next = JSONObject()
        val iter = source.keys()
        while (iter.hasNext()) {
            val k = iter.next()
            val ts = source.optLong(k, 0L)
            if (ts > 0L && (nowMillis - ts) < DEDUP_TTL_MILLIS) {
                next.put(k, ts)
            }
        }
        return next
    }

    private companion object {
        const val PREFS_NAME = "buct_smart_reminders"
        const val DEDUP_TTL_MILLIS = 60L * 24L * 3600L * 1000L

        const val KEY_ENABLED = "enabled"
        const val KEY_THEOL_DDL = "theol_ddl"
        const val KEY_THEOL_OFFSETS = "theol_offsets"
        const val KEY_LIBRARY_DUE = "library_due"
        const val KEY_BG_SYNC = "bg_sync"
        const val KEY_SYNC_INTERVAL = "sync_interval"
        const val KEY_JW_NOTICE = "jw_notice"
        const val KEY_ELE_LOW = "ele_low"
        const val KEY_CARD_LOW = "card_low"
        const val KEY_CARD_THRESHOLD = "card_threshold"
        const val KEY_QUIET_HOURS = "quiet_hours"
        const val KEY_DEDUP_MAP = "dedup_map"
        const val KEY_SEEDED_PREFIX = "seeded_"
        const val KEY_LOW_STATE_PREFIX = "low_state_"
        const val KEY_ALARM_COUNT = "alarm_count"
        const val KEY_LAST_RUN_AT = "last_run_at"
        const val KEY_LAST_RUN_RESULT = "last_run_result"
        const val KEY_LAST_RUN_NOTIFIED = "last_run_notified"
    }
}

package com.buct.course.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.buct.one.data.model.ReminderRuleEngine
import com.buct.one.data.store.PortalStore
import com.buct.one.data.store.ReminderStore
import com.buct.one.data.store.TheolStore
import org.json.JSONArray
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 本地智能提醒双引擎调度器：
 * - 引擎 A（AlarmManager）：作业/测验 DDL 与图书馆还书日确定性精确闹钟，无需常驻后台
 * - 引擎 B（WorkManager）：低频后台巡检（教务调停课、宿舍电费、校园卡余额）
 */
object SmartReminderScheduler {

    const val ACTION_SMART_REMINDER = "com.buct.course.action.SMART_REMINDER"
    const val ACTION_TEST_ALARM = "com.buct.course.action.SMART_REMINDER_TEST_ALARM"

    const val EXTRA_KEY = "reminder_key"
    const val EXTRA_TITLE = "reminder_title"
    const val EXTRA_BODY = "reminder_body"
    const val EXTRA_CHANNEL = "reminder_channel"
    const val EXTRA_TARGET = "reminder_target"

    const val TARGET_MAIN = "main"
    const val TARGET_JWNOTICE = "jwnotice"
    const val TARGET_PORTAL = "portal"
    const val TARGET_ELECTRICITY = "electricity"
    const val TARGET_CARD = "card"

    private const val WORK_NAME_PERIODIC = "buct_smart_reminder_periodic"
    private const val WORK_NAME_ONE_TIME = "buct_smart_reminder_once"
    private const val PREFS_ALARMS = "smart_reminder_alarms"
    private const val KEY_CODES = "scheduled_codes"
    private const val TEST_ALARM_REQUEST_CODE = 990010
    private const val MAX_SCHEDULED_ALARMS = 60

    private data class ScheduledAlarm(
        val key: String,
        val triggerAt: Long,
        val title: String,
        val body: String,
        val channelId: String,
        val target: String
    )

    fun reschedule(context: Context) {
        val appContext = context.applicationContext
        val store = ReminderStore(appContext)
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        cancelAllAlarms(appContext, alarmManager)

        val settings = store.readSettings()
        if (!settings.enabled) {
            store.writeScheduledAlarmCount(0)
            return
        }

        val now = System.currentTimeMillis()
        val candidates = mutableListOf<ScheduledAlarm>()

        if (settings.theolDdlEnabled && settings.theolOffsetsHours.isNotEmpty()) {
            collectTheolAlarms(appContext, settings.theolOffsetsHours, candidates)
        }
        if (settings.libraryDueEnabled) {
            collectLibraryAlarms(appContext, candidates)
        }

        val upcoming = candidates
            .filter { it.triggerAt > now && !store.isNotified(it.key, now) }
            .distinctBy { it.key }
            .sortedBy { it.triggerAt }
            .take(MAX_SCHEDULED_ALARMS)

        val codes = mutableListOf<Int>()
        for (alarm in upcoming) {
            val code = requestCode(alarm.key)
            val pending = buildReminderPendingIntent(
                context = appContext,
                code = code,
                key = alarm.key,
                title = alarm.title,
                body = alarm.body,
                channelId = alarm.channelId,
                target = alarm.target
            )
            val scheduled = scheduleExactOrInexact(alarmManager, alarm.triggerAt, pending)
            if (scheduled) codes += code
        }

        appContext.getSharedPreferences(PREFS_ALARMS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CODES, JSONArray(codes).toString())
            .commit()
        store.writeScheduledAlarmCount(codes.size)
    }

    fun scheduleTestAlarm10s(context: Context): Boolean {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return false
        val intent = Intent(appContext, SmartReminderReceiver::class.java).setAction(ACTION_TEST_ALARM)
        val pending = PendingIntent.getBroadcast(
            appContext,
            TEST_ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return scheduleExactOrInexact(alarmManager, System.currentTimeMillis() + 10_000L, pending)
    }

    fun deferToNextMorning(context: Context, sourceIntent: Intent) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        val key = sourceIntent.getStringExtra(EXTRA_KEY) ?: return
        val title = sourceIntent.getStringExtra(EXTRA_TITLE) ?: return
        val body = sourceIntent.getStringExtra(EXTRA_BODY).orEmpty()
        val channel = sourceIntent.getStringExtra(EXTRA_CHANNEL) ?: ReminderChannels.CHANNEL_DIGEST
        val target = sourceIntent.getStringExtra(EXTRA_TARGET) ?: TARGET_MAIN

        val cal = Calendar.getInstance()
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (minutes >= 23 * 60 + 30) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        cal.set(Calendar.HOUR_OF_DAY, 7)
        cal.set(Calendar.MINUTE, 30)
        cal.set(Calendar.SECOND, 5)
        cal.set(Calendar.MILLISECOND, 0)

        val code = requestCode("$key#deferred")
        val pending = buildReminderPendingIntent(appContext, code, key, title, body, channel, target)
        scheduleExactOrInexact(alarmManager, cal.timeInMillis, pending)
    }

    fun syncWorkSchedule(context: Context) {
        val appContext = context.applicationContext
        val settings = ReminderStore(appContext).readSettings()
        val workManager = runCatching { WorkManager.getInstance(appContext) }.getOrNull() ?: return
        if (!settings.enabled || !settings.backgroundSyncEnabled) {
            workManager.cancelUniqueWork(WORK_NAME_PERIODIC)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<CampusBackgroundSyncWorker>(
            settings.syncIntervalHours.toLong().coerceAtLeast(1L),
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun runBackgroundCheckNow(context: Context) {
        val appContext = context.applicationContext
        val workManager = runCatching { WorkManager.getInstance(appContext) }.getOrNull() ?: return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<CampusBackgroundSyncWorker>()
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniqueWork(
            WORK_NAME_ONE_TIME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun collectTheolAlarms(
        appContext: Context,
        offsetsHours: List<Int>,
        out: MutableList<ScheduledAlarm>
    ) {
        val theolStore = TheolStore(appContext)
        val snapshot = runCatching { theolStore.readTheol(0).data }.getOrNull() ?: return
        val filters = runCatching { theolStore.readFilterPrefs() }.getOrNull()

        snapshot.homeworks
            .distinctBy { "${it.lid}:${it.hwtid}" }
            .filter { it.canSubmit && it.deadlineMillis != null && filters?.isHomeworkVisible(it) != false }
            .forEach { hw ->
                val deadline = hw.deadlineMillis ?: return@forEach
                val course = hw.courseName.ifBlank { "北化在线" }
                for (offsetHours in offsetsHours) {
                    out += ScheduledAlarm(
                        key = "hw:${hw.lid}:${hw.hwtid}:$offsetHours",
                        triggerAt = deadline - offsetHours * 3_600_000L,
                        title = "作业临期：$course",
                        body = "「${hw.title}」还有 $offsetHours 小时截止",
                        channelId = if (offsetHours <= 3) ReminderChannels.CHANNEL_URGENT else ReminderChannels.CHANNEL_DIGEST,
                        target = TARGET_MAIN
                    )
                }
            }

        snapshot.tests
            .distinctBy { "${it.lid}:${it.testId}" }
            .filter { !it.hasResult && it.endTimeMillis != null && filters?.isTestVisible(it) != false }
            .forEach { test ->
                val deadline = test.endTimeMillis ?: return@forEach
                val course = test.courseName.ifBlank { "北化在线" }
                for (offsetHours in offsetsHours) {
                    out += ScheduledAlarm(
                        key = "test:${test.lid}:${test.testId}:$offsetHours",
                        triggerAt = deadline - offsetHours * 3_600_000L,
                        title = "测验临期：$course",
                        body = "「${test.title}」还有 $offsetHours 小时截止",
                        channelId = if (offsetHours <= 3) ReminderChannels.CHANNEL_URGENT else ReminderChannels.CHANNEL_DIGEST,
                        target = TARGET_MAIN
                    )
                }
            }
    }

    private fun collectLibraryAlarms(
        appContext: Context,
        out: MutableList<ScheduledAlarm>
    ) {
        val library = runCatching { PortalStore(appContext).readLibrary(0).data }.getOrNull() ?: return
        for (book in library.books) {
            val ymd = ReminderRuleEngine.parseSimpleDateYmd(book.dueDate) ?: continue
            val dueMorning = Calendar.getInstance().apply {
                clear()
                set(ymd.first, ymd.second - 1, ymd.third, 9, 0, 0)
            }.timeInMillis
            val threeDaysBefore = dueMorning - 3L * 24L * 3_600_000L
            val idSeed = book.barcode.ifBlank { book.title }
            out += ScheduledAlarm(
                key = "book:$idSeed:${book.dueDate}:3d",
                triggerAt = threeDaysBefore,
                title = "图书 3 天后到期",
                body = "《${book.title}》应还日期 ${book.dueDate}，记得提前续借或归还",
                channelId = ReminderChannels.CHANNEL_DIGEST,
                target = TARGET_PORTAL
            )
            out += ScheduledAlarm(
                key = "book:$idSeed:${book.dueDate}:0d",
                triggerAt = dueMorning,
                title = "图书今日到期",
                body = "《${book.title}》今日（${book.dueDate}）到期，请及时归还或续借",
                channelId = ReminderChannels.CHANNEL_URGENT,
                target = TARGET_PORTAL
            )
        }
    }

    private fun cancelAllAlarms(context: Context, alarmManager: AlarmManager) {
        val raw = context.getSharedPreferences(PREFS_ALARMS, Context.MODE_PRIVATE)
            .getString(KEY_CODES, null) ?: return
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val code = array.optInt(i)
                val intent = Intent(context, SmartReminderReceiver::class.java).setAction(ACTION_SMART_REMINDER)
                val pending = PendingIntent.getBroadcast(
                    context,
                    code,
                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (pending != null) {
                    alarmManager.cancel(pending)
                    pending.cancel()
                }
            }
        }
    }

    private fun scheduleExactOrInexact(
        alarmManager: AlarmManager,
        triggerAt: Long,
        pending: PendingIntent
    ): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
        true
    }.getOrElse {
        runCatching {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            true
        }.getOrDefault(false)
    }

    private fun requestCode(key: String): Int =
        (key.hashCode() and 0x0FFFFFFF) + 200_000

    private fun buildReminderPendingIntent(
        context: Context,
        code: Int,
        key: String,
        title: String,
        body: String,
        channelId: String,
        target: String
    ): PendingIntent {
        val intent = Intent(context, SmartReminderReceiver::class.java).apply {
            action = ACTION_SMART_REMINDER
            putExtra(EXTRA_KEY, key)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BODY, body)
            putExtra(EXTRA_CHANNEL, channelId)
            putExtra(EXTRA_TARGET, target)
        }
        return PendingIntent.getBroadcast(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

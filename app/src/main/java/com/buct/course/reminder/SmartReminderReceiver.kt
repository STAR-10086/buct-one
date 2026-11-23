package com.buct.course.reminder

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.buct.course.EcardActivity
import com.buct.course.ElectricityActivity
import com.buct.course.JwNoticeActivity
import com.buct.course.MainActivity
import com.buct.course.PortalActivity
import com.buct.one.data.model.ReminderRuleEngine
import com.buct.one.data.store.ReminderStore
import java.util.Calendar

/**
 * 接收本地智能提醒闹钟与系统开机/时间/权限变更事件。
 */
class SmartReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SmartReminderScheduler.ACTION_TEST_ALARM -> {
                ReminderChannels.notify(
                    context = context,
                    channelId = ReminderChannels.CHANNEL_URGENT,
                    notificationId = 990010,
                    title = "后台闹钟测试成功",
                    body = "即使应用退到后台或被划掉，本地提醒也能准时送达。"
                )
            }
            SmartReminderScheduler.ACTION_SMART_REMINDER -> handleSmartReminder(context, intent)
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                SmartReminderScheduler.reschedule(context)
                SmartReminderScheduler.syncWorkSchedule(context)
            }
        }
    }

    private fun handleSmartReminder(context: Context, intent: Intent) {
        val store = ReminderStore(context)
        val settings = store.readSettings()
        if (!settings.enabled) return

        val key = intent.getStringExtra(SmartReminderScheduler.EXTRA_KEY) ?: return
        val title = intent.getStringExtra(SmartReminderScheduler.EXTRA_TITLE) ?: return
        val body = intent.getStringExtra(SmartReminderScheduler.EXTRA_BODY).orEmpty()
        val channelId = intent.getStringExtra(SmartReminderScheduler.EXTRA_CHANNEL)
            ?: ReminderChannels.CHANNEL_DIGEST
        val target = intent.getStringExtra(SmartReminderScheduler.EXTRA_TARGET)
            ?: SmartReminderScheduler.TARGET_MAIN

        if (store.isNotified(key)) return

        val cal = Calendar.getInstance()
        if (ReminderRuleEngine.isInQuietHours(
                hourOfDay = cal.get(Calendar.HOUR_OF_DAY),
                minute = cal.get(Calendar.MINUTE),
                quietHoursEnabled = settings.quietHoursEnabled
            )
        ) {
            SmartReminderScheduler.deferToNextMorning(context, intent)
            return
        }

        val targetActivity = when (target) {
            SmartReminderScheduler.TARGET_JWNOTICE -> JwNoticeActivity::class.java
            SmartReminderScheduler.TARGET_PORTAL -> PortalActivity::class.java
            SmartReminderScheduler.TARGET_ELECTRICITY -> ElectricityActivity::class.java
            SmartReminderScheduler.TARGET_CARD -> EcardActivity::class.java
            else -> MainActivity::class.java
        }

        val notified = ReminderChannels.notify(
            context = context,
            channelId = channelId,
            notificationId = (key.hashCode() and 0x0FFFFFFF) + 300_000,
            title = title,
            body = body,
            targetActivity = targetActivity
        )
        if (notified) {
            store.markNotified(key)
        }
    }
}

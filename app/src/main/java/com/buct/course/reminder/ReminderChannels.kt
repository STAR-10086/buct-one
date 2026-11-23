package com.buct.course.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.buct.course.MainActivity
import com.buct.course.R

data class ReminderDiagnostics(
    val notificationsEnabled: Boolean,
    val urgentChannelEnabled: Boolean,
    val digestChannelEnabled: Boolean,
    val exactAlarmAllowed: Boolean,
    val ignoringBatteryOptimizations: Boolean
)

/**
 * 智能提醒通知渠道注册、权限诊断与统一发通知入口。
 */
object ReminderChannels {

    const val CHANNEL_URGENT = "reminder_urgent"
    const val CHANNEL_DIGEST = "reminder_digest"

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_URGENT) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_URGENT,
                    "学业与紧要通知",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "作业/测验临期、图书当日到期、教务调停课新消息"
                }
            )
        }
        if (manager.getNotificationChannel(CHANNEL_DIGEST) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_DIGEST,
                    "账单与日常提醒",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "24 小时前 DDL 预告、图书 3 天到期、电费/校园卡低余额"
                }
            )
        }
    }

    fun diagnose(context: Context): ReminderDiagnostics {
        ensureCreated(context)
        val notificationsGranted = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED)

        val nm = context.getSystemService(NotificationManager::class.java)
        val urgentEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            nm.getNotificationChannel(CHANNEL_URGENT)?.importance != NotificationManager.IMPORTANCE_NONE
        } else {
            true
        }
        val digestEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            nm.getNotificationChannel(CHANNEL_DIGEST)?.importance != NotificationManager.IMPORTANCE_NONE
        } else {
            true
        }

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val exactAlarmAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (alarmManager?.canScheduleExactAlarms() == true)

        val pm = context.getSystemService(PowerManager::class.java)
        val ignoringBattery = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            (pm?.isIgnoringBatteryOptimizations(context.packageName) == true)

        return ReminderDiagnostics(
            notificationsEnabled = notificationsGranted,
            urgentChannelEnabled = urgentEnabled,
            digestChannelEnabled = digestEnabled,
            exactAlarmAllowed = exactAlarmAllowed,
            ignoringBatteryOptimizations = ignoringBattery
        )
    }

    fun notify(
        context: Context,
        channelId: String,
        notificationId: Int,
        title: String,
        body: String,
        targetActivity: Class<*> = MainActivity::class.java
    ): Boolean {
        ensureCreated(context)
        val intent = Intent(context, targetActivity).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val priority = if (channelId == CHANNEL_URGENT) {
            NotificationCompat.PRIORITY_HIGH
        } else {
            NotificationCompat.PRIORITY_DEFAULT
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_course_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        return runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            true
        }.getOrDefault(false)
    }

    fun sendImmediateTest(context: Context): Boolean = notify(
        context = context,
        channelId = CHANNEL_URGENT,
        notificationId = 990001,
        title = "BUCT One 测试通知",
        body = "通知通道正常！你可以点击「10 秒后后台闹钟测试」并退到后台或划掉应用，验证离线闹钟是否准时送达。"
    )
}

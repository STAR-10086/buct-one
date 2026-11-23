package com.buct.course.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.buct.course.EcardActivity
import com.buct.course.ElectricityActivity
import com.buct.course.JwNoticeActivity
import com.buct.one.data.model.ReminderRuleEngine
import com.buct.one.data.model.ReminderRunSummary
import com.buct.one.data.model.electricityTargetShortLabel
import com.buct.one.data.model.formatElectricityPrimaryValue
import com.buct.one.data.model.formatFenToYuan
import com.buct.one.data.model.isLowElectricityBalance
import com.buct.one.data.store.ReminderStore
import com.buct.one.data.sync.CardSyncService
import com.buct.one.data.sync.ElectricitySyncService
import com.buct.one.data.sync.JwNoticeSyncService
import java.util.Calendar

/**
 * 引擎 B：低频后台增量巡检 Worker（教务调停课消息、宿舍电费、校园卡余额）。
 * 全程在手机本地直连/WebVPN 拉取，不上传任何个人凭据。
 */
class CampusBackgroundSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = ReminderStore(applicationContext)
        val settings = store.readSettings()
        if (!settings.enabled || !settings.backgroundSyncEnabled) {
            return Result.success()
        }

        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val quietNow = ReminderRuleEngine.isInQuietHours(
            hourOfDay = cal.get(Calendar.HOUR_OF_DAY),
            minute = cal.get(Calendar.MINUTE),
            quietHoursEnabled = settings.quietHoursEnabled
        )

        var notifiedCount = 0
        val checkedDomains = mutableListOf<String>()

        if (settings.jwNoticeEnabled) {
            runCatching {
                val svc = JwNoticeSyncService.getInstance(applicationContext)
                if (svc.canAutoSync()) {
                    checkedDomains += "教务"
                    val snap = svc.refresh(userInitiated = false).getOrNull()?.data
                    if (snap != null) {
                        if (!store.hasSeededDomain("jwnotice")) {
                            store.seedNotified(snap.reminders.map { "jwnotice:${it.id}" }, now)
                            store.markDomainSeeded("jwnotice")
                        } else {
                            for (item in snap.reminders) {
                                if (item.isRead) continue
                                val key = "jwnotice:${item.id}"
                                if (store.isNotified(key, now) || quietNow) continue
                                val body = item.content.ifBlank { item.createdAt }.take(120)
                                val ok = ReminderChannels.notify(
                                    context = applicationContext,
                                    channelId = ReminderChannels.CHANNEL_URGENT,
                                    notificationId = (key.hashCode() and 0x0FFFFFFF) + 400_000,
                                    title = "教务新消息：${item.title.ifBlank { "调停课通知" }}",
                                    body = body,
                                    targetActivity = JwNoticeActivity::class.java
                                )
                                if (ok) {
                                    store.markNotified(key, now)
                                    notifiedCount++
                                }
                            }
                        }
                    }
                }
            }
        }

        if (settings.electricityLowEnabled) {
            runCatching {
                val svc = ElectricitySyncService.getInstance(applicationContext)
                if (svc.canAutoSync()) {
                    checkedDomains += "电费"
                    val snap = svc.refresh(userInitiated = false).getOrNull()
                    if (snap != null) {
                        for (reading in snap.readings) {
                            val isLow = isLowElectricityBalance(reading, snap.lowBalanceThreshold)
                            val targetKey = "${reading.target.schoolid}_${reading.target.roomid}_${reading.target.meterType}"
                            val triggered = store.updateLowBalanceState("ele", targetKey, isLow)
                            if (triggered && !quietNow) {
                                val label = electricityTargetShortLabel(reading.target)
                                val ok = ReminderChannels.notify(
                                    context = applicationContext,
                                    channelId = ReminderChannels.CHANNEL_DIGEST,
                                    notificationId = (targetKey.hashCode() and 0x0FFFFFFF) + 500_000,
                                    title = "宿舍电费余额不足",
                                    body = "$label 当前余额 ${formatElectricityPrimaryValue(reading)}，已低于 ${snap.lowBalanceThreshold} 阈值",
                                    targetActivity = ElectricityActivity::class.java
                                )
                                if (ok) notifiedCount++
                            }
                        }
                    }
                }
            }
        }

        if (settings.cardLowEnabled) {
            runCatching {
                val svc = CardSyncService.getInstance(applicationContext)
                if (svc.canAutoSync()) {
                    checkedDomains += "校园卡"
                    val snap = svc.refresh(pageSize = 5).getOrNull()
                    if (snap != null) {
                        val thresholdFen = settings.cardLowThresholdYuan * 100L
                        val isLow = snap.account.balanceFen < thresholdFen
                        val triggered = store.updateLowBalanceState("card", "default", isLow)
                        if (triggered && !quietNow) {
                            val ok = ReminderChannels.notify(
                                context = applicationContext,
                                channelId = ReminderChannels.CHANNEL_DIGEST,
                                notificationId = 600_001,
                                title = "校园卡余额偏低",
                                body = "当前余额 ¥${formatFenToYuan(snap.account.balanceFen)}，已低于 ¥${settings.cardLowThresholdYuan} 预警线",
                                targetActivity = EcardActivity::class.java
                            )
                            if (ok) notifiedCount++
                        }
                    }
                }
            }
        }

        runCatching { SmartReminderScheduler.reschedule(applicationContext) }

        val resultDesc = buildString {
            if (checkedDomains.isEmpty()) {
                append("无可用自动同步凭据")
            } else {
                append("已巡检 ")
                append(checkedDomains.joinToString(" / "))
            }
            if (quietNow) append("（夜间静默）")
        }
        store.writeRunSummary(
            ReminderRunSummary(
                lastRunAtMillis = now,
                lastResult = resultDesc,
                notifiedCount = notifiedCount
            )
        )
        return Result.success()
    }
}

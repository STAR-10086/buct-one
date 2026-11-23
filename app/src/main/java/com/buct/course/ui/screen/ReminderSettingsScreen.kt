package com.buct.course.ui.screen

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.buct.course.reminder.ReminderChannels
import com.buct.course.reminder.SmartReminderScheduler
import com.buct.course.ui.system.GlassToaster
import com.buct.course.ui.system.InsetGroupedRow
import com.buct.course.ui.system.InsetGroupedSection
import com.buct.course.ui.system.LiquidSwitch
import com.buct.course.ui.system.SystemIconButton
import com.buct.course.ui.system.SystemPrimaryButton
import com.buct.course.ui.system.SystemSecondaryButton
import com.buct.course.ui.system.SystemTopBar
import com.buct.one.data.model.ReminderRuleEngine
import com.buct.one.data.model.ReminderSettings
import com.buct.one.data.store.ReminderStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReminderSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { ReminderStore(context) }

    var refreshTick by remember { mutableIntStateOf(0) }
    var settings by remember(context) { mutableStateOf(store.readSettings()) }
    var diagnostics by remember(refreshTick) { mutableStateOf(ReminderChannels.diagnose(context)) }
    var alarmCount by remember(refreshTick) { mutableIntStateOf(store.readScheduledAlarmCount()) }
    var runSummary by remember(refreshTick) { mutableStateOf(store.readRunSummary()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refreshTick++
        GlassToaster.show(if (granted) "通知权限已开启" else "未授予通知权限，可在系统设置中开启")
    }

    fun updateSettings(next: ReminderSettings) {
        settings = next
        store.writeSettings(next)
        SmartReminderScheduler.reschedule(context)
        SmartReminderScheduler.syncWorkSchedule(context)
        alarmCount = store.readScheduledAlarmCount()
    }

    val channelsOk = diagnostics.urgentChannelEnabled && diagnostics.digestChannelEnabled

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        SystemTopBar(
            title = "提醒与通知",
            subtitle = "本地双引擎 · 不常驻后台 · 零凭据上云",
            navigationIcon = {
                SystemIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack)
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            InsetGroupedSection(
                header = "通知与后台体检",
                footer = "DDL、还书、考试闹钟由系统 AlarmManager 触发，划掉后台也能响；WorkManager 低频巡检则受省电策略影响。"
            ) {
                InsetGroupedRow(
                    icon = Icons.Outlined.NotificationsActive,
                    iconTint = if (diagnostics.notificationsEnabled) Color(0xFF34C759) else Color(0xFFFF9F0A),
                    title = "通知总权限",
                    subtitle = if (diagnostics.notificationsEnabled) "已开启 · 允许弹窗与横幅通知" else "未授权 · 无法弹出任何提醒",
                    showDivider = true,
                    trailing = {
                        TextButton(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !diagnostics.notificationsEnabled) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    openAppNotificationSettings(context)
                                }
                            }
                        ) {
                            Text(if (diagnostics.notificationsEnabled) "设置" else "去开启")
                        }
                    }
                )
                InsetGroupedRow(
                    icon = Icons.Outlined.NotificationsNone,
                    iconTint = if (channelsOk) Color(0xFF34C759) else Color(0xFFFF9F0A),
                    title = "提醒通知渠道",
                    subtitle = if (channelsOk) "学业紧要 / 日常账单渠道均正常" else "有渠道被系统关闭或静音",
                    showDivider = true,
                    trailing = {
                        TextButton(onClick = { openAppNotificationSettings(context) }) {
                            Text(if (channelsOk) "管理" else "去开启")
                        }
                    }
                )
                InsetGroupedRow(
                    icon = Icons.Outlined.Alarm,
                    iconTint = if (diagnostics.exactAlarmAllowed) Color(0xFF34C759) else Color(0xFFFF9F0A),
                    title = "精确闹钟权限",
                    subtitle = if (diagnostics.exactAlarmAllowed) "已允许 · DDL 与还书提醒准时触发" else "未允许 · 闹钟可能延迟数分钟",
                    showDivider = true,
                    trailing = {
                        TextButton(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                        )
                                    }.onFailure { openAppDetailsSettings(context) }
                                } else {
                                    GlassToaster.show("当前系统版本无需单独授予")
                                }
                            }
                        ) {
                            Text(if (diagnostics.exactAlarmAllowed) "管理" else "去开启")
                        }
                    }
                )
                InsetGroupedRow(
                    icon = Icons.Outlined.BatteryChargingFull,
                    iconTint = if (diagnostics.ignoringBatteryOptimizations) Color(0xFF34C759) else Color(0xFF0A84FF),
                    title = "电池优化白名单",
                    subtitle = if (diagnostics.ignoringBatteryOptimizations) {
                        "已设为无限制 · 后台巡检更稳定"
                    } else {
                        "受系统省电限制 · 仅影响定时联网巡检"
                    },
                    showDivider = false,
                    trailing = {
                        TextButton(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                        )
                                    }.onFailure {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                        }.onFailure { openAppDetailsSettings(context) }
                                    }
                                }
                            }
                        ) {
                            Text(if (diagnostics.ignoringBatteryOptimizations) "已放行" else "去设置")
                        }
                    }
                )
            }

            InsetGroupedSection(
                header = "实测与运行状态",
                footer = "点「10 秒后后台闹钟测试」后可立即退回桌面或划掉应用，10 秒后收到通知即说明系统允许离线送达。"
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val lastRunText = if (runSummary.lastRunAtMillis > 0L) {
                        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                        "${fmt.format(Date(runSummary.lastRunAtMillis))} · ${runSummary.lastResult}（通知 ${runSummary.notifiedCount} 条）"
                    } else {
                        "尚未执行过后台巡检"
                    }
                    Text(
                        text = "已排期闹钟：$alarmCount 个",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "上次巡检：$lastRunText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SystemPrimaryButton(
                            text = "立即发测试通知",
                            onClick = {
                                val ok = ReminderChannels.sendImmediateTest(context)
                                GlassToaster.show(if (ok) "已发送测试通知" else "发送失败，请先授予通知权限")
                            },
                            modifier = Modifier.weight(1f)
                        )
                        SystemSecondaryButton(
                            text = "10 秒后台闹钟",
                            onClick = {
                                val ok = SmartReminderScheduler.scheduleTestAlarm10s(context)
                                GlassToaster.show(
                                    if (ok) "已预约 10 秒后闹钟，现在可退到后台验证" else "预约失败，请检查闹钟权限"
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    SystemSecondaryButton(
                        text = "立即执行一次后台巡检",
                        onClick = {
                            SmartReminderScheduler.runBackgroundCheckNow(context)
                            GlassToaster.show("已入队后台巡检，联网完成后自动更新状态")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            InsetGroupedSection(
                header = "本地确定性提醒（不依赖常驻）",
                footer = "同步完北化在线与图书馆后自动在本地注册系统闹钟。"
            ) {
                InsetGroupedRow(
                    icon = Icons.Outlined.NotificationsActive,
                    iconTint = Color(0xFF5E5CE6),
                    title = "启用智能提醒",
                    subtitle = "关闭后将取消所有待触发提醒并暂停后台巡检",
                    showDivider = true,
                    trailing = {
                        LiquidSwitch(
                            checked = settings.enabled,
                            onCheckedChange = { updateSettings(settings.copy(enabled = it)) }
                        )
                    }
                )
                InsetGroupedRow(
                    icon = Icons.Outlined.Assignment,
                    iconTint = Color(0xFF0A84FF),
                    title = "作业 / 测验 DDL 提醒",
                    subtitle = "未提交作业与未完成测验按提前量提醒",
                    showDivider = settings.theolDdlEnabled,
                    trailing = {
                        LiquidSwitch(
                            checked = settings.theolDdlEnabled,
                            onCheckedChange = { updateSettings(settings.copy(theolDdlEnabled = it)) }
                        )
                    }
                )
                if (settings.theolDdlEnabled) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "DDL 提前提醒时间",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            for (hours in ReminderRuleEngine.VALID_DDL_OFFSETS_HOURS) {
                                val selected = hours in settings.theolOffsetsHours
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        val nextOffsets = if (selected) {
                                            settings.theolOffsetsHours - hours
                                        } else {
                                            settings.theolOffsetsHours + hours
                                        }
                                        if (nextOffsets.isEmpty()) {
                                            GlassToaster.show("至少保留一个提前提醒时间")
                                        } else {
                                            updateSettings(settings.copy(theolOffsetsHours = nextOffsets))
                                        }
                                    },
                                    label = { Text("提前 ${hours}h") }
                                )
                            }
                        }
                    }
                }
                InsetGroupedRow(
                    icon = Icons.Outlined.MenuBook,
                    iconTint = Color(0xFF34C759),
                    title = "图书馆还书到期提醒",
                    subtitle = "在应还日前 3 天与当天 09:00 各提醒一次",
                    showDivider = true,
                    trailing = {
                        LiquidSwitch(
                            checked = settings.libraryDueEnabled,
                            onCheckedChange = { updateSettings(settings.copy(libraryDueEnabled = it)) }
                        )
                    }
                )
                InsetGroupedRow(
                    icon = Icons.Outlined.NightsStay,
                    iconTint = Color(0xFFAF52DE),
                    title = "夜间免打扰（23:30 – 07:30）",
                    subtitle = "此时段内的提醒自动顺延至次日 07:30 弹出",
                    showDivider = false,
                    trailing = {
                        LiquidSwitch(
                            checked = settings.quietHoursEnabled,
                            onCheckedChange = { updateSettings(settings.copy(quietHoursEnabled = it)) }
                        )
                    }
                )
            }

            InsetGroupedSection(
                header = "低频后台巡检（WorkManager）",
                footer = "仅在联网且电量正常时由系统低频唤醒检查变动，已提醒项自动去重。"
            ) {
                InsetGroupedRow(
                    icon = Icons.Outlined.Sync,
                    iconTint = Color(0xFF0A84FF),
                    title = "启用定时后台巡检",
                    subtitle = "定期检查调停课消息、宿舍电费与校园卡余额",
                    showDivider = true,
                    trailing = {
                        LiquidSwitch(
                            checked = settings.backgroundSyncEnabled,
                            onCheckedChange = { updateSettings(settings.copy(backgroundSyncEnabled = it)) }
                        )
                    }
                )
                if (settings.backgroundSyncEnabled) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "巡检频率",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (hours in ReminderRuleEngine.VALID_SYNC_INTERVALS_HOURS) {
                                FilterChip(
                                    selected = settings.syncIntervalHours == hours,
                                    onClick = { updateSettings(settings.copy(syncIntervalHours = hours)) },
                                    label = { Text("每 $hours 小时") }
                                )
                            }
                        }
                    }
                }
                InsetGroupedRow(
                    icon = Icons.Outlined.School,
                    iconTint = Color(0xFFFF375F),
                    title = "教务调停课新消息",
                    subtitle = "首次巡检自动跳过历史消息，仅提醒新未读消息",
                    showDivider = true,
                    trailing = {
                        LiquidSwitch(
                            checked = settings.jwNoticeEnabled,
                            onCheckedChange = { updateSettings(settings.copy(jwNoticeEnabled = it)) }
                        )
                    }
                )
                InsetGroupedRow(
                    icon = Icons.Outlined.Bolt,
                    iconTint = Color(0xFFFF9F0A),
                    title = "宿舍电费低余额预警",
                    subtitle = "余额跌破电费页设定阈值时提醒一次",
                    showDivider = true,
                    trailing = {
                        LiquidSwitch(
                            checked = settings.electricityLowEnabled,
                            onCheckedChange = { updateSettings(settings.copy(electricityLowEnabled = it)) }
                        )
                    }
                )
                InsetGroupedRow(
                    icon = Icons.Outlined.CreditCard,
                    iconTint = Color(0xFF30B0C7),
                    title = "校园卡低余额预警",
                    subtitle = "需在校园卡模块开启记住密码以支持自动查询",
                    showDivider = settings.cardLowEnabled,
                    trailing = {
                        LiquidSwitch(
                            checked = settings.cardLowEnabled,
                            onCheckedChange = { updateSettings(settings.copy(cardLowEnabled = it)) }
                        )
                    }
                )
                if (settings.cardLowEnabled) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "校园卡低余额预警阈值",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (yuan in ReminderRuleEngine.VALID_CARD_THRESHOLDS_YUAN) {
                                FilterChip(
                                    selected = settings.cardLowThresholdYuan == yuan,
                                    onClick = { updateSettings(settings.copy(cardLowThresholdYuan = yuan)) },
                                    label = { Text("低于 ¥$yuan") }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun openAppNotificationSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { openAppDetailsSettings(context) }
}

private fun openAppDetailsSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        )
    }
}

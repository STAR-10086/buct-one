package com.buct.course.ui.route

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buct.course.EcardActivity
import com.buct.course.SjcxActivity
import com.buct.course.manager.ScheduleSettingsManager
import com.buct.course.manager.StartupPage
import com.buct.course.manager.UserManager
import com.buct.course.ui.screen.EmptyClassroomDialog
import com.buct.course.ui.system.GlassStatChip
import com.buct.course.ui.system.LocalAppOverlayBottomInset
import com.buct.course.ui.system.glassBorderColor
import com.buct.course.ui.system.glassSurfaceColor
import com.buct.course.widget.WidgetScheduleSource
import com.buct.course.widget.parseClockMinutes
import com.buct.one.data.model.RefreshState
import com.buct.one.data.model.formatFenToYuan
import com.buct.one.data.sync.CardSyncService
import com.buct.one.data.sync.SjcxSyncService
import com.buct.one.data.sync.TheolSyncService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 工作台首屏（五栏底栏居中主入口）。
 * 只读本地快照渲染，保证冷启动与无网状态下即刻展示内容。
 */
@Composable
fun DashboardRoute(
    onSwitchPage: (StartupPage) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val userManager = remember { UserManager.getInstance().apply { init(context) } }
    val session by userManager.sessionState.state.collectAsState()
    val displayName = remember(session.token, session.expired, userManager.studentName) {
        userManager.studentName?.trim()?.takeIf { it.isNotBlank() } ?: "北化同学"
    }

    val theolService = remember(context) { TheolSyncService.getInstance(context) }
    val theolSlice by theolService.theolState.collectAsState()
    val localTodos by theolService.todosState.collectAsState()
    val filterPrefs by theolService.filterPrefs.collectAsState()
    val syncing by theolService.syncing.collectAsState()
    val cardService = remember(context) { CardSyncService.getInstance(context) }
    val cardSlice by cardService.cardState.collectAsState()
    val cardHasSession by cardService.hasSession.collectAsState()
    val cardSyncing by cardService.syncing.collectAsState()
    val sjcxService = remember(context) { SjcxSyncService.getInstance(context) }
    val sjcxSlice by sjcxService.sjcxState.collectAsState()
    val sjcxSyncing by sjcxService.syncing.collectAsState()
    var showEmptyClassroomDialog by remember { mutableStateOf(false) }
    val classroomSlot = remember(showEmptyClassroomDialog) { resolveCurrentClassroomSlot(context) }
    val classroomDayLabel = listOf("一", "二", "三", "四", "五", "六", "日")
        .getOrElse(classroomSlot.day - 1) { "一" }

    val now = remember { Date() }
    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..10 -> "早上好"
            in 11..13 -> "中午好"
            in 14..17 -> "下午好"
            else -> "晚上好"
        }
    }
    val dateLine = remember(now) {
        SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(now)
    }

    val pendingHomeworks = remember(theolSlice, filterPrefs) {
        theolSlice.data?.homeworks
            ?.filter { it.canSubmit && filterPrefs.isHomeworkVisible(it) }
            .orEmpty()
    }
    val pendingTests = remember(theolSlice, filterPrefs) {
        theolSlice.data?.tests
            ?.filter { it.canStart && filterPrefs.isTestVisible(it) }
            .orEmpty()
    }
    val activeLocalTodos = remember(localTodos) {
        localTodos.filter { !it.deleted && !it.completed }
    }
    val bottomInset = LocalAppOverlayBottomInset.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 14.dp,
            bottom = bottomInset + 20.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "$greeting，$displayName",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$dateLine · BUCT One 工作台",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(glassSurfaceColor())
                        .border(0.5.dp, glassBorderColor(), CircleShape)
                        .clickable(enabled = !syncing && !cardSyncing && !sjcxSyncing) {
                            scope.launch { theolService.refreshIfSessionValid() }
                            scope.launch { sjcxService.refresh() }
                            if (cardHasSession) {
                                scope.launch { cardService.refresh() }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "刷新快照",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassStatChip(
                    value = if (theolSlice.provenance.refreshState == RefreshState.NEVER) "--" else pendingHomeworks.size.toString(),
                    label = "待交作业",
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSwitchPage(StartupPage.Homework) },
                    valueColor = if (pendingHomeworks.isNotEmpty()) Color(0xFFFF453A) else MaterialTheme.colorScheme.onSurface
                )
                GlassStatChip(
                    value = if (theolSlice.provenance.refreshState == RefreshState.NEVER) "--" else pendingTests.size.toString(),
                    label = "在线测验",
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSwitchPage(StartupPage.Homework) },
                    valueColor = MaterialTheme.colorScheme.primary
                )
                GlassStatChip(
                    value = activeLocalTodos.size.toString(),
                    label = "本地待办",
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSwitchPage(StartupPage.Homework) }
                )
            }
        }

        // 卡片 1：北化在线作业与待办摘要
        item {
            DashboardGlassCard(
                title = "北化在线 · 待办与 DDL",
                badge = when (theolSlice.provenance.refreshState) {
                    RefreshState.NEVER -> "未同步"
                    RefreshState.FRESH -> "最新"
                    RefreshState.EMPTY_CONFIRMED -> "已清空"
                    RefreshState.STALE -> "历史快照"
                    RefreshState.FAILED_FALLBACK -> "同步失败·显示旧数据"
                },
                badgeColor = when (theolSlice.provenance.refreshState) {
                    RefreshState.FAILED_FALLBACK -> Color(0xFFFF453A)
                    RefreshState.NEVER -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.primary
                },
                onClick = { onSwitchPage(StartupPage.Homework) }
            ) {
                when {
                    theolSlice.provenance.refreshState == RefreshState.NEVER && activeLocalTodos.isEmpty() -> {
                        Text(
                            text = "尚未同步北化在线。点击进入「作业」页，使用北化统一身份认证登录一次即可拉取全部作业与测验。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    pendingHomeworks.isEmpty() && pendingTests.isEmpty() && activeLocalTodos.isEmpty() -> {
                        Text(
                            text = "当前没有待提交的作业或待办事项。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        pendingHomeworks.take(3).forEach { hw ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = hw.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${hw.courseName} · 截止 ${hw.deadline.ifBlank { "未标注" }}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        if (pendingHomeworks.isEmpty() && activeLocalTodos.isNotEmpty()) {
                            activeLocalTodos.take(3).forEach { todo ->
                                Text(
                                    text = "• ${todo.title}${if (todo.courseName.isNotBlank()) "（${todo.courseName}）" else ""}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
                if (!theolSlice.lastError.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "上次同步提示：${theolSlice.lastError}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFF453A)
                    )
                }
            }
        }

        // 卡片 2：校园卡余额与近期消费流水（ecard.buct.edu.cn 独立认证）
        item {
            val cardData = cardSlice.data
            DashboardGlassCard(
                title = "校园卡 · 余额与流水",
                badge = when {
                    cardSlice.provenance.refreshState == RefreshState.NEVER -> "未登录"
                    cardSlice.provenance.refreshState == RefreshState.FAILED_FALLBACK -> "同步失败·显示旧数据"
                    !cardHasSession && cardData != null -> "会话过期"
                    cardSlice.provenance.refreshState == RefreshState.EMPTY_CONFIRMED -> "暂无流水"
                    cardSlice.provenance.refreshState == RefreshState.STALE -> "历史快照"
                    else -> "最新"
                },
                badgeColor = when {
                    cardSlice.provenance.refreshState == RefreshState.FAILED_FALLBACK -> Color(0xFFFF453A)
                    !cardHasSession && cardData != null -> Color(0xFFFF9500)
                    cardSlice.provenance.refreshState == RefreshState.NEVER -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.primary
                },
                onClick = {
                    context.startActivity(Intent(context, EcardActivity::class.java))
                }
            ) {
                if (cardData == null) {
                    Text(
                        text = "校园卡平台（ecard.buct.edu.cn）独立于统一身份认证，默认密码一般为身份证后六位。点击登录即可查看实时余额与消费流水。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "¥ ${formatFenToYuan(cardData.account.balanceFen)}",
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${cardData.account.cardTypeName} · ${cardData.account.statusLabel} · ${cardData.account.maskedId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.Outlined.CreditCard,
                            contentDescription = "进入校园卡",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    if (cardData.transactions.isEmpty()) {
                        Text(
                            text = "近期暂无消费或充值记录，点击查看详情",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        cardData.transactions.take(2).forEach { tx ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${tx.title} · ${tx.formattedTime.substringBefore(' ')}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "¥ ${formatFenToYuan(tx.amountFen)}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
                if (!cardSlice.lastError.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "提示：${cardSlice.lastError}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFF453A)
                    )
                }
            }
        }

        // 卡片 3：实践教学平台（实验课表 / 抢讲座 / 创新学分）
        item {
            val sjcxData = sjcxSlice.data
            val sjcxState = sjcxSlice.provenance.refreshState
            val sjcxBadge = when (sjcxState) {
                RefreshState.NEVER -> "点击进入"
                RefreshState.FRESH, RefreshState.EMPTY_CONFIRMED -> {
                    val openCount = sjcxData?.openLectures?.size ?: 0
                    if (openCount > 0) "${openCount}场讲座可报" else "已同步"
                }
                RefreshState.STALE -> "历史快照"
                RefreshState.FAILED_FALLBACK -> "离线快照"
            }
            val sjcxBadgeColor = when (sjcxState) {
                RefreshState.FRESH, RefreshState.EMPTY_CONFIRMED -> Color(0xFF34C759)
                RefreshState.FAILED_FALLBACK -> Color(0xFFFF453A)
                else -> MaterialTheme.colorScheme.primary
            }

            DashboardGlassCard(
                title = "实践教学 · 实验课表与抢讲座",
                badge = sjcxBadge,
                badgeColor = sjcxBadgeColor,
                onClick = { SjcxActivity.launch(context, initialTab = 0) }
            ) {
                if (sjcxData != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "实验排课 ${sjcxData.schedules.size} 节 · 实验报告 ${sjcxData.reports.size} 项",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "开放报名讲座 ${sjcxData.openLectures.size} 场 · 已获创新学分 ${String.format(Locale.ROOT, "%.1f", sjcxData.totalCredit)} 分",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Text(
                        text = "统一身份认证免密直连 sjcx.buct.edu.cn，一键查实验课表、抢讲座、写讲座心得与查创新学分",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickActionTile("实验课表", Icons.Outlined.Science, Modifier.weight(1f)) {
                        SjcxActivity.launch(context, initialTab = 0)
                    }
                    QuickActionTile("抢讲座", Icons.Outlined.Campaign, Modifier.weight(1f)) {
                        SjcxActivity.launch(context, initialTab = 1)
                    }
                    QuickActionTile("我的讲座", Icons.Outlined.Campaign, Modifier.weight(1f)) {
                        SjcxActivity.launch(context, initialTab = 2)
                    }
                    QuickActionTile("创新学分", Icons.Outlined.WorkspacePremium, Modifier.weight(1f)) {
                        SjcxActivity.launch(context, initialTab = 3)
                    }
                }
            }
        }

        // 卡片 4：查空闲教室独立卡片（已移除与上方大卡片重复的校园卡/实验课表/抢讲座小卡片）
        item {
            DashboardGlassCard(
                title = "查空闲教室",
                badge = "第 ${classroomSlot.week} 周 · 周${classroomDayLabel}",
                badgeColor = MaterialTheme.colorScheme.primary,
                onClick = { showEmptyClassroomDialog = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        .clickable { showEmptyClassroomDialog = true }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MeetingRoom,
                        contentDescription = "查空闲教室",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "自习室 / 空闲教室查询",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "当前定位：第 ${classroomSlot.week} 周 · 周${classroomDayLabel} · 第 ${classroomSlot.period} 节 · 点击自由切换",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    if (showEmptyClassroomDialog) {
        EmptyClassroomDialog(
            semester = classroomSlot.semester,
            week = classroomSlot.week,
            day = classroomSlot.day,
            period = classroomSlot.period,
            allowTimeSwitch = true,
            onDismiss = { showEmptyClassroomDialog = false }
        )
    }
}

private data class ClassroomSlot(
    val semester: String,
    val week: Int,
    val day: Int,
    val period: Int
)

private fun resolveCurrentClassroomSlot(context: Context): ClassroomSlot {
    val termId = WidgetScheduleSource.resolveTerm(context, AppWidgetManager.INVALID_APPWIDGET_ID).id
    val startMillis = WidgetScheduleSource.resolveSemesterStartMillis(context)
    val cal = Calendar.getInstance()
    val week = WidgetScheduleSource.weekForDate(startMillis, cal)
    val day = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
    val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    val settings = ScheduleSettingsManager.getInstance().apply { init(context) }
    val periodTimes = settings.getPeriodTimes().sortedBy { it.period }
    val defaultTimes = settings.getDefaultPeriodTimes().sortedBy { it.period }
    val activePeriod = (if (periodTimes.isNotEmpty()) periodTimes else defaultTimes)
        .firstOrNull { pt ->
            val endMin = parseClockMinutes(pt.endTime)
            endMin != null && endMin > nowMinutes
        }?.period ?: 1
    return ClassroomSlot(
        semester = termId,
        week = week,
        day = day,
        period = activePeriod.coerceIn(1, 12)
    )
}

@Composable
private fun DashboardGlassCard(
    title: String,
    badge: String,
    badgeColor: Color,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(badgeColor.copy(alpha = 0.14f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = badgeColor
                )
            }
        }
        content()
    }
}

@Composable
private fun QuickActionTile(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

package com.buct.course.ui.route

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
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buct.course.manager.StartupPage
import com.buct.course.ui.system.GlassStatChip
import com.buct.course.ui.system.LocalAppOverlayBottomInset
import com.buct.course.ui.system.glassBorderColor
import com.buct.course.ui.system.glassSurfaceColor
import com.buct.one.data.model.RefreshState
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
    val theolService = remember(context) { TheolSyncService.getInstance(context) }
    val theolSlice by theolService.theolState.collectAsState()
    val localTodos by theolService.todosState.collectAsState()
    val filterPrefs by theolService.filterPrefs.collectAsState()
    val syncing by theolService.syncing.collectAsState()

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
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "$greeting，北化同学",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
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
                        .clickable(enabled = !syncing) {
                            scope.launch { theolService.refreshIfSessionValid() }
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

        // 卡片 2：快捷入口网格
        item {
            DashboardGlassCard(
                title = "校园入口",
                badge = "五栏直达",
                badgeColor = MaterialTheme.colorScheme.primary
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    QuickActionTile("课表", Icons.Outlined.DateRange, Modifier.weight(1f)) {
                        onSwitchPage(StartupPage.Schedule)
                    }
                    QuickActionTile("成绩考试", Icons.Outlined.BarChart, Modifier.weight(1f)) {
                        onSwitchPage(StartupPage.Grades)
                    }
                    QuickActionTile("北化在线", Icons.Outlined.Assignment, Modifier.weight(1f)) {
                        onSwitchPage(StartupPage.Homework)
                    }
                    QuickActionTile("我的设置", Icons.Outlined.Settings, Modifier.weight(1f)) {
                        onSwitchPage(StartupPage.Settings)
                    }
                }
            }
        }
    }
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

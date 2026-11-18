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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buct.course.TheolSubmitWebViewActivity
import com.buct.course.ui.system.LocalAppOverlayBottomInset
import com.buct.course.ui.system.glassBorderColor
import com.buct.course.ui.system.glassSurfaceColor
import com.buct.one.data.model.LocalTodoItem
import com.buct.one.data.model.RefreshState
import com.buct.one.data.model.TheolFilterPrefs
import com.buct.one.data.model.TheolHomeworkItem
import com.buct.one.data.model.TheolTestItem
import com.buct.one.data.sync.TheolSyncService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 作业与待办页（五栏底栏独立一级 Tab）。
 * 接入北化在线（THEOL / MEOL）原生认证与抓取 + 手动删除/课程黑名单 + 复用凭证交作业 + 本地自定义待办。
 */
@Composable
fun HomeworkRoute() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember(context) { TheolSyncService.getInstance(context) }

    val theolSlice by service.theolState.collectAsState()
    val localTodos by service.todosState.collectAsState()
    val filterPrefs by service.filterPrefs.collectAsState()
    val syncing by service.syncing.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) } // 0=待交 1=全部作业 2=测验 3=本地待办
    var showLoginDialog by rememberSaveable { mutableStateOf(false) }
    var showAddTodoDialog by rememberSaveable { mutableStateOf(false) }
    var showFilterManageDialog by rememberSaveable { mutableStateOf(false) }
    var pendingBlacklistCourse by remember { mutableStateOf<Pair<String, String>?>(null) }
    var feedbackMsg by remember { mutableStateOf<String?>(null) }
    var autoTried by rememberSaveable { mutableStateOf(false) }

    // 首次进入作业页且本地已有教务统一认证凭证时，自动静默复用凭证同步北化在线
    LaunchedEffect(Unit) {
        if (!autoTried && theolSlice.provenance.refreshState == RefreshState.NEVER && service.hasSavedSsoSession()) {
            autoTried = true
            service.refreshIfSessionValid()
        }
    }

    val allHomeworks = theolSlice.data?.homeworks.orEmpty()
    val homeworks = remember(allHomeworks, filterPrefs) {
        allHomeworks.filter { filterPrefs.isHomeworkVisible(it) }
    }
    val pendingHomeworks = remember(homeworks) { homeworks.filter { it.canSubmit } }
    val allTests = theolSlice.data?.tests.orEmpty()
    val tests = remember(allTests, filterPrefs) {
        allTests.filter { filterPrefs.isTestVisible(it) }
    }
    val activeTodos = remember(localTodos) { localTodos.filter { !it.deleted } }

    val hasActiveFilters = filterPrefs.blacklistedCourses.isNotEmpty() ||
        filterPrefs.hiddenHomeworkIds.isNotEmpty() ||
        filterPrefs.hiddenTestKeys.isNotEmpty()

    val bottomInset = LocalAppOverlayBottomInset.current
    val timeFormatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        // 顶部标题与圆形玻璃操作钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "作业与测验",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                val subtitle = when (theolSlice.provenance.refreshState) {
                    RefreshState.NEVER -> "北化在线尚未同步"
                    RefreshState.FRESH, RefreshState.EMPTY_CONFIRMED ->
                        "更新于 ${timeFormatter.format(Date(theolSlice.provenance.capturedAt))}"
                    RefreshState.STALE ->
                        "历史快照 · ${timeFormatter.format(Date(theolSlice.provenance.capturedAt))}"
                    RefreshState.FAILED_FALLBACK ->
                        "同步失败（已保留旧快照）"
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (theolSlice.provenance.refreshState == RefreshState.FAILED_FALLBACK) {
                        Color(0xFFFF453A)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircleGlassButton(
                    icon = Icons.Outlined.FilterList,
                    description = "黑名单与已隐藏管理",
                    tint = if (hasActiveFilters) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    onClick = { showFilterManageDialog = true }
                )
                CircleGlassButton(
                    icon = Icons.Outlined.Add,
                    description = "新建本地待办",
                    onClick = { showAddTodoDialog = true }
                )
                CircleGlassButton(
                    icon = Icons.Outlined.Login,
                    description = "登录北化在线",
                    onClick = { showLoginDialog = true }
                )
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(glassSurfaceColor())
                        .border(0.5.dp, glassBorderColor(), CircleShape)
                        .clickable(enabled = !syncing) {
                            scope.launch {
                                feedbackMsg = null
                                val res = service.refreshIfSessionValid()
                                if (res.isFailure) {
                                    feedbackMsg = res.exceptionOrNull()?.message
                                    if (feedbackMsg?.contains("登录") == true || feedbackMsg?.contains("过期") == true) {
                                        showLoginDialog = true
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (syncing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "刷新北化在线",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 悬浮胶囊分段选择器
        val segments = listOf(
            "待交(${pendingHomeworks.size})",
            "全部(${homeworks.size})",
            "测验(${tests.size})",
            "待办(${activeTodos.count { !it.completed }})"
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(glassSurfaceColor())
                .border(0.5.dp, glassBorderColor(), RoundedCornerShape(22.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            segments.forEachIndexed { index, label ->
                val selected = selectedTab == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                        )
                        .clickable { selectedTab = index }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        val bannerError = feedbackMsg ?: theolSlice.lastError
        if (!bannerError.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFFFF453A).copy(alpha = 0.12f))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = bannerError,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF453A)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomInset + 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (selectedTab) {
                0 -> {
                    if (pendingHomeworks.isEmpty()) {
                        item {
                            EmptyGlassHint(
                                text = if (theolSlice.provenance.refreshState == RefreshState.NEVER) {
                                    "尚未同步北化在线。点击右上角登录图标，用统一身份认证账号原生登录即可获取待交作业。"
                                } else {
                                    "当前没有待提交的作业。"
                                }
                            )
                        }
                    } else {
                        items(pendingHomeworks, key = { it.hwtid }) { hw ->
                            val targetUrl = hw.submitHref.ifBlank { hw.detailHref }
                            HomeworkGlassCard(
                                hw = hw,
                                onOpenSubmit = {
                                    TheolSubmitWebViewActivity.launch(
                                        context = context,
                                        url = targetUrl,
                                        title = hw.title,
                                        courseName = hw.courseName,
                                        courseLid = hw.lid
                                    )
                                },
                                onHideHomework = { service.hideHomework(hw.hwtid) },
                                onBlacklistCourse = {
                                    pendingBlacklistCourse = hw.lid to hw.courseName.ifBlank { "未命名课程" }
                                }
                            )
                        }
                    }
                }
                1 -> {
                    if (homeworks.isEmpty()) {
                        item { EmptyGlassHint("暂无作业记录，请点击右上角刷新或登录北化在线。") }
                    } else {
                        items(homeworks, key = { it.hwtid }) { hw ->
                            val targetUrl = hw.submitHref.ifBlank { hw.detailHref }
                            HomeworkGlassCard(
                                hw = hw,
                                onOpenSubmit = {
                                    TheolSubmitWebViewActivity.launch(
                                        context = context,
                                        url = targetUrl,
                                        title = hw.title,
                                        courseName = hw.courseName,
                                        courseLid = hw.lid
                                    )
                                },
                                onHideHomework = { service.hideHomework(hw.hwtid) },
                                onBlacklistCourse = {
                                    pendingBlacklistCourse = hw.lid to hw.courseName.ifBlank { "未命名课程" }
                                }
                            )
                        }
                    }
                }
                2 -> {
                    if (tests.isEmpty()) {
                        item { EmptyGlassHint("暂无在线测验记录。") }
                    } else {
                        items(tests, key = { "${it.lid}:${it.testId}" }) { test ->
                            TestGlassCard(
                                test = test,
                                onOpenTest = {
                                    if (test.testUrl.isNotBlank()) {
                                        TheolSubmitWebViewActivity.launch(
                                            context = context,
                                            url = test.testUrl,
                                            title = test.title,
                                            courseName = test.courseName,
                                            courseLid = test.lid
                                        )
                                    }
                                },
                                onHideTest = { service.hideTest(test.lid, test.testId) },
                                onBlacklistCourse = {
                                    pendingBlacklistCourse = test.lid to test.courseName.ifBlank { "未命名课程" }
                                }
                            )
                        }
                    }
                }
                3 -> {
                    if (activeTodos.isEmpty()) {
                        item { EmptyGlassHint("暂无本地待办。点击右上角 + 新建自定义作业或 DDL 提醒。") }
                    } else {
                        items(activeTodos, key = { it.id }) { todo ->
                            LocalTodoGlassCard(
                                item = todo,
                                onToggle = { service.toggleTodoCompleted(todo.id) },
                                onDelete = { service.softDeleteTodo(todo.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    pendingBlacklistCourse?.let { (lid, courseName) ->
        AlertDialog(
            onDismissRequest = { pendingBlacklistCourse = null },
            title = { Text("拉黑课程") },
            text = {
                Text("确定将「$courseName」加入黑名单吗？之后该课程的作业和测验（包括刷新同步后）将不再显示，可在右上角筛选管理中随时移出。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        service.blacklistCourse(lid, courseName)
                        pendingBlacklistCourse = null
                    }
                ) {
                    Text("拉黑", color = Color(0xFFFF453A))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBlacklistCourse = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (showFilterManageDialog) {
        FilterManageDialog(
            filterPrefs = filterPrefs,
            onDismiss = { showFilterManageDialog = false },
            onUnblacklist = { key -> service.unblacklistCourse(key) },
            onRestoreAllHomeworks = { service.restoreAllHiddenHomeworks() },
            onRestoreAllTests = { service.restoreAllHiddenTests() }
        )
    }

    if (showLoginDialog) {
        TheolNativeLoginDialog(
            syncing = syncing,
            onDismiss = { showLoginDialog = false },
            onSubmit = { username, pwdChars ->
                scope.launch {
                    feedbackMsg = null
                    val res = service.loginAndSync(username, pwdChars)
                    if (res.isSuccess) {
                        showLoginDialog = false
                    } else {
                        feedbackMsg = res.exceptionOrNull()?.message ?: "登录失败"
                    }
                }
            }
        )
    }

    if (showAddTodoDialog) {
        AddLocalTodoDialog(
            onDismiss = { showAddTodoDialog = false },
            onAdd = { title, course ->
                service.addLocalTodo(title = title, courseName = course, deadlineMillis = null)
                showAddTodoDialog = false
                selectedTab = 3
            }
        )
    }
}

@Composable
private fun CircleGlassButton(
    icon: ImageVector,
    description: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun HomeworkGlassCard(
    hw: TheolHomeworkItem,
    onOpenSubmit: () -> Unit,
    onHideHomework: () -> Unit,
    onBlacklistCourse: () -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    val badgeColor = when {
        hw.canSubmit -> Color(0xFFFF453A)
        hw.hasResult || hw.status == "已完成" -> Color(0xFF34C759)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .clickable(onClick = onOpenSubmit)
            .padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = hw.courseName.ifBlank { "北化在线课程" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(badgeColor.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (hw.isGroup) "${hw.status} · 分组" else hw.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor
                    )
                }
                Box {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "作业操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable { menuExpanded = true }
                            .padding(4.dp)
                    )
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (hw.canSubmit) "前往交作业" else "查看作业详情") },
                            onClick = {
                                menuExpanded = false
                                onOpenSubmit()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("删除此作业") },
                            onClick = {
                                menuExpanded = false
                                onHideHomework()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "拉黑课程「${hw.courseName.ifBlank { "该课程" }}」",
                                    color = Color(0xFFFF453A)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onBlacklistCourse()
                            }
                        )
                    }
                }
            }
        }
        Text(
            text = hw.title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(end = 6.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "截止：${hw.deadline.ifBlank { "无限制" }}${if (hw.score.isNotBlank()) " · 成绩 ${hw.score}" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (hw.canSubmit) {
                Text(
                    text = "点按提交 ›",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun TestGlassCard(
    test: TheolTestItem,
    onOpenTest: () -> Unit,
    onHideTest: () -> Unit,
    onBlacklistCourse: () -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .then(if (test.testUrl.isNotBlank()) Modifier.clickable(onClick = onOpenTest) else Modifier)
            .padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = test.courseName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = test.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (test.canStart) Color(0xFFFF453A) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "测验操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable { menuExpanded = true }
                            .padding(4.dp)
                    )
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        if (test.testUrl.isNotBlank()) {
                            DropdownMenuItem(
                                text = { Text("打开测验页面") },
                                onClick = {
                                    menuExpanded = false
                                    onOpenTest()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("隐藏此测验") },
                            onClick = {
                                menuExpanded = false
                                onHideTest()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "拉黑课程「${test.courseName.ifBlank { "该课程" }}」",
                                    color = Color(0xFFFF453A)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onBlacklistCourse()
                            }
                        )
                    }
                }
            }
        }
        Text(
            text = test.title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(end = 6.dp)
        )
        Text(
            text = "时间：${test.startTime} ~ ${test.endTime}${if (test.duration.isNotBlank()) " · 时长 ${test.duration}" else ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 6.dp)
        )
    }
}

@Composable
private fun FilterManageDialog(
    filterPrefs: TheolFilterPrefs,
    onDismiss: () -> Unit,
    onUnblacklist: (String) -> Unit,
    onRestoreAllHomeworks: () -> Unit,
    onRestoreAllTests: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("课程黑名单与已隐藏项") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (filterPrefs.blacklistedCourses.isEmpty() &&
                    filterPrefs.hiddenHomeworkIds.isEmpty() &&
                    filterPrefs.hiddenTestKeys.isEmpty()
                ) {
                    Text(
                        text = "当前没有拉黑课程或手动删除的作业/测验。在卡片右上角菜单可隐藏单条作业或拉黑已退课程。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (filterPrefs.blacklistedCourses.isNotEmpty()) {
                    Text(
                        text = "已拉黑课程（${filterPrefs.blacklistedCourses.size}）",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    filterPrefs.blacklistedCourses.forEach { bc ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = bc.courseName.ifBlank { "课程 #${bc.lid}" },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            TextButton(onClick = { onUnblacklist(bc.key) }) {
                                Text("移出")
                            }
                        }
                    }
                }

                if (filterPrefs.hiddenHomeworkIds.isNotEmpty() || filterPrefs.hiddenTestKeys.isNotEmpty()) {
                    if (filterPrefs.blacklistedCourses.isNotEmpty()) {
                        HorizontalDivider()
                    }
                    if (filterPrefs.hiddenHomeworkIds.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "已隐藏作业（${filterPrefs.hiddenHomeworkIds.size}）",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            TextButton(onClick = onRestoreAllHomeworks) {
                                Text("恢复全部")
                            }
                        }
                    }
                    if (filterPrefs.hiddenTestKeys.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "已隐藏测验（${filterPrefs.hiddenTestKeys.size}）",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            TextButton(onClick = onRestoreAllTests) {
                                Text("恢复全部")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun LocalTodoGlassCard(
    item: LocalTodoItem,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = if (item.completed) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (item.completed) Color(0xFF34C759) else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (item.completed) TextDecoration.LineThrough else TextDecoration.None
                ),
                color = if (item.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
            if (item.courseName.isNotBlank()) {
                Text(
                    text = item.courseName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = Icons.Outlined.DeleteOutline,
            contentDescription = "删除",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(20.dp)
                .clickable(onClick = onDelete)
        )
    }
}

@Composable
private fun EmptyGlassHint(text: String) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .padding(20.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TheolNativeLoginDialog(
    syncing: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, CharArray) -> Unit
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!syncing) onDismiss() },
        title = { Text("原生登录北化在线") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "直连 sidportal.buct.edu.cn 完成 AES 原生认证，密码仅在内存加密后即刻清零，绝不落盘。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.trim() },
                    label = { Text("学号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("统一身份认证密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !syncing && username.isNotBlank() && password.isNotEmpty(),
                onClick = {
                    val chars = password.toCharArray()
                    password = ""
                    onSubmit(username, chars)
                }
            ) {
                Text(if (syncing) "同步中…" else "登录并同步")
            }
        },
        dismissButton = {
            TextButton(enabled = !syncing, onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun AddLocalTodoDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var course by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建本地待办") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("待办 / 作业标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = course,
                    onValueChange = { course = it },
                    label = { Text("所属课程（选填）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = { onAdd(title.trim(), course.trim()) }
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

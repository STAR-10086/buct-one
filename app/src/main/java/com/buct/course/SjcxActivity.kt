package com.buct.course

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buct.course.manager.AppThemeCoordinator
import com.buct.course.manager.UserManager
import com.buct.course.ui.system.GlassLoadingIndicator
import com.buct.course.ui.system.GlassPageScaffold
import com.buct.course.ui.system.GlassTextField
import com.buct.course.ui.system.GlassToaster
import com.buct.course.ui.system.SystemActionButton
import com.buct.course.ui.system.SystemCapacityIndicator
import com.buct.course.ui.system.SystemCompactSegmentedControl
import com.buct.course.ui.system.SystemDestructiveButton
import com.buct.course.ui.system.SystemDialog
import com.buct.course.ui.system.SystemDivider
import com.buct.course.ui.system.SystemEmptyState
import com.buct.course.ui.system.SystemIconButton
import com.buct.course.ui.system.SystemPrimaryButton
import com.buct.course.ui.system.SystemSecondaryButton
import com.buct.course.ui.system.glassBorderColor
import com.buct.course.ui.system.glassSurfaceColor
import com.buct.course.ui.theme.CourseSelectorTheme
import com.buct.one.data.model.RefreshState
import com.buct.one.data.model.SjcxCreditRecordItem
import com.buct.one.data.model.SjcxExperimentScheduleItem
import com.buct.one.data.model.SjcxExperimentScoreItem
import com.buct.one.data.model.SjcxLectureBatchItem
import com.buct.one.data.model.SjcxMyLectureItem
import com.buct.one.data.sync.SjcxSyncService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 实践教学综合服务平台（sjcx.buct.edu.cn）独立原生液态玻璃界面。
 *
 * 功能：
 * 1. 实验课表（本学期所有实验排课、周次筛选、实验室房间、节次、指导教师）
 * 2. 抢讲座（讲座详情弹窗、未满一键报名、已满/未开始卡片直接点击尝试捡漏与前台连刷蹲守、无感重登）
 * 3. 我的讲座（已报名讲座列表、取消报名、在线填写/修改讲座心得体会）
 * 4. 成绩与学分（实验分项与总评成绩 + 创新学分认定明细与累计学分）
 */
class SjcxActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialTab = intent.getIntExtra(EXTRA_INITIAL_TAB, 0).coerceIn(0, 3)
        setContent {
            CourseSelectorTheme {
                SjcxScreen(
                    initialTab = initialTab,
                    onBack = { finish() }
                )
            }
        }
    }

    companion object {
        private const val EXTRA_INITIAL_TAB = "extra_initial_tab"

        fun launch(context: Context, initialTab: Int = 0) {
            context.startActivity(
                Intent(context, SjcxActivity::class.java).apply {
                    putExtra(EXTRA_INITIAL_TAB, initialTab)
                }
            )
        }
    }
}

private fun isAuthRequiredError(message: String): Boolean =
    message.contains("登录") ||
        message.contains("过期") ||
        message.contains("会话") ||
        message.contains("票据")

private fun isPermanentLectureError(message: String): Boolean {
    val keywords = listOf("已报名", "重复", "已结束", "截止", "缺勤", "黑名单", "超出", "限制", "密码", "未建立", "冻结")
    return keywords.any { message.contains(it) }
}

@Composable
private fun SjcxScreen(
    initialTab: Int,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember(context) { SjcxSyncService.getInstance(context) }

    val slice by service.sjcxState.collectAsState()
    val syncing by service.syncing.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) } // 0=实验课表 1=抢讲座 2=我的讲座 3=成绩与学分
    var selectedWeekFilter by rememberSaveable { mutableIntStateOf(0) } // 0=全部周次, >0=指定周
    var showEndedLectures by rememberSaveable { mutableStateOf(false) }
    var showLoginDialog by rememberSaveable { mutableStateOf(false) }

    var selectedLectureForDetail by remember { mutableStateOf<SjcxLectureBatchItem?>(null) }
    var editingExperienceItem by remember { mutableStateOf<SjcxMyLectureItem?>(null) }
    var confirmSignupItem by remember { mutableStateOf<SjcxLectureBatchItem?>(null) }
    var confirmCancelItem by remember { mutableStateOf<SjcxMyLectureItem?>(null) }
    var feedbackMsg by remember { mutableStateOf<String?>(null) }

    // 前台连刷蹲守抢讲座状态
    var pollingLecture by remember { mutableStateOf<SjcxLectureBatchItem?>(null) }
    var pollingAttempts by remember { mutableIntStateOf(0) }
    var pollingLastMessage by remember { mutableStateOf("") }
    var pollingJob by remember { mutableStateOf<Job?>(null) }

    fun stopLecturePolling() {
        pollingJob?.cancel()
        pollingJob = null
        pollingLecture = null
        pollingAttempts = 0
        pollingLastMessage = ""
    }

    DisposableEffect(Unit) {
        onDispose {
            pollingJob?.cancel()
        }
    }

    suspend fun registerLectureWithRelogin(projectTimeId: String): Result<String> {
        return service.registerLecture(projectTimeId)
    }

    fun startLecturePolling(lecture: SjcxLectureBatchItem) {
        stopLecturePolling()
        pollingLecture = lecture
        pollingAttempts = 0
        pollingLastMessage = "正在发起首次抢报尝试…"
        feedbackMsg = null
        pollingJob = scope.launch {
            while (isActive) {
                pollingAttempts += 1
                val res = registerLectureWithRelogin(lecture.id)
                if (res.isSuccess) {
                    val okMsg = res.getOrNull().orEmpty().ifBlank { "报名成功" }
                    stopLecturePolling()
                    feedbackMsg = null
                    GlassToaster.show(okMsg)
                    selectedTab = 2
                    break
                }
                val errMsg = res.exceptionOrNull()?.message.orEmpty().ifBlank { "暂未抢到名额" }
                pollingLastMessage = errMsg
                if (isAuthRequiredError(errMsg)) {
                    stopLecturePolling()
                    feedbackMsg = errMsg
                    GlassToaster.show(errMsg)
                    showLoginDialog = true
                    break
                }
                if (isPermanentLectureError(errMsg)) {
                    stopLecturePolling()
                    feedbackMsg = errMsg
                    GlassToaster.show(errMsg)
                    break
                }
                delay(1500L)
            }
        }
    }

    fun triggerSingleRegister(target: SjcxLectureBatchItem) {
        scope.launch {
            feedbackMsg = null
            val res = registerLectureWithRelogin(target.id)
            res.onSuccess { msg ->
                feedbackMsg = null
                GlassToaster.show(msg)
                selectedTab = 2
            }.onFailure { err ->
                val msg = err.message ?: "报名失败"
                feedbackMsg = msg
                GlassToaster.show(msg)
                if (isAuthRequiredError(msg)) {
                    showLoginDialog = true
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if ((slice.provenance.refreshState == RefreshState.NEVER ||
                slice.provenance.refreshState == RefreshState.FAILED_FALLBACK ||
                !slice.lastError.isNullOrBlank()) && service.canAutoSync()
        ) {
            service.refresh()
        }
    }

    val data = slice.data
    val schedules = data?.schedules.orEmpty()
    val openLectures = data?.openLectures.orEmpty()
    val endedLectures = data?.endedLectures.orEmpty()
    val myLectures = data?.myLectures.orEmpty()
    val expScores = data?.scores.orEmpty()
    val creditRecords = data?.creditRecords.orEmpty()
    val timeFormatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }

    val sub = when (slice.provenance.refreshState) {
        RefreshState.NEVER -> "尚未同步 · 统一身份认证免密换票"
        RefreshState.FRESH, RefreshState.EMPTY_CONFIRMED -> {
            val termInfo = data?.yearterm?.takeIf { it.isNotBlank() }?.let { "$it · " }.orEmpty()
            "${termInfo}第 ${data?.currentWeek ?: 1} 周 · 更新于 ${timeFormatter.format(Date(slice.provenance.capturedAt))}"
        }
        RefreshState.STALE -> "历史缓存 · ${timeFormatter.format(Date(slice.provenance.capturedAt))}"
        RefreshState.FAILED_FALLBACK -> "同步失败（已保留本地快照）"
    }

    GlassPageScaffold(
        title = "实践教学与讲座",
        subtitle = sub,
        onBack = onBack,
        actions = {
            SystemIconButton(
                icon = Icons.Outlined.Login,
                contentDescription = "统一身份认证登录",
                enabled = !syncing,
                onClick = { showLoginDialog = true }
            )
            if (syncing) {
                GlassLoadingIndicator(size = 38.dp)
            } else {
                SystemIconButton(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = "刷新",
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = {
                        scope.launch {
                            feedbackMsg = null
                            val res = service.refresh(userInitiated = true)
                            if (res.isFailure) {
                                val err = res.exceptionOrNull()?.message ?: "刷新失败"
                                feedbackMsg = err
                                GlassToaster.show(err)
                                if (isAuthRequiredError(err)) {
                                    showLoginDialog = true
                                }
                            } else {
                                GlassToaster.show("实践教学数据已刷新")
                            }
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding() + 4.dp,
                    start = 16.dp,
                    end = 16.dp
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val tabs = listOf(
                "课表(${schedules.size})",
                "抢讲座(${openLectures.size})",
                "我的(${myLectures.size})",
                "成绩学分"
            )
            SystemCompactSegmentedControl(
                options = tabs,
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it }
            )

            pollingLecture?.let { activePoll ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .border(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassLoadingIndicator(size = 28.dp)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "正在蹲守《${activePoll.projectName}》 · 第 $pollingAttempts 次",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (pollingLastMessage.isNotBlank()) {
                            Text(
                                text = "最近响应：$pollingLastMessage",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    SystemActionButton(
                        text = "停止",
                        primary = false,
                        onClick = { stopLecturePolling() }
                    )
                }
            }

            val bannerError = feedbackMsg ?: slice.lastError
            if (!bannerError.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFFF453A).copy(alpha = 0.12f))
                        .border(0.5.dp, Color(0xFFFF453A).copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = bannerError,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF453A)
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (selectedTab) {
                    0 -> {
                        val distinctWeeks = schedules.map { it.week }.filter { it > 0 }.distinct().sorted()
                        if (distinctWeeks.isNotEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    GlassFilterPill(
                                        label = "全部 (${schedules.size})",
                                        selected = selectedWeekFilter == 0,
                                        onClick = { selectedWeekFilter = 0 }
                                    )
                                    val currentW = data?.currentWeek ?: 0
                                    if (currentW > 0 && currentW in distinctWeeks) {
                                        GlassFilterPill(
                                            label = "本周 (第${currentW}周)",
                                            selected = selectedWeekFilter == currentW,
                                            onClick = { selectedWeekFilter = currentW }
                                        )
                                    }
                                    distinctWeeks.filter { it != currentW }.forEach { w ->
                                        GlassFilterPill(
                                            label = "第${w}周",
                                            selected = selectedWeekFilter == w,
                                            onClick = { selectedWeekFilter = w }
                                        )
                                    }
                                }
                            }
                        }

                        val filteredSchedules = if (selectedWeekFilter == 0) {
                            schedules
                        } else {
                            schedules.filter { it.week == selectedWeekFilter }
                        }

                        if (filteredSchedules.isEmpty()) {
                            item {
                                SystemEmptyState(
                                    title = "暂无实验排课",
                                    message = if (slice.provenance.refreshState == RefreshState.NEVER) {
                                        "尚未同步实验课表，请点击右上角刷新按钮同步。"
                                    } else {
                                        "当前筛选条件下暂无实验课安排。"
                                    },
                                    icon = Icons.Outlined.Science
                                )
                            }
                        } else {
                            items(
                                filteredSchedules,
                                key = { "${it.courseName}:${it.expName}:${it.week}:${it.weekDay}:${it.jcText}" }
                            ) { item ->
                                ExperimentScheduleCard(
                                    item = item,
                                    isCurrentWeek = item.week == (data?.currentWeek ?: -1)
                                )
                            }
                        }
                    }

                    1 -> {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (showEndedLectures) {
                                        "未开始 / 已结束活动（共 ${endedLectures.size} 场）"
                                    } else {
                                        "当前开放（共 ${openLectures.size} 场 · 本学期缺勤 ${data?.noSignCount ?: 0} 次）"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                GlassFilterPill(
                                    label = if (showEndedLectures) "看当前开放 (${openLectures.size})" else "看未开始/结束 (${endedLectures.size})",
                                    selected = showEndedLectures,
                                    onClick = { showEndedLectures = !showEndedLectures }
                                )
                            }
                        }

                        val activeList = if (showEndedLectures) endedLectures else openLectures
                        if (activeList.isEmpty()) {
                            item {
                                SystemEmptyState(
                                    title = if (showEndedLectures) "暂无未开始或已结束讲座" else "暂无开放报名讲座",
                                    message = if (showEndedLectures) {
                                        "未开始或已结束的讲座活动会显示在这里。"
                                    } else {
                                        "当前暂无开放报名讲座，可切换查看未开始/已结束讲座并提前蹲守。"
                                    },
                                    icon = Icons.Outlined.Campaign
                                )
                            }
                        } else {
                            items(activeList, key = { it.id }) { lecture ->
                                val isPollingThis = pollingLecture?.id == lecture.id
                                LectureBatchCard(
                                    item = lecture,
                                    isUpcomingOrEnded = showEndedLectures,
                                    isPollingThis = isPollingThis,
                                    syncing = syncing,
                                    onCardClick = { selectedLectureForDetail = lecture },
                                    onSingleRegister = { confirmSignupItem = lecture },
                                    onTogglePolling = {
                                        if (isPollingThis) {
                                            stopLecturePolling()
                                        } else {
                                            startLecturePolling(lecture)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    2 -> {
                        if (myLectures.isEmpty()) {
                            item {
                                SystemEmptyState(
                                    title = "暂无已报名讲座",
                                    message = "报名成功后的讲座和活动会出现在这里，支持在线填写心得体会。",
                                    icon = Icons.Outlined.Campaign
                                )
                            }
                        } else {
                            items(myLectures, key = { "${it.creProjectStuId}:${it.projectId}:${it.batchName}" }) { myItem ->
                                MyLectureCard(
                                    item = myItem,
                                    enabled = !syncing,
                                    onEditExperience = { editingExperienceItem = myItem },
                                    onCancelRegistration = { confirmCancelItem = myItem }
                                )
                            }
                        }
                    }

                    3 -> {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(glassSurfaceColor())
                                    .border(0.5.dp, glassBorderColor(), RoundedCornerShape(22.dp))
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.WorkspacePremium,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = "已获创新学分",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Text(
                                        text = "${String.format(Locale.ROOT, "%.1f", data?.totalCredit ?: 0.0)} 学分",
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = "共 ${creditRecords.size} 条认定记录 · 实验课程共 ${expScores.size} 项成绩",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (expScores.isNotEmpty()) {
                            item {
                                Text(
                                    text = "实验成绩明细",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                            items(expScores, key = { "${it.courseId}:${it.expName}:${it.week}" }) { sc ->
                                ExperimentScoreCard(item = sc)
                            }
                        }

                        if (creditRecords.isNotEmpty()) {
                            item {
                                Text(
                                    text = "创新学分认定明细",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                            items(creditRecords, key = { "${it.creStuId}:${it.projectName}:${it.addTime}" }) { cr ->
                                CreditRecordCard(item = cr)
                            }
                        }

                        if (expScores.isEmpty() && creditRecords.isEmpty()) {
                            item {
                                SystemEmptyState(
                                    title = "暂无成绩与创新学分",
                                    message = "当前还没有已出分的实验项目或已认定的创新学分记录。",
                                    icon = Icons.Outlined.WorkspacePremium
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 讲座详情与抢报操作弹窗（任何讲座卡片点击即开，已满/未开始也可操作）
    selectedLectureForDetail?.let { lecture ->
        val isPollingThis = pollingLecture?.id == lecture.id
        SystemDialog(
            onDismissRequest = { selectedLectureForDetail = null },
            title = {
                Text(
                    text = lecture.projectName,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            confirmButton = {
                if (isPollingThis) {
                    SystemDestructiveButton(
                        text = "停止蹲守",
                        onClick = {
                            stopLecturePolling()
                            selectedLectureForDetail = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (lecture.isFull || showEndedLectures) {
                    SystemPrimaryButton(
                        text = "连刷抢名额",
                        onClick = {
                            selectedLectureForDetail = null
                            startLecturePolling(lecture)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    SystemPrimaryButton(
                        text = "立即报名",
                        enabled = !syncing,
                        onClick = {
                            selectedLectureForDetail = null
                            confirmSignupItem = lecture
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            dismissButton = {
                SystemSecondaryButton(
                    text = "关闭",
                    onClick = { selectedLectureForDetail = null },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = buildList {
                        add(lecture.batch.ifBlank { "第 1 批" })
                        if (lecture.campusName.isNotBlank()) add(lecture.campusName)
                        add(if (lecture.requireReview) "需审核" else "免审核")
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                if (lecture.totalNum > 0) {
                    SystemCapacityIndicator(
                        selected = lecture.registrationNum,
                        capacity = lecture.totalNum
                    )
                }

                SystemDivider(alpha = 0.35f)

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (lecture.place.isNotBlank()) {
                        Text(
                            text = "地点：${lecture.place}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (lecture.speaker.isNotBlank()) {
                        Text(
                            text = "主讲人：${lecture.speaker}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (lecture.teacherName.isNotBlank()) {
                        Text(
                            text = "负责教师：${lecture.teacherName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (lecture.signupBeginTime.isNotBlank() || lecture.signupEndTime.isNotBlank()) {
                        Text(
                            text = "报名时间：${lecture.signupBeginTime} ~ ${lecture.signupEndTime}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (lecture.lectureBeginTime.isNotBlank() || lecture.lectureEndTime.isNotBlank()) {
                        Text(
                            text = "举办时间：${lecture.lectureBeginTime} ~ ${lecture.lectureEndTime}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if ((lecture.isFull || showEndedLectures) && !isPollingThis) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                            .clickable(enabled = !syncing) {
                                selectedLectureForDetail = null
                                triggerSingleRegister(lecture)
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "立即单次提交报名请求（尝试捡漏 / 抢开场）",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    confirmSignupItem?.let { target ->
        SystemDialog(
            onDismissRequest = { confirmSignupItem = null },
            title = {
                Text(
                    text = if (target.isFull) "确认尝试捡漏报名" else "确认报名讲座",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            confirmButton = {
                SystemPrimaryButton(
                    text = if (target.isFull) "尝试报名" else "立即报名",
                    enabled = !syncing,
                    onClick = {
                        confirmSignupItem = null
                        triggerSingleRegister(target)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                SystemSecondaryButton(
                    text = "取消",
                    onClick = { confirmSignupItem = null },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            Text(
                text = buildString {
                    append("确认向实践教学系统提交《${target.projectName}（${target.batch.ifBlank { "第 1 批" }}）》报名请求吗？\n\n")
                    append("地点：${target.place.ifBlank { "待定" }}\n")
                    append("活动时间：${target.lectureBeginTime} ~ ${target.lectureEndTime}\n")
                    if (target.isFull) {
                        append("\n注：当前展示人数已达上限（${target.registrationNum}/${target.totalNum}），若有人临时退选可直接捡漏成功。")
                    } else if (target.requireReview) {
                        append("\n注：本活动报名后需等待负责人审核。")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    confirmCancelItem?.let { target ->
        SystemDialog(
            onDismissRequest = { confirmCancelItem = null },
            title = {
                Text(
                    text = "取消讲座报名",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            confirmButton = {
                SystemDestructiveButton(
                    text = "确认取消",
                    enabled = !syncing,
                    onClick = {
                        val id = target.creProjectStuId
                        confirmCancelItem = null
                        scope.launch {
                            val res = service.cancelLectureRegistration(id)
                            res.onSuccess { msg ->
                                GlassToaster.show(msg)
                            }.onFailure { err ->
                                val msg = err.message ?: "取消失败"
                                feedbackMsg = msg
                                GlassToaster.show(msg)
                                if (isAuthRequiredError(msg)) {
                                    showLoginDialog = true
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                SystemSecondaryButton(
                    text = "返回",
                    onClick = { confirmCancelItem = null },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            Text(
                text = "确认取消《${target.projectName}》的报名吗？",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    editingExperienceItem?.let { target ->
        var text by rememberSaveable(target.creProjectStuId) { mutableStateOf(target.experience) }
        SystemDialog(
            onDismissRequest = { if (!syncing) editingExperienceItem = null },
            title = {
                Text(
                    text = if (target.isRecognized) "讲座心得体会（已认定学分）" else "填写 / 修改心得体会",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            confirmButton = if (!target.isRecognized) {
                {
                    SystemPrimaryButton(
                        text = "保存",
                        enabled = !syncing && text.isNotBlank(),
                        onClick = {
                            val stuId = target.creProjectStuId
                            val content = text
                            editingExperienceItem = null
                            scope.launch {
                                val res = service.saveLectureExperience(stuId, content)
                                res.onSuccess { msg ->
                                    GlassToaster.show(msg)
                                }.onFailure { err ->
                                    val msg = err.message ?: "保存失败"
                                    feedbackMsg = msg
                                    GlassToaster.show(msg)
                                    if (isAuthRequiredError(msg)) {
                                        showLoginDialog = true
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else null,
            dismissButton = {
                SystemSecondaryButton(
                    text = "关闭",
                    onClick = { editingExperienceItem = null },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = target.projectName,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
                GlassTextField(
                    value = text,
                    onValueChange = { if (!target.isRecognized) text = it },
                    placeholder = "请输入讲座心得体会…",
                    singleLine = false,
                    enabled = !target.isRecognized,
                    minHeight = 132.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (showLoginDialog) {
        val defaultUser = remember {
            UserManager.getInstance().apply { init(context) }.let {
                it.username.ifBlank { it.studentId.orEmpty() }
            }
        }
        var username by rememberSaveable { mutableStateOf(defaultUser) }
        var password by remember { mutableStateOf("") }

        fun submitLogin() {
            if (syncing || username.isBlank() || password.isEmpty()) return
            val chars = password.toCharArray()
            password = ""
            scope.launch {
                feedbackMsg = null
                val res = service.loginAndSync(username, chars)
                if (res.isSuccess) {
                    showLoginDialog = false
                    GlassToaster.show("登录成功并已同步实践教学数据")
                } else {
                    val err = res.exceptionOrNull()?.message ?: "登录失败"
                    feedbackMsg = err
                    GlassToaster.show(err)
                }
            }
        }

        SystemDialog(
            onDismissRequest = { if (!syncing) showLoginDialog = false },
            title = {
                Text(
                    text = "统一身份认证登录",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            confirmButton = {
                SystemPrimaryButton(
                    text = if (syncing) "同步中…" else "登录并同步",
                    enabled = !syncing && username.isNotBlank() && password.isNotEmpty(),
                    onClick = ::submitLogin,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                SystemSecondaryButton(
                    text = "取消",
                    enabled = !syncing,
                    onClick = { showLoginDialog = false },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "实践教学平台（sjcx.buct.edu.cn）支持统一身份认证 CAS 单点登录。密码仅在内存加密换票后立即清零。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                GlassTextField(
                    value = username,
                    onValueChange = { username = it.trim() },
                    placeholder = "请输入学号",
                    leadingIcon = Icons.Outlined.Person,
                    singleLine = true,
                    enabled = !syncing,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                GlassTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "请输入统一身份认证密码",
                    leadingIcon = Icons.Outlined.Lock,
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !syncing,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { submitLogin() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun GlassFilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else glassSurfaceColor()
            )
            .border(
                width = 0.5.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else glassBorderColor(),
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun ExperimentScheduleCard(
    item: SjcxExperimentScheduleItem,
    isCurrentWeek: Boolean
) {
    val badgeColor = if (isCurrentWeek) Color(0xFF34C759) else MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), RoundedCornerShape(22.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Science,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = item.courseName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(badgeColor.copy(alpha = 0.14f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (isCurrentWeek) "本周 · 第${item.week}周 ${item.weekDayLabel}" else "第${item.week}周 · ${item.weekDayLabel}",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = badgeColor
                )
            }
        }

        Text(
            text = item.expName,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = "第 ${item.week} 周 · ${item.weekDayLabel} · ${item.jcText}（${item.formLabel}）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = buildString {
                    append(item.roomName.ifBlank { "实验室待定" })
                    if (item.seatNumber.isNotBlank()) append(" · 机位/座位 ${item.seatNumber}")
                    if (item.teacherName.isNotBlank()) append(" · 指导教师：${item.teacherName}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LectureBatchCard(
    item: SjcxLectureBatchItem,
    isUpcomingOrEnded: Boolean,
    isPollingThis: Boolean,
    syncing: Boolean,
    onCardClick: () -> Unit,
    onSingleRegister: () -> Unit,
    onTogglePolling: () -> Unit
) {
    val full = item.isFull
    val statusColor = when {
        isPollingThis -> MaterialTheme.colorScheme.primary
        full -> Color(0xFFFF9F0A)
        else -> Color(0xFF34C759)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(glassSurfaceColor())
            .border(
                width = if (isPollingThis) 1.dp else 0.5.dp,
                color = if (isPollingThis) MaterialTheme.colorScheme.primary.copy(alpha = 0.65f) else glassBorderColor(),
                shape = RoundedCornerShape(22.dp)
            )
            .clickable(onClick = onCardClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Campaign,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = buildString {
                        append(item.batch.ifBlank { "第 1 批" })
                        if (item.campusName.isNotBlank()) append(" · ${item.campusName}")
                        if (item.requireReview) append(" · 需审核")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (item.totalNum > 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(statusColor.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "${item.registrationNum} / ${item.totalNum} 人" + if (full) "（已满）" else "",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = statusColor
                    )
                }
            }
        }

        Text(
            text = item.projectName,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )

        if (item.totalNum > 0) {
            SystemCapacityIndicator(
                selected = item.registrationNum,
                capacity = item.totalNum
            )
        }

        if (item.place.isNotBlank() || item.speaker.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = buildString {
                        append(item.place.ifBlank { "地点待定" })
                        if (item.speaker.isNotBlank()) append(" · 主讲人：${item.speaker}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (item.signupBeginTime.isNotBlank() || item.signupEndTime.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "报名时间：${item.signupBeginTime} ~ ${item.signupEndTime}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (item.lectureBeginTime.isNotBlank() || item.lectureEndTime.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.EventAvailable,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "举办时间：${item.lectureBeginTime} ~ ${item.lectureEndTime}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isUpcomingOrEnded) {
                SystemActionButton(
                    text = if (isPollingThis) "停止蹲守" else "查看详情 / 抢报",
                    icon = if (isPollingThis) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                    primary = isPollingThis,
                    onClick = if (isPollingThis) onTogglePolling else onCardClick
                )
            } else if (!full) {
                SystemActionButton(
                    text = "一键报名",
                    icon = Icons.Outlined.PlayArrow,
                    primary = true,
                    enabled = !syncing,
                    onClick = onSingleRegister
                )
            } else {
                SystemActionButton(
                    text = "尝试捡漏",
                    primary = false,
                    enabled = !syncing,
                    onClick = onSingleRegister
                )
                SystemActionButton(
                    text = if (isPollingThis) "停止蹲守" else "连刷抢名额",
                    icon = if (isPollingThis) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                    primary = true,
                    onClick = onTogglePolling
                )
            }
        }
    }
}

@Composable
private fun MyLectureCard(
    item: SjcxMyLectureItem,
    enabled: Boolean,
    onEditExperience: () -> Unit,
    onCancelRegistration: () -> Unit
) {
    val badgeColor = when {
        item.isRecognized || item.pendingName.contains("通过") -> Color(0xFF34C759)
        item.pendingName.contains("驳回") || item.pendingName.contains("不通过") -> Color(0xFFFF453A)
        else -> Color(0xFF0A84FF)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), RoundedCornerShape(22.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "批次 ${item.batchName.ifBlank { "1" }}" +
                    if (item.speaker.isNotBlank()) " · 主讲：${item.speaker}" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(badgeColor.copy(alpha = 0.14f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (item.isRecognized) "${item.pendingName.ifBlank { "已通过" }} · 已认学分"
                    else item.pendingName.ifBlank { "已报名" },
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = badgeColor
                )
            }
        }

        Text(
            text = item.projectName,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )

        if (item.place.isNotBlank()) {
            Text(
                text = "地点：${item.place}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (item.beginTime.isNotBlank() || item.endTime.isNotBlank()) {
            Text(
                text = "时间：${item.beginTime} ~ ${item.endTime}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (item.experience.isNotBlank()) {
            Text(
                text = "心得：${item.experience}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!item.isRecognized && item.creProjectStuId.isNotBlank()) {
                SystemActionButton(
                    text = "取消报名",
                    primary = false,
                    enabled = enabled,
                    onClick = onCancelRegistration
                )
            }
            SystemActionButton(
                text = when {
                    item.isRecognized -> "查看心得"
                    item.experience.isNotBlank() -> "修改心得"
                    else -> "填写心得"
                },
                icon = Icons.Outlined.EditNote,
                primary = true,
                enabled = enabled,
                onClick = onEditExperience
            )
        }
    }
}

@Composable
private fun ExperimentScoreCard(item: SjcxExperimentScoreItem) {
    val hasScore = item.totalScore.isNotBlank()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.expName,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = buildString {
                        append(item.courseName)
                        if (item.week > 0) append(" · 第${item.week}周")
                        if (item.teacherName.isNotBlank()) append(" · ${item.teacherName}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = if (hasScore) "${item.totalScore} 分" else "未出分",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = if (hasScore) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (item.subScoresLabel.isNotBlank()) {
            Text(
                text = item.subScoresLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CreditRecordCard(item: SjcxCreditRecordItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.projectName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = buildString {
                        if (item.standardName.isNotBlank()) append(item.standardName)
                        if (item.pendingName.isNotBlank()) append(" · ${item.pendingName}")
                        if (item.addTime.isNotBlank()) append(" · ${item.addTime.substringBefore(' ')}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "+${String.format(Locale.ROOT, "%.1f", item.credit)}",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF34C759)
            )
        }
    }
}

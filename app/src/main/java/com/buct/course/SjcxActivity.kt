package com.buct.course

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buct.course.manager.UserManager
import com.buct.course.ui.system.GlassToaster
import com.buct.course.ui.system.glassBorderColor
import com.buct.course.ui.system.glassSurfaceColor
import com.buct.course.ui.theme.CourseTheme
import com.buct.one.data.model.RefreshState
import com.buct.one.data.model.SjcxCreditRecordItem
import com.buct.one.data.model.SjcxExperimentScheduleItem
import com.buct.one.data.model.SjcxExperimentScoreItem
import com.buct.one.data.model.SjcxLectureBatchItem
import com.buct.one.data.model.SjcxMyLectureItem
import com.buct.one.data.sync.SjcxSyncService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 实践教学综合服务平台（sjcx.buct.edu.cn）独立原生界面。
 *
 * 功能：
 * 1. 实验课表（本学期所有实验排课、周次筛选、实验室房间、节次、指导教师）
 * 2. 抢讲座（当前开放报名的讲座/活动一键报名、人数上限进度、未开始/已结束活动查询）
 * 3. 我的讲座（已报名讲座列表、取消报名、在线填写/修改讲座心得体会）
 * 4. 成绩与学分（实验分项与总评成绩 + 创新学分认定明细与累计学分）
 */
class SjcxActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialTab = intent.getIntExtra(EXTRA_INITIAL_TAB, 0).coerceIn(0, 3)
        setContent {
            CourseTheme {
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
    var editingExperienceItem by remember { mutableStateOf<SjcxMyLectureItem?>(null) }
    var confirmSignupItem by remember { mutableStateOf<SjcxLectureBatchItem?>(null) }
    var confirmCancelItem by remember { mutableStateOf<SjcxMyLectureItem?>(null) }
    var feedbackMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (slice.provenance.refreshState == RefreshState.NEVER && service.hasSavedSsoSession()) {
            val res = service.refresh()
            if (res.isFailure) {
                val msg = res.exceptionOrNull()?.message.orEmpty()
                if (msg.contains("登录") || msg.contains("过期")) {
                    // 尝试利用已保存的教务密码自动静默登录
                    val user = UserManager.getInstance()
                    val pwd = user.accountPassword
                    val uname = user.username.ifBlank { user.studentId.orEmpty() }
                    if (!pwd.isNullOrEmpty() && uname.isNotBlank()) {
                        service.loginAndSync(uname, pwd.toCharArray())
                    }
                }
            }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        // 顶部栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(glassSurfaceColor())
                        .border(0.5.dp, glassBorderColor(), CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = "实践教学与讲座",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    val sub = when (slice.provenance.refreshState) {
                        RefreshState.NEVER -> "尚未同步 · 走统一身份认证免密换票"
                        RefreshState.FRESH, RefreshState.EMPTY_CONFIRMED -> {
                            val termInfo = data?.yearterm?.takeIf { it.isNotBlank() }?.let { "$it · " }.orEmpty()
                            "${termInfo}第 ${data?.currentWeek ?: 1} 周 · 更新于 ${timeFormatter.format(Date(slice.provenance.capturedAt))}"
                        }
                        RefreshState.STALE -> "历史缓存 · ${timeFormatter.format(Date(slice.provenance.capturedAt))}"
                        RefreshState.FAILED_FALLBACK -> "同步失败（已保留本地快照）"
                    }
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (slice.provenance.refreshState == RefreshState.FAILED_FALLBACK) {
                            Color(0xFFFF453A)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(glassSurfaceColor())
                        .border(0.5.dp, glassBorderColor(), CircleShape)
                        .clickable { showLoginDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Login,
                        contentDescription = "统一身份认证登录",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(glassSurfaceColor())
                        .border(0.5.dp, glassBorderColor(), CircleShape)
                        .clickable(enabled = !syncing) {
                            scope.launch {
                                feedbackMsg = null
                                val res = service.refresh()
                                if (res.isFailure) {
                                    val user = UserManager.getInstance()
                                    val pwd = user.accountPassword
                                    val uname = user.username.ifBlank { user.studentId.orEmpty() }
                                    if (!pwd.isNullOrEmpty() && uname.isNotBlank()) {
                                        val retry = service.loginAndSync(uname, pwd.toCharArray())
                                        if (retry.isFailure) {
                                            feedbackMsg = retry.exceptionOrNull()?.message
                                            showLoginDialog = true
                                        }
                                    } else {
                                        feedbackMsg = res.exceptionOrNull()?.message
                                        showLoginDialog = true
                                    }
                                } else {
                                    GlassToaster.show("实践教学数据已刷新")
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
                            contentDescription = "刷新",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 顶部四分段栏
        val tabs = listOf(
            "实验课表(${schedules.size})",
            "抢讲座(${openLectures.size})",
            "我的讲座(${myLectures.size})",
            "成绩学分"
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
            tabs.forEachIndexed { index, label ->
                val selected = selectedTab == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
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

        val bannerError = feedbackMsg ?: slice.lastError
        if (!bannerError.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
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
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (selectedTab) {
                0 -> {
                    val distinctWeeks = schedules.map { it.week }.filter { it > 0 }.distinct().sorted()
                    if (distinctWeeks.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                WeekFilterChip(
                                    label = "全部 (${schedules.size})",
                                    selected = selectedWeekFilter == 0,
                                    onClick = { selectedWeekFilter = 0 }
                                )
                                val currentW = data?.currentWeek ?: 0
                                if (currentW > 0 && currentW in distinctWeeks) {
                                    WeekFilterChip(
                                        label = "本周 (第${currentW}周)",
                                        selected = selectedWeekFilter == currentW,
                                        onClick = { selectedWeekFilter = currentW }
                                    )
                                }
                                distinctWeeks.filter { it != currentW }.take(4).forEach { w ->
                                    WeekFilterChip(
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
                            SjcxEmptyCard(
                                text = if (slice.provenance.refreshState == RefreshState.NEVER) {
                                    "尚未同步实验课表，请点击右上角刷新按钮同步。"
                                } else {
                                    "当前筛选条件下暂无实验课安排。"
                                }
                            )
                        }
                    } else {
                        items(filteredSchedules, key = { "${it.courseName}:${it.expName}:${it.week}:${it.weekDay}:${it.jcText}" }) { item ->
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
                                    "正在开放报名（共 ${openLectures.size} 场 · 本学期缺勤 ${data?.noSignCount ?: 0} 次）"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            WeekFilterChip(
                                label = if (showEndedLectures) "看开放报名 (${openLectures.size})" else "看未开始/已结束 (${endedLectures.size})",
                                selected = showEndedLectures,
                                onClick = { showEndedLectures = !showEndedLectures }
                            )
                        }
                    }

                    val activeList = if (showEndedLectures) endedLectures else openLectures
                    if (activeList.isEmpty()) {
                        item {
                            SjcxEmptyCard(
                                text = if (showEndedLectures) "暂无未开始或已结束的讲座记录。" else "当前暂无正在开放报名的讲座活动。"
                            )
                        }
                    } else {
                        items(activeList, key = { it.id }) { lecture ->
                            LectureBatchCard(
                                item = lecture,
                                allowRegister = !showEndedLectures && !syncing,
                                onRegisterClick = { confirmSignupItem = lecture }
                            )
                        }
                    }
                }

                2 -> {
                    if (myLectures.isEmpty()) {
                        item {
                            SjcxEmptyCard("暂无已报名的讲座或活动记录。")
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
                            SjcxEmptyCard("暂无实验成绩与创新学分记录。")
                        }
                    }
                }
            }
        }
    }

    confirmSignupItem?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmSignupItem = null },
            title = { Text("确认报名讲座") },
            text = {
                Text(
                    buildString {
                        append("确认报名《${target.projectName}（${target.batch}）》吗？\n\n")
                        append("地点：${target.place.ifBlank { "待定" }}\n")
                        append("活动时间：${target.lectureBeginTime} ~ ${target.lectureEndTime}\n")
                        if (target.requireReview) {
                            append("\n注：本活动报名后需等待负责人审核。")
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = target.id
                        confirmSignupItem = null
                        scope.launch {
                            val res = service.registerLecture(id)
                            res.onSuccess { msg ->
                                GlassToaster.show(msg)
                            }.onFailure { err ->
                                GlassToaster.show(err.message ?: "报名失败")
                            }
                        }
                    }
                ) {
                    Text("立即报名")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignupItem = null }) {
                    Text("取消")
                }
            }
        )
    }

    confirmCancelItem?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmCancelItem = null },
            title = { Text("取消讲座报名") },
            text = {
                Text("确认取消《${target.projectName}》的报名吗？")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = target.creProjectStuId
                        confirmCancelItem = null
                        scope.launch {
                            val res = service.cancelLectureRegistration(id)
                            res.onSuccess { msg ->
                                GlassToaster.show(msg)
                            }.onFailure { err ->
                                GlassToaster.show(err.message ?: "取消失败")
                            }
                        }
                    }
                ) {
                    Text("确认取消", color = Color(0xFFFF453A))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancelItem = null }) {
                    Text("返回")
                }
            }
        )
    }

    editingExperienceItem?.let { target ->
        var text by rememberSaveable(target.creProjectStuId) { mutableStateOf(target.experience) }
        AlertDialog(
            onDismissRequest = { if (!syncing) editingExperienceItem = null },
            title = { Text(if (target.isRecognized) "讲座心得体会（已认定学分）" else "填写/修改心得体会") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = target.projectName,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { if (!target.isRecognized) text = it },
                        readOnly = target.isRecognized,
                        label = { Text("心得体会") },
                        minLines = 6,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                if (!target.isRecognized) {
                    TextButton(
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
                                    GlassToaster.show(err.message ?: "保存失败")
                                }
                            }
                        }
                    ) {
                        Text("保存")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { editingExperienceItem = null }) {
                    Text("关闭")
                }
            }
        )
    }

    if (showLoginDialog) {
        val defaultUser = remember {
            UserManager.getInstance().username.ifBlank { UserManager.getInstance().studentId.orEmpty() }
        }
        var username by rememberSaveable { mutableStateOf(defaultUser) }
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!syncing) showLoginDialog = false },
            title = { Text("统一身份认证登录") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "实践教学平台（sjcx.buct.edu.cn）支持统一身份认证 CAS 单点登录。密码仅在内存加密换票后立即清零。",
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
                        scope.launch {
                            feedbackMsg = null
                            val res = service.loginAndSync(username, chars)
                            if (res.isSuccess) {
                                showLoginDialog = false
                                GlassToaster.show("登录成功并已同步实践教学数据")
                            } else {
                                feedbackMsg = res.exceptionOrNull()?.message ?: "登录失败"
                            }
                        }
                    }
                ) {
                    Text(if (syncing) "同步中…" else "登录并同步")
                }
            },
            dismissButton = {
                TextButton(enabled = !syncing, onClick = { showLoginDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun WeekFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else glassSurfaceColor()
            )
            .border(
                width = if (selected) 1.1.dp else 0.5.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else glassBorderColor(),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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
    allowRegister: Boolean,
    onRegisterClick: () -> Unit
) {
    val full = item.isFull
    val statusColor = if (full) Color(0xFFFF9F0A) else Color(0xFF34C759)
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

        if (allowRegister) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    enabled = !full,
                    onClick = onRegisterClick,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (full) "名额已满" else "一键报名",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
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
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!item.isRecognized && item.creProjectStuId.isNotBlank()) {
                TextButton(enabled = enabled, onClick = onCancelRegistration) {
                    Text("取消报名", color = Color(0xFFFF453A))
                }
            }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(
                enabled = enabled,
                onClick = onEditExperience,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.EditNote,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = when {
                        item.isRecognized -> "查看心得"
                        item.experience.isNotBlank() -> "修改心得"
                        else -> "填写心得"
                    },
                    style = MaterialTheme.typography.labelMedium
                )
            }
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

@Composable
private fun SjcxEmptyCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), RoundedCornerShape(22.dp))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

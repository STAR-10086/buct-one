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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buct.course.manager.AppThemeCoordinator
import com.buct.course.manager.UserManager
import com.buct.course.ui.system.GlassLoadingIndicator
import com.buct.course.ui.system.GlassPageScaffold
import com.buct.course.ui.system.GlassProgressBar
import com.buct.course.ui.system.GlassTextField
import com.buct.course.ui.system.GlassToaster
import com.buct.course.ui.system.GlassWindowHost
import com.buct.course.ui.system.SystemActionButton
import com.buct.course.ui.system.SystemCompactSegmentedControl
import com.buct.course.ui.system.SystemDialog
import com.buct.course.ui.system.SystemDivider
import com.buct.course.ui.system.SystemEmptyState
import com.buct.course.ui.system.SystemIconButton
import com.buct.course.ui.system.SystemPrimaryButton
import com.buct.course.ui.system.SystemSecondaryButton
import com.buct.course.ui.system.glassBorderColor
import com.buct.course.ui.system.glassSurfaceColor
import com.buct.course.ui.theme.CourseSelectorTheme
import com.buct.one.data.model.FitnessGender
import com.buct.one.data.model.FitnessGradeGroup
import com.buct.one.data.model.FitnessSimulationInput
import com.buct.one.data.model.FitnessSimulationResult
import com.buct.one.data.model.NationalFitnessScorer
import com.buct.one.data.model.RefreshState
import com.buct.one.data.model.TyglExamInfo
import com.buct.one.data.model.TyglExerciseColumn
import com.buct.one.data.model.TyglExerciseTerm
import com.buct.one.data.model.TyglFitnessRecord
import com.buct.one.data.model.TyglPeCourseScore
import com.buct.one.data.model.TyglProfile
import com.buct.one.data.model.TyglRunPlan
import com.buct.one.data.sync.TyglSyncService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 体质测试与体育管理平台（tygl.buct.edu.cn）原生液态玻璃界面。
 * 复用 sidportal 统一身份认证，支持历年体测成绩、课外锻炼（阳光跑）、体育课/理论考试及 2014《国标》离线算分。
 */
class TyglActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialTab = intent.getIntExtra(EXTRA_INITIAL_TAB, 0).coerceIn(0, 3)
        setContent {
            CourseSelectorTheme {
                GlassWindowHost {
                    TyglScreen(
                        initialTab = initialTab,
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_INITIAL_TAB = "extra_initial_tab"

        fun launch(context: Context, initialTab: Int = 0) {
            context.startActivity(
                Intent(context, TyglActivity::class.java).apply {
                    putExtra(EXTRA_INITIAL_TAB, initialTab.coerceIn(0, 3))
                }
            )
        }
    }
}

private data class SimFormState(
    val genderIndex: Int = 0,
    val gradeIndex: Int = 0,
    val heightText: String = "",
    val weightText: String = "",
    val lungText: String = "",
    val sprint50Text: String = "",
    val sitReachText: String = "",
    val jumpText: String = "",
    val strengthText: String = "",
    val enduranceText: String = ""
)

@Composable
private fun TyglScreen(
    initialTab: Int,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember(context) { TyglSyncService.getInstance(context) }

    val slice by service.tyglState.collectAsState()
    val syncing by service.syncing.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) } // 0=体测成绩 1=课外锻炼 2=体育课/考试 3=国标算分
    var selectedYearKey by rememberSaveable { mutableStateOf("") }
    var showLoginDialog by rememberSaveable { mutableStateOf(false) }
    var feedbackMsg by remember { mutableStateOf<String?>(null) }

    var simForm by remember { mutableStateOf(SimFormState()) }
    var simPrefilledOnce by rememberSaveable { mutableStateOf(false) }

    fun applyRecordToSimulator(profile: TyglProfile, record: TyglFitnessRecord) {
        val inp = NationalFitnessScorer.inputFromRecord(profile, record)
        simForm = SimFormState(
            genderIndex = if (inp.gender == FitnessGender.FEMALE) 1 else 0,
            gradeIndex = if (inp.gradeGroup == FitnessGradeGroup.UPPER) 1 else 0,
            heightText = inp.heightCm?.let { formatTrimmedNumber(it, 1) }.orEmpty(),
            weightText = inp.weightKg?.let { formatTrimmedNumber(it, 1) }.orEmpty(),
            lungText = inp.lungMl?.toString().orEmpty(),
            sprint50Text = inp.sprint50Sec?.let { formatTrimmedNumber(it, 1) }.orEmpty(),
            sitReachText = inp.sitReachCm?.let { formatTrimmedNumber(it, 1) }.orEmpty(),
            jumpText = inp.jumpCm?.toString().orEmpty(),
            strengthText = inp.strengthCount?.toString().orEmpty(),
            enduranceText = inp.enduranceSec?.let { NationalFitnessScorer.formatSecondsToDot(it) }.orEmpty()
        )
    }

    LaunchedEffect(Unit) {
        if ((slice.provenance.refreshState == RefreshState.NEVER ||
                slice.provenance.refreshState == RefreshState.FAILED_FALLBACK ||
                !slice.lastError.isNullOrBlank()) && service.canAutoSync()
        ) {
            service.refresh()
        }
    }

    val snapshot = slice.data
    val fitnessRecords = snapshot?.fitnessRecords.orEmpty()
    LaunchedEffect(fitnessRecords) {
        if (selectedYearKey.isBlank() && fitnessRecords.isNotEmpty()) {
            selectedYearKey = fitnessRecords.first().yearKey
        }
        if (!simPrefilledOnce && snapshot != null && fitnessRecords.isNotEmpty()) {
            applyRecordToSimulator(snapshot.profile, fitnessRecords.first())
            simPrefilledOnce = true
        }
    }
    val selectedRecord = remember(fitnessRecords, selectedYearKey) {
        fitnessRecords.firstOrNull { it.yearKey == selectedYearKey } ?: fitnessRecords.firstOrNull()
    }

    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }
    val statusText = when {
        syncing -> "正在同步体测与体育数据…"
        selectedTab == 3 && snapshot == null -> "2014《国标》算分模拟器（离线可用）"
        slice.provenance.refreshState == RefreshState.NEVER -> "尚未同步体测平台（公网直连 · 复用统一认证）"
        slice.provenance.refreshState == RefreshState.FAILED_FALLBACK ->
            "刷新失败，展示历史快照 · ${timeFmt.format(Date(slice.provenance.capturedAt))}"
        else -> "更新于 ${timeFmt.format(Date(slice.provenance.capturedAt))}"
    }

    GlassPageScaffold(
        title = "体测与体育",
        subtitle = statusText,
        onBack = onBack,
        actions = {
            SystemIconButton(
                icon = Icons.Outlined.Login,
                contentDescription = "统一身份登录",
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
                                val err = res.exceptionOrNull()?.message ?: "体测数据刷新失败"
                                feedbackMsg = err
                                GlassToaster.show(err)
                                if (err.contains("登录") || err.contains("过期") || err.contains("会话")) {
                                    showLoginDialog = true
                                }
                            } else {
                                GlassToaster.show("体测与体育数据已更新")
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

            SystemCompactSegmentedControl(
                options = listOf("体测成绩", "课外锻炼", "体育课/考试", "国标算分"),
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it }
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (selectedTab == 3) {
                    item {
                        FitnessSimulatorContent(
                            form = simForm,
                            onFormChange = { simForm = it },
                            latestRecord = selectedRecord ?: fitnessRecords.firstOrNull(),
                            onPrefillLatest = { rec ->
                                snapshot?.let { applyRecordToSimulator(it.profile, rec) }
                                GlassToaster.show("已按 ${rec.yearLabel} 实测成绩填入")
                            }
                        )
                    }
                } else if (snapshot == null) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SystemEmptyState(
                                title = if (syncing) "正在同步体测与体育平台" else "尚未同步 tygl.buct.edu.cn",
                                message = if (syncing) {
                                    "正在通过统一身份认证拉取历年体测成绩、阳光跑与体育课信息…"
                                } else {
                                    "点击右上角刷新或登录统一身份认证（tygl 支持公网直连，无需校园网）。"
                                },
                                icon = Icons.Outlined.FitnessCenter
                            )
                            SystemActionButton(
                                text = "先用离线《国标》算分模拟器 ›",
                                icon = Icons.Outlined.Calculate,
                                onClick = { selectedTab = 3 },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                } else {
                    when (selectedTab) {
                        0 -> {
                            item {
                                TyglFitnessHeroCard(
                                    profile = snapshot.profile,
                                    record = selectedRecord
                                )
                            }

                            if (fitnessRecords.size > 1) {
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        fitnessRecords.forEach { rec ->
                                            val active = rec.yearKey == selectedRecord?.yearKey
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(50))
                                                    .background(
                                                        if (active) MaterialTheme.colorScheme.primary else glassSurfaceColor()
                                                    )
                                                    .border(
                                                        0.5.dp,
                                                        if (active) MaterialTheme.colorScheme.primary else glassBorderColor(),
                                                        RoundedCornerShape(50)
                                                    )
                                                    .clickable { selectedYearKey = rec.yearKey }
                                                    .padding(horizontal = 13.dp, vertical = 7.dp)
                                            ) {
                                                val label = buildString {
                                                    append(rec.yearLabel)
                                                    if (rec.totalScoreText.isNotBlank()) {
                                                        append(" · ${rec.totalScoreText}")
                                                    }
                                                }
                                                Text(
                                                    text = label,
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                                                    ),
                                                    color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (selectedRecord != null) {
                                item {
                                    SystemActionButton(
                                        text = "用 ${selectedRecord.yearLabel} 成绩进入《国标》算分模拟",
                                        icon = Icons.Outlined.Calculate,
                                        primary = true,
                                        onClick = {
                                            applyRecordToSimulator(snapshot.profile, selectedRecord)
                                            selectedTab = 3
                                            GlassToaster.show("已填入 ${selectedRecord.yearLabel} 成绩，可拖动调整模拟")
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                if (selectedRecord.items.isNotEmpty()) {
                                    items(selectedRecord.items, key = { "${selectedRecord.yearKey}:${it.name}" }) { item ->
                                        TyglFitnessItemCard(
                                            name = item.name,
                                            rawResult = item.rawResult,
                                            score = item.score,
                                            scoreText = item.scoreText,
                                            level = item.level
                                        )
                                    }
                                } else {
                                    item {
                                        SystemEmptyState(
                                            title = "暂无单项明细",
                                            message = "该测试年度暂未录入单项体测明细。",
                                            icon = Icons.Outlined.FitnessCenter
                                        )
                                    }
                                }

                                if (selectedRecord.bonusScoreText.isNotBlank() ||
                                    selectedRecord.bonusReason.isNotBlank() ||
                                    selectedRecord.prescriptions.isNotEmpty()
                                ) {
                                    item {
                                        TyglBonusAndPrescriptionCard(selectedRecord)
                                    }
                                }
                            } else {
                                item {
                                    SystemEmptyState(
                                        title = "暂无体测记录",
                                        message = "当前账号暂无历史体质测试记录。",
                                        icon = Icons.Outlined.FitnessCenter
                                    )
                                }
                            }
                        }

                        1 -> {
                            snapshot.runPlan?.let { plan ->
                                item {
                                    TyglRunPlanCard(plan)
                                }
                            }

                            if (snapshot.exerciseTerms.isEmpty()) {
                                item {
                                    SystemEmptyState(
                                        title = "暂无课外锻炼记录",
                                        message = if (slice.provenance.refreshState == RefreshState.EMPTY_CONFIRMED) {
                                            "当前未查询到阳光跑或校内定向越野记录。"
                                        } else {
                                            "当前暂无课外锻炼学期汇总记录。"
                                        },
                                        icon = Icons.Outlined.DirectionsRun
                                    )
                                }
                            } else {
                                items(
                                    snapshot.exerciseTerms,
                                    key = { "${it.academicYear}:${it.term}" }
                                ) { term ->
                                    TyglExerciseTermCard(
                                        term = term,
                                        columns = snapshot.exerciseColumns
                                    )
                                }
                            }
                        }

                        2 -> {
                            snapshot.examInfo?.let { exam ->
                                item {
                                    TyglExamCard(exam)
                                }
                            }

                            if (snapshot.peCourses.isEmpty()) {
                                item {
                                    SystemEmptyState(
                                        title = "暂无体育课记录",
                                        message = "当前未查询到体育选课及成绩列表。",
                                        icon = Icons.Outlined.School
                                    )
                                }
                            } else {
                                items(
                                    snapshot.peCourses,
                                    key = { "${it.academicYear}:${it.term}:${it.courseName}" }
                                ) { course ->
                                    TyglPeCourseCard(course)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLoginDialog) {
        TyglSidLoginDialog(
            syncing = syncing,
            onDismiss = { showLoginDialog = false },
            onSubmit = { username, passwordChars ->
                scope.launch {
                    feedbackMsg = null
                    val res = service.loginAndSync(username, passwordChars)
                    if (res.isSuccess) {
                        showLoginDialog = false
                        GlassToaster.show("登录成功并已同步体测与体育数据")
                    } else {
                        val err = res.exceptionOrNull()?.message ?: "登录失败"
                        feedbackMsg = err
                        GlassToaster.show(err)
                    }
                }
            }
        )
    }
}

@Composable
private fun TyglFitnessHeroCard(
    profile: TyglProfile,
    record: TyglFitnessRecord?
) {
    val shape = RoundedCornerShape(24.dp)
    val levelColor = levelAccentColor(record?.level.orEmpty())
    val scoreVal = record?.totalScore
    val gradPass = scoreVal != null && scoreVal + 1e-9 >= 50.0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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
                        .background(levelColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FitnessCenter,
                        contentDescription = null,
                        tint = levelColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = buildString {
                            append(profile.studentName.ifBlank { "北化同学" })
                            if (profile.studentId.isNotBlank()) append(" · ${profile.studentId}")
                        },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val meta = listOf(
                        record?.gradeName?.takeIf { it.isNotBlank() } ?: profile.gradeName,
                        profile.className,
                        profile.collegeName
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = record?.totalScoreText?.ifBlank { "--" } ?: "--",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = levelColor
                    )
                    if (record != null && record.level.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(levelColor.copy(alpha = 0.14f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = record.level,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = levelColor
                            )
                        }
                    }
                }
                Text(
                    text = record?.yearLabel?.ifBlank { "体质测试总分" } ?: "体质测试总分",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (record != null) {
            val physicalMeta = buildList {
                if (record.heightText.isNotBlank()) add("身高 ${record.heightText}")
                if (record.weightText.isNotBlank()) add("体重 ${record.weightText}")
                if (record.updatedAt.isNotBlank()) add("更新 ${record.updatedAt.take(10)}")
            }.joinToString(" · ")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (scoreVal != null) {
                            (if (gradPass) Color(0xFF34C759) else Color(0xFFFF9F0A)).copy(alpha = 0.12f)
                        } else {
                            glassSurfaceColor()
                        }
                    )
                    .border(0.5.dp, glassBorderColor(), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = physicalMeta.ifBlank { "体质测试记录已加载" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                if (scoreVal != null) {
                    Text(
                        text = if (gradPass) "≥50 毕业达标" else "<50 需注意补测",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (gradPass) Color(0xFF34C759) else Color(0xFFFF9F0A)
                    )
                }
            }
        }
    }
}

@Composable
private fun TyglFitnessItemCard(
    name: String,
    rawResult: String,
    score: Double?,
    scoreText: String,
    level: String
) {
    val shape = RoundedCornerShape(20.dp)
    val tint = levelAccentColor(level)
    val ratio = ((score ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "实测成绩：${rawResult.ifBlank { "暂无" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (scoreText.isNotBlank()) "${scoreText}分" else "--",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = tint
                )
                if (level.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(tint.copy(alpha = 0.14f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = level,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = tint
                        )
                    }
                }
            }
        }
        GlassProgressBar(
            progress = ratio,
            tint = tint,
            height = 6.dp
        )
    }
}

@Composable
private fun TyglBonusAndPrescriptionCard(record: TyglFitnessRecord) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "加分与运动处方",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
        if (record.bonusScoreText.isNotBlank() || record.bonusReason.isNotBlank()) {
            Text(
                text = "附加加分：${record.bonusScoreText.ifBlank { "0" }} ${record.bonusReason}".trim(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        record.prescriptions.forEach { p ->
            SystemDivider(alpha = 0.35f)
            Text(
                text = "${p.diagnosis} · ${p.prescription}".trim(' ', '·'),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (p.note.isNotBlank()) {
                Text(
                    text = p.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TyglRunPlanCard(plan: TyglRunPlan) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
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
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.DirectionsRun,
                    contentDescription = null,
                    tint = Color(0xFF34C759),
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "当前跑步计划 · ${plan.projectName.ifBlank { "阳光跑" }}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (plan.projectCode.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF34C759).copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "#${plan.projectCode}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color(0xFF34C759)
                    )
                }
            }
        }
        SystemDivider(alpha = 0.35f)
        if (plan.dateRange.isNotBlank()) {
            PlanInfoRow("开放日期", plan.dateRange)
        }
        if (plan.timeRange.isNotBlank()) {
            PlanInfoRow("每日时段", plan.timeRange)
        }
        if (plan.distanceLimit.isNotBlank()) {
            PlanInfoRow("单次距离", plan.distanceLimit)
        }
        if (plan.paceLimit.isNotBlank()) {
            PlanInfoRow("有效配速", plan.paceLimit)
        }
        if (plan.weekDays.isNotBlank()) {
            PlanInfoRow("开放星期", plan.weekDays.replace(";", " "))
        }
    }
}

@Composable
private fun PlanInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun TyglExerciseTermCard(
    term: TyglExerciseTerm,
    columns: List<TyglExerciseColumn>
) {
    val shape = RoundedCornerShape(20.dp)
    val metrics = remember(term, columns) {
        if (columns.isNotEmpty()) {
            columns.map { col -> col.label to (term.countsByCode[col.code] ?: "0") }
        } else {
            term.countsByCode.entries.map { it.key to it.value }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "${term.academicYear} 学年 · 第 ${term.term} 学期",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            metrics.forEach { (label, countText) ->
                val num = countText.toDoubleOrNull() ?: 0.0
                val tint = if (num > 0.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(tint.copy(alpha = if (num > 0.0) 0.10f else 0.05f))
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "$countText 次",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = tint,
                        maxLines = 1
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun TyglExamCard(exam: TyglExamInfo) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
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
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Quiz,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "体育课理论考试",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            exam.remainingAttempts?.let { left ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "剩余 $left 次",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        val meta = listOf(
            exam.currentTerm.takeIf { it.isNotBlank() },
            exam.peClass.takeIf { it.isNotBlank() },
            exam.bestScore.takeIf { it.isNotBlank() }?.let { "最高成绩：$it" }
        ).filterNotNull().joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (exam.scheduleNotice.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFFF9F0A).copy(alpha = 0.12f))
                    .border(0.5.dp, Color(0xFFFF9F0A).copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = exam.scheduleNotice,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Color(0xFFFF9F0A)
                )
            }
        }
        if (exam.notice.isNotBlank()) {
            Text(
                text = exam.notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (exam.records.isNotEmpty()) {
            SystemDivider(alpha = 0.35f)
            exam.records.forEach { rec ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${rec.startTime} · ${rec.duration}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${rec.score} 分",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun TyglPeCourseCard(course: TyglPeCourseScore) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = course.courseName,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            Text(
                text = "${course.academicYear} 第${course.term}学期",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (course.statusMessage.isNotBlank()) {
            Text(
                text = course.statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val scoreSummary = buildList {
                if (course.termScore.isNotBlank()) add("学期得分 ${course.termScore} (${course.termLevel})")
                if (course.actualScore.isNotBlank()) add("实际得分 ${course.actualScore} (${course.actualLevel})")
            }.joinToString(" · ")
            if (scoreSummary.isNotBlank()) {
                Text(
                    text = scoreSummary,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        val extra = listOf(
            course.teacher.takeIf { it.isNotBlank() }?.let { "教师：$it" },
            course.specialNote.takeIf { it.isNotBlank() && it != "无" }?.let { "特殊情况：$it" }
        ).filterNotNull().joinToString(" · ")
        if (extra.isNotBlank()) {
            Text(
                text = extra,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FitnessSimulatorContent(
    form: SimFormState,
    onFormChange: (SimFormState) -> Unit,
    latestRecord: TyglFitnessRecord?,
    onPrefillLatest: (TyglFitnessRecord) -> Unit
) {
    val simInput = remember(form) {
        FitnessSimulationInput(
            gender = if (form.genderIndex == 1) FitnessGender.FEMALE else FitnessGender.MALE,
            gradeGroup = if (form.gradeIndex == 1) FitnessGradeGroup.UPPER else FitnessGradeGroup.LOWER,
            heightCm = NationalFitnessScorer.extractHeightCm(form.heightText),
            weightKg = NationalFitnessScorer.extractDouble(form.weightText),
            lungMl = NationalFitnessScorer.extractDouble(form.lungText)?.let { kotlin.math.round(it).toInt() },
            sprint50Sec = NationalFitnessScorer.extractDouble(form.sprint50Text),
            sitReachCm = NationalFitnessScorer.extractDouble(form.sitReachText),
            jumpCm = NationalFitnessScorer.extractDouble(form.jumpText)?.let { kotlin.math.round(it).toInt() },
            strengthCount = NationalFitnessScorer.extractDouble(form.strengthText)?.let { kotlin.math.round(it).toInt() },
            enduranceSec = NationalFitnessScorer.parseEnduranceToSeconds(form.enduranceText)
        )
    }
    val result: FitnessSimulationResult = remember(simInput) {
        NationalFitnessScorer.evaluate(simInput)
    }
    val levelColor = levelAccentColor(result.level)
    val isMale = form.genderIndex == 0
    val shape = RoundedCornerShape(22.dp)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 标准组别切换
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                SystemCompactSegmentedControl(
                    options = listOf("男生", "女生"),
                    selectedIndex = form.genderIndex,
                    onSelect = { onFormChange(form.copy(genderIndex = it)) }
                )
            }
            Box(modifier = Modifier.weight(1.3f)) {
                SystemCompactSegmentedControl(
                    options = listOf("大一/大二", "大三/大四"),
                    selectedIndex = form.gradeIndex,
                    onSelect = { onFormChange(form.copy(gradeIndex = it)) }
                )
            }
        }

        // 实时算分总览卡片
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(glassSurfaceColor())
                .border(0.5.dp, glassBorderColor(), shape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "《国标》预测总分",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = String.format(Locale.ROOT, "%.1f", result.finalTotal),
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = levelColor
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(levelColor.copy(alpha = 0.14f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = result.level,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = levelColor
                            )
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "标准 ${String.format(Locale.ROOT, "%.1f", result.standardTotal)} + 加分 ${result.bonusTotal}",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = buildString {
                            result.bmi?.let { append("BMI ${String.format(Locale.ROOT, "%.1f", it)} · ") }
                            append(if (result.finalTotal + 1e-9 >= 50.0) "≥50 毕业达标" else "<50 未达毕业线")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (result.finalTotal + 1e-9 >= 50.0) Color(0xFF34C759) else Color(0xFFFF453A)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (latestRecord != null) {
                    SystemActionButton(
                        text = "填入实测成绩 (${latestRecord.yearLabel})",
                        icon = Icons.Outlined.FitnessCenter,
                        primary = true,
                        onClick = { onPrefillLatest(latestRecord) },
                        modifier = Modifier.weight(1f)
                    )
                }
                SystemActionButton(
                    text = "清空",
                    onClick = {
                        onFormChange(
                            SimFormState(
                                genderIndex = form.genderIndex,
                                gradeIndex = form.gradeIndex
                            )
                        )
                    }
                )
            }
        }

        // 输入区卡片
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(glassSurfaceColor())
                .border(0.5.dp, glassBorderColor(), shape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "各项成绩录入（长跑支持 4.30 / 4:30 / 4'30\"）",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SimNumberField(
                    label = "身高 (cm)",
                    value = form.heightText,
                    placeholder = "如 180.6",
                    onValueChange = { onFormChange(form.copy(heightText = it)) },
                    modifier = Modifier.weight(1f)
                )
                SimNumberField(
                    label = "体重 (kg)",
                    value = form.weightText,
                    placeholder = "如 71.7",
                    onValueChange = { onFormChange(form.copy(weightText = it)) },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SimNumberField(
                    label = "肺活量 (ml)",
                    value = form.lungText,
                    placeholder = if (isMale) "及格 ≥3100" else "及格 ≥2000",
                    onValueChange = { onFormChange(form.copy(lungText = it)) },
                    modifier = Modifier.weight(1f)
                )
                SimNumberField(
                    label = "50米跑 (秒)",
                    value = form.sprint50Text,
                    placeholder = "如 8.1",
                    onValueChange = { onFormChange(form.copy(sprint50Text = it)) },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SimNumberField(
                    label = "坐位体前屈 (cm)",
                    value = form.sitReachText,
                    placeholder = "如 10.0",
                    onValueChange = { onFormChange(form.copy(sitReachText = it)) },
                    modifier = Modifier.weight(1f)
                )
                SimNumberField(
                    label = "立定跳远 (cm)",
                    value = form.jumpText,
                    placeholder = if (isMale) "如 215" else "如 165",
                    onValueChange = { onFormChange(form.copy(jumpText = it)) },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SimNumberField(
                    label = if (isMale) "引体向上 (个)" else "1分钟仰卧起坐 (个)",
                    value = form.strengthText,
                    placeholder = if (isMale) "及格 10~11" else "及格 26~27",
                    onValueChange = { onFormChange(form.copy(strengthText = it)) },
                    modifier = Modifier.weight(1f)
                )
                SimNumberField(
                    label = if (isMale) "1000米跑 (分.秒)" else "800米跑 (分.秒)",
                    value = form.enduranceText,
                    placeholder = if (isMale) "如 4.30" else "如 4.18",
                    onValueChange = { onFormChange(form.copy(enduranceText = it)) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 单项实时得分与提分建议
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(glassSurfaceColor())
                .border(0.5.dp, glassBorderColor(), shape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "分项得分与下一档差值（${if (isMale) "男生" else "女生"} · ${if (form.gradeIndex == 1) "大三/大四" else "大一/大二"}）",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            result.items.forEachIndexed { idx, item ->
                if (idx > 0) SystemDivider(alpha = 0.25f)
                val itemTint = if (item.level == "未填") MaterialTheme.colorScheme.onSurfaceVariant else levelAccentColor(item.level)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${item.name}（${item.weightPercent}%）",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = buildString {
                                    append("${item.score}分")
                                    if (item.bonus > 0) append(" +${item.bonus}加分")
                                },
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = itemTint
                            )
                            Text(
                                text = item.level,
                                style = MaterialTheme.typography.labelSmall,
                                color = itemTint
                            )
                        }
                    }
                    GlassProgressBar(
                        progress = (item.score / 100f).coerceIn(0f, 1f),
                        tint = itemTint,
                        height = 5.dp
                    )
                    Text(
                        text = item.nextTargetHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SimNumberField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        GlassTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            singleLine = true,
            minHeight = 44.dp,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Next
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun TyglSidLoginDialog(
    syncing: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (username: String, passwordChars: CharArray) -> Unit
) {
    val context = LocalContext.current
    val userManager = remember { UserManager.getInstance().apply { init(context) } }
    var username by remember { mutableStateOf(userManager.username.ifBlank { userManager.studentId.orEmpty() }) }
    var password by remember { mutableStateOf("") }

    fun submit() {
        if (syncing || username.isBlank() || password.isEmpty()) return
        val chars = password.toCharArray()
        password = ""
        onSubmit(username.trim(), chars)
    }

    SystemDialog(
        onDismissRequest = { if (!syncing) onDismiss() },
        title = {
            Text(
                text = "统一身份认证登录",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        confirmButton = {
            SystemPrimaryButton(
                text = if (syncing) "登录中…" else "登录并同步",
                enabled = !syncing && username.isNotBlank() && password.isNotEmpty(),
                onClick = ::submit,
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            SystemSecondaryButton(
                text = "取消",
                enabled = !syncing,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "登录 sidportal.buct.edu.cn 以获取体质测试成绩、阳光跑计划及体育课信息（凭据仅用于本次换票，学号密码不落盘）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            GlassTextField(
                value = username,
                onValueChange = { username = it },
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
                singleLine = true,
                enabled = !syncing,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun levelAccentColor(level: String): Color = when {
    level.contains("优秀") || level.contains("正常") -> Color(0xFF34C759)
    level.contains("良好") -> Color(0xFF0A84FF)
    level.contains("及格") || level.contains("低体重") || level.contains("超重") -> Color(0xFFFF9F0A)
    level.contains("不及格") || level.contains("肥胖") -> Color(0xFFFF453A)
    else -> Color(0xFF0A84FF)
}

private fun formatTrimmedNumber(value: Double, decimals: Int): String {
    val formatted = String.format(Locale.ROOT, "%.${decimals}f", value)
    return if (formatted.endsWith(".0")) formatted.dropLast(2) else formatted
}

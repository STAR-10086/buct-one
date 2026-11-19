package com.buct.course.ui.screen

import com.buct.course.ui.theme.moduleEntrance
import com.buct.course.ui.theme.ModuleMotion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buct.course.ui.system.GlassSegmentedBar
import com.buct.course.ui.system.PagePadding
import com.buct.course.ui.system.SystemCard
import com.buct.course.ui.system.SystemEmptyState
import com.buct.course.ui.system.SystemLoadingState
import com.buct.course.ui.system.SystemSectionHeader
import com.buct.course.ui.system.SystemStatStrip
import com.buct.course.ui.system.SystemStatusBadge
import com.buct.course.ui.system.SystemTone
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.buct.course.ui.system.LocalAppBackdrop
import com.buct.course.ui.system.LocalAppOverlayBottomInset
import com.buct.course.ui.system.SystemDivider
import com.buct.course.ui.system.SystemPicker
import com.buct.course.ui.system.isBackdropSupported
import com.buct.course.ui.system.rememberGlassAccessibilityMode
import com.buct.course.ui.system.reportNoticeAnchor
import com.buct.course.ui.theme.NeuPrimary
import com.buct.course.ui.theme.SemanticDanger
import com.buct.course.ui.theme.SemanticInfo
import com.buct.course.ui.theme.SemanticSuccess
import com.buct.course.ui.theme.SemanticWarning

data class GradeItemUi(
    val courseName: String,
    val grade: String,
    val credits: String,
    val gpa: String,
    val courseType: String,
    val year: String = "",
    val term: String = "",
    val college: String = "",
    val courseCode: String = "",
    val teachingClass: String = "",
    val jxbId: String = "",
    val jobId: String = "",
    val detail: String = ""
)

data class ExamItemUi(
    val courseName: String,
    val examTime: String,
    val location: String,
    val seatNumber: String,
    val examName: String,
    val teacher: String,
    /** 自建考试才有 id；教务同步来的为空。 */
    val id: String = "",
    /** true = 用户自己添加的考试。 */
    val isCustom: Boolean = false,
    /** 自建考试的开始时间戳，用于排序与「已结束」判断。 */
    val startAt: Long = 0L
)

/** 是否已经考完：考试当天仍显示，第二天起算结束。 */
private fun ExamItemUi.isPastExam(): Boolean {
    val millis = com.buct.course.schedule.ScheduleDates.parseDateMillis(examTime) ?: return false
    return millis + 24 * 60 * 60 * 1000L < System.currentTimeMillis()
}

/**
 * 考试类型标签归一化。
 *
 * 教务返回的「考试名称」格式不统一：有的是「考试」，有的是
 * 「期末考试（学期中考试周）」——括号里还带「学期中」，会让「期中」判断误命中，
 * 而且长标签会把课程名挤成省略号。这里先去掉括号补充说明，再压成 2–4 个字。
 */
fun examBadgeLabel(raw: String, examWeek: Int? = null): String {
    val core = raw.trim().substringBefore("（").substringBefore("(").trim()
    if (core.isEmpty()) return "考试"
    return when {
        core.contains("补考") -> "补考"
        core.contains("重修") -> "重修"
        core.contains("缓考") -> "缓考"
        // 教务偶尔把学期末的期末考试写成「期中考试（学期中考试周）」——
        // 但时间落在第 18 周及以后的其实就是期末，按考试时间纠正
        examWeek != null && examWeek >= 18 && core.contains("期中") -> "期末"
        core.contains("期末") -> "期末"
        core.contains("期中") -> "期中"
        else -> if (core.length <= 4) core else "考试"
    }
}

fun examBadgeTone(raw: String, examWeek: Int? = null): SystemTone = when (examBadgeLabel(raw, examWeek)) {
    "期中", "补考", "重修", "缓考" -> SystemTone.Warning
    else -> SystemTone.Info
}

/**
 * 把本地自建考试并入教务考试列表。
 * 时间格式刻意与教务保持一致（yyyy-MM-dd(HH:mm-HH:mm)），
 * 这样周次换算、日视图、小组件都能直接复用现成逻辑。
 */
fun mergeExamsWithLocal(
    context: android.content.Context,
    remote: List<ExamItemUi>
): List<ExamItemUi> {
    val custom = com.buct.course.manager.CustomExamStore.list(context).filterNot { it.hidden }
    if (custom.isEmpty()) return remote
    val startFormat = java.text.SimpleDateFormat("yyyy-MM-dd(HH:mm", java.util.Locale.CHINA)
    val endFormat = java.text.SimpleDateFormat("HH:mm)", java.util.Locale.CHINA)
    val mapped = custom.map { exam ->
        val examTime = startFormat.format(java.util.Date(exam.startAt)) + "-" +
            endFormat.format(java.util.Date(if (exam.endAt > exam.startAt) exam.endAt else exam.startAt + 2 * 60 * 60 * 1000L))
        ExamItemUi(
            courseName = exam.course.ifBlank { exam.title },
            examTime = examTime,
            location = exam.location,
            seatNumber = exam.seat,
            examName = exam.title,
            teacher = "",
            id = exam.id,
            isCustom = true,
            startAt = exam.startAt
        )
    }
    return (remote + mapped).sortedBy { item ->
        com.buct.course.schedule.ScheduleDates.parseDateMillis(item.examTime) ?: Long.MAX_VALUE
    }
}

/**
 * 考试安排跨路由共享缓存：成绩页（GradesRoute）加载后写入，
 * 课表页（ScheduleRoute）读取以在日视图/周视图展示当天/本周考试。
 */object ExamScheduleSharedCache {
    @Volatile var examList: List<ExamItemUi> = emptyList()
    @Volatile var examSemester: String = ""
    @Volatile var updatedAt: Long = 0L
}

/**
 * 北化教务处官方 GPA 规则（https://jiaowuchu.buct.edu.cn/2024/0322/c614a189338/page.htm）：
 * 素质教育课程、两级制计分课程、体育课程不参与平均学分绩点（GPA）的计算，其他课程都要计入。
 */
internal fun isGpaExcludedGrade(grade: GradeItemUi): Boolean {
    // 1. 素质教育课程（课程性质含"素质教育"，如"素质教育课程选修"）
    if (grade.courseType.contains("素质教育")) return true
    // 2. 体育课程（课程代码 PHE 开头，或课程名含"体育"）
    if (grade.courseCode.startsWith("PHE", ignoreCase = true)) return true
    if (grade.courseName.contains("体育")) return true
    // 3. 两级制计分课程（成绩只记 合格/不合格/P/U/Pass，无绩点）
    val g = grade.grade.trim()
    if (g == "合格" || g == "不合格" ||
        g.equals("P", ignoreCase = true) || g.equals("U", ignoreCase = true) ||
        g.equals("Pass", ignoreCase = true) || g.equals("Unpass", ignoreCase = true) ||
        g.equals("通过", ignoreCase = true) || g.equals("不通过", ignoreCase = true)
    ) return true
    return false
}

internal fun semesterAverageGpa(grades: List<GradeItemUi>): String {
    // 按北化官方规则：素质教育课程、两级制计分课程、体育课程不参与 GPA
    val graded = grades.filterNot { isGpaExcludedGrade(it) }.mapNotNull { grade ->
        val credit = grade.credits.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return@mapNotNull null
        val point = grade.gpa.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
        credit to point
    }
    val credits = graded.sumOf { it.first }
    return if (credits > 0) java.lang.String.format(java.util.Locale.ROOT, "%.2f", graded.sumOf { it.first * it.second } / credits) else "--"
}

data class OverallStatsUi(
    val gpa: String,
    val credits: String,
    val courseCount: Int,
    val excellent: Int,
    val good: Int,
    val medium: Int,
    val pass: Int,
    val notGraded: Int = 0,
    val fail: Int = 0
)

/**
 * 成绩分布档位：0=优, 1=良, 2=中, 3=及格, 4=不及格, 5=未出分
 */
enum class GradeBucket(val label: String, val shortLabel: String, val tone: SystemTone) {
    Excellent("优秀 (≥90 / A)", "优", SystemTone.Success),
    Good("良好 (80-89 / B)", "良", SystemTone.Info),
    Medium("中等 (70-79 / C)", "中", SystemTone.Warning),
    Pass("及格 (60-69 / D / 合格)", "及格", SystemTone.Warning),
    Fail("不及格 (<60 / F / 不合格)", "不及格", SystemTone.Danger),
    NotGraded("未出分 / 在修", "未出分", SystemTone.Neutral)
}

fun classifyGradeBucket(gradeRaw: String): GradeBucket {
    val gradeText = gradeRaw.trim()
    if (gradeText.isEmpty() || gradeText == "--" || gradeText.equals("null", ignoreCase = true)) {
        return GradeBucket.NotGraded
    }
    val numeric = gradeText.toDoubleOrNull()
        ?: gradeText.replace("[^0-9.]".toRegex(), "").toDoubleOrNull()?.takeIf {
            gradeText.any { ch -> ch.isDigit() }
        }
    if (numeric != null) {
        return when {
            numeric >= 90.0 -> GradeBucket.Excellent
            numeric >= 80.0 -> GradeBucket.Good
            numeric >= 70.0 -> GradeBucket.Medium
            numeric >= 60.0 -> GradeBucket.Pass
            else -> GradeBucket.Fail
        }
    }
    return when {
        gradeText.contains("优秀") || gradeText == "优" || gradeText.startsWith("A", ignoreCase = true) ->
            GradeBucket.Excellent
        gradeText.contains("良好") || gradeText == "良" || gradeText.startsWith("B", ignoreCase = true) ->
            GradeBucket.Good
        gradeText.contains("中等") || gradeText == "中" || gradeText.startsWith("C", ignoreCase = true) ->
            GradeBucket.Medium
        gradeText.contains("不及格") || gradeText.contains("不合格") || gradeText.contains("未通过") ||
            gradeText.contains("不通过") || gradeText.equals("F", ignoreCase = true) ||
            gradeText.equals("U", ignoreCase = true) || gradeText.equals("Unpass", ignoreCase = true) ->
            GradeBucket.Fail
        gradeText.contains("合格") || gradeText.contains("及格") || gradeText.contains("通过") ||
            gradeText.startsWith("D", ignoreCase = true) ||
            gradeText.equals("P", ignoreCase = true) || gradeText.equals("Pass", ignoreCase = true) ->
            GradeBucket.Pass
        else -> GradeBucket.NotGraded
    }
}

data class CurriculumCourseUi(
    val courseCode: String,
    val courseName: String,
    val credits: String,
    val studyStatus: String, // "4"=已修通过, "1"=在修, "2"=未通过, "3"=未修
    val grade: String = "",
    val maxGrade: String = "",
    val suggestedYear: String = "",
    val suggestedTerm: String = "",
    val courseCategory: String = ""
) {
    val isPassed: Boolean get() = studyStatus == "4"
    val isStudying: Boolean get() = studyStatus == "1"
    val isFailed: Boolean get() = studyStatus == "2"
    val isUnstudied: Boolean get() = studyStatus != "4"
}

data class CurriculumNodeUi(
    val id: String,
    val parentId: String,
    val name: String,
    val earnedCredits: Double,
    val requiredCredits: Double,
    val passed: Boolean,
    val isLeaf: Boolean,
    val courses: List<CurriculumCourseUi> = emptyList()
) {
    val remainingCredits: Double
        get() = (requiredCredits - earnedCredits).coerceAtLeast(0.0)
}

// ── 顶栏折叠几何 ────────────────────────────────────────────────
// 与课表页同一条铁律：展开态与折叠态的高度【差】必须等于折叠行程，
// 于是顶栏收缩与内容上滚 1:1 对消、全程跟手。脱钩就会互相追赶。
//
// 总高不再写死：标题块要跟着系统字体缩放走，三个高度全部由下面这组 token 推导，
// 那条恒等式因此是结构性的（见 GradesHeaderMetrics.travel），不靠人肉对账。

private class GradesHeaderMetrics {
    var expanded by mutableStateOf(140.dp)
    var collapsed by mutableStateOf(68.dp)
    val travel: Dp get() = (expanded - collapsed).coerceAtLeast(1.dp)
}

@Composable
private fun rememberGradesHeaderMetrics(): GradesHeaderMetrics = remember(LocalDensity.current) { GradesHeaderMetrics() }

@Composable
fun GradesScreen(
    currentTab: Int,
    onTabChange: (Int) -> Unit,
    semesterGrades: List<GradeItemUi>,
    semesters: List<String>,
    currentSemester: String,
    onSemesterChange: (String) -> Unit,
    semesterIsLoading: Boolean,
    overallGrades: List<GradeItemUi>,
    overallStats: OverallStatsUi,
    overallIsLoading: Boolean,
    officialOverallGpa: String = "",
    examList: List<ExamItemUi>,
    examSemesters: List<String>,
    currentExamSemester: String,
    onExamSemesterChange: (String) -> Unit,
    examIsLoading: Boolean,
    onRefresh: () -> Unit,
    onAddExam: (() -> Unit)? = null,
    onEditExam: ((ExamItemUi) -> Unit)? = null,
    onExportGrades: (List<GradeItemUi>) -> Unit = {},
    semesterError: String = "",
    overallError: String = "",
    examError: String = "",
    curriculumNodes: List<CurriculumNodeUi> = emptyList(),
    curriculumIsLoading: Boolean = false,
    curriculumError: String = ""
) {
    val tabTitles = listOf("学期", "总体", "培养", "考试")
    val isRefreshing = semesterIsLoading || overallIsLoading || curriculumIsLoading || examIsLoading
    val subtitle = when (currentTab) {
        0 -> "${semesterGrades.size} 门课程"
        1 -> "累计成绩与分布概览"
        2 -> "培养方案与学分修读进度"
        else -> "近期考试安排"
    }

    // 每个 tab 一份滚动位置：共用一份会让切 tab 时另一页的位置被顶掉，
    // 折叠进度也跟着跳。顶栏读当前 tab 那一份。
    val semesterListState = rememberLazyListState()
    val overallListState = rememberLazyListState()
    val curriculumListState = rememberLazyListState()
    val examListState = rememberLazyListState()
    val activeListState = when (currentTab) {
        0 -> semesterListState
        1 -> overallListState
        2 -> curriculumListState
        else -> examListState
    }
    val metrics = rememberGradesHeaderMetrics()
    val travelPx = with(LocalDensity.current) { metrics.travel.toPx() }
    val headerCollapse by remember(travelPx, activeListState) {
        derivedStateOf {
            // 第 0 项就是首屏那一块内容，它比行程高得多，所以只看它的偏移；
            // 一旦滚过第 0 项，直接判定为完全折叠。
            if (activeListState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (activeListState.firstVisibleItemScrollOffset / travelPx).coerceIn(0f, 1f)
            }
        }
    }

    // 内容的捕获层。顶栏玻璃采样「壁纸 + 这一层」，于是列表从玻璃条底下穿过时
    // 会被折射；顶栏本身不在这一层内，不构成自采样。
    val wallpaperBackdrop = LocalAppBackdrop.current
    val contentBackdrop = if (wallpaperBackdrop != null && isBackdropSupported()) {
        rememberLayerBackdrop()
    } else {
        null
    }
    val headerSampleBackdrop = if (wallpaperBackdrop != null && contentBackdrop != null) {
        rememberCombinedBackdrop(wallpaperBackdrop, contentBackdrop)
    } else {
        null
    }

    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // 【常量】top inset：Scaffold 的 topPadding 随顶栏一起缩，喂给滚动容器会让
    // 内容走两倍行程（课表页踩过）。这里固定按展开态高度留白。
    val contentTopInset = statusBarHeight + metrics.expanded
    val contentBottomInset = LocalAppOverlayBottomInset.current + 24.dp

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            // topBar slot 只测量单个子项，内联通知必须与顶栏在同一个 Column 里
            Column(modifier = Modifier.moduleEntrance(0).reportNoticeAnchor()) {
                GradesHeader(
                    subtitle = subtitle,
                    tabTitles = tabTitles,
                    currentTab = currentTab,
                    onTabChange = onTabChange,
                    collapseFraction = headerCollapse,
                    metrics = metrics,
                    sampleBackdrop = headerSampleBackdrop,
                    shareEnabled = when (currentTab) {
                        0 -> semesterGrades.isNotEmpty()
                        1 -> overallGrades.isNotEmpty()
                        else -> false
                    },
                    showShare = currentTab == 0 || currentTab == 1,
                    isRefreshing = isRefreshing,
                    onShare = {
                        val grades = if (currentTab == 0) semesterGrades else overallGrades
                        if (grades.isNotEmpty()) onExportGrades(grades)
                    },
                    onRefresh = onRefresh
                )
            }
        }
    ) { padding ->
        // 内容铺满整屏、从顶栏底下穿过；留白由各自的 contentPadding 负责（见上）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding)
                .then(
                    if (contentBackdrop != null) {
                        Modifier.layerBackdrop(contentBackdrop)
                    } else {
                        Modifier
                    }
                )
        ) {
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "grades_tab_content"
            ) { tab ->
                when (tab) {
                    0 -> SemesterGradesContent(
                        grades = semesterGrades,
                        semesters = semesters,
                        currentSemester = currentSemester,
                        onSemesterChange = onSemesterChange,
                        isLoading = semesterIsLoading,
                        error = semesterError,
                        listState = semesterListState,
                        officialOverallGpa = officialOverallGpa,
                        exams = examList,
                        topInset = contentTopInset,
                        bottomInset = contentBottomInset
                    )

                    1 -> OverallGradesContent(
                        grades = overallGrades,
                        stats = overallStats,
                        isLoading = overallIsLoading,
                        error = overallError,
                        listState = overallListState,
                        exams = examList,
                        topInset = contentTopInset,
                        bottomInset = contentBottomInset
                    )

                    2 -> CurriculumPlanContent(
                        nodes = curriculumNodes,
                        isLoading = curriculumIsLoading,
                        error = curriculumError,
                        listState = curriculumListState,
                        topInset = contentTopInset,
                        bottomInset = contentBottomInset
                    )

                    else -> ExamScheduleContent(
                        exams = examList,
                        semesters = examSemesters,
                        currentSemester = currentExamSemester,
                        onSemesterChange = onExamSemesterChange,
                        isLoading = examIsLoading,
                        error = examError,
                        listState = examListState,
                        topInset = contentTopInset,
                        bottomInset = contentBottomInset,
                        onAddExam = onAddExam,
                        onEditExam = onEditExam
                    )
                }
            }
        }
    }
}

/**
 * 成绩页顶栏：上划收拢成一条悬浮玻璃胶囊。
 *
 * 展开态是 iOS 的大标题（直接浮在内容上）+ 一条**全宽原尺寸**的选择栏；折叠态只留选择栏。
 * 三个标签本身就说明了在哪一页，小标题是多余信息。
 *
 * 两枚动作钮**不属于任何一行**——它们是这个 Box 的第二个子节点，钉在右上角。理由有两条：
 * 1. 放进选择栏那一行就要和它抢宽度，三段中文标签会被挤成一条窄带；
 * 2. 标题块整块收掉之后，"标题行右上角"与"选择栏右侧"这两个位置的 y 几乎重合，
 *    所以芯片全程只走几 dp——真正在动的是选择栏升上来、以及它右侧连续让出的那一段。
 */
@Composable
private fun GradesHeader(
    subtitle: String, tabTitles: List<String>, currentTab: Int, onTabChange: (Int) -> Unit,
    collapseFraction: Float, metrics: GradesHeaderMetrics, sampleBackdrop: Backdrop?,
    showShare: Boolean, shareEnabled: Boolean, isRefreshing: Boolean,
    onShare: () -> Unit, onRefresh: () -> Unit
) {
    MeasuredGradesHeader(subtitle, tabTitles, currentTab, onTabChange, collapseFraction, sampleBackdrop,
        showShare, shareEnabled, isRefreshing, onShare, onRefresh) { expanded, collapsed ->
        metrics.expanded = expanded
        metrics.collapsed = collapsed
    }
}
@Composable
private fun OverallGradesContent(
    grades: List<GradeItemUi>,
    stats: OverallStatsUi,
    isLoading: Boolean,
    error: String,
    listState: LazyListState,
    exams: List<ExamItemUi>,
    topInset: Dp,
    bottomInset: Dp
) {
    var selectedBucketName by rememberSaveable { mutableStateOf("") }
    val selectedBucket = remember(selectedBucketName) {
        GradeBucket.entries.firstOrNull { it.name == selectedBucketName }
    }
    val visibleGrades = remember(grades, selectedBucket) {
        if (selectedBucket == null) grades
        else grades.filter { classifyGradeBucket(it.grade) == selectedBucket }
    }
    val rowKeys = remember(visibleGrades) { gradeRowKeys(visibleGrades) }
    when {
        isLoading && grades.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = topInset),
                contentAlignment = Alignment.Center
            ) {
                SystemLoadingState(text = "正在加载总体成绩…")
            }
        }

        grades.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = topInset),
                contentAlignment = Alignment.Center
            ) {
                SystemEmptyState(
                    title = if (error.isNotBlank()) "总体成绩加载失败" else "暂无总体成绩",
                    message = error.ifBlank { "点击刷新获取最新成绩" }
                )
            }
        }

        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(
                    start = PagePadding,
                    end = PagePadding,
                    top = topInset,
                    bottom = bottomInset
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Column(Modifier.moduleEntrance(1), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        GradeRefreshStatus(isLoading, error)
                        SystemStatStrip(
                            items = listOf(
                                "累计绩点" to stats.gpa.ifBlank { "--" },
                                "已修学分" to stats.credits.ifBlank { "0" },
                                "总课程" to stats.courseCount.toString()
                            )
                        )
                        GradeDistributionCard(
                            stats = stats,
                            selectedBucket = selectedBucket,
                            onBucketClick = { bucket ->
                                selectedBucketName = if (selectedBucket == bucket) "" else bucket.name
                            }
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SystemSectionHeader(
                                title = if (selectedBucket == null) "课程明细" else "筛选：${selectedBucket.label}",
                                subtitle = if (selectedBucket == null) {
                                    "共 ${grades.size} 门课程 · 点击上方分布可按档位筛选"
                                } else {
                                    "共 ${visibleGrades.size} 门（总 ${grades.size} 门）"
                                },
                                modifier = Modifier.weight(1f)
                            )
                            if (selectedBucket != null) {
                                GlassFilterChip(
                                    label = "全部 (${grades.size})",
                                    selected = false,
                                    onClick = { selectedBucketName = "" },
                                    compact = true
                                )
                            }
                        }
                    }
                }

                if (visibleGrades.isEmpty()) {
                    item {
                        SystemEmptyState(
                            title = "该档位下暂无课程",
                            message = "点击上方分布标签可切换或恢复查看全部课程"
                        )
                    }
                } else {
                    items(visibleGrades.size, key = { rowKeys[it] }, contentType = { "grade" }) { index ->
                        GradeItemRow(item = visibleGrades[index], exams = exams)
                    }
                }
            }
        }
    }
}

@Composable
private fun SemesterGradesContent(
    grades: List<GradeItemUi>,
    semesters: List<String>,
    currentSemester: String,
    onSemesterChange: (String) -> Unit,
    isLoading: Boolean,
    error: String,
    listState: LazyListState,
    officialOverallGpa: String,
    exams: List<ExamItemUi>,
    topInset: Dp,
    bottomInset: Dp
) {
    val totalCredits = remember(grades) { grades.filterNot { isGpaExcludedGrade(it) }.sumOf { it.credits.toDoubleOrNull() ?: 0.0 } }
    val averageGpa = remember(grades, currentSemester, officialOverallGpa) {
        // 学期「全部」直接展示官方绩点（N105515 GPA），避免合并接口绩点字段口径差异
        if (currentSemester == "全部" && officialOverallGpa.isNotBlank()
            && officialOverallGpa != "0" && officialOverallGpa != "0.0") {
            officialOverallGpa
        } else {
            semesterAverageGpa(grades)
        }
    }
    val rowKeys = remember(grades) { gradeRowKeys(grades) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = PagePadding,
            end = PagePadding,
            top = topInset,
            bottom = bottomInset
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(Modifier.moduleEntrance(1), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SemesterSelector(
                    semesters = semesters,
                    currentSemester = currentSemester,
                    onSemesterChange = onSemesterChange
                )

                when {
                    isLoading && grades.isEmpty() -> SystemLoadingState(text = "正在加载学期成绩…")
                    error.isNotBlank() && grades.isEmpty() -> SystemEmptyState(title = "学期成绩加载失败", message = error)
                    grades.isEmpty() -> SystemEmptyState(
                        title = "暂无学期成绩",
                        message = if (currentSemester.isBlank()) {
                            "请选择学期查看成绩"
                        } else {
                            "$currentSemester 暂无成绩记录"
                        }
                    )
                    else -> {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            GradeRefreshStatus(isLoading, error)
                            SystemStatStrip(
                                items = listOf(
                                    "平均绩点" to averageGpa,
                                    "总学分" to String.format("%.1f", totalCredits),
                                    "课程数" to grades.size.toString()
                                )
                            )
                            SystemSectionHeader(
                                title = "课程明细",
                                subtitle = null
                            )
                        }
                    }
                }
            }
        }

        if (grades.isNotEmpty()) {
            items(grades.size, key = { rowKeys[it] }, contentType = { "grade" }) { index ->
                GradeItemRow(item = grades[index], exams = exams)
            }
        }
    }
}

@Composable
private fun GradeRefreshStatus(isLoading: Boolean, error: String) {
    if (isLoading) {
        androidx.compose.material3.LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(2.dp)
        )
    } else if (error.isNotBlank()) {
        Text(error, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SemesterSelector(
    semesters: List<String>,
    currentSemester: String,
    onSemesterChange: (String) -> Unit
) {
    val selectedIndex = semesters.indexOf(currentSemester).takeIf { it >= 0 }
    SystemPicker(
        options = semesters,
        selectedIndex = selectedIndex,
        onSelect = { index -> onSemesterChange(semesters[index]) },
        modifier = Modifier.fillMaxWidth(),
        label = "学期",
        placeholder = "选择学期"
    )
}

@Composable
private fun GradeDistributionCard(
    stats: OverallStatsUi,
    selectedBucket: GradeBucket? = null,
    onBucketClick: ((GradeBucket) -> Unit)? = null
) {
    SystemCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant
    ) {
        SystemSectionHeader(
            title = "成绩分布",
            subtitle = if (selectedBucket != null) "已筛选：${selectedBucket.shortLabel}（再次点击取消）" else "点击任一档位可筛选对应课程"
        )

        GlassSegmentedBar(
            segments = listOf(
                stats.excellent.toFloat() to SemanticSuccess,
                stats.good.toFloat() to SemanticInfo,
                stats.medium.toFloat() to SemanticWarning,
                stats.pass.toFloat() to Color(0xFFB26A00),
                stats.fail.toFloat() to SemanticDanger
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DistributionItem(
                label = "优",
                count = stats.excellent,
                tone = SystemTone.Success,
                selected = selectedBucket == GradeBucket.Excellent,
                onClick = { onBucketClick?.invoke(GradeBucket.Excellent) },
                modifier = Modifier.weight(1f)
            )
            DistributionItem(
                label = "良",
                count = stats.good,
                tone = SystemTone.Info,
                selected = selectedBucket == GradeBucket.Good,
                onClick = { onBucketClick?.invoke(GradeBucket.Good) },
                modifier = Modifier.weight(1f)
            )
            DistributionItem(
                label = "中",
                count = stats.medium,
                tone = SystemTone.Warning,
                selected = selectedBucket == GradeBucket.Medium,
                onClick = { onBucketClick?.invoke(GradeBucket.Medium) },
                modifier = Modifier.weight(1f)
            )
            DistributionItem(
                label = "及格",
                count = stats.pass,
                tone = SystemTone.Warning,
                selected = selectedBucket == GradeBucket.Pass,
                onClick = { onBucketClick?.invoke(GradeBucket.Pass) },
                modifier = Modifier.weight(1f)
            )
            DistributionItem(
                label = "不及格",
                count = stats.fail,
                tone = SystemTone.Danger,
                selected = selectedBucket == GradeBucket.Fail,
                onClick = { onBucketClick?.invoke(GradeBucket.Fail) },
                modifier = Modifier.weight(1f)
            )
            DistributionItem(
                label = "未出分",
                count = stats.notGraded,
                tone = SystemTone.Neutral,
                selected = selectedBucket == GradeBucket.NotGraded,
                onClick = { onBucketClick?.invoke(GradeBucket.NotGraded) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun DistributionItem(
    label: String,
    count: Int,
    tone: SystemTone,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val accentColor = when (tone) {
        SystemTone.Success -> SemanticSuccess
        SystemTone.Info -> SemanticInfo
        SystemTone.Warning -> SemanticWarning
        SystemTone.Danger -> SemanticDanger
        else -> MaterialTheme.colorScheme.primary
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) accentColor.copy(alpha = 0.16f)
                else Color.Transparent
            )
            .then(
                if (selected) {
                    Modifier.border(1.dp, accentColor.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                } else {
                    Modifier
                }
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SystemStatusBadge(
            text = label,
            tone = tone
        )
        Text(
            text = "$count 门",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            ),
            color = if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GradeItemRow(
    item: GradeItemUi,
    exams: List<ExamItemUi>
) {
    val components = remember(item.detail) { parseGradeComponents(item.detail) }
    // 参考官网最新版：有成绩构成分项时默认展开直接展示（老师给的具体分和占比），无分项时收起
    var expanded by rememberSaveable(item.year, item.term, item.courseCode, item.courseName, item.jxbId) { mutableStateOf(components.isNotEmpty()) }
    val notes = remember(item.detail) {
        item.detail.split(Regex("\\s*[|；;]\\s*")).filter { it.isNotBlank() && parseGradeComponents(it).isEmpty() }
    }
    val gradeColor = getGradeColor(item.grade)
    val reduced = com.buct.course.ui.system.rememberGlassAccessibilityMode().reduceMotion
    val hasDetail = item.detail.isNotEmpty() || item.courseCode.isNotEmpty()
    val notGraded = item.grade.isBlank() || item.grade == "--" || item.grade == "null"
    val semesterLabel = remember(item.year, item.term) { formatSemesterLabel(item.year, item.term) }
    val exam = remember(item.courseName, exams) { exams.firstOrNull { it.courseName == item.courseName } }
    SystemCard(
        modifier = Modifier.fillMaxWidth().moduleEntrance(2),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        onClick = if (hasDetail) ({ expanded = !expanded }) else null
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // 左侧：课程名 + 代码/学院
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.courseName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val subInfo = buildString {
                        if (item.courseCode.isNotEmpty()) append(item.courseCode)
                        if (item.college.isNotEmpty()) {
                            if (isNotEmpty()) append("   ")
                            append(item.college)
                        }
                    }
                    if (subInfo.isNotEmpty()) {
                        Text(
                            text = subInfo,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 右侧：成绩/未出分（主位）+ 学分 + chevron
                Column(horizontalAlignment = Alignment.End) {
                    if (notGraded) {
                        // 未出分：圆角小徽章，与整页标签风格统一
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "未出分",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            text = item.grade,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = gradeColor
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${item.credits}学分",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (hasDetail) {
                    com.buct.course.ui.system.AnimatedLineIcon(
                        com.buct.course.ui.system.AnimatedIconSpec.Chevron, Modifier.size(20.dp),
                        state = if (expanded) com.buct.course.ui.system.IconVisualState.Expanded else com.buct.course.ui.system.IconVisualState.Idle,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 展开区域：成绩构成进度条
            AnimatedVisibility(
                visible = expanded,
                enter = ModuleMotion.expand(reduced),
                exit = ModuleMotion.collapse(reduced)
            ) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    SystemDivider(alpha = 0.5f)
                    Spacer(modifier = Modifier.height(10.dp))

                    // 开课学期（未出分课程即"要完成的学期"）；无学期字段的课程行不显示
                    if (item.year.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.DateRange, contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "学期：$semesterLabel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 考试安排（有则显示时间/地点/座位）
                    if (exam != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Schedule, contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "考试：${exam.examTime.ifBlank { "时间待定" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (exam.location.isNotBlank() || exam.seatNumber.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.LocationOn, contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = buildString {
                                        if (exam.location.isNotBlank()) append("地点：${exam.location}")
                                        if (exam.seatNumber.isNotBlank()) {
                                            if (isNotEmpty()) append("   ")
                                            append("座位：${exam.seatNumber}")
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    if (components.isNotEmpty()) {
                        Text(
                            text = "成绩构成",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        components.forEachIndexed { index, comp ->
                            GradeComponentBar(
                                label = comp.label,
                                score = comp.score,
                                maxScore = 100f,
                                // 同一族浓淡阶梯，而不是紫/蓝/绿/橙/青五种循环色：
                                // 那五种颜色不携带任何语义，只是在卡片里制造噪声。
                                color = NeuPrimary.copy(
                                    alpha = (0.95f - index * 0.15f).coerceAtLeast(0.42f)
                                )
                            )
                            if (index < components.lastIndex) {
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                    } else if (item.detail.isBlank()) {
                        // 无分项时展示基本信息
                        Text(
                            text = "暂无分项详情",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    if (notes.isNotEmpty()) Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)

                    // 元信息落到小芯片上（复用筛选面板那一枚）：原先绩点根本没露过面，
                    // 教学班只在"无分项"那条分支里以裸文字出现。
                    val metaChips = buildList {
                        if (item.gpa.isNotBlank() && item.gpa != "0" && item.gpa != "0.0") add("绩点 ${item.gpa}")
                        if (item.credits.isNotBlank()) add("${item.credits} 学分")
                        if (item.courseType.isNotBlank()) add(item.courseType)
                        if (item.teachingClass.isNotBlank()) add(item.teachingClass)
                    }
                    if (metaChips.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            metaChips.forEach { label ->
                                GlassFilterChip(
                                    label = label,
                                    selected = false,
                                    compact = true
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class GradeComponent(val label: String, val score: Float)

// 学期标签：xnm(2026) + xqm(3/12/16) → "2026-2027-1/2/3"
private fun formatSemesterLabel(yearRaw: String, termRaw: String): String {
    val year = yearRaw.toIntOrNull()
    val termLabel = when (termRaw) {
        "12" -> "2"
        "16" -> "3"
        "3" -> "1"
        "1" -> "1"
        else -> termRaw.ifEmpty { "--" }
    }
    return if (year != null) "$year-${year + 1}-$termLabel" else yearRaw.ifEmpty { "--" }
}

private fun parseGradeComponents(detail: String): List<GradeComponent> {
    return detail.replace('：', ':').split(Regex("\\s*[|；;]\\s*")).mapNotNull { part ->
        // 格式: "平时(30%): 90" 或 "平时: 90"
        val colonIdx = part.lastIndexOf(':')
        if (colonIdx < 0) return@mapNotNull null
        val label = part.substring(0, colonIdx).trim()
        val scoreStr = part.substring(colonIdx + 1).trim()
        val score = scoreStr.toFloatOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null
        GradeComponent(label, score)
    }
}

@Composable
private fun GradeComponentBar(
    label: String,
    score: Float,
    maxScore: Float,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
        ) {
            val fill by animateFloatAsState(
                targetValue = (score / maxScore).coerceIn(0f, 1f),
                animationSpec = spring(dampingRatio = 0.9f, stiffness = 260f),
                label = "gradeBarFill"
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = fill)
                    .clip(RoundedCornerShape(6.dp))
                    .background(color)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (score == score.toLong().toFloat()) score.toLong().toString()
                   else score.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun ExamScheduleContent(
    exams: List<ExamItemUi>,
    semesters: List<String>,
    currentSemester: String,
    onSemesterChange: (String) -> Unit,
    isLoading: Boolean,
    error: String,
    listState: LazyListState,
    topInset: Dp,
    bottomInset: Dp,
    onAddExam: (() -> Unit)? = null,
    onEditExam: ((ExamItemUi) -> Unit)? = null
) {
    var hidePast by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    val visibleExams = remember(exams, hidePast) {
        if (hidePast) exams.filterNot { it.isPastExam() } else exams
    }
    // 学期选择器始终显示（即使当前学期暂无考试也能切换学期）
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = PagePadding,
            end = PagePadding,
            top = topInset,
            bottom = bottomInset
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(Modifier.moduleEntrance(1), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SemesterSelector(
                    semesters = semesters,
                    currentSemester = currentSemester,
                    onSemesterChange = onSemesterChange
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val pastCount = remember(exams) { exams.count { it.isPastExam() } }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (hidePast) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            )
                            .border(
                                width = if (hidePast) 1.2.dp else 0.6.dp,
                                color = if (hidePast) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(18.dp)
                            )
                            .clickable { hidePast = !hidePast }
                            .padding(horizontal = 13.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = if (hidePast) {
                                if (pastCount > 0) "✓ 已隐藏已结束 ($pastCount)" else "✓ 已隐藏已结束"
                            } else {
                                "隐藏已结束"
                            },
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (hidePast) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = if (hidePast) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (onAddExam != null) {
                        TextButton(onClick = onAddExam) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("添加考试")
                        }
                    }
                }
            }
        }

        when {
            isLoading && visibleExams.isEmpty() -> {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        SystemLoadingState(text = "正在加载考试安排…")
                    }
                }
            }

            visibleExams.isEmpty() -> {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        SystemEmptyState(
                            title = if (error.isNotBlank()) "考试安排加载失败" else "暂无考试安排",
                            message = error.ifBlank { "点击刷新获取最新考试信息" }
                        )
                    }
                }
            }

            else -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SystemStatStrip(
                            items = listOf(
                                "考试数" to visibleExams.size.toString(),
                                "最近状态" to "已同步",
                                "查看方式" to "列表"
                            )
                        )
                        SystemSectionHeader(
                            title = "考试列表",
                            subtitle = "按时间顺序展示"
                        )
                    }
                }

                items(visibleExams) { exam ->
                    Box(
                        Modifier
                            .moduleEntrance(2)
                            .then(
                                if (exam.isCustom && onEditExam != null) {
                                    Modifier.clickable { onEditExam.invoke(exam) }
                                } else {
                                    Modifier
                                }
                            )
                    ) { ExamItemRow(exam = exam) }
                }
            }
        }
    }
}

@Composable
private fun ExamItemRow(
    exam: ExamItemUi
) {
    val semesterStart = com.buct.course.manager.ScheduleSettingsManager.getInstance().semesterStartDate
    val examWeek = com.buct.course.schedule.ScheduleDates.examWeek(exam.examTime, semesterStart)
    val examTone = examBadgeTone(exam.examName, examWeek)

    SystemCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        val badgeText = examBadgeLabel(exam.examName, examWeek)
        // 归一化后标签都是 2–4 个字，基本不会再触发换行；保留兜底以防教务返回异常值。
        val stackedBadge = badgeText.length > 6
        if (stackedBadge) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = exam.courseName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                SystemStatusBadge(text = badgeText, tone = examTone)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = exam.courseName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(12.dp))
                SystemStatusBadge(text = badgeText, tone = examTone)
            }
        }

        ExamDetailRow(
            icon = Icons.Default.Schedule,
            text = exam.examTime.ifBlank { "未提供考试时间" }
        )
        val examWeekLabel = com.buct.course.schedule.ScheduleDates.examWeekDayLabel(
            exam.examTime,
            com.buct.course.manager.ScheduleSettingsManager.getInstance().semesterStartDate
        )
        if (examWeekLabel != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.DateRange,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = examWeekLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        ExamDetailRow(
            icon = Icons.Default.LocationOn,
            text = buildString {
                append(exam.location.ifBlank { "未提供地点" })
                if (exam.seatNumber.isNotBlank()) {
                    append(" · 座位 ${exam.seatNumber}")
                }
            }
        )
        ExamDetailRow(
            icon = Icons.Default.Person,
            text = exam.teacher.ifBlank { "未提供教师信息" }
        )
    }
}

@Composable
private fun ExamDetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

fun getGradeColor(grade: String): Color {
    return when (classifyGradeBucket(grade)) {
        GradeBucket.Excellent -> SemanticSuccess
        GradeBucket.Good -> SemanticInfo
        GradeBucket.Medium -> SemanticWarning
        GradeBucket.Pass -> Color(0xFFB26A00)
        GradeBucket.Fail -> SemanticDanger
        GradeBucket.NotGraded -> SemanticInfo
    }
}

@Composable
private fun CurriculumPlanContent(
    nodes: List<CurriculumNodeUi>,
    isLoading: Boolean,
    error: String,
    listState: LazyListState,
    topInset: Dp,
    bottomInset: Dp
) {
    // 0 = 未修/未通过/在修, 1 = 已修通过, 2 = 全部课程
    var courseFilterMode by rememberSaveable { mutableStateOf(0) }
    val rootNode = remember(nodes) { nodes.firstOrNull { it.parentId.isBlank() } }
    val childNodes = remember(nodes) {
        nodes.filter { it.parentId.isNotBlank() && (it.isLeaf || it.courses.isNotEmpty() || it.requiredCredits > 0.0) }
    }
    val parentNameMap = remember(nodes) { nodes.associate { it.id to it.name } }

    when {
        isLoading && nodes.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = topInset),
                contentAlignment = Alignment.Center
            ) {
                SystemLoadingState(text = "正在加载培养方案与学分修读情况…")
            }
        }

        nodes.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = topInset),
                contentAlignment = Alignment.Center
            ) {
                SystemEmptyState(
                    title = if (error.isNotBlank()) "培养方案加载失败" else "暂无培养方案数据",
                    message = error.ifBlank { "点击右上角刷新获取学生学业情况（N105515）" }
                )
            }
        }

        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(
                    start = PagePadding,
                    end = PagePadding,
                    top = topInset,
                    bottom = bottomInset
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Column(Modifier.moduleEntrance(1), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        GradeRefreshStatus(isLoading, error)

                        val totalEarned = rootNode?.earnedCredits
                            ?: childNodes.filter { it.isLeaf }.sumOf { it.earnedCredits }
                        val totalRequired = rootNode?.requiredCredits
                            ?: childNodes.filter { it.isLeaf }.sumOf { it.requiredCredits }
                        val totalRemaining = (totalRequired - totalEarned).coerceAtLeast(0.0)
                        val unmetModuleCount = childNodes.count { !it.passed && it.remainingCredits > 0.0 }

                        SystemStatStrip(
                            items = listOf(
                                "已获学分" to String.format(java.util.Locale.ROOT, "%.1f", totalEarned),
                                "要求总学分" to String.format(java.util.Locale.ROOT, "%.1f", totalRequired),
                                "未满学分" to String.format(java.util.Locale.ROOT, "%.1f", totalRemaining)
                            )
                        )

                        SystemCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = rootNode?.name?.ifBlank { "专业培养方案" } ?: "专业培养方案",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = if (unmetModuleCount == 0 && totalRemaining <= 0.0) {
                                            "所有模块学分已修满"
                                        } else {
                                            "还有 $unmetModuleCount 个模块未修满，共差 ${String.format(java.util.Locale.ROOT, "%.1f", totalRemaining)} 学分"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                SystemStatusBadge(
                                    text = if (totalRemaining <= 0.0 && totalRequired > 0.0) "已达标" else "修读中",
                                    tone = if (totalRemaining <= 0.0 && totalRequired > 0.0) SystemTone.Success else SystemTone.Info
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            val overallProgress = if (totalRequired > 0.0) {
                                (totalEarned / totalRequired).toFloat().coerceIn(0f, 1f)
                            } else 1f
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(overallProgress)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(if (overallProgress >= 1f) SemanticSuccess else NeuPrimary)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(
                                0 to "未修 / 在修 / 未过",
                                1 to "已修通过",
                                2 to "全部课程"
                            ).forEach { (mode, label) ->
                                val selected = courseFilterMode == mode
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                        )
                                        .border(
                                            width = if (selected) 1.2.dp else 0.5.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant,
                                            shape = RoundedCornerShape(16.dp)
                                        )
                                        .clickable { courseFilterMode = mode }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                        ),
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                items(childNodes, key = { it.id }) { node ->
                    val parentName = parentNameMap[node.parentId].orEmpty()
                    val displayTitle = if (parentName.isNotBlank() && parentName != rootNode?.name) {
                        "$parentName · ${node.name}"
                    } else {
                        node.name
                    }
                    CurriculumNodeCard(
                        node = node,
                        displayTitle = displayTitle,
                        courseFilterMode = courseFilterMode
                    )
                }
            }
        }
    }
}

@Composable
private fun CurriculumNodeCard(
    node: CurriculumNodeUi,
    displayTitle: String,
    courseFilterMode: Int
) {
    val isMet = node.passed || (node.requiredCredits > 0.0 && node.earnedCredits >= node.requiredCredits)
    var expanded by rememberSaveable(node.id) { mutableStateOf(!isMet) }
    val filteredCourses = remember(node.courses, courseFilterMode) {
        when (courseFilterMode) {
            0 -> node.courses.filter { !it.isPassed }
            1 -> node.courses.filter { it.isPassed }
            else -> node.courses
        }
    }
    val studyingCount = remember(node.courses) { node.courses.count { it.isStudying } }
    val unstudiedCount = remember(node.courses) { node.courses.count { it.studyStatus == "3" } }
    val failedCount = remember(node.courses) { node.courses.count { it.isFailed } }

    SystemCard(
        modifier = Modifier.fillMaxWidth().moduleEntrance(2),
        onClick = if (node.courses.isNotEmpty()) ({ expanded = !expanded }) else null
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                val subText = buildString {
                    append("已修 ${String.format(java.util.Locale.ROOT, "%.1f", node.earnedCredits)} / 要求 ${String.format(java.util.Locale.ROOT, "%.1f", node.requiredCredits)} 学分")
                    if (node.remainingCredits > 0.0 && !isMet) {
                        append(" · 还差 ${String.format(java.util.Locale.ROOT, "%.1f", node.remainingCredits)} 学分")
                    }
                }
                Text(
                    text = subText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!isMet && node.remainingCredits > 0.0) SemanticWarning else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (node.courses.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            append("共 ${node.courses.size} 门")
                            if (studyingCount > 0) append(" · 在修 $studyingCount 门")
                            if (unstudiedCount > 0) append(" · 未修 $unstudiedCount 门")
                            if (failedCount > 0) append(" · 未过 $failedCount 门")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            SystemStatusBadge(
                text = if (isMet) "已修满" else "差 ${String.format(java.util.Locale.ROOT, "%.1f", node.remainingCredits)}分",
                tone = if (isMet) SystemTone.Success else SystemTone.Warning
            )
        }

        if (node.requiredCredits > 0.0) {
            Spacer(Modifier.height(8.dp))
            val ratio = (node.earnedCredits / node.requiredCredits).toFloat().coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(ratio)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isMet) SemanticSuccess else SemanticWarning)
                )
            }
        }

        AnimatedVisibility(visible = expanded && node.courses.isNotEmpty()) {
            Column(modifier = Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SystemDivider(alpha = 0.5f)
                if (filteredCourses.isEmpty()) {
                    Text(
                        text = when (courseFilterMode) {
                            0 -> "该模块下没有未修或未通过的计划课程"
                            1 -> "该模块下暂无已修通过的课程"
                            else -> "暂无课程明细"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                } else {
                    filteredCourses.forEach { course ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = course.courseName,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                val meta = buildString {
                                    if (course.courseCode.isNotBlank()) append(course.courseCode)
                                    if (course.credits.isNotBlank()) {
                                        if (isNotEmpty()) append(" · ")
                                        append("${course.credits}学分")
                                    }
                                    if (course.suggestedYear.isNotBlank()) {
                                        if (isNotEmpty()) append(" · ")
                                        append("建议 ${course.suggestedYear}")
                                        if (course.suggestedTerm.isNotBlank()) append("-${course.suggestedTerm}")
                                    }
                                }
                                if (meta.isNotBlank()) {
                                    Text(
                                        text = meta,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            val (badgeLabel, badgeTone) = when (course.studyStatus) {
                                "4" -> (course.grade.ifBlank { "已通过" }) to SystemTone.Success
                                "1" -> "在修" to SystemTone.Info
                                "2" -> (if (course.grade.isNotBlank()) "未过(${course.grade})" else "未通过") to SystemTone.Danger
                                else -> "未修" to SystemTone.Neutral
                            }
                            SystemStatusBadge(text = badgeLabel, tone = badgeTone)
                        }
                    }
                }
            }
        }
    }
}

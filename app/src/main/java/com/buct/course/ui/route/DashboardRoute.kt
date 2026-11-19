package com.buct.course.ui.route

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.buct.course.EcardActivity
import com.buct.course.ScsActivity
import com.buct.course.SjcxActivity
import com.buct.course.manager.BottomNavPreferences
import com.buct.course.manager.ScheduleSettingsManager
import com.buct.course.manager.StartupPage
import com.buct.course.manager.UserManager
import com.buct.course.ui.screen.EmptyClassroomDialog
import com.buct.course.ui.system.GlassStatChip
import com.buct.course.ui.system.GlassSubpage
import com.buct.course.ui.system.GlassToaster
import com.buct.course.ui.system.LocalAppOverlayBottomInset
import com.buct.course.ui.system.glassBorderColor
import com.buct.course.ui.system.glassSurfaceColor
import com.buct.course.widget.WidgetScheduleSource
import com.buct.course.widget.parseClockMinutes
import com.buct.one.data.model.CardSnapshot
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.LocalTodoItem
import com.buct.one.data.model.RefreshState
import com.buct.one.data.model.ScsSnapshot
import com.buct.one.data.model.SjcxSnapshot
import com.buct.one.data.model.TheolHomeworkItem
import com.buct.one.data.model.TheolSnapshot
import com.buct.one.data.model.TheolTestItem
import com.buct.one.data.model.formatFenToYuan
import com.buct.one.data.sync.CardSyncService
import com.buct.one.data.sync.ScsSyncService
import com.buct.one.data.sync.SjcxSyncService
import com.buct.one.data.sync.TheolSyncService
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

/**
 * 工作台卡片尺寸规格：以 4 列逻辑单元（满行 = 4 格）进行自适应混合装箱：
 * - [LARGE]：`2×1` 大卡片（占满整行 4 格）
 * - [MEDIUM]：`1×1` 中卡片（占半行 2 格，高 136dp）
 * - [MINI]：`0.5×0.5` 迷你小卡片（占 1/4 行 1 格，高 92dp，与实践教学快捷瓷砖等大）
 */
private enum class CardSize(val units: Int, val label: String) {
    MINI(1, "0.5×0.5"),
    MEDIUM(2, "1×1"),
    LARGE(4, "2×1");

    companion object {
        fun fromUnits(units: Int): CardSize = when {
            units >= 4 -> LARGE
            units >= 2 -> MEDIUM
            else -> MINI
        }
    }
}

private enum class DashboardModuleId(
    val id: String,
    val title: String,
    val shortTitle: String,
    val icon: ImageVector,
    val defaultSize: CardSize,
    val defaultVisible: Boolean,
    val supportedSizes: List<CardSize>
) {
    THEOL(
        id = "theol",
        title = "北化在线 · 待办与 DDL",
        shortTitle = "北化在线",
        icon = Icons.Outlined.Assignment,
        defaultSize = CardSize.LARGE,
        defaultVisible = true,
        supportedSizes = listOf(CardSize.MINI, CardSize.MEDIUM, CardSize.LARGE)
    ),
    SCS(
        id = "scs",
        title = "第二课堂 · 五育成绩",
        shortTitle = "第二课堂",
        icon = Icons.Outlined.School,
        defaultSize = CardSize.MEDIUM,
        defaultVisible = true,
        supportedSizes = listOf(CardSize.MINI, CardSize.MEDIUM, CardSize.LARGE)
    ),
    ECARD(
        id = "ecard",
        title = "校园卡 · 余额与流水",
        shortTitle = "校园卡",
        icon = Icons.Outlined.CreditCard,
        defaultSize = CardSize.MEDIUM,
        defaultVisible = true,
        supportedSizes = listOf(CardSize.MINI, CardSize.MEDIUM, CardSize.LARGE)
    ),
    SJCX(
        id = "sjcx",
        title = "实践教学 · 实验与讲座",
        shortTitle = "实践教学",
        icon = Icons.Outlined.Science,
        defaultSize = CardSize.MEDIUM,
        defaultVisible = true,
        supportedSizes = listOf(CardSize.MINI, CardSize.MEDIUM, CardSize.LARGE)
    ),
    CLASSROOM(
        id = "classroom",
        title = "查空闲教室",
        shortTitle = "空闲教室",
        icon = Icons.Outlined.MeetingRoom,
        defaultSize = CardSize.MEDIUM,
        defaultVisible = true,
        supportedSizes = listOf(CardSize.MINI, CardSize.MEDIUM, CardSize.LARGE)
    ),
    EXP_SCHEDULE(
        id = "exp_schedule",
        title = "实验课表",
        shortTitle = "实验课表",
        icon = Icons.Outlined.Science,
        defaultSize = CardSize.MINI,
        defaultVisible = true,
        supportedSizes = listOf(CardSize.MINI, CardSize.MEDIUM)
    ),
    LECTURES(
        id = "lectures",
        title = "抢讲座",
        shortTitle = "抢讲座",
        icon = Icons.Outlined.Campaign,
        defaultSize = CardSize.MINI,
        defaultVisible = true,
        supportedSizes = listOf(CardSize.MINI, CardSize.MEDIUM)
    ),
    INNOVATION_CREDIT(
        id = "innovation_credit",
        title = "创新学分",
        shortTitle = "创新学分",
        icon = Icons.Outlined.WorkspacePremium,
        defaultSize = CardSize.MINI,
        defaultVisible = true,
        supportedSizes = listOf(CardSize.MINI, CardSize.MEDIUM)
    ),
    SCS_ACTIVITIES(
        id = "scs_activities",
        title = "二课活动",
        shortTitle = "二课活动",
        icon = Icons.Outlined.EventAvailable,
        defaultSize = CardSize.MINI,
        defaultVisible = true,
        supportedSizes = listOf(CardSize.MINI, CardSize.MEDIUM)
    ),
    COURSE_SELECT(
        id = "course_select",
        title = "选课抢课",
        shortTitle = "选课抢课",
        icon = Icons.Outlined.LibraryAdd,
        defaultSize = CardSize.MINI,
        defaultVisible = true,
        supportedSizes = listOf(CardSize.MINI, CardSize.MEDIUM)
    ),
    FRIENDS(
        id = "friends",
        title = "好友课表",
        shortTitle = "好友课表",
        icon = Icons.Outlined.People,
        defaultSize = CardSize.MINI,
        defaultVisible = true,
        supportedSizes = listOf(CardSize.MINI, CardSize.MEDIUM)
    ),
    EXAMS(
        id = "exams",
        title = "成绩考试",
        shortTitle = "成绩考试",
        icon = Icons.Outlined.BarChart,
        defaultSize = CardSize.MINI,
        defaultVisible = true,
        supportedSizes = listOf(CardSize.MINI, CardSize.MEDIUM)
    ),
    TODO_QUICK(
        id = "todo_quick",
        title = "待办清单",
        shortTitle = "待办清单",
        icon = Icons.Outlined.Checklist,
        defaultSize = CardSize.MINI,
        defaultVisible = true,
        supportedSizes = listOf(CardSize.MINI, CardSize.MEDIUM)
    ),
    MY_LECTURES(
        id = "my_lectures",
        title = "我的讲座",
        shortTitle = "我的讲座",
        icon = Icons.Outlined.EditNote,
        defaultSize = CardSize.MINI,
        defaultVisible = false,
        supportedSizes = listOf(CardSize.MINI, CardSize.MEDIUM)
    );

    fun stepSize(delta: Int, current: CardSize): CardSize {
        val idx = supportedSizes.indexOf(current).let { if (it >= 0) it else 0 }
        val nextIdx = (idx + delta).coerceIn(0, supportedSizes.lastIndex)
        return supportedSizes[nextIdx]
    }

    fun nextCycleSize(current: CardSize): CardSize {
        if (supportedSizes.size <= 1) return current
        val idx = supportedSizes.indexOf(current).let { if (it >= 0) it else 0 }
        // 点击时按 大 -> 中 -> 迷你 循环切换，手感更直观
        val descending = supportedSizes.sortedByDescending { it.units }
        val descIdx = descending.indexOf(supportedSizes[idx]).let { if (it >= 0) it else 0 }
        return descending[(descIdx + 1) % descending.size]
    }

    companion object {
        fun fromId(id: String): DashboardModuleId? = entries.firstOrNull { it.id == id }
    }
}

private data class DashboardModuleConfig(
    val module: DashboardModuleId,
    val visible: Boolean = module.defaultVisible,
    val size: CardSize = module.defaultSize
)

private data class DashboardGridRow(
    val items: List<DashboardModuleConfig>,
    val emptyUnits: Int
)

private enum class DashboardSubpage {
    COURSE_SELECT,
    FRIENDS
}

private const val MAX_UNDO_HISTORY = 30

/**
 * 将可见模块按每行 4 个逻辑单元贪心装箱，支持 2×1(4) / 1×1(2) / 0.5×0.5(1) 任意混合排列。
 */
private fun packModulesInto4ColGrid(configs: List<DashboardModuleConfig>): List<DashboardGridRow> {
    val active = configs.filter { it.visible }
    val rows = ArrayList<DashboardGridRow>()
    var currentRow = ArrayList<DashboardModuleConfig>()
    var usedUnits = 0

    for (cfg in active) {
        val u = cfg.size.units
        if (usedUnits > 0 && usedUnits + u > 4) {
            rows.add(DashboardGridRow(items = currentRow.toList(), emptyUnits = (4 - usedUnits).coerceAtLeast(0)))
            currentRow = ArrayList()
            usedUnits = 0
        }
        currentRow.add(cfg)
        usedUnits += u
        if (usedUnits >= 4) {
            rows.add(DashboardGridRow(items = currentRow.toList(), emptyUnits = 0))
            currentRow = ArrayList()
            usedUnits = 0
        }
    }
    if (currentRow.isNotEmpty()) {
        rows.add(DashboardGridRow(items = currentRow.toList(), emptyUnits = (4 - usedUnits).coerceAtLeast(0)))
    }
    return rows
}

private object DashboardLayoutStore {
    private const val PREF_NAME = "buct_dashboard_grid_prefs"
    private const val KEY_LAYOUT_V2 = "layout_v2"
    private const val KEY_LAYOUT_V1 = "layout_v1"

    fun defaultConfigs(): List<DashboardModuleConfig> =
        DashboardModuleId.entries.map {
            DashboardModuleConfig(module = it, visible = it.defaultVisible, size = it.defaultSize)
        }

    fun load(context: Context): List<DashboardModuleConfig> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val rawV2 = prefs.getString(KEY_LAYOUT_V2, null)
        if (!rawV2.isNullOrBlank()) {
            return parseLayoutJson(rawV2, isV2 = true)
        }
        val rawV1 = prefs.getString(KEY_LAYOUT_V1, null)
        if (!rawV1.isNullOrBlank()) {
            return parseLayoutJson(rawV1, isV2 = false)
        }
        return defaultConfigs()
    }

    private fun parseLayoutJson(raw: String, isV2: Boolean): List<DashboardModuleConfig> = runCatching {
        val arr = JSONArray(raw)
        val loaded = ArrayList<DashboardModuleConfig>()
        val seen = HashSet<DashboardModuleId>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val mod = DashboardModuleId.fromId(obj.optString("id")) ?: continue
            if (!seen.add(mod)) continue
            val visible = obj.optBoolean("visible", mod.defaultVisible)
            val parsedSize = if (isV2) {
                CardSize.fromUnits(obj.optInt("units", mod.defaultSize.units))
            } else {
                val span = obj.optInt("span", 2)
                if (span >= 2) CardSize.LARGE else CardSize.MEDIUM
            }
            val safeSize = if (parsedSize in mod.supportedSizes) parsedSize else mod.defaultSize
            loaded.add(DashboardModuleConfig(module = mod, visible = visible, size = safeSize))
        }
        for (mod in DashboardModuleId.entries) {
            if (mod !in seen) {
                loaded.add(DashboardModuleConfig(module = mod, visible = mod.defaultVisible, size = mod.defaultSize))
            }
        }
        loaded
    }.getOrElse { defaultConfigs() }

    fun save(context: Context, configs: List<DashboardModuleConfig>) {
        val arr = JSONArray()
        configs.forEach { cfg ->
            arr.put(
                JSONObject().apply {
                    put("id", cfg.module.id)
                    put("visible", cfg.visible)
                    put("units", cfg.size.units)
                }
            )
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAYOUT_V2, arr.toString())
            .apply()
    }
}

/**
 * 工作台首屏（五栏底栏居中主入口）。
 * 支持全屏悬浮卡片动画拖拽排序、右上角双向箭头拖拽/点击调节尺寸（2×1 / 1×1 / 0.5×0.5）以及撤回/重置/保存。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardRoute(
    onSwitchPage: (StartupPage) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
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

    val scsService = remember(context) { ScsSyncService.getInstance(context) }
    val scsSlice by scsService.scsState.collectAsState()
    val scsSyncing by scsService.syncing.collectAsState()

    var showEmptyClassroomDialog by remember { mutableStateOf(false) }
    var activeSubpage by remember { mutableStateOf<DashboardSubpage?>(null) }
    val classroomSlot = remember(showEmptyClassroomDialog) { resolveCurrentClassroomSlot(context) }
    val classroomDayLabel = listOf("一", "二", "三", "四", "五", "六", "日")
        .getOrElse(classroomSlot.day - 1) { "一" }

    var savedConfigs by remember { mutableStateOf(DashboardLayoutStore.load(context)) }
    var isEditingLayout by rememberSaveable { mutableStateOf(false) }
    var draftConfigs by remember { mutableStateOf(savedConfigs) }
    var undoStack by remember { mutableStateOf<List<List<DashboardModuleConfig>>>(emptyList()) }

    val cardBounds = remember { mutableStateMapOf<DashboardModuleId, Rect>() }
    var draggingModule by remember { mutableStateOf<DashboardModuleId?>(null) }
    var draggingStartTopLeft by remember { mutableStateOf(Offset.Zero) }
    var draggingPointerDelta by remember { mutableStateOf(Offset.Zero) }
    var hoveredTargetModule by remember { mutableStateOf<DashboardModuleId?>(null) }
    var lastSwapMs by remember { mutableLongStateOf(0L) }
    val releaseOffset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var releasingModule by remember { mutableStateOf<DashboardModuleId?>(null) }

    var resizingModule by remember { mutableStateOf<DashboardModuleId?>(null) }
    var resizePreviewScale by remember { mutableStateOf(1f) }

    fun pushUndoSnapshot(snapshot: List<DashboardModuleConfig>) {
        if (undoStack.lastOrNull() == snapshot) return
        undoStack = (undoStack + listOf(snapshot)).takeLast(MAX_UNDO_HISTORY)
    }

    fun enterEditMode() {
        draftConfigs = savedConfigs
        undoStack = emptyList()
        draggingModule = null
        hoveredTargetModule = null
        releasingModule = null
        isEditingLayout = true
    }

    fun applyStepSize(module: DashboardModuleId, delta: Int) {
        val idx = draftConfigs.indexOfFirst { it.module == module }
        if (idx < 0) return
        val current = draftConfigs[idx]
        val targetSize = module.stepSize(delta, current.size)
        if (targetSize == current.size) return
        pushUndoSnapshot(draftConfigs)
        draftConfigs = draftConfigs.toMutableList().apply {
            this[idx] = current.copy(size = targetSize)
        }
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun applyCycleSize(module: DashboardModuleId) {
        val idx = draftConfigs.indexOfFirst { it.module == module }
        if (idx < 0) return
        val current = draftConfigs[idx]
        val nextSize = module.nextCycleSize(current.size)
        if (nextSize == current.size) return
        pushUndoSnapshot(draftConfigs)
        draftConfigs = draftConfigs.toMutableList().apply {
            this[idx] = current.copy(size = nextSize)
        }
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun moveDraftModule(fromModule: DashboardModuleId, toModule: DashboardModuleId) {
        val fromIdx = draftConfigs.indexOfFirst { it.module == fromModule }
        val toIdx = draftConfigs.indexOfFirst { it.module == toModule }
        if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx) return
        val mutable = draftConfigs.toMutableList()
        val item = mutable.removeAt(fromIdx)
        mutable.add(toIdx, item)
        draftConfigs = mutable
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun setModuleVisible(module: DashboardModuleId, visible: Boolean) {
        val idx = draftConfigs.indexOfFirst { it.module == module }
        if (idx < 0 || draftConfigs[idx].visible == visible) return
        pushUndoSnapshot(draftConfigs)
        draftConfigs = draftConfigs.toMutableList().apply {
            this[idx] = this[idx].copy(visible = visible)
        }
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun navigateToPageOrWarn(page: StartupPage) {
        if (BottomNavPreferences.isVisible(page)) {
            onSwitchPage(page)
        } else {
            GlassToaster.show("该页面已在底栏隐藏，可在「我的 → 底部导航栏配置」恢复")
        }
    }

    val activeConfigs = if (isEditingLayout) draftConfigs else savedConfigs
    val gridRows = remember(activeConfigs) { packModulesInto4ColGrid(activeConfigs) }
    val hiddenConfigs = remember(draftConfigs) { draftConfigs.filter { !it.visible } }

    val now = remember { Date() }
    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..10 -> "早上好"
            in 11..13 -> "中午好"
            in 14..17 -> "下午好"
            else -> "晚上好"
        }
    }
    val dateLine = remember(now) { SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(now) }

    val pendingHomeworks = remember(theolSlice, filterPrefs) {
        theolSlice.data?.homeworks?.filter { it.canSubmit && filterPrefs.isHomeworkVisible(it) }.orEmpty()
    }
    val pendingTests = remember(theolSlice, filterPrefs) {
        theolSlice.data?.tests?.filter { it.canStart && filterPrefs.isTestVisible(it) }.orEmpty()
    }
    val activeLocalTodos = remember(localTodos) {
        localTodos.filter { !it.deleted && !it.completed }
    }
    val bottomInset = LocalAppOverlayBottomInset.current
    val scrollState = rememberScrollState()
    val anySyncing = syncing || cardSyncing || sjcxSyncing || scsSyncing

    val currentDraftState by rememberUpdatedState(draftConfigs)
    val latestBoundsState by rememberUpdatedState(cardBounds)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(scrollState)
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = 14.dp,
                bottom = bottomInset + 24.dp
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. 顶部区：正常态为问候+全局刷新，自定义编辑态在屏幕右上角提供 撤回/重置/取消/保存 操作栏
        if (isEditingLayout) {
            DashboardEditHeader(
                canUndo = undoStack.isNotEmpty(),
                onUndo = {
                    val previous = undoStack.lastOrNull() ?: return@DashboardEditHeader
                    undoStack = undoStack.dropLast(1)
                    draftConfigs = previous
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
                onReset = {
                    val defaults = DashboardLayoutStore.defaultConfigs()
                    if (draftConfigs != defaults) {
                        pushUndoSnapshot(draftConfigs)
                        draftConfigs = defaults
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        GlassToaster.show("已重置为默认工作台布局（点击保存生效）")
                    }
                },
                onCancel = {
                    draftConfigs = savedConfigs
                    undoStack = emptyList()
                    draggingModule = null
                    hoveredTargetModule = null
                    releasingModule = null
                    resizingModule = null
                    isEditingLayout = false
                },
                onSave = {
                    savedConfigs = draftConfigs
                    DashboardLayoutStore.save(context, draftConfigs)
                    undoStack = emptyList()
                    draggingModule = null
                    hoveredTargetModule = null
                    releasingModule = null
                    resizingModule = null
                    isEditingLayout = false
                    GlassToaster.show("工作台布局已保存")
                }
            )
        } else {
            DashboardNormalHeader(
                greeting = greeting,
                displayName = displayName,
                dateLine = dateLine,
                anySyncing = anySyncing,
                onRefreshAll = {
                    scope.launch { theolService.refreshIfSessionValid() }
                    scope.launch { sjcxService.refresh() }
                    if (scsService.hasSavedSsoSession()) {
                        scope.launch { scsService.refresh() }
                    }
                    if (cardHasSession) {
                        scope.launch { cardService.refresh() }
                    }
                }
            )
        }

        // 2. 顶部总待办统计胶囊条
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GlassStatChip(
                value = if (theolSlice.provenance.refreshState == RefreshState.NEVER) "--" else pendingHomeworks.size.toString(),
                label = "待交作业",
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = !isEditingLayout) { navigateToPageOrWarn(StartupPage.Homework) },
                valueColor = if (pendingHomeworks.isNotEmpty()) Color(0xFFFF453A) else MaterialTheme.colorScheme.onSurface
            )
            GlassStatChip(
                value = if (theolSlice.provenance.refreshState == RefreshState.NEVER) "--" else pendingTests.size.toString(),
                label = "在线测验",
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = !isEditingLayout) { navigateToPageOrWarn(StartupPage.Homework) },
                valueColor = MaterialTheme.colorScheme.primary
            )
            GlassStatChip(
                value = activeLocalTodos.size.toString(),
                label = "本地待办",
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = !isEditingLayout) { navigateToPageOrWarn(StartupPage.Homework) }
            )
        }

        // 3. 悬浮卡片工作台栅格（4 格弹性混合排版，支持长按浮起动画拖拽换位与右上角箭头缩放）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (gridRows.isEmpty()) {
                val emptyShape = RoundedCornerShape(24.dp)
                val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(112.dp)
                        .clip(emptyShape)
                        .background(glassSurfaceColor().copy(alpha = 0.45f))
                        .drawBehind {
                            drawRoundRect(
                                color = outlineColor,
                                cornerRadius = CornerRadius(24.dp.toPx()),
                                style = Stroke(
                                    width = 1.4.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
                                )
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isEditingLayout) "工作台已清空，可从下方「添加模块」加回卡片"
                        else "所有模块均已隐藏，点击下方「自定义工作台」启用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                gridRows.forEach { row ->
                    val hasDraggingInRow = row.items.any {
                        it.module == draggingModule || it.module == releasingModule
                    }
                    val rowHasLarge = row.items.any { it.size == CardSize.LARGE }
                    val rowHasMedium = row.items.any { it.size == CardSize.MEDIUM }
                    val fixedHeightDp = when {
                        rowHasLarge -> null
                        rowHasMedium -> 136
                        else -> 92
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(if (hasDraggingInRow) 20f else 0f)
                            .animateContentSize(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            ),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        row.items.forEach { cfg ->
                            val modId = cfg.module
                            val isDragging = draggingModule == modId
                            val isReleasing = releasingModule == modId
                            val isHoveredTarget = hoveredTargetModule == modId && !isDragging
                            val isResizing = resizingModule == modId

                            val visualTranslation = when {
                                isDragging -> {
                                    val curTopLeft = cardBounds[modId]?.topLeft ?: draggingStartTopLeft
                                    (draggingStartTopLeft + draggingPointerDelta) - curTopLeft
                                }
                                isReleasing -> releaseOffset.value
                                else -> Offset.Zero
                            }

                            EditableFloatingCardSlot(
                                config = cfg,
                                isEditing = isEditingLayout,
                                isDragging = isDragging,
                                isHoveredTarget = isHoveredTarget,
                                isResizing = isResizing,
                                resizePreviewScale = if (isResizing) resizePreviewScale else 1f,
                                visualOffset = visualTranslation,
                                modifier = Modifier
                                    .weight(cfg.size.units.toFloat())
                                    .zIndex(
                                        when {
                                            isDragging -> 30f
                                            isReleasing -> 25f
                                            isResizing -> 20f
                                            else -> 0f
                                        }
                                    )
                                    .onGloballyPositioned { coords ->
                                        cardBounds[modId] = coords.boundsInRoot()
                                    }
                                    .pointerInput(modId, isEditingLayout) {
                                        if (!isEditingLayout) return@pointerInput
                                        var startSnapshot = currentDraftState
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                startSnapshot = currentDraftState
                                                releasingModule = null
                                                draggingModule = modId
                                                draggingStartTopLeft = latestBoundsState[modId]?.topLeft ?: Offset.Zero
                                                draggingPointerDelta = Offset.Zero
                                                hoveredTargetModule = null
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                draggingPointerDelta += dragAmount
                                                val anchorTopLeft = draggingStartTopLeft
                                                val selfSize = latestBoundsState[modId]?.size
                                                val center = if (selfSize != null) {
                                                    anchorTopLeft + draggingPointerDelta + Offset(selfSize.width / 2f, selfSize.height / 2f)
                                                } else {
                                                    anchorTopLeft + draggingPointerDelta
                                                }
                                                val visibleSet = currentDraftState.asSequence()
                                                    .filter { it.visible }
                                                    .map { it.module }
                                                    .toSet()
                                                val target = latestBoundsState.entries.firstOrNull { (candidate, rect) ->
                                                    candidate != modId &&
                                                        candidate in visibleSet &&
                                                        rect.contains(center)
                                                }?.key
                                                hoveredTargetModule = target
                                                val nowMs = SystemClock.uptimeMillis()
                                                if (target != null && nowMs - lastSwapMs >= 200L) {
                                                    lastSwapMs = nowMs
                                                    moveDraftModule(modId, target)
                                                }
                                            },
                                            onDragEnd = {
                                                val finalVisualOffset = run {
                                                    val curTopLeft = latestBoundsState[modId]?.topLeft ?: draggingStartTopLeft
                                                    (draggingStartTopLeft + draggingPointerDelta) - curTopLeft
                                                }
                                                if (currentDraftState != startSnapshot) {
                                                    pushUndoSnapshot(startSnapshot)
                                                }
                                                draggingModule = null
                                                hoveredTargetModule = null
                                                releasingModule = modId
                                                scope.launch {
                                                    releaseOffset.snapTo(finalVisualOffset)
                                                    releaseOffset.animateTo(
                                                        Offset.Zero,
                                                        spring(
                                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                                            stiffness = Spring.StiffnessMediumLow
                                                        )
                                                    )
                                                    if (releasingModule == modId) releasingModule = null
                                                }
                                            },
                                            onDragCancel = {
                                                draggingModule = null
                                                hoveredTargetModule = null
                                                releasingModule = null
                                            }
                                        )
                                    },
                                onHideModule = { setModuleVisible(modId, false) },
                                onStepSize = { delta -> applyStepSize(modId, delta) },
                                onCycleSize = { applyCycleSize(modId) },
                                onResizePreview = { scale ->
                                    resizingModule = modId
                                    resizePreviewScale = scale
                                },
                                onResizePreviewEnd = {
                                    if (resizingModule == modId) {
                                        resizingModule = null
                                        resizePreviewScale = 1f
                                    }
                                }
                            ) {
                                RenderModuleCard(
                                    config = cfg,
                                    fixedHeightDp = fixedHeightDp,
                                    isEditing = isEditingLayout,
                                    context = context,
                                    theolSlice = theolSlice,
                                    pendingHomeworks = pendingHomeworks,
                                    pendingTests = pendingTests,
                                    activeLocalTodos = activeLocalTodos,
                                    cardSlice = cardSlice,
                                    cardHasSession = cardHasSession,
                                    sjcxSlice = sjcxSlice,
                                    scsSlice = scsSlice,
                                    classroomSlot = classroomSlot,
                                    classroomDayLabel = classroomDayLabel,
                                    onNavigateTab = ::navigateToPageOrWarn,
                                    onOpenClassroom = { showEmptyClassroomDialog = true },
                                    onOpenCourseSelect = {
                                        if (BottomNavPreferences.isVisible(StartupPage.Courses)) {
                                            onSwitchPage(StartupPage.Courses)
                                        } else {
                                            activeSubpage = DashboardSubpage.COURSE_SELECT
                                        }
                                    },
                                    onOpenFriends = {
                                        if (BottomNavPreferences.isVisible(StartupPage.Friends)) {
                                            onSwitchPage(StartupPage.Friends)
                                        } else {
                                            activeSubpage = DashboardSubpage.FRIENDS
                                        }
                                    }
                                )
                            }
                        }

                        if (row.emptyUnits > 0) {
                            if (isEditingLayout) {
                                val slotHeight = (fixedHeightDp ?: 110).dp
                                val emptyShape = RoundedCornerShape(22.dp)
                                val dashColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                Box(
                                    modifier = Modifier
                                        .weight(row.emptyUnits.toFloat())
                                        .height(slotHeight)
                                        .clip(emptyShape)
                                        .drawBehind {
                                            drawRoundRect(
                                                color = dashColor,
                                                cornerRadius = CornerRadius(22.dp.toPx()),
                                                style = Stroke(
                                                    width = 1.1.dp.toPx(),
                                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                                                )
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "空位",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                            } else {
                                Spacer(modifier = Modifier.weight(row.emptyUnits.toFloat()))
                            }
                        }
                    }
                }
            }
        }

        // 4. 编辑态底部仅保留可添回的隐藏模块胶囊条；正常态展示轻量入口按钮（不再放置旧配置大面板）
        AnimatedVisibility(visible = isEditingLayout && hiddenConfigs.isNotEmpty()) {
            HiddenModulesDock(
                hiddenConfigs = hiddenConfigs,
                onRestoreModule = { mod -> setModuleVisible(mod, true) }
            )
        }

        if (!isEditingLayout) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(glassSurfaceColor())
                        .border(0.5.dp, glassBorderColor(), RoundedCornerShape(22.dp))
                        .clickable { enterEditMode() }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DashboardCustomize,
                        contentDescription = "自定义工作台",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "自定义工作台",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface
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

    if (activeSubpage != null) {
        val currentSubpage = activeSubpage
        GlassSubpage(onDismiss = { activeSubpage = null }) { close ->
            Box(modifier = Modifier.fillMaxSize()) {
                when (currentSubpage) {
                    DashboardSubpage.COURSE_SELECT -> CourseSectionRoute()
                    DashboardSubpage.FRIENDS -> FriendsRoute(onBack = close)
                    null -> Unit
                }
                if (currentSubpage == DashboardSubpage.COURSE_SELECT) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(top = 12.dp, end = 16.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(glassSurfaceColor().copy(alpha = 0.92f))
                            .border(0.5.dp, glassBorderColor(), CircleShape)
                            .clickable(onClick = close),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "关闭页面",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardNormalHeader(
    greeting: String,
    displayName: String,
    dateLine: String,
    anySyncing: Boolean,
    onRefreshAll: () -> Unit
) {
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
                .clickable(enabled = !anySyncing, onClick = onRefreshAll),
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

@Composable
private fun DashboardEditHeader(
    canUndo: Boolean,
    onUndo: () -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "自定义工作台",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EditHeaderActionPill(
                    label = "撤回",
                    icon = Icons.AutoMirrored.Outlined.Undo,
                    enabled = canUndo,
                    accent = false,
                    onClick = onUndo
                )
                EditHeaderActionPill(
                    label = "重置",
                    icon = Icons.Outlined.RestartAlt,
                    enabled = true,
                    accent = false,
                    onClick = onReset
                )
                EditHeaderActionPill(
                    label = "取消",
                    icon = null,
                    enabled = true,
                    accent = false,
                    onClick = onCancel
                )
                EditHeaderActionPill(
                    label = "保存",
                    icon = Icons.Outlined.Check,
                    enabled = true,
                    accent = true,
                    onClick = onSave
                )
            }
        }
        Text(
            text = "长按卡片拖动排序 · 拖/点右上角箭头调大小（2×1 / 1×1 / 0.5×0.5）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun EditHeaderActionPill(
    label: String,
    icon: ImageVector?,
    enabled: Boolean,
    accent: Boolean,
    onClick: () -> Unit
) {
    val bgColor = when {
        accent -> MaterialTheme.colorScheme.primary
        enabled -> glassSurfaceColor()
        else -> glassSurfaceColor().copy(alpha = 0.45f)
    }
    val fgColor = when {
        accent -> MaterialTheme.colorScheme.onPrimary
        enabled -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .border(
                0.5.dp,
                if (accent) MaterialTheme.colorScheme.primary else glassBorderColor(),
                RoundedCornerShape(16.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = fgColor,
                modifier = Modifier.size(14.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = fgColor,
            maxLines = 1
        )
    }
}

/**
 * 编辑态悬浮卡片外框：提供动画放大/浮起投影、虚线编辑框线、左上角隐藏按钮与右上角双向斜箭头缩放手柄。
 */
@Composable
private fun EditableFloatingCardSlot(
    config: DashboardModuleConfig,
    isEditing: Boolean,
    isDragging: Boolean,
    isHoveredTarget: Boolean,
    isResizing: Boolean,
    resizePreviewScale: Float,
    visualOffset: Offset,
    modifier: Modifier = Modifier,
    onHideModule: () -> Unit,
    onStepSize: (Int) -> Unit,
    onCycleSize: () -> Unit,
    onResizePreview: (Float) -> Unit,
    onResizePreviewEnd: () -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val viewConfig = LocalViewConfiguration.current
    val dragThresholdPx = remember(density) { with(density) { 38.dp.toPx() } }

    val targetScale = when {
        isDragging -> 1.05f
        isResizing -> resizePreviewScale
        isHoveredTarget -> 0.96f
        isEditing -> 0.985f
        else -> 1f
    }
    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "cardScale"
    )
    val animatedShadowDp by animateDpAsState(
        targetValue = if (isDragging) 18.dp else if (isEditing) 6.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "cardShadow"
    )
    val animatedOutlineColor by animateColorAsState(
        targetValue = when {
            isDragging -> MaterialTheme.colorScheme.primary
            isHoveredTarget -> MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
            isEditing -> MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
            else -> Color.Transparent
        },
        label = "cardOutline"
    )

    val cornerDp = if (config.size == CardSize.MINI) 20.dp else 26.dp
    val shadowPx = with(density) { animatedShadowDp.toPx() }
    val dashStrokePx = with(density) { 1.4.dp.toPx() }
    val cornerPx = with(density) { cornerDp.toPx() }
    val latestOnStep by rememberUpdatedState(onStepSize)
    val latestOnCycle by rememberUpdatedState(onCycleSize)
    val latestOnPreview by rememberUpdatedState(onResizePreview)
    val latestOnPreviewEnd by rememberUpdatedState(onResizePreviewEnd)

    Box(
        modifier = modifier.graphicsLayer {
            translationX = visualOffset.x
            translationY = visualOffset.y
            scaleX = animatedScale
            scaleY = animatedScale
            shadowElevation = shadowPx
            shape = RoundedCornerShape(cornerDp)
            clip = false
        }
    ) {
        content()

        if (isEditing) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBehind {
                        drawRoundRect(
                            color = animatedOutlineColor,
                            cornerRadius = CornerRadius(cornerPx),
                            style = Stroke(
                                width = dashStrokePx,
                                pathEffect = if (isDragging || isHoveredTarget) null
                                else PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                            )
                        )
                    }
            )

            // 左上角移出工作台按钮
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                    .border(0.5.dp, glassBorderColor(), CircleShape)
                    .clickable(onClick = onHideModule),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "隐藏${config.module.shortTitle}",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp)
                )
            }

            // 右上角双向箭头手柄：支持点击循环尺寸，以及按住往外/往内拖动实时缩放大小
            if (config.module.supportedSizes.size > 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape)
                        .pointerInput(config.module, isEditing) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                var totalDelta = Offset.Zero
                                var stepAccum = Offset.Zero
                                var didDragResize = false
                                var released = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        change.consume()
                                        released = true
                                        break
                                    }
                                    val d = change.positionChange()
                                    if (d != Offset.Zero) {
                                        change.consume()
                                        totalDelta += d
                                        stepAccum += d
                                        // 往右/右上拖为放大，往左/左下拖为缩小
                                        val score = stepAccum.x - stepAccum.y * 0.45f
                                        latestOnPreview((1f + score / (dragThresholdPx * 2.8f)).coerceIn(0.88f, 1.12f))
                                        if (score >= dragThresholdPx) {
                                            latestOnStep(+1)
                                            stepAccum = Offset.Zero
                                            didDragResize = true
                                        } else if (score <= -dragThresholdPx) {
                                            latestOnStep(-1)
                                            stepAccum = Offset.Zero
                                            didDragResize = true
                                        }
                                    }
                                }
                                latestOnPreviewEnd()
                                if (released && !didDragResize && hypot(totalDelta.x, totalDelta.y) <= viewConfig.touchSlop * 1.5f) {
                                    latestOnCycle()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.OpenInFull,
                        contentDescription = "拖动或点击调整尺寸",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HiddenModulesDock(
    hiddenConfigs: List<DashboardModuleConfig>,
    onRestoreModule: (DashboardModuleId) -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "点击添加到工作台",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            hiddenConfigs.forEach { cfg ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                        .clickable { onRestoreModule(cfg.module) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = cfg.module.shortTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun RenderModuleCard(
    config: DashboardModuleConfig,
    fixedHeightDp: Int?,
    isEditing: Boolean,
    context: Context,
    theolSlice: DomainSlice<TheolSnapshot>,
    pendingHomeworks: List<TheolHomeworkItem>,
    pendingTests: List<TheolTestItem>,
    activeLocalTodos: List<LocalTodoItem>,
    cardSlice: DomainSlice<CardSnapshot>,
    cardHasSession: Boolean,
    sjcxSlice: DomainSlice<SjcxSnapshot>,
    scsSlice: DomainSlice<ScsSnapshot>,
    classroomSlot: ClassroomSlot,
    classroomDayLabel: String,
    onNavigateTab: (StartupPage) -> Unit,
    onOpenClassroom: () -> Unit,
    onOpenCourseSelect: () -> Unit,
    onOpenFriends: () -> Unit
) {
    when (config.size) {
        CardSize.LARGE -> RenderLargeModuleCard(
            module = config.module,
            isEditing = isEditing,
            context = context,
            theolSlice = theolSlice,
            pendingHomeworks = pendingHomeworks,
            pendingTests = pendingTests,
            activeLocalTodos = activeLocalTodos,
            cardSlice = cardSlice,
            cardHasSession = cardHasSession,
            sjcxSlice = sjcxSlice,
            scsSlice = scsSlice,
            classroomSlot = classroomSlot,
            classroomDayLabel = classroomDayLabel,
            onNavigateTab = onNavigateTab,
            onOpenClassroom = onOpenClassroom
        )

        CardSize.MEDIUM -> {
            val summary = buildMediumSummary(
                module = config.module,
                context = context,
                theolSlice = theolSlice,
                pendingHomeworks = pendingHomeworks,
                pendingTests = pendingTests,
                activeLocalTodos = activeLocalTodos,
                cardSlice = cardSlice,
                sjcxSlice = sjcxSlice,
                scsSlice = scsSlice,
                classroomSlot = classroomSlot,
                classroomDayLabel = classroomDayLabel,
                onNavigateTab = onNavigateTab,
                onOpenClassroom = onOpenClassroom,
                onOpenCourseSelect = onOpenCourseSelect,
                onOpenFriends = onOpenFriends
            )
            CompactMediumCard(
                icon = config.module.icon,
                summary = summary,
                heightDp = fixedHeightDp ?: 136,
                isEditing = isEditing
            )
        }

        CardSize.MINI -> {
            val summary = buildMiniSummary(
                module = config.module,
                context = context,
                theolSlice = theolSlice,
                pendingHomeworks = pendingHomeworks,
                cardSlice = cardSlice,
                sjcxSlice = sjcxSlice,
                scsSlice = scsSlice,
                classroomSlot = classroomSlot,
                onNavigateTab = onNavigateTab,
                onOpenClassroom = onOpenClassroom,
                onOpenCourseSelect = onOpenCourseSelect,
                onOpenFriends = onOpenFriends
            )
            CompactMiniCard(
                icon = config.module.icon,
                summary = summary,
                heightDp = fixedHeightDp ?: 92,
                isEditing = isEditing
            )
        }
    }
}

private data class ModuleTileSummary(
    val title: String,
    val primaryValue: String,
    val subLine: String,
    val onClick: () -> Unit
)

private fun buildMediumSummary(
    module: DashboardModuleId,
    context: Context,
    theolSlice: DomainSlice<TheolSnapshot>,
    pendingHomeworks: List<TheolHomeworkItem>,
    pendingTests: List<TheolTestItem>,
    activeLocalTodos: List<LocalTodoItem>,
    cardSlice: DomainSlice<CardSnapshot>,
    sjcxSlice: DomainSlice<SjcxSnapshot>,
    scsSlice: DomainSlice<ScsSnapshot>,
    classroomSlot: ClassroomSlot,
    classroomDayLabel: String,
    onNavigateTab: (StartupPage) -> Unit,
    onOpenClassroom: () -> Unit,
    onOpenCourseSelect: () -> Unit,
    onOpenFriends: () -> Unit
): ModuleTileSummary = when (module) {
    DashboardModuleId.THEOL -> ModuleTileSummary(
        title = "北化在线",
        primaryValue = if (theolSlice.provenance.refreshState == RefreshState.NEVER) "待同步"
        else "${pendingHomeworks.size} 项待交",
        subLine = "测验 ${pendingTests.size} · 待办 ${activeLocalTodos.size}",
        onClick = { onNavigateTab(StartupPage.Homework) }
    )

    DashboardModuleId.SCS -> {
        val scsData = scsSlice.data
        val latestTerm = scsData?.terms?.firstOrNull()
        val valText = when {
            latestTerm?.totalScore != null -> String.format(Locale.ROOT, "%.1f 分", latestTerm.totalScore)
            scsData != null -> "已登录"
            else -> "五育综测"
        }
        val sub = when {
            latestTerm != null -> "${latestTerm.termName.ifBlank { latestTerm.yearName }} · 活动 ${scsData?.activities?.size ?: 0}"
            scsData?.currentWindow?.isOpen == true -> "自评已开放"
            else -> "德智体美劳与二课活动"
        }
        ModuleTileSummary("第二课堂", valText, sub) { ScsActivity.launch(context, initialTab = 0) }
    }

    DashboardModuleId.ECARD -> {
        val cardData = cardSlice.data
        val valText = if (cardData != null) "¥ ${formatFenToYuan(cardData.account.balanceFen)}" else "点击登录"
        val sub = cardData?.transactions?.firstOrNull()?.let { "${it.title} ¥${formatFenToYuan(it.amountFen)}" }
            ?: if (cardData != null) "${cardData.account.statusLabel} · ${cardData.account.maskedId}" else "独立查询余额与流水"
        ModuleTileSummary("校园卡", valText, sub) {
            context.startActivity(Intent(context, EcardActivity::class.java))
        }
    }

    DashboardModuleId.SJCX -> {
        val sjcxData = sjcxSlice.data
        val valText = if (sjcxData != null) {
            if (sjcxData.openLectures.isNotEmpty()) "${sjcxData.openLectures.size} 场可抢"
            else "${sjcxData.schedules.size} 节实验"
        } else {
            "实验与讲座"
        }
        val sub = if (sjcxData != null) {
            "排课 ${sjcxData.schedules.size} · ${String.format(Locale.ROOT, "%.1f", sjcxData.totalCredit)}学分"
        } else {
            "实验课表 · 抢讲座"
        }
        ModuleTileSummary("实践教学", valText, sub) { SjcxActivity.launch(context, initialTab = 0) }
    }

    DashboardModuleId.CLASSROOM -> ModuleTileSummary(
        title = "空闲教室",
        primaryValue = "第 ${classroomSlot.week} 周",
        subLine = "周${classroomDayLabel} · 第 ${classroomSlot.period} 节空教室",
        onClick = onOpenClassroom
    )

    DashboardModuleId.EXP_SCHEDULE -> {
        val cnt = sjcxSlice.data?.schedules?.size
        ModuleTileSummary(
            title = "实验课表",
            primaryValue = if (cnt != null) "$cnt 节实验" else "查排课",
            subLine = "sjcx.buct.edu.cn 实验安排",
            onClick = { SjcxActivity.launch(context, initialTab = 0) }
        )
    }

    DashboardModuleId.LECTURES -> {
        val cnt = sjcxSlice.data?.openLectures?.size
        ModuleTileSummary(
            title = "抢讲座",
            primaryValue = if (cnt != null) "$cnt 场可报" else "预约报名",
            subLine = "一键报名校内开放讲座",
            onClick = { SjcxActivity.launch(context, initialTab = 1) }
        )
    }

    DashboardModuleId.INNOVATION_CREDIT -> {
        val cr = sjcxSlice.data?.totalCredit
        ModuleTileSummary(
            title = "创新学分",
            primaryValue = if (cr != null) "${String.format(Locale.ROOT, "%.1f", cr)} 分" else "查看学分",
            subLine = "实践教学总学分与成绩明细",
            onClick = { SjcxActivity.launch(context, initialTab = 3) }
        )
    }

    DashboardModuleId.SCS_ACTIVITIES -> {
        val cnt = scsSlice.data?.activities?.size
        ModuleTileSummary(
            title = "二课活动",
            primaryValue = if (cnt != null) "$cnt 项记录" else "活动列表",
            subLine = "第二课堂参与与认证活动",
            onClick = { ScsActivity.launch(context, initialTab = 1) }
        )
    }

    DashboardModuleId.COURSE_SELECT -> ModuleTileSummary(
        title = "选课抢课",
        primaryValue = "全校选课",
        subLine = "队列抢课与已选课程管理",
        onClick = onOpenCourseSelect
    )

    DashboardModuleId.FRIENDS -> ModuleTileSummary(
        title = "好友课表",
        primaryValue = "课表对比",
        subLine = "查看同学当前课程与空余时间",
        onClick = onOpenFriends
    )

    DashboardModuleId.EXAMS -> ModuleTileSummary(
        title = "成绩与考试",
        primaryValue = "GPA · 考表",
        subLine = "查分、培养方案与考试倒计时",
        onClick = { onNavigateTab(StartupPage.Grades) }
    )

    DashboardModuleId.TODO_QUICK -> ModuleTileSummary(
        title = "待办清单",
        primaryValue = "${activeLocalTodos.size} 项本地",
        subLine = "待交作业 ${pendingHomeworks.size} · 自定义事项",
        onClick = { onNavigateTab(StartupPage.Homework) }
    )

    DashboardModuleId.MY_LECTURES -> ModuleTileSummary(
        title = "我的讲座",
        primaryValue = "心得 / 记录",
        subLine = "已报讲座签到与心得提交",
        onClick = { SjcxActivity.launch(context, initialTab = 2) }
    )
}

private fun buildMiniSummary(
    module: DashboardModuleId,
    context: Context,
    theolSlice: DomainSlice<TheolSnapshot>,
    pendingHomeworks: List<TheolHomeworkItem>,
    cardSlice: DomainSlice<CardSnapshot>,
    sjcxSlice: DomainSlice<SjcxSnapshot>,
    scsSlice: DomainSlice<ScsSnapshot>,
    classroomSlot: ClassroomSlot,
    onNavigateTab: (StartupPage) -> Unit,
    onOpenClassroom: () -> Unit,
    onOpenCourseSelect: () -> Unit,
    onOpenFriends: () -> Unit
): ModuleTileSummary = when (module) {
    DashboardModuleId.THEOL -> ModuleTileSummary(
        title = "北化在线",
        primaryValue = if (theolSlice.provenance.refreshState == RefreshState.NEVER) "待同步" else "${pendingHomeworks.size}待交",
        subLine = "",
        onClick = { onNavigateTab(StartupPage.Homework) }
    )

    DashboardModuleId.SCS -> {
        val score = scsSlice.data?.terms?.firstOrNull()?.totalScore
        ModuleTileSummary(
            title = "第二课堂",
            primaryValue = if (score != null) String.format(Locale.ROOT, "%.1f分", score) else "五育",
            subLine = "",
            onClick = { ScsActivity.launch(context, initialTab = 0) }
        )
    }

    DashboardModuleId.ECARD -> {
        val bal = cardSlice.data?.account?.balanceFen
        ModuleTileSummary(
            title = "校园卡",
            primaryValue = if (bal != null) "¥${formatFenToYuan(bal)}" else "余额",
            subLine = "",
            onClick = { context.startActivity(Intent(context, EcardActivity::class.java)) }
        )
    }

    DashboardModuleId.SJCX -> {
        val open = sjcxSlice.data?.openLectures?.size
        ModuleTileSummary(
            title = "实践教学",
            primaryValue = if (open != null) "${open}可抢" else "实验",
            subLine = "",
            onClick = { SjcxActivity.launch(context, initialTab = 0) }
        )
    }

    DashboardModuleId.CLASSROOM -> ModuleTileSummary(
        title = "空教室",
        primaryValue = "第${classroomSlot.period}节",
        subLine = "",
        onClick = onOpenClassroom
    )

    DashboardModuleId.EXP_SCHEDULE -> {
        val cnt = sjcxSlice.data?.schedules?.size
        ModuleTileSummary(
            title = "实验课表",
            primaryValue = if (cnt != null) "${cnt}节" else "排课",
            subLine = "",
            onClick = { SjcxActivity.launch(context, initialTab = 0) }
        )
    }

    DashboardModuleId.LECTURES -> {
        val cnt = sjcxSlice.data?.openLectures?.size
        ModuleTileSummary(
            title = "抢讲座",
            primaryValue = if (cnt != null) "${cnt}场" else "报名",
            subLine = "",
            onClick = { SjcxActivity.launch(context, initialTab = 1) }
        )
    }

    DashboardModuleId.INNOVATION_CREDIT -> {
        val cr = sjcxSlice.data?.totalCredit
        ModuleTileSummary(
            title = "创新学分",
            primaryValue = if (cr != null) "${String.format(Locale.ROOT, "%.1f", cr)}分" else "学分",
            subLine = "",
            onClick = { SjcxActivity.launch(context, initialTab = 3) }
        )
    }

    DashboardModuleId.SCS_ACTIVITIES -> ModuleTileSummary(
        title = "二课活动",
        primaryValue = scsSlice.data?.activities?.size?.let { "${it}项" } ?: "活动",
        subLine = "",
        onClick = { ScsActivity.launch(context, initialTab = 1) }
    )

    DashboardModuleId.COURSE_SELECT -> ModuleTileSummary(
        title = "选课抢课",
        primaryValue = "选课",
        subLine = "",
        onClick = onOpenCourseSelect
    )

    DashboardModuleId.FRIENDS -> ModuleTileSummary(
        title = "好友课表",
        primaryValue = "课表",
        subLine = "",
        onClick = onOpenFriends
    )

    DashboardModuleId.EXAMS -> ModuleTileSummary(
        title = "成绩考试",
        primaryValue = "GPA",
        subLine = "",
        onClick = { onNavigateTab(StartupPage.Grades) }
    )

    DashboardModuleId.TODO_QUICK -> ModuleTileSummary(
        title = "待办清单",
        primaryValue = "待办",
        subLine = "",
        onClick = { onNavigateTab(StartupPage.Homework) }
    )

    DashboardModuleId.MY_LECTURES -> ModuleTileSummary(
        title = "我的讲座",
        primaryValue = "心得",
        subLine = "",
        onClick = { SjcxActivity.launch(context, initialTab = 2) }
    )
}

@Composable
private fun CompactMediumCard(
    icon: ImageVector,
    summary: ModuleTileSummary,
    heightDp: Int,
    isEditing: Boolean
) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .clickable(enabled = !isEditing, onClick = summary.onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = summary.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = summary.title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = summary.primaryValue,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = summary.subLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 0.5×0.5 迷你小卡片：1/4 宽度、精炼图标+短标题+小字状态，视觉量感与实践教学模块快捷瓷砖一致。
 */
@Composable
private fun CompactMiniCard(
    icon: ImageVector,
    summary: ModuleTileSummary,
    heightDp: Int,
    isEditing: Boolean
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .clickable(enabled = !isEditing, onClick = summary.onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = summary.title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp)
            )
        }
        Text(
            text = summary.title,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Text(
            text = summary.primaryValue,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RenderLargeModuleCard(
    module: DashboardModuleId,
    isEditing: Boolean,
    context: Context,
    theolSlice: DomainSlice<TheolSnapshot>,
    pendingHomeworks: List<TheolHomeworkItem>,
    pendingTests: List<TheolTestItem>,
    activeLocalTodos: List<LocalTodoItem>,
    cardSlice: DomainSlice<CardSnapshot>,
    cardHasSession: Boolean,
    sjcxSlice: DomainSlice<SjcxSnapshot>,
    scsSlice: DomainSlice<ScsSnapshot>,
    classroomSlot: ClassroomSlot,
    classroomDayLabel: String,
    onNavigateTab: (StartupPage) -> Unit,
    onOpenClassroom: () -> Unit
) {
    when (module) {
        DashboardModuleId.THEOL -> {
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
                onClick = if (isEditing) null else ({ onNavigateTab(StartupPage.Homework) })
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
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                            ) {
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
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "上次同步提示：${theolSlice.lastError}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFF453A)
                    )
                }
            }
        }

        DashboardModuleId.SCS -> {
            val scsData = scsSlice.data
            val latestTerm = scsData?.terms?.firstOrNull()
            val scsBadge = when {
                scsSlice.provenance.refreshState == RefreshState.NEVER -> "点击进入"
                scsData?.currentWindow?.isOpen == true -> "自评开放中"
                latestTerm != null -> latestTerm.termName.ifBlank { latestTerm.yearName }
                else -> "已同步"
            }
            val scsBadgeColor = when {
                scsSlice.provenance.refreshState == RefreshState.FAILED_FALLBACK -> Color(0xFFFF453A)
                scsData?.currentWindow?.isOpen == true -> Color(0xFF34C759)
                else -> MaterialTheme.colorScheme.primary
            }
            DashboardGlassCard(
                title = "第二课堂 · 五育综测",
                badge = scsBadge,
                badgeColor = scsBadgeColor,
                onClick = if (isEditing) null else ({ ScsActivity.launch(context, initialTab = 0) })
            ) {
                if (latestTerm != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${latestTerm.yearName} · ${latestTerm.termName}".trim(' ', '·'),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "总分 ${latestTerm.totalScore?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "--"} · 活动记录 ${scsData?.activities?.size ?: 0} 项",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Outlined.School,
                            contentDescription = "第二课堂",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ScsDimensionMiniBadge("德", latestTerm.moral, Modifier.weight(1f))
                        ScsDimensionMiniBadge("智", latestTerm.academic, Modifier.weight(1f))
                        ScsDimensionMiniBadge("体", latestTerm.physical, Modifier.weight(1f))
                        ScsDimensionMiniBadge("美", latestTerm.aesthetics, Modifier.weight(1f))
                        ScsDimensionMiniBadge("劳", latestTerm.labor, Modifier.weight(1f))
                    }
                } else {
                    Text(
                        text = "统一身份认证免密直连 scs.buct.edu.cn，查看德智体美劳五育综测成绩、明细与二课活动",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        DashboardModuleId.ECARD -> {
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
                onClick = if (isEditing) null else ({
                    context.startActivity(Intent(context, EcardActivity::class.java))
                })
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

        DashboardModuleId.SJCX -> {
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
                onClick = if (isEditing) null else ({ SjcxActivity.launch(context, initialTab = 0) })
            ) {
                if (sjcxData != null) {
                    Column(modifier = Modifier.fillMaxWidth()) {
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
                    QuickActionTile("实验课表", Icons.Outlined.Science, Modifier.weight(1f), !isEditing) {
                        SjcxActivity.launch(context, initialTab = 0)
                    }
                    QuickActionTile("抢讲座", Icons.Outlined.Campaign, Modifier.weight(1f), !isEditing) {
                        SjcxActivity.launch(context, initialTab = 1)
                    }
                    QuickActionTile("我的讲座", Icons.Outlined.Campaign, Modifier.weight(1f), !isEditing) {
                        SjcxActivity.launch(context, initialTab = 2)
                    }
                    QuickActionTile("创新学分", Icons.Outlined.WorkspacePremium, Modifier.weight(1f), !isEditing) {
                        SjcxActivity.launch(context, initialTab = 3)
                    }
                }
            }
        }

        DashboardModuleId.CLASSROOM -> {
            DashboardGlassCard(
                title = "查空闲教室",
                badge = "第 ${classroomSlot.week} 周 · 周${classroomDayLabel}",
                badgeColor = MaterialTheme.colorScheme.primary,
                onClick = if (isEditing) null else onOpenClassroom
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        .clickable(enabled = !isEditing, onClick = onOpenClassroom)
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

        else -> {
            DashboardGlassCard(
                title = module.title,
                badge = "快捷入口",
                badgeColor = MaterialTheme.colorScheme.primary
            ) {
                Text(
                    text = "可通过右上角箭头缩放到 0.5×0.5 迷你小卡片。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ScsDimensionMiniBadge(label: String, score: Double?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = score?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "--",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
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
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .clickable(enabled = enabled, onClick = onClick)
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

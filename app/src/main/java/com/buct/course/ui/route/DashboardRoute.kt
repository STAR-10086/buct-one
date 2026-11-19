package com.buct.course.ui.route

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.buct.course.EcardActivity
import com.buct.course.SjcxActivity
import com.buct.course.manager.ScheduleSettingsManager
import com.buct.course.manager.StartupPage
import com.buct.course.manager.UserManager
import com.buct.course.ui.screen.EmptyClassroomDialog
import com.buct.course.ui.system.GlassStatChip
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
import com.buct.one.data.model.SjcxSnapshot
import com.buct.one.data.model.TheolHomeworkItem
import com.buct.one.data.model.TheolSnapshot
import com.buct.one.data.model.TheolTestItem
import com.buct.one.data.model.formatFenToYuan
import com.buct.one.data.sync.CardSyncService
import com.buct.one.data.sync.SjcxSyncService
import com.buct.one.data.sync.TheolSyncService
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 工作台模块定义：支持任意排序、显隐切换、以及 2×1 大卡片 / 1×1 小卡片 栅格骨架约束。
 */
private enum class DashboardModuleId(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val defaultSpan: Int
) {
    THEOL("theol", "北化在线 · 待办与 DDL", Icons.Outlined.Assignment, 2),
    ECARD("ecard", "校园卡 · 余额与流水", Icons.Outlined.CreditCard, 2),
    SJCX("sjcx", "实践教学 · 实验与讲座", Icons.Outlined.Science, 2),
    CLASSROOM("classroom", "查空闲教室", Icons.Outlined.MeetingRoom, 2);

    companion object {
        fun fromId(id: String): DashboardModuleId? = entries.firstOrNull { it.id == id }
    }
}

private data class DashboardModuleConfig(
    val module: DashboardModuleId,
    val visible: Boolean = true,
    /** 栅格跨度：2 = 整行大卡片 (2×1)，1 = 半行小卡片 (1×1) */
    val span: Int = module.defaultSpan
)

private sealed interface DashboardGridRow {
    data class FullWidthSlot(val config: DashboardModuleConfig) : DashboardGridRow
    data class HalfWidthSlots(
        val left: DashboardModuleConfig,
        /** 右侧槽位为空时，在编辑态渲染虚线空槽位骨架 */
        val right: DashboardModuleConfig?
    ) : DashboardGridRow
}

/**
 * 将模块列表按 2 列栅格骨架自动装箱：
 * - span == 2 的大卡片独占整行（若前一个 1×1 槽位未配对，则右侧留出 1×1 骨架空槽）
 * - 连续两个 span == 1 的小卡片自动并排拼成一行两格
 */
private fun packModulesIntoGridSkeleton(
    configs: List<DashboardModuleConfig>,
    includeHidden: Boolean
): List<DashboardGridRow> {
    val active = if (includeHidden) configs else configs.filter { it.visible }
    val rows = ArrayList<DashboardGridRow>()
    var pendingHalf: DashboardModuleConfig? = null

    for (cfg in active) {
        if (cfg.span >= 2) {
            if (pendingHalf != null) {
                rows.add(DashboardGridRow.HalfWidthSlots(pendingHalf, null))
                pendingHalf = null
            }
            rows.add(DashboardGridRow.FullWidthSlot(cfg))
        } else {
            if (pendingHalf == null) {
                pendingHalf = cfg
            } else {
                rows.add(DashboardGridRow.HalfWidthSlots(pendingHalf, cfg))
                pendingHalf = null
            }
        }
    }
    if (pendingHalf != null) {
        rows.add(DashboardGridRow.HalfWidthSlots(pendingHalf, null))
    }
    return rows
}

private object DashboardLayoutStore {
    private const val PREF_NAME = "buct_dashboard_grid_prefs"
    private const val KEY_LAYOUT_JSON = "layout_v1"

    fun defaultConfigs(): List<DashboardModuleConfig> =
        DashboardModuleId.entries.map { DashboardModuleConfig(module = it, visible = true, span = it.defaultSpan) }

    fun load(context: Context): List<DashboardModuleConfig> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LAYOUT_JSON, null) ?: return defaultConfigs()
        return runCatching {
            val arr = JSONArray(raw)
            val loaded = ArrayList<DashboardModuleConfig>()
            val seen = HashSet<DashboardModuleId>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val mod = DashboardModuleId.fromId(obj.optString("id")) ?: continue
                if (!seen.add(mod)) continue
                val visible = obj.optBoolean("visible", true)
                val span = obj.optInt("span", mod.defaultSpan).coerceIn(1, 2)
                loaded.add(DashboardModuleConfig(module = mod, visible = visible, span = span))
            }
            for (mod in DashboardModuleId.entries) {
                if (mod !in seen) {
                    loaded.add(DashboardModuleConfig(module = mod, visible = true, span = mod.defaultSpan))
                }
            }
            loaded
        }.getOrElse { defaultConfigs() }
    }

    fun save(context: Context, configs: List<DashboardModuleConfig>) {
        val arr = JSONArray()
        configs.forEach { cfg ->
            arr.put(
                JSONObject().apply {
                    put("id", cfg.module.id)
                    put("visible", cfg.visible)
                    put("span", cfg.span.coerceIn(1, 2))
                }
            )
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAYOUT_JSON, arr.toString())
            .apply()
    }
}

/**
 * 工作台首屏（五栏底栏居中主入口）。
 * 顶部固定展示总待办数量，下方采用 2 列模块化栅格骨架，支持大卡片（2×1）/ 小卡片（1×1）切换、显隐与任意排序。
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

    var moduleConfigs by remember { mutableStateOf(DashboardLayoutStore.load(context)) }
    var isEditingLayout by rememberSaveable { mutableStateOf(false) }

    fun updateConfigs(next: List<DashboardModuleConfig>) {
        moduleConfigs = next
        DashboardLayoutStore.save(context, next)
    }

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

    val gridRows = remember(moduleConfigs, isEditingLayout) {
        packModulesIntoGridSkeleton(moduleConfigs, includeHidden = false)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 14.dp,
            bottom = bottomInset + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. 顶部问候与全局刷新按钮（保持现状）
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
                        text = if (isEditingLayout) {
                            "栅格骨架编辑模式 · 2列槽位约束"
                        } else {
                            "$dateLine · BUCT One 工作台"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isEditingLayout) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
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

        // 2. 顶部总待办统计胶囊条（保持现状）
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

        // 3. 模块化 2 列栅格区（按用户自定义顺序与 2×1 / 1×1 跨度摆放）
        if (gridRows.isEmpty()) {
            item {
                SkeletonSlotBox(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                    showSkeleton = true
                ) {
                    Text(
                        text = "所有工作台模块均已隐藏，点击下方「自定义工作台」恢复显示",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            gridRows.forEachIndexed { rowIdx, row ->
                item(key = "grid_row_$rowIdx") {
                    when (row) {
                        is DashboardGridRow.FullWidthSlot -> {
                            SkeletonSlotBox(
                                modifier = Modifier.fillMaxWidth(),
                                showSkeleton = isEditingLayout
                            ) {
                                RenderModuleLargeCard(
                                    config = row.config,
                                    context = context,
                                    theolSlice = theolSlice,
                                    pendingHomeworks = pendingHomeworks,
                                    pendingTests = pendingTests,
                                    activeLocalTodos = activeLocalTodos,
                                    cardSlice = cardSlice,
                                    cardHasSession = cardHasSession,
                                    sjcxSlice = sjcxSlice,
                                    classroomSlot = classroomSlot,
                                    classroomDayLabel = classroomDayLabel,
                                    onSwitchPage = onSwitchPage,
                                    onOpenClassroom = { showEmptyClassroomDialog = true }
                                )
                            }
                        }

                        is DashboardGridRow.HalfWidthSlots -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                SkeletonSlotBox(
                                    modifier = Modifier.weight(1f),
                                    showSkeleton = isEditingLayout
                                ) {
                                    RenderModuleSmallCard(
                                        config = row.left,
                                        context = context,
                                        theolSlice = theolSlice,
                                        pendingHomeworks = pendingHomeworks,
                                        pendingTests = pendingTests,
                                        activeLocalTodos = activeLocalTodos,
                                        cardSlice = cardSlice,
                                        cardHasSession = cardHasSession,
                                        sjcxSlice = sjcxSlice,
                                        classroomSlot = classroomSlot,
                                        classroomDayLabel = classroomDayLabel,
                                        onSwitchPage = onSwitchPage,
                                        onOpenClassroom = { showEmptyClassroomDialog = true }
                                    )
                                }

                                if (row.right != null) {
                                    SkeletonSlotBox(
                                        modifier = Modifier.weight(1f),
                                        showSkeleton = isEditingLayout
                                    ) {
                                        RenderModuleSmallCard(
                                            config = row.right,
                                            context = context,
                                            theolSlice = theolSlice,
                                            pendingHomeworks = pendingHomeworks,
                                            pendingTests = pendingTests,
                                            activeLocalTodos = activeLocalTodos,
                                            cardSlice = cardSlice,
                                            cardHasSession = cardHasSession,
                                            sjcxSlice = sjcxSlice,
                                            classroomSlot = classroomSlot,
                                            classroomDayLabel = classroomDayLabel,
                                            onSwitchPage = onSwitchPage,
                                            onOpenClassroom = { showEmptyClassroomDialog = true }
                                        )
                                    }
                                } else {
                                    // 右侧剩余 1×1 空槽位：正常态透明占位保持 1×1 宽度约束，编辑态显示虚线骨架槽
                                    if (isEditingLayout) {
                                        SkeletonSlotBox(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(142.dp),
                                            showSkeleton = true
                                        ) {
                                            Text(
                                                text = "1×1 空闲栅格槽\n（将其他模块设为小卡片自动填入）",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. 页面最下方：自定义工作台面板与入口按钮
        item {
            if (isEditingLayout) {
                DashboardCustomizePanel(
                    configs = moduleConfigs,
                    onUpdateConfigs = ::updateConfigs,
                    onReset = {
                        updateConfigs(DashboardLayoutStore.defaultConfigs())
                        GlassToaster.show("已恢复默认工作台布局")
                    },
                    onDone = {
                        isEditingLayout = false
                        GlassToaster.show("工作台布局已保存")
                    }
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(22.dp))
                            .background(glassSurfaceColor())
                            .border(0.5.dp, glassBorderColor(), RoundedCornerShape(22.dp))
                            .clickable { isEditingLayout = true }
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
                            text = "自定义工作台（显隐 · 大小卡片 · 排序）",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
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

/**
 * 栅格槽位骨架包装器：在编辑态绘制虚线槽位边框与四角定位骨架线，限制每个卡片严格落在 2×1 或 1×1 格子里。
 */
@Composable
private fun SkeletonSlotBox(
    modifier: Modifier = Modifier,
    showSkeleton: Boolean,
    cornerRadius: Dp = 28.dp,
    content: @Composable () -> Unit
) {
    val strokeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        content()
        if (showSkeleton) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val r = cornerRadius.toPx()
                drawRoundRect(
                    color = strokeColor,
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(
                        width = 1.6.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
                    )
                )
            }
        }
    }
}

@Composable
private fun DashboardCustomizePanel(
    configs: List<DashboardModuleConfig>,
    onUpdateConfigs: (List<DashboardModuleConfig>) -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit
) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "工作台模块与栅格骨架配置",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "2×1 大卡片占整行，1×1 小卡片自动两两并排摆入栅格槽位",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onReset) {
                Icon(
                    imageVector = Icons.Outlined.RestartAlt,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("重置")
            }
        }

        // 2列栅格迷你骨架示意图
        val previewRows = remember(configs) { packModulesIntoGridSkeleton(configs, includeHidden = false) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "栅格骨架实时预览（2 列约束）",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary
            )
            if (previewRows.isEmpty()) {
                Text(
                    text = "（当前无可见模块）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                previewRows.forEach { r ->
                    when (r) {
                        is DashboardGridRow.FullWidthSlot -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(28.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                                    .border(0.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "[2×1 整行槽] ${r.config.module.title.substringBefore(" ·")}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        is DashboardGridRow.HalfWidthSlots -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(28.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                                        .border(0.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "[1×1] ${r.left.module.title.substringBefore(" ·")}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1
                                    )
                                }
                                if (r.right != null) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(28.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                                            .border(0.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "[1×1] ${r.right.module.title.substringBefore(" ·")}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(28.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .border(0.8.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "[1×1 空槽]",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 模块列表：支持上移、下移、显隐切换、大卡(2×1)/小卡(1×1)切换
        configs.forEachIndexed { index, cfg ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (cfg.visible) MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                    )
                    .border(0.5.dp, glassBorderColor(), RoundedCornerShape(16.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 显隐按钮
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            if (cfg.visible) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable {
                            val updated = configs.toMutableList()
                            updated[index] = cfg.copy(visible = !cfg.visible)
                            onUpdateConfigs(updated)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (cfg.visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                        contentDescription = if (cfg.visible) "已显示" else "已隐藏",
                        tint = if (cfg.visible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = cfg.module.title.substringBefore(" ·"),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (cfg.visible) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (!cfg.visible) "已隐藏"
                        else if (cfg.span == 2) "大卡片（2×1 整行槽位）"
                        else "小卡片（1×1 半行槽位）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 大小卡片切换胶囊
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    listOf(2 to "大卡", 1 to "小卡").forEach { (spanVal, label) ->
                        val selected = cfg.span == spanVal
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else Color.Transparent
                                )
                                .clickable {
                                    val updated = configs.toMutableList()
                                    updated[index] = cfg.copy(span = spanVal, visible = true)
                                    onUpdateConfigs(updated)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                ),
                                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 上移 / 下移排序按钮
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(enabled = index > 0) {
                            val updated = configs.toMutableList()
                            val tmp = updated[index - 1]
                            updated[index - 1] = updated[index]
                            updated[index] = tmp
                            onUpdateConfigs(updated)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowUpward,
                        contentDescription = "上移",
                        tint = if (index > 0) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(16.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(enabled = index < configs.lastIndex) {
                            val updated = configs.toMutableList()
                            val tmp = updated[index + 1]
                            updated[index + 1] = updated[index]
                            updated[index] = tmp
                            onUpdateConfigs(updated)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowDownward,
                        contentDescription = "下移",
                        tint = if (index < configs.lastIndex) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("完成自定义")
        }
    }
}

@Composable
private fun RenderModuleLargeCard(
    config: DashboardModuleConfig,
    context: Context,
    theolSlice: DomainSlice<TheolSnapshot>,
    pendingHomeworks: List<TheolHomeworkItem>,
    pendingTests: List<TheolTestItem>,
    activeLocalTodos: List<LocalTodoItem>,
    cardSlice: DomainSlice<CardSnapshot>,
    cardHasSession: Boolean,
    sjcxSlice: DomainSlice<SjcxSnapshot>,
    classroomSlot: ClassroomSlot,
    classroomDayLabel: String,
    onSwitchPage: (StartupPage) -> Unit,
    onOpenClassroom: () -> Unit
) {
    when (config.module) {
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

        DashboardModuleId.CLASSROOM -> {
            DashboardGlassCard(
                title = "查空闲教室",
                badge = "第 ${classroomSlot.week} 周 · 周${classroomDayLabel}",
                badgeColor = MaterialTheme.colorScheme.primary,
                onClick = onOpenClassroom
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        .clickable(onClick = onOpenClassroom)
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
}

/**
 * 1×1 半宽小卡片渲染器：严格固定在 142.dp 高度槽位内，保持左右两格对称齐平。
 */
@Composable
private fun RenderModuleSmallCard(
    config: DashboardModuleConfig,
    context: Context,
    theolSlice: DomainSlice<TheolSnapshot>,
    pendingHomeworks: List<TheolHomeworkItem>,
    pendingTests: List<TheolTestItem>,
    activeLocalTodos: List<LocalTodoItem>,
    cardSlice: DomainSlice<CardSnapshot>,
    cardHasSession: Boolean,
    sjcxSlice: DomainSlice<SjcxSnapshot>,
    classroomSlot: ClassroomSlot,
    classroomDayLabel: String,
    onSwitchPage: (StartupPage) -> Unit,
    onOpenClassroom: () -> Unit
) {
    val shape = RoundedCornerShape(26.dp)
    val (title, primaryValue, subLine, onClick) = when (config.module) {
        DashboardModuleId.THEOL -> {
            val countText = if (theolSlice.provenance.refreshState == RefreshState.NEVER) {
                "待同步"
            } else {
                "${pendingHomeworks.size} 项待交"
            }
            val sub = "测验 ${pendingTests.size} · 待办 ${activeLocalTodos.size}"
            Quad("北化在线", countText, sub) { onSwitchPage(StartupPage.Homework) }
        }

        DashboardModuleId.ECARD -> {
            val cardData = cardSlice.data
            val valText = if (cardData != null) {
                "¥ ${formatFenToYuan(cardData.account.balanceFen)}"
            } else {
                "点击登录"
            }
            val sub = if (cardData != null) {
                cardData.transactions.firstOrNull()?.let { "${it.title} ¥${formatFenToYuan(it.amountFen)}" }
                    ?: "${cardData.account.statusLabel} · ${cardData.account.maskedId}"
            } else {
                "独立查询余额与流水"
            }
            Quad("校园卡", valText, sub) {
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
                "排课 ${sjcxData.schedules.size} · 报告 ${sjcxData.reports.size} · ${String.format(Locale.ROOT, "%.1f", sjcxData.totalCredit)}分"
            } else {
                "实验课表 · 抢讲座"
            }
            Quad("实践教学", valText, sub) {
                SjcxActivity.launch(context, initialTab = 0)
            }
        }

        DashboardModuleId.CLASSROOM -> {
            val valText = "第 ${classroomSlot.week} 周"
            val sub = "周${classroomDayLabel} · 第 ${classroomSlot.period} 节空教室"
            Quad("空闲教室", valText, sub, onOpenClassroom)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(142.dp)
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = config.module.icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = primaryValue,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class Quad(
    val title: String,
    val primaryValue: String,
    val subLine: String,
    val onClick: () -> Unit
)

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

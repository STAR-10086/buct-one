package com.buct.course

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.WorkspacePremium
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
import com.buct.course.ui.system.GlassProgressBar
import com.buct.course.ui.system.GlassTextField
import com.buct.course.ui.system.GlassToaster
import com.buct.course.ui.system.GlassWindowHost
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
import com.buct.one.data.model.RefreshState
import com.buct.one.data.model.ScsActivityItem
import com.buct.one.data.model.ScsCategoryDetail
import com.buct.one.data.model.ScsEvalItem
import com.buct.one.data.model.ScsManualCalculator
import com.buct.one.data.model.ScsSnapshot
import com.buct.one.data.model.ScsTermScore
import com.buct.one.data.store.ScsManualStore
import com.buct.one.data.sync.ScsSyncService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 第二课堂综合素质评价平台（scs.buct.edu.cn）原生液态玻璃界面。
 * 复用 sidportal 统一身份认证换取 JWT，支持：
 * 1. 五育（德/智/体/美/劳）历年成绩与基础/拓展双上限进度
 * 2. 离线可用的五育估分记账本（按北化 80%/20% 封顶实时测算缺口，指导后续二课报名）
 * 3. 原生官方学生自评（细则浏览、审核状态、传图申报、撤销与评定记录）
 * 4. 校园二课活动清单与一键记入估分
 */
class ScsActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialTab = intent.getIntExtra(EXTRA_INITIAL_TAB, 0).coerceIn(0, 3)
        setContent {
            CourseSelectorTheme {
                GlassWindowHost {
                    ScsScreen(
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
                Intent(context, ScsActivity::class.java).apply {
                    putExtra(EXTRA_INITIAL_TAB, initialTab)
                }
            )
        }
    }
}

@Composable
private fun ScsScreen(
    initialTab: Int,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember(context) { ScsSyncService.getInstance(context) }
    val manualStore = remember(context) { ScsManualStore(context) }

    val slice by service.scsState.collectAsState()
    val syncing by service.syncing.collectAsState()
    var manualData by remember { mutableStateOf(manualStore.read()) }

    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) } // 0=五育成绩 1=估分记账 2=官方自评 3=二课活动
    var selectedTermKey by rememberSaveable { mutableStateOf("") }
    var manualTermKey by rememberSaveable { mutableStateOf("") }
    var manualDimFilter by rememberSaveable { mutableIntStateOf(0) } // 0=全部 1..5=德智体美劳
    var officialEvalDimId by rememberSaveable { mutableIntStateOf(1) } // 1..5
    var showOnlyScored by rememberSaveable { mutableStateOf(true) }
    var showLoginDialog by rememberSaveable { mutableStateOf(false) }
    var feedbackMsg by remember { mutableStateOf<String?>(null) }

    var manualEditorRequest by remember { mutableStateOf<ScsManualEditorRequest?>(null) }
    var showRulesSubpage by remember { mutableStateOf(false) }
    var activeSelfEvalTarget by remember { mutableStateOf<ScsSelfEvalTarget?>(null) }
    var showAuditHistory by remember { mutableStateOf(false) }

    val webVpnAuthLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            feedbackMsg = null
            scope.launch {
                service.refresh(userInitiated = true)
            }
        }
    }
    fun launchWebVpnAuth() {
        webVpnAuthLauncher.launch(
            Intent(context, LoginActivity::class.java).apply {
                putExtra(LoginActivity.EXTRA_WEBVPN_AUTH_MODE, true)
                putExtra(LoginActivity.EXTRA_RETURN_TO_CALLER, true)
            }
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
    val terms = snapshot?.terms.orEmpty()
    val singleTerms = remember(terms) { terms.filter { !it.isFullYear } }
    val currentWindow = snapshot?.currentWindow

    val defaultTerm = remember(terms) {
        terms.firstOrNull { !it.isFullYear && it.totalScore != null }
            ?: terms.firstOrNull { !it.isFullYear }
            ?: terms.firstOrNull()
    }
    val activeSelfEvalTerm = remember(terms, currentWindow) {
        if (currentWindow != null) {
            terms.firstOrNull { it.yearId == currentWindow.yearId && it.termId == currentWindow.termId }
        } else {
            null
        }
            ?: terms.firstOrNull { !it.isFullYear && it.details.isNotEmpty() }
            ?: terms.firstOrNull { !it.isFullYear }
    }
    val defaultManualTermKey = remember(currentWindow, activeSelfEvalTerm) {
        when {
            currentWindow != null && currentWindow.yearId > 0 && currentWindow.termId > 0 ->
                "${currentWindow.yearId}:${currentWindow.termId}"
            activeSelfEvalTerm != null -> "${activeSelfEvalTerm.yearId}:${activeSelfEvalTerm.termId}"
            else -> ScsManualStore.DEFAULT_TERM_KEY
        }
    }

    LaunchedEffect(terms) {
        if (selectedTermKey.isBlank() && defaultTerm != null) {
            selectedTermKey = "${defaultTerm.yearId}:${defaultTerm.termId}"
        }
    }
    LaunchedEffect(defaultManualTermKey) {
        if (manualTermKey.isBlank()) {
            manualTermKey = defaultManualTermKey
        }
    }

    val selectedTerm = remember(terms, selectedTermKey, defaultTerm) {
        terms.firstOrNull { "${it.yearId}:${it.termId}" == selectedTermKey } ?: defaultTerm
    }
    val activeManualTermKey = manualTermKey.ifBlank { defaultManualTermKey }
    val manualOfficialTerm = remember(singleTerms, activeManualTermKey) {
        singleTerms.firstOrNull { "${it.yearId}:${it.termId}" == activeManualTermKey }
    }
    val manualTermLabel = remember(manualOfficialTerm, currentWindow, activeManualTermKey) {
        when {
            manualOfficialTerm != null -> "${manualOfficialTerm.yearName} ${manualOfficialTerm.termName}"
            currentWindow != null && "${currentWindow.yearId}:${currentWindow.termId}" == activeManualTermKey ->
                "${currentWindow.yearName} ${currentWindow.termName}".trim().ifBlank { "当前学期" }
            activeManualTermKey == ScsManualStore.DEFAULT_TERM_KEY -> "当前学期（离线）"
            else -> activeManualTermKey
        }
    }
    val manualTermOptions = remember(singleTerms, currentWindow, manualData.entries, defaultManualTermKey) {
        val map = LinkedHashMap<String, String>()
        if (currentWindow != null && currentWindow.yearId > 0 && currentWindow.termId > 0) {
            val key = "${currentWindow.yearId}:${currentWindow.termId}"
            map[key] = "${currentWindow.yearName} ${currentWindow.termName}".trim().ifBlank { "当前学期" }
        }
        singleTerms.forEach { t ->
            val key = "${t.yearId}:${t.termId}"
            if (!map.containsKey(key)) {
                map[key] = "${t.yearName} ${t.termName}".trim()
            }
        }
        if (map.isEmpty()) {
            map[ScsManualStore.DEFAULT_TERM_KEY] = "当前学期（离线）"
        }
        manualData.entries.forEach { e ->
            if (!map.containsKey(e.termKey)) {
                map[e.termKey] = if (e.termKey == ScsManualStore.DEFAULT_TERM_KEY) "当前学期（离线）" else e.termKey
            }
        }
        map.entries.map { it.key to it.value }
    }

    val activeTermOfficialItems = remember(manualOfficialTerm, activeSelfEvalTerm) {
        (manualOfficialTerm?.details?.takeIf { it.isNotEmpty() } ?: activeSelfEvalTerm?.details.orEmpty())
            .flatMap { it.items }
    }

    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }
    val statusText = when {
        syncing -> "正在同步第二课堂数据…"
        selectedTab == 1 && snapshot == null -> "五育估分记账本（离线可用 · 官方 80%/20% 封顶）"
        slice.provenance.refreshState == RefreshState.NEVER -> "尚未同步第二课堂（复用统一身份认证）"
        slice.provenance.refreshState == RefreshState.FAILED_FALLBACK ->
            "刷新失败，展示历史快照 · ${timeFmt.format(Date(slice.provenance.capturedAt))}"
        else -> "更新于 ${timeFmt.format(Date(slice.provenance.capturedAt))}"
    }

    fun openSelfEvalForItem(
        item: ScsEvalItem,
        yearId: Int = currentWindow?.yearId?.takeIf { it > 0 } ?: activeSelfEvalTerm?.yearId ?: 0,
        termId: Int = currentWindow?.termId?.takeIf { it > 0 } ?: activeSelfEvalTerm?.termId ?: 0,
        prefillScore: Double? = null,
        prefillLaborHours: Double? = null,
        prefillMark: String = ""
    ) {
        if (yearId <= 0 || termId <= 0) {
            GlassToaster.show("请先刷新同步当前学期信息")
            return
        }
        activeSelfEvalTarget = ScsSelfEvalTarget(
            yearId = yearId,
            termId = termId,
            item = item,
            prefillScore = prefillScore,
            prefillLaborHours = prefillLaborHours,
            prefillMark = prefillMark
        )
    }

    GlassPageScaffold(
        title = "第二课堂",
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
                                val err = res.exceptionOrNull()?.message ?: "第二课堂刷新失败"
                                feedbackMsg = err
                                GlassToaster.show(err)
                                val needWebVpn = err.contains("WebVPN", ignoreCase = true) ||
                                    err.contains("二次验证")
                                if (!needWebVpn && (err.contains("登录") || err.contains("过期") || err.contains("会话"))) {
                                    showLoginDialog = true
                                }
                            } else {
                                GlassToaster.show("第二课堂数据已刷新")
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
                val needWebVpnAuth = bannerError.contains("WebVPN", ignoreCase = true) ||
                    bannerError.contains("二次验证")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFFF453A).copy(alpha = 0.12f))
                        .border(0.5.dp, Color(0xFFFF453A).copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = bannerError,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF453A)
                    )
                    if (needWebVpnAuth) {
                        SystemPrimaryButton(
                            text = "去完成 WebVPN 认证",
                            enabled = !syncing,
                            onClick = ::launchWebVpnAuth,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            SystemCompactSegmentedControl(
                options = listOf("五育成绩", "估分记账", "官方自评", "二课活动"),
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
                when (selectedTab) {
                    1 -> {
                        // ===== Tab 1: 估分记账（离线可用）=====
                        val termEntries = manualData.entries.filter { it.termKey == activeManualTermKey }
                        val calcResult = ScsManualCalculator.calculate(
                            termEntries = termEntries,
                            officialTerm = manualOfficialTerm,
                            officialDimensions = snapshot?.dimensions.orEmpty(),
                            customRules = manualData.customRules,
                            includeOfficialBaseline = manualData.includeOfficialBaseline && manualOfficialTerm != null
                        )

                        if (manualTermOptions.size > 1) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    manualTermOptions.forEach { (key, label) ->
                                        val active = key == activeManualTermKey
                                        val cnt = manualData.entries.count { it.termKey == key }
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(50))
                                                .background(if (active) MaterialTheme.colorScheme.primary else glassSurfaceColor())
                                                .border(
                                                    0.5.dp,
                                                    if (active) MaterialTheme.colorScheme.primary else glassBorderColor(),
                                                    RoundedCornerShape(50)
                                                )
                                                .clickable { manualTermKey = key }
                                                .padding(horizontal = 12.dp, vertical = 7.dp)
                                        ) {
                                            Text(
                                                text = if (cnt > 0) "$label ($cnt)" else label,
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

                        item {
                            ScsManualPlannerDashboardCard(
                                termLabel = manualTermLabel,
                                result = calcResult,
                                includeOfficialBaseline = manualData.includeOfficialBaseline,
                                hasOfficialTerm = manualOfficialTerm != null,
                                onToggleIncludeOfficial = {
                                    manualData = manualStore.setIncludeOfficialBaseline(!manualData.includeOfficialBaseline)
                                },
                                onAddClick = {
                                    manualEditorRequest = ScsManualEditorRequest(
                                        prefillTypeId = if (manualDimFilter in 1..5) manualDimFilter else 1
                                    )
                                },
                                onRulesClick = { showRulesSubpage = true }
                            )
                        }

                        item {
                            ScsManualQuickTemplatesRow(
                                onSelectTemplate = { tpl ->
                                    manualEditorRequest = ScsManualEditorRequest(
                                        prefillTypeId = tpl.typeId,
                                        prefillIsBasic = tpl.isBasic,
                                        prefillTitle = tpl.title,
                                        prefillScore = tpl.defaultScore,
                                        prefillLaborHours = tpl.defaultLaborHours,
                                        prefillLinkedItemMaxScore = tpl.capHint
                                    )
                                }
                            )
                        }

                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val filters = listOf(0 to "全部(${termEntries.size})") + (1..5).map { id ->
                                    id to "${scsDimShortName(id)}(${termEntries.count { it.typeId == id }})"
                                }
                                filters.forEach { (id, label) ->
                                    val active = manualDimFilter == id
                                    val tint = if (id == 0) MaterialTheme.colorScheme.primary else scsDimColor(id)
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(if (active) tint else glassSurfaceColor())
                                            .border(0.5.dp, if (active) tint else glassBorderColor(), RoundedCornerShape(50))
                                            .clickable { manualDimFilter = id }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                                            ),
                                            color = if (active) Color.White else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }

                        val visibleEntries = if (manualDimFilter in 1..5) {
                            termEntries.filter { it.typeId == manualDimFilter }
                        } else {
                            termEntries
                        }

                        if (visibleEntries.isEmpty()) {
                            item {
                                SystemEmptyState(
                                    title = "还没有估分记录",
                                    message = "点上方「记一笔」或北化常用二课模板，随时记录参加的讲座、志愿与文体活动，实时查看五育封顶与剩余缺口。",
                                    icon = Icons.Outlined.WorkspacePremium
                                )
                            }
                        } else {
                            items(visibleEntries, key = { it.id }) { entry ->
                                val linkedOfficial = entry.linkedItemId?.let { id ->
                                    activeTermOfficialItems.firstOrNull { it.id == id }
                                }
                                val hasOfficialSelfEval = activeSelfEvalTerm != null && activeSelfEvalTerm.details.isNotEmpty()
                                ScsManualEntryCard(
                                    entry = entry,
                                    canTransferToOfficial = hasOfficialSelfEval,
                                    onToggleEnabled = {
                                        manualData = manualStore.toggleEntry(entry.id)
                                    },
                                    onEdit = {
                                        manualEditorRequest = ScsManualEditorRequest(editingEntry = entry)
                                    },
                                    onDelete = {
                                        manualData = manualStore.deleteEntry(entry.id)
                                        GlassToaster.show("已删除估分记录")
                                    },
                                    onTransferToOfficial = {
                                        val noteText = buildString {
                                            if (entry.title.isNotBlank()) append(entry.title)
                                            if (entry.dateText.isNotBlank()) append("（${entry.dateText}）")
                                            if (entry.note.isNotBlank()) {
                                                if (isNotEmpty()) append("：")
                                                append(entry.note)
                                            }
                                        }
                                        if (linkedOfficial != null) {
                                            openSelfEvalForItem(
                                                item = linkedOfficial,
                                                prefillScore = entry.score.takeIf { it > 0.0 },
                                                prefillLaborHours = entry.laborHours.takeIf { it > 0.0 },
                                                prefillMark = noteText
                                            )
                                        } else {
                                            officialEvalDimId = entry.typeId
                                            selectedTab = 2
                                            GlassToaster.show("已切换到${scsDimShortName(entry.typeId)}育自评，请选择对应细则填报")
                                        }
                                    }
                                )
                            }
                        }
                    }

                    2 -> {
                        // ===== Tab 2: 官方自评 =====
                        if (snapshot == null) {
                            item {
                                SystemEmptyState(
                                    title = if (syncing) "正在同步第二课堂" else "尚未同步第二课堂",
                                    message = if (syncing) {
                                        "正在拉取官方自评窗口与五育评分细则…"
                                    } else {
                                        "请点击右上角刷新或登录统一身份认证以拉取官方自评细则；离线状态下可先在「估分记账」记录。"
                                    },
                                    icon = Icons.Outlined.School
                                )
                            }
                        } else {
                            val evalYearId = currentWindow?.yearId?.takeIf { it > 0 } ?: activeSelfEvalTerm?.yearId ?: 0
                            val evalTermId = currentWindow?.termId?.takeIf { it > 0 } ?: activeSelfEvalTerm?.termId ?: 0
                            val evalTermLabel = when {
                                currentWindow != null && (currentWindow.yearName.isNotBlank() || currentWindow.termName.isNotBlank()) ->
                                    "${currentWindow.yearName} ${currentWindow.termName}".trim()
                                activeSelfEvalTerm != null -> "${activeSelfEvalTerm.yearName} ${activeSelfEvalTerm.termName}".trim()
                                else -> "当前学期"
                            }
                            item {
                                ScsOfficialSelfEvalWindowCard(
                                    window = currentWindow,
                                    termLabel = evalTermLabel,
                                    onOpenHistory = {
                                        if (evalYearId > 0 && evalTermId > 0) {
                                            showAuditHistory = true
                                        } else {
                                            GlassToaster.show("暂无当前学期信息，请先刷新")
                                        }
                                    }
                                )
                            }

                            val evalDetails = activeSelfEvalTerm?.details.orEmpty()
                            if (evalDetails.isEmpty()) {
                                item {
                                    SystemEmptyState(
                                        title = "暂无当学期官方细则",
                                        message = "请点击右上角刷新重新拉取当前学期的五育自评细则表。",
                                        icon = Icons.Outlined.School
                                    )
                                }
                            } else {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        (1..5).forEach { id ->
                                            val active = officialEvalDimId == id
                                            val tint = scsDimColor(id)
                                            val cat = evalDetails.firstOrNull { it.typeId == id }
                                            val hasPending = cat?.items?.any { it.havePendApproval } == true
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .background(if (active) tint else glassSurfaceColor())
                                                    .border(
                                                        0.5.dp,
                                                        if (active) tint else glassBorderColor(),
                                                        RoundedCornerShape(14.dp)
                                                    )
                                                    .clickable { officialEvalDimId = id }
                                                    .padding(vertical = 9.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = if (hasPending) "${scsDimShortName(id)}·审" else scsDimShortName(id),
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = if (active) Color.White else MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                }

                                val currentCategory = evalDetails.firstOrNull { it.typeId == officialEvalDimId }
                                    ?: evalDetails.first()
                                val dimRule = ScsManualCalculator.resolveRules(snapshot.dimensions, emptyList())
                                    .firstOrNull { it.typeId == currentCategory.typeId }

                                item {
                                    val shape = RoundedCornerShape(20.dp)
                                    val tint = scsDimColor(currentCategory.typeId)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(shape)
                                            .background(glassSurfaceColor())
                                            .border(0.5.dp, glassBorderColor(), shape)
                                            .padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = currentCategory.title.ifBlank { "${scsDimShortName(currentCategory.typeId)}育评定" },
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = buildString {
                                                    append("基础 ${currentCategory.basicScore?.let(::formatScsScore) ?: "0"}/${formatScsScore(dimRule?.basicMax ?: 80.0)}")
                                                    append(" · 拓展 ${currentCategory.extScore?.let(::formatScsScore) ?: "0"}/${formatScsScore(dimRule?.extMax ?: 20.0)}")
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = "${currentCategory.totalScore?.let(::formatScsScore) ?: "0"} / ${formatScsScore(dimRule?.totalMax ?: currentCategory.maxTotalScore ?: 100.0)}",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = tint
                                        )
                                    }
                                }

                                val basicItems = currentCategory.items.filter { it.isBasic }
                                val extItems = currentCategory.items.filter { !it.isBasic }

                                if (basicItems.isNotEmpty()) {
                                    item {
                                        Text(
                                            text = "基础评定（${basicItems.size} 项 · 上限 ${formatScsScore(dimRule?.basicMax ?: 80.0)} 分）",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    items(basicItems, key = { "b_${it.id}" }) { evalItem ->
                                        ScsOfficialEvalItemCard(
                                            item = evalItem,
                                            onClick = {
                                                openSelfEvalForItem(
                                                    item = evalItem,
                                                    yearId = evalYearId,
                                                    termId = evalTermId
                                                )
                                            }
                                        )
                                    }
                                }

                                if (extItems.isNotEmpty()) {
                                    item {
                                        Text(
                                            text = "拓展评定（${extItems.size} 项 · 上限 ${formatScsScore(dimRule?.extMax ?: 20.0)} 分）",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    items(extItems, key = { "e_${it.id}" }) { evalItem ->
                                        ScsOfficialEvalItemCard(
                                            item = evalItem,
                                            onClick = {
                                                openSelfEvalForItem(
                                                    item = evalItem,
                                                    yearId = evalYearId,
                                                    termId = evalTermId
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    0 -> {
                        // ===== Tab 0: 五育成绩 =====
                        if (snapshot == null) {
                            item {
                                SystemEmptyState(
                                    title = if (syncing) "正在同步第二课堂" else "暂无第二课堂本地快照",
                                    message = if (syncing) {
                                        "正在通过统一身份认证拉取五育综测与二课活动数据…"
                                    } else {
                                        "校内网或校园 VPN 下点击右上角刷新或登录统一身份认证；也可直接切到「估分记账」离线记分。"
                                    },
                                    icon = Icons.Outlined.School
                                )
                            }
                        } else {
                            item {
                                ScsSummaryHeroCard(
                                    snapshot = snapshot,
                                    selectedTerm = selectedTerm,
                                    onGoSelfEval = { selectedTab = 2 }
                                )
                            }

                            if (terms.isNotEmpty()) {
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        terms.forEach { t ->
                                            val key = "${t.yearId}:${t.termId}"
                                            val active = selectedTerm?.let { "${it.yearId}:${it.termId}" == key } == true
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
                                                    .clickable { selectedTermKey = key }
                                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                                            ) {
                                                val label = buildString {
                                                    append(t.yearName)
                                                    append(' ')
                                                    append(t.termName)
                                                    t.totalScore?.let { append(" · ${formatScore(it)}") }
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

                            selectedTerm?.let { term ->
                                item {
                                    ScsFiveDimensionsCard(
                                        term = term,
                                        snapshot = snapshot
                                    )
                                }

                                if (term.details.isNotEmpty()) {
                                    item {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${term.yearName} ${term.termName} 评分细则",
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.onBackground
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(50))
                                                    .background(glassSurfaceColor())
                                                    .border(0.5.dp, glassBorderColor(), RoundedCornerShape(50))
                                                    .clickable { showOnlyScored = !showOnlyScored }
                                                    .padding(horizontal = 11.dp, vertical = 5.dp)
                                            ) {
                                                Text(
                                                    text = if (showOnlyScored) "仅看得分项" else "显示全部细则",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }

                                    items(term.details, key = { "${term.termId}:${it.typeId}" }) { category ->
                                        ScsCategoryDetailCard(
                                            category = category,
                                            onlyScored = showOnlyScored,
                                            onOpenSelfEval = { evalItem ->
                                                openSelfEvalForItem(
                                                    item = evalItem,
                                                    yearId = term.yearId,
                                                    termId = term.termId
                                                )
                                            }
                                        )
                                    }
                                } else if (term.isFullYear) {
                                    item {
                                        SystemEmptyState(
                                            title = "年度汇总视图",
                                            message = "「全年」视图展示年度汇总总分；点击上方「第一学期 / 第二学期」胶囊可查看各维度评分明细。",
                                            icon = Icons.Outlined.School
                                        )
                                    }
                                } else {
                                    item {
                                        SystemEmptyState(
                                            title = "暂无细则数据",
                                            message = "该学期暂未生成五育评分细则数据。",
                                            icon = Icons.Outlined.School
                                        )
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        // ===== Tab 3: 二课活动 =====
                        if (snapshot == null) {
                            item {
                                SystemEmptyState(
                                    title = if (syncing) "正在同步第二课堂" else "暂无第二课堂本地快照",
                                    message = if (syncing) {
                                        "正在拉取二课活动数据…"
                                    } else {
                                        "校内网或校园 VPN 下点击右上角刷新或登录统一身份认证。"
                                    },
                                    icon = Icons.Outlined.WorkspacePremium
                                )
                            }
                        } else {
                            val activities = snapshot.activities
                            if (activities.isEmpty()) {
                                item {
                                    SystemEmptyState(
                                        title = "暂无二课活动",
                                        message = if (slice.provenance.refreshState == RefreshState.EMPTY_CONFIRMED) {
                                            "当前第二课堂活动列表为空（学校暂未发布可报名活动）。"
                                        } else {
                                            "当前暂无第二课堂活动记录。"
                                        },
                                        icon = Icons.Outlined.WorkspacePremium
                                    )
                                }
                            } else {
                                items(activities, key = { it.id }) { act ->
                                    ScsActivityCard(
                                        act = act,
                                        onAddToManual = {
                                            val guessedType = ScsManualCalculator.guessTypeIdFromText(
                                                "${act.categoryName} ${act.title}"
                                            )
                                            val parsedScore = act.score.trim().toDoubleOrNull() ?: 0.0
                                            manualEditorRequest = ScsManualEditorRequest(
                                                prefillTypeId = guessedType,
                                                prefillIsBasic = true,
                                                prefillTitle = act.title,
                                                prefillScore = parsedScore,
                                                prefillDateText = act.startTime.take(10),
                                                prefillNote = listOf(act.categoryName, act.location)
                                                    .filter { it.isNotBlank() }
                                                    .joinToString(" · ")
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 二级页面：估分记账表单
    manualEditorRequest?.let { req ->
        ScsManualEntryEditorSubpage(
            termKey = activeManualTermKey,
            termLabel = manualTermLabel,
            request = req,
            officialItems = activeTermOfficialItems,
            onDismiss = { manualEditorRequest = null },
            onSave = { entry ->
                manualData = manualStore.upsertEntry(entry)
                GlassToaster.show(if (req.editingEntry == null) "已记入五育估分" else "估分记录已更新")
            }
        )
    }

    // 二级页面：自定义五育上限规则
    if (showRulesSubpage) {
        ScsManualRulesSubpage(
            officialDimensions = snapshot?.dimensions.orEmpty(),
            currentCustomRules = manualData.customRules,
            onDismiss = { showRulesSubpage = false },
            onSaveRules = { nextRules ->
                manualData = manualStore.saveCustomRules(nextRules)
            }
        )
    }

    // 二级页面：官方单项自评详情与提交
    activeSelfEvalTarget?.let { target ->
        ScsItemSelfEvalDetailSubpage(
            target = target,
            service = service,
            onDismiss = { activeSelfEvalTarget = null },
            onAddManualFallback = { evalItem ->
                selectedTab = 1
                manualEditorRequest = ScsManualEditorRequest(
                    prefillTypeId = evalItem.typeId.coerceIn(1, 5),
                    prefillIsBasic = evalItem.isBasic,
                    prefillTitle = evalItem.shortDesc.ifBlank { evalItem.description.take(24) },
                    prefillScore = evalItem.optionalScores.firstOrNull { it > 0.0 } ?: 0.0,
                    prefillLinkedItemId = evalItem.id,
                    prefillLinkedItemMaxScore = evalItem.itemMaxScore,
                    prefillLinkedItemShortDesc = evalItem.shortDesc.ifBlank { evalItem.description.take(36) }
                )
            }
        )
    }

    // 二级页面：官方评定历史
    if (showAuditHistory) {
        val evalYearId = currentWindow?.yearId?.takeIf { it > 0 } ?: activeSelfEvalTerm?.yearId ?: 0
        val evalTermId = currentWindow?.termId?.takeIf { it > 0 } ?: activeSelfEvalTerm?.termId ?: 0
        val evalTermLabel = when {
            currentWindow != null && (currentWindow.yearName.isNotBlank() || currentWindow.termName.isNotBlank()) ->
                "${currentWindow.yearName} ${currentWindow.termName}".trim()
            activeSelfEvalTerm != null -> "${activeSelfEvalTerm.yearName} ${activeSelfEvalTerm.termName}".trim()
            else -> "当前学期"
        }
        ScsAuditHistorySubpage(
            yearId = evalYearId,
            termId = evalTermId,
            termLabel = evalTermLabel,
            service = service,
            onDismiss = { showAuditHistory = false }
        )
    }

    if (showLoginDialog) {
        ScsSidLoginDialog(
            syncing = syncing,
            onDismiss = { showLoginDialog = false },
            onSubmit = { username, passwordChars ->
                scope.launch {
                    feedbackMsg = null
                    val res = service.loginAndSync(username, passwordChars)
                    if (res.isSuccess) {
                        showLoginDialog = false
                        GlassToaster.show("登录成功并已同步第二课堂数据")
                    } else {
                        val err = res.exceptionOrNull()?.message ?: "登录失败"
                        feedbackMsg = err
                        GlassToaster.show(err)
                        if (err.contains("WebVPN", ignoreCase = true) || err.contains("二次验证")) {
                            showLoginDialog = false
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun ScsSummaryHeroCard(
    snapshot: ScsSnapshot,
    selectedTerm: ScsTermScore?,
    onGoSelfEval: () -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    val cumulativeTotal = snapshot.terms
        .filter { !it.isFullYear }
        .sumOf { it.totalScore ?: 0.0 }
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
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WorkspacePremium,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = buildString {
                            append(snapshot.studentName.ifBlank { "北化同学" })
                            if (snapshot.studentId.isNotBlank()) append(" · ${snapshot.studentId}")
                        },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val orgText = listOf(snapshot.collegeName, snapshot.className)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    if (orgText.isNotBlank()) {
                        Text(
                            text = orgText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatScore(selectedTerm?.totalScore ?: cumulativeTotal),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (selectedTerm?.totalScore != null) {
                        "${selectedTerm.yearName} ${selectedTerm.termName}"
                    } else {
                        "累计已评总分"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        snapshot.currentWindow?.let { win ->
            val badgeBg = if (win.isOpen) Color(0xFF34C759).copy(alpha = 0.14f) else glassSurfaceColor()
            val badgeFg = if (win.isOpen) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(badgeBg)
                    .border(0.5.dp, glassBorderColor(), RoundedCornerShape(14.dp))
                    .clickable { onGoSelfEval() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${win.yearName} ${win.termName} · ${if (win.isOpen) "学生自评开放中" else "自评未开放"}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = badgeFg
                    )
                    if (win.endTime.isNotBlank()) {
                        Text(
                            text = "截止 ${win.endTime.take(10)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeFg
                        )
                    }
                }
                Text(
                    text = if (win.isOpen) "去自评 ›" else "看细则 ›",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (win.isOpen) badgeFg else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ScsFiveDimensionsCard(
    term: ScsTermScore,
    snapshot: ScsSnapshot
) {
    val shape = RoundedCornerShape(24.dp)
    val rulesById = remember(snapshot.dimensions, term.isFullYear) {
        val factor = if (term.isFullYear) 2.0 else 1.0
        ScsManualCalculator.resolveRules(snapshot.dimensions, emptyList()).associate { rule ->
            rule.typeId to Triple(rule.totalMax * factor, rule.basicMax * factor, rule.extMax * factor)
        }
    }
    val detailByType = remember(term.details) { term.details.associateBy { it.typeId } }
    val rows = listOf(
        Triple(1, "德 · 道德思想", term.moral),
        Triple(2, "智 · 学术科创", term.academic),
        Triple(3, "体 · 体育健康", term.physical),
        Triple(4, "美 · 美学人文", term.aesthetics),
        Triple(5, "劳 · 劳动实践", term.labor)
    )

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
            Text(
                text = "五育分项得分（${term.yearName} ${term.termName}）",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (term.totalScore != null) "总分 ${formatScore(term.totalScore)}" else "待评定",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary
            )
        }
        rows.forEach { (typeId, label, value) ->
            val tint = scsDimColor(typeId)
            val (maxScore, basicMax, extMax) = rulesById[typeId] ?: Triple(100.0, 80.0, 20.0)
            val detail = detailByType[typeId]
            val ratio = if (value != null && maxScore > 0.0) (value / maxScore).toFloat().coerceIn(0f, 1f) else 0f
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (value != null) "${formatScore(value)} / ${formatScore(maxScore)}" else "-- / ${formatScore(maxScore)}",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (value != null) tint else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                GlassProgressBar(
                    progress = ratio,
                    tint = tint,
                    height = 6.dp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val splitText = buildString {
                        append("基础 ")
                        append(detail?.basicScore?.let(::formatScore) ?: "--")
                        append("/${formatScore(basicMax)}")
                        append(" · 拓展 ")
                        append(detail?.extScore?.let(::formatScore) ?: "--")
                        append("/${formatScore(extMax)}")
                    }
                    Text(
                        text = splitText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (value != null && maxScore > value) {
                        Text(
                            text = "差 ${formatScore(maxScore - value)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScsCategoryDetailCard(
    category: ScsCategoryDetail,
    onlyScored: Boolean,
    onOpenSelfEval: (ScsEvalItem) -> Unit
) {
    var expanded by remember(category.typeId, onlyScored) {
        mutableStateOf(category.items.any { (it.score ?: 0.0) > 0.0 || it.mark.isNotBlank() || it.havePendApproval })
    }
    val visibleItems = remember(category.items, onlyScored) {
        if (onlyScored) {
            val scored = category.items.filter {
                (it.score ?: 0.0) > 0.0 || it.mark.isNotBlank() || (it.laborHours ?: 0.0) > 0.0 || it.havePendApproval
            }
            scored.ifEmpty { category.items }
        } else {
            category.items
        }
    }
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .clickable { expanded = !expanded }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.title.ifBlank { "类别 ${category.typeId}" },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                val summary = buildString {
                    category.basicScore?.let { append("基础 ${formatScore(it)}") }
                    category.extScore?.let {
                        if (isNotEmpty()) append(" · ")
                        append("拓展 ${formatScore(it)}")
                    }
                    if (isEmpty()) append("共 ${category.items.size} 项细则")
                }
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                category.totalScore?.let { total ->
                    Text(
                        text = buildString {
                            append(formatScore(total))
                            category.maxTotalScore?.let { append(" / ${formatScore(it)}") }
                        },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SystemDivider(alpha = 0.35f)
                visibleItems.forEach { item ->
                    ScsEvalItemRow(
                        item = item,
                        onOpenSelfEval = { onOpenSelfEval(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ScsEvalItemRow(
    item: ScsEvalItem,
    onOpenSelfEval: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), RoundedCornerShape(14.dp))
            .clickable { onOpenSelfEval() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = "[${if (item.isBasic) "基础" else "拓展"}] ${item.shortDesc.ifBlank { item.description }}",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            Column(horizontalAlignment = Alignment.End) {
                val scoreVal = item.score
                Text(
                    text = if (scoreVal != null) "+${formatScore(scoreVal)} 分" else "未填",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if ((scoreVal ?: 0.0) > 0.0) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.havePendApproval) {
                    Text(
                        text = "待审核",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color(0xFFFF9F0A)
                    )
                }
            }
        }
        if (item.description.isNotBlank() && item.description != item.shortDesc) {
            Text(
                text = item.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (item.mark.isNotBlank()) {
            Text(
                text = "申报说明：${item.mark}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        item.laborHours?.takeIf { it > 0.0 }?.let { hrs ->
            Text(
                text = "劳动时长：${formatScore(hrs)} 小时",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFF9F0A)
            )
        }
        if (item.teacherComments.isNotBlank()) {
            Text(
                text = "评语：${item.teacherComments}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (item.stuAble) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.itemMaxScore?.takeIf { it > 0.0 }?.let { "单项封顶 ${formatScore(it)} 分" }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "自评详情 ›",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ScsActivityCard(
    act: ScsActivityItem,
    onAddToManual: () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
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
                text = act.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (act.score.isNotBlank()) {
                Text(
                    text = "+${act.score} 分",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        val meta = listOf(act.categoryName, act.startTime, act.location, act.status)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .clickable { onAddToManual() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "记入估分",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ScsSidLoginDialog(
    syncing: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (username: String, passwordChars: CharArray) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
                text = "登录 sidportal.buct.edu.cn 以获取第二课堂五育评定成绩（凭据仅用于本次换票，学号密码不落盘）。",
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

private fun formatScore(value: Double): String = formatScsScore(value)

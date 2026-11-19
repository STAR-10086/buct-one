package com.buct.course.ui.route

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buct.course.TheolAttachmentOpener
import com.buct.course.TheolSubmitWebViewActivity
import com.buct.course.ui.system.GlassLoadingIndicator
import com.buct.course.ui.system.GlassTextField
import com.buct.course.ui.system.GlassToaster
import com.buct.course.ui.system.LocalAppOverlayBottomInset
import com.buct.course.ui.system.SystemActionButton
import com.buct.course.ui.system.SystemCompactSegmentedControl
import com.buct.course.ui.system.SystemDestructiveButton
import com.buct.course.ui.system.SystemDialog
import com.buct.course.ui.system.SystemDivider
import com.buct.course.ui.system.SystemEmptyState
import com.buct.course.ui.system.SystemIconButton
import com.buct.course.ui.system.SystemPrimaryButton
import com.buct.course.ui.system.SystemSecondaryButton
import com.buct.course.ui.system.SystemTopBar
import com.buct.course.ui.system.glassBorderColor
import com.buct.course.ui.system.glassSurfaceColor
import com.buct.one.data.model.LocalTodoItem
import com.buct.one.data.model.RefreshState
import com.buct.one.data.model.SjcxExperimentReportItem
import com.buct.one.data.model.TheolDownloadedFile
import com.buct.one.data.model.TheolFilterPrefs
import com.buct.one.data.model.TheolHomeworkAttachment
import com.buct.one.data.model.TheolHomeworkDetail
import com.buct.one.data.model.TheolHomeworkItem
import com.buct.one.data.model.TheolTestDetail
import com.buct.one.data.model.TheolTestItem
import com.buct.one.data.sync.SjcxSyncService
import com.buct.one.data.sync.TheolSyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
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
    val sjcxService = remember(context) { SjcxSyncService.getInstance(context) }

    val theolSlice by service.theolState.collectAsState()
    val localTodos by service.todosState.collectAsState()
    val filterPrefs by service.filterPrefs.collectAsState()
    val theolSyncing by service.syncing.collectAsState()
    val theolBgSyncing by service.backgroundSyncing.collectAsState()
    val sjcxSlice by sjcxService.sjcxState.collectAsState()
    val sjcxSyncing by sjcxService.syncing.collectAsState()
    val syncing = theolSyncing || sjcxSyncing

    var selectedTab by rememberSaveable { mutableIntStateOf(0) } // 0=待交 1=全部 2=测验 3=实验 4=待办
    var showLoginDialog by rememberSaveable { mutableStateOf(false) }
    var showAddTodoDialog by rememberSaveable { mutableStateOf(false) }
    var showFilterManageDialog by rememberSaveable { mutableStateOf(false) }
    var pendingBlacklistCourse by remember { mutableStateOf<Pair<String, String>?>(null) }
    var selectedHomeworkForDetail by remember { mutableStateOf<TheolHomeworkItem?>(null) }
    var selectedTestForDetail by remember { mutableStateOf<TheolTestItem?>(null) }
    var pendingUploadExpReport by remember { mutableStateOf<SjcxExperimentReportItem?>(null) }
    var feedbackMsg by remember { mutableStateOf<String?>(null) }
    var autoTried by rememberSaveable { mutableStateOf(false) }

    val pdfUploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val targetItem = pendingUploadExpReport
        pendingUploadExpReport = null
        if (uri == null || targetItem == null) return@rememberLauncherForActivityResult
        scope.launch {
            feedbackMsg = null
            GlassToaster.show("正在上传实验报告…")
            val uploadResult = withContext(Dispatchers.IO) {
                runCatching {
                    var displayName = "实验报告.pdf"
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && cursor.moveToFirst()) {
                            displayName = cursor.getString(nameIdx)?.takeIf { it.isNotBlank() } ?: displayName
                        }
                    }
                    val tempDir = File(context.cacheDir, "sjcx_upload").apply { if (!exists()) mkdirs() }
                    val tempFile = File(tempDir, "upload_${System.currentTimeMillis()}.pdf")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw IllegalStateException("无法读取所选 PDF 文件")
                    try {
                        sjcxService.uploadExperimentReport(targetItem, tempFile, displayName).getOrThrow()
                    } finally {
                        runCatching { tempFile.delete() }
                    }
                }
            }
            if (uploadResult.isSuccess) {
                GlassToaster.show(uploadResult.getOrNull() ?: "实验报告上传成功")
            } else {
                val err = uploadResult.exceptionOrNull()?.message ?: "实验报告上传失败"
                feedbackMsg = err
                GlassToaster.show(err)
            }
        }
    }

    // 首次进入作业页且本地已有教务统一认证凭证时，自动静默复用凭证同步北化在线与实践教学实验报告
    LaunchedEffect(Unit) {
        if (!autoTried && service.hasSavedSsoSession()) {
            autoTried = true
            if (theolSlice.provenance.refreshState == RefreshState.NEVER) {
                launch { service.refreshIfSessionValid() }
            }
            if (sjcxSlice.provenance.refreshState == RefreshState.NEVER) {
                launch { sjcxService.refresh() }
            }
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
    val expReports = sjcxSlice.data?.reports.orEmpty()
    val activeTodos = remember(localTodos) { localTodos.filter { !it.deleted } }

    val hasActiveFilters = filterPrefs.blacklistedCourses.isNotEmpty() ||
        filterPrefs.hiddenHomeworkIds.isNotEmpty() ||
        filterPrefs.hiddenTestKeys.isNotEmpty()

    val bottomInset = LocalAppOverlayBottomInset.current
    val timeFormatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }

    val baseSubtitle = when (theolSlice.provenance.refreshState) {
        RefreshState.NEVER -> "尚未同步"
        RefreshState.FRESH, RefreshState.EMPTY_CONFIRMED ->
            "更新于 ${timeFormatter.format(Date(theolSlice.provenance.capturedAt))}"
        RefreshState.STALE ->
            "历史快照 · ${timeFormatter.format(Date(theolSlice.provenance.capturedAt))}"
        RefreshState.FAILED_FALLBACK ->
            "同步失败（已保留旧快照）"
    }
    val subtitle = if (theolBgSyncing) "$baseSubtitle · 后台补齐已交课程中…" else baseSubtitle

    Column(modifier = Modifier.fillMaxSize()) {
        SystemTopBar(
            title = "作业",
            subtitle = "北化在线 · $subtitle",
            actions = {
                SystemIconButton(
                    icon = Icons.Outlined.FilterList,
                    contentDescription = "黑名单与已隐藏管理",
                    tint = if (hasActiveFilters) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    onClick = { showFilterManageDialog = true }
                )
                SystemIconButton(
                    icon = Icons.Outlined.Add,
                    contentDescription = "新建本地待办",
                    onClick = { showAddTodoDialog = true }
                )
                SystemIconButton(
                    icon = Icons.Outlined.Login,
                    contentDescription = "登录北化在线",
                    onClick = { showLoginDialog = true }
                )
                if (syncing) {
                    GlassLoadingIndicator(size = 38.dp)
                } else {
                    SystemIconButton(
                        icon = Icons.Outlined.Refresh,
                        contentDescription = "刷新北化在线",
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = {
                            scope.launch {
                                feedbackMsg = null
                                if (selectedTab == 3) {
                                    val res = sjcxService.refresh()
                                    if (res.isFailure) {
                                        feedbackMsg = res.exceptionOrNull()?.message
                                        if (feedbackMsg?.contains("登录") == true || feedbackMsg?.contains("过期") == true) {
                                            showLoginDialog = true
                                        }
                                    }
                                } else {
                                    launch { sjcxService.refresh() }
                                    val res = service.refreshIfSessionValid()
                                    if (res.isFailure) {
                                        feedbackMsg = res.exceptionOrNull()?.message
                                        if (feedbackMsg?.contains("登录") == true || feedbackMsg?.contains("过期") == true) {
                                            showLoginDialog = true
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            }
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            val segments = listOf(
                "待交${pendingHomeworks.size}",
                "全部",
                "测验${tests.size}",
                "实验${expReports.size}",
                "待办${activeTodos.count { !it.completed }}"
            )
            SystemCompactSegmentedControl(
                options = segments,
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it }
            )

            val bannerError = feedbackMsg ?: if (selectedTab == 3) sjcxSlice.lastError else theolSlice.lastError
            if (!bannerError.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFFFF453A).copy(alpha = 0.12f))
                        .border(0.5.dp, Color(0xFFFF453A).copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = bannerError,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF453A)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomInset + 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (selectedTab) {
                    0 -> {
                        if (pendingHomeworks.isEmpty()) {
                            item {
                                SystemEmptyState(
                                    title = if (theolSlice.provenance.refreshState == RefreshState.NEVER) "北化在线尚未同步" else "当前暂无待交作业",
                                    message = if (theolSlice.provenance.refreshState == RefreshState.NEVER) {
                                        "点击右上角登录图标，用统一身份认证账号原生登录即可获取待交作业。"
                                    } else {
                                        "太棒了，当前没有待提交的作业任务。"
                                    },
                                    icon = Icons.Outlined.Assignment
                                )
                            }
                        } else {
                            items(pendingHomeworks, key = { it.hwtid }) { hw ->
                                val targetUrl = hw.submitHref.ifBlank { hw.detailHref }
                                HomeworkGlassCard(
                                    hw = hw,
                                    onOpenDetail = { selectedHomeworkForDetail = hw },
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
                            item {
                                SystemEmptyState(
                                    title = "暂无作业记录",
                                    message = "请点击右上角刷新或登录北化在线获取全部课程作业。",
                                    icon = Icons.Outlined.Assignment
                                )
                            }
                        } else {
                            items(homeworks, key = { it.hwtid }) { hw ->
                                val targetUrl = when {
                                    hw.canSubmit -> hw.submitHref.ifBlank { hw.detailHref }
                                    hw.resultHref.isNotBlank() -> hw.resultHref
                                    else -> hw.detailHref
                                }
                                HomeworkGlassCard(
                                    hw = hw,
                                    onOpenDetail = { selectedHomeworkForDetail = hw },
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
                            item {
                                SystemEmptyState(
                                    title = "暂无在线测验记录",
                                    message = "当前课程暂无在线测验或已全部隐藏。",
                                    icon = Icons.Outlined.Assignment
                                )
                            }
                        } else {
                            items(tests, key = { "${it.lid}:${it.testId}" }) { test ->
                                TestGlassCard(
                                    test = test,
                                    onOpenDetail = { selectedTestForDetail = test },
                                    onOpenTest = {
                                        TheolSubmitWebViewActivity.launch(
                                            context = context,
                                            url = resolveSafeTestUrl(test),
                                            title = test.title,
                                            courseName = test.courseName,
                                            courseLid = test.lid
                                        )
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
                        if (expReports.isEmpty()) {
                            item {
                                SystemEmptyState(
                                    title = "暂无实验报告任务",
                                    message = if (sjcxSlice.provenance.refreshState == RefreshState.NEVER) {
                                        "尚未同步实践教学平台（sjcx.buct.edu.cn）。点击右上角刷新或登录按钮，即可同步本学期实验报告与上传入口。"
                                    } else {
                                        "本学期暂无实验报告任务。"
                                    },
                                    icon = Icons.Outlined.Science
                                )
                            }
                        } else {
                            items(expReports, key = { "${it.courseId}:${it.expId}:${it.stuId}" }) { exp ->
                                ExperimentReportGlassCard(
                                    item = exp,
                                    enabled = !syncing,
                                    onDownloadTemplate = {
                                        scope.launch {
                                            GlassToaster.show("正在下载实验报告模板…")
                                            val res = sjcxService.downloadReportTemplate(exp)
                                            res.onSuccess { downloaded ->
                                                TheolAttachmentOpener.open(
                                                    context,
                                                    TheolDownloadedFile(
                                                        filePath = downloaded.localPath,
                                                        fileName = downloaded.displayName,
                                                        mimeType = downloaded.mimeType,
                                                        sizeBytes = downloaded.sizeBytes
                                                    )
                                                )
                                            }.onFailure { err ->
                                                GlassToaster.show(err.message ?: "下载模板失败")
                                            }
                                        }
                                    },
                                    onDownloadSubmitted = {
                                        scope.launch {
                                            GlassToaster.show("正在下载已提交报告…")
                                            val res = sjcxService.downloadSubmittedReport(exp)
                                            res.onSuccess { downloaded ->
                                                TheolAttachmentOpener.open(
                                                    context,
                                                    TheolDownloadedFile(
                                                        filePath = downloaded.localPath,
                                                        fileName = downloaded.displayName,
                                                        mimeType = downloaded.mimeType,
                                                        sizeBytes = downloaded.sizeBytes
                                                    )
                                                )
                                            }.onFailure { err ->
                                                GlassToaster.show(err.message ?: "下载已交报告失败")
                                            }
                                        }
                                    },
                                    onUploadPdf = {
                                        pendingUploadExpReport = exp
                                        pdfUploadLauncher.launch(arrayOf("application/pdf"))
                                    }
                                )
                            }
                        }
                    }
                    4 -> {
                        if (activeTodos.isEmpty()) {
                            item {
                                SystemEmptyState(
                                    title = "暂无本地待办",
                                    message = "点击右上角 + 新建自定义作业或 DDL 提醒。",
                                    icon = Icons.Outlined.CheckCircle
                                )
                            }
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
    }

    selectedHomeworkForDetail?.let { hw ->
        HomeworkDetailDialog(
            hw = hw,
            service = service,
            onDismiss = { selectedHomeworkForDetail = null },
            onGoSubmit = {
                selectedHomeworkForDetail = null
                val targetUrl = when {
                    hw.canSubmit -> hw.submitHref.ifBlank { hw.detailHref }
                    hw.resultHref.isNotBlank() -> hw.resultHref
                    else -> hw.detailHref
                }
                TheolSubmitWebViewActivity.launch(
                    context = context,
                    url = targetUrl,
                    title = hw.title,
                    courseName = hw.courseName,
                    courseLid = hw.lid
                )
            },
            onOpenCourseHome = {
                selectedHomeworkForDetail = null
                val courseUrl = "https://course.buct.edu.cn/meol/jpk/course/layout/newpage/index.jsp?courseId=${hw.lid}"
                TheolSubmitWebViewActivity.launch(
                    context = context,
                    url = courseUrl,
                    title = hw.courseName.ifBlank { "课程主页" },
                    courseName = hw.courseName,
                    courseLid = hw.lid
                )
            },
            onOpenAttachment = { attachment ->
                TheolSubmitWebViewActivity.launch(
                    context = context,
                    url = attachment.url,
                    title = attachment.name,
                    courseName = hw.courseName,
                    courseLid = hw.lid
                )
            }
        )
    }

    selectedTestForDetail?.let { test ->
        TestDetailDialog(
            test = test,
            service = service,
            onDismiss = { selectedTestForDetail = null },
            onOpenTestList = {
                selectedTestForDetail = null
                TheolSubmitWebViewActivity.launch(
                    context = context,
                    url = resolveSafeTestUrl(test),
                    title = test.title,
                    courseName = test.courseName,
                    courseLid = test.lid
                )
            },
            onOpenResult = { resultUrl ->
                selectedTestForDetail = null
                TheolSubmitWebViewActivity.launch(
                    context = context,
                    url = resultUrl,
                    title = "${test.title} · 成绩结果",
                    courseName = test.courseName,
                    courseLid = test.lid
                )
            },
            onOpenCourseHome = {
                selectedTestForDetail = null
                val courseUrl = "https://course.buct.edu.cn/meol/jpk/course/layout/newpage/index.jsp?courseId=${URLEncoder.encode(test.lid, "UTF-8")}"
                TheolSubmitWebViewActivity.launch(
                    context = context,
                    url = courseUrl,
                    title = test.courseName.ifBlank { "课程主页" },
                    courseName = test.courseName,
                    courseLid = test.lid
                )
            }
        )
    }

    pendingBlacklistCourse?.let { (lid, courseName) ->
        SystemDialog(
            onDismissRequest = { pendingBlacklistCourse = null },
            title = {
                Text(
                    text = "拉黑课程",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
            },
            confirmButton = {
                SystemDestructiveButton(
                    text = "拉黑",
                    onClick = {
                        service.blacklistCourse(lid, courseName)
                        pendingBlacklistCourse = null
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                SystemSecondaryButton(
                    text = "取消",
                    onClick = { pendingBlacklistCourse = null },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            Text(
                text = "确定将「$courseName」加入黑名单吗？之后该课程的作业和测验（包括刷新同步后）将不再显示，可在右上角筛选管理中随时移出。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
                    val pwdCopyForSjcx = pwdChars.clone()
                    val res = service.loginAndSync(username, pwdChars)
                    if (res.isSuccess) {
                        pwdCopyForSjcx.fill('\u0000')
                        launch { sjcxService.refresh() }
                        showLoginDialog = false
                    } else {
                        val sjcxRes = sjcxService.loginAndSync(username, pwdCopyForSjcx)
                        if (sjcxRes.isSuccess) {
                            launch { service.refreshIfSessionValid() }
                            showLoginDialog = false
                        } else {
                            feedbackMsg = res.exceptionOrNull()?.message ?: "登录失败"
                        }
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
                selectedTab = 4
            }
        )
    }
}

@Composable
private fun HomeworkGlassCard(
    hw: TheolHomeworkItem,
    onOpenDetail: () -> Unit,
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
            .clickable(onClick = onOpenDetail)
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
                            text = { Text("查看作业详情") },
                            onClick = {
                                menuExpanded = false
                                onOpenDetail()
                            }
                        )
                        if (hw.canSubmit) {
                            DropdownMenuItem(
                                text = { Text("直接去交作业") },
                                onClick = {
                                    menuExpanded = false
                                    onOpenSubmit()
                                }
                            )
                        } else if (hw.hasResult || hw.resultHref.isNotBlank()) {
                            DropdownMenuItem(
                                text = { Text("查看已交结果") },
                                onClick = {
                                    menuExpanded = false
                                    onOpenSubmit()
                                }
                            )
                        }
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
            Text(
                text = "查看详情 ›",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun HomeworkDetailDialog(
    hw: TheolHomeworkItem,
    service: TheolSyncService,
    onDismiss: () -> Unit,
    onGoSubmit: () -> Unit,
    onOpenCourseHome: () -> Unit,
    onOpenAttachment: (com.buct.one.data.model.TheolHomeworkAttachment) -> Unit
) {
    var detail by remember(hw.hwtid) { mutableStateOf<TheolHomeworkDetail?>(null) }
    var loading by remember(hw.hwtid) { mutableStateOf(true) }
    var errorMsg by remember(hw.hwtid) { mutableStateOf<String?>(null) }
    var reloadTick by remember(hw.hwtid) { mutableIntStateOf(0) }
    val detailContext = LocalContext.current
    val attachmentScope = rememberCoroutineScope()
    var downloadingUrl by remember(hw.hwtid) { mutableStateOf<String?>(null) }
    var attachmentError by remember(hw.hwtid) { mutableStateOf<String?>(null) }
    var lastFailedAttachment by remember(hw.hwtid) { mutableStateOf<TheolHomeworkAttachment?>(null) }

    LaunchedEffect(hw.hwtid, reloadTick) {
        loading = true
        errorMsg = null
        val res = service.fetchHomeworkDetail(hw, forceRefresh = reloadTick > 0)
        if (res.isSuccess) {
            detail = res.getOrNull()
        } else {
            errorMsg = res.exceptionOrNull()?.message ?: "加载作业详情失败"
        }
        loading = false
    }

    val statusColor = when {
        hw.canSubmit -> Color(0xFFFF453A)
        hw.hasResult || hw.status == "已完成" -> Color(0xFF34C759)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val deadlineDisplay = detail?.deadline?.ifBlank { null } ?: hw.deadline.ifBlank { "无限制" }
    val scoreDisplay = detail?.score?.ifBlank { null } ?: hw.score
    val publisherDisplay = detail?.publisher?.ifBlank { null } ?: hw.publisher

    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = hw.courseName.ifBlank { "北化在线课程" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = detail?.title?.ifBlank { null } ?: hw.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        confirmButton = {
            SystemPrimaryButton(
                text = when {
                    hw.canSubmit -> "去交作业"
                    hw.hasResult || hw.resultHref.isNotBlank() -> "查看结果"
                    else -> "打开作业页"
                },
                onClick = onGoSubmit,
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            SystemSecondaryButton(
                text = "关闭",
                onClick = onDismiss,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(statusColor.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (hw.isGroup) "${hw.status} · 分组作业" else hw.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor
                    )
                }
                if (publisherDisplay.isNotBlank()) {
                    Text(
                        text = "发布：$publisherDisplay",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "截止时间：$deadlineDisplay",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (scoreDisplay.isNotBlank()) {
                    Text(
                        text = "评分：$scoreDisplay",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (hw.lid.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        .clickable { onOpenCourseHome() }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "进入课程主页",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = Icons.Outlined.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            SystemDivider(alpha = 0.35f)

            when {
                loading -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassLoadingIndicator(size = 24.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "正在获取作业要求…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                errorMsg != null -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "未能读取正文说明：${errorMsg}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFF453A)
                        )
                        Text(
                            text = "仍可点击下方按钮尝试直接进入北化在线查看或提交。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = { reloadTick++ },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("重试拉取详情")
                        }
                    }
                }
                else -> {
                    val content = detail?.contentText.orEmpty()
                    if (content.isNotBlank()) {
                        SelectionContainer {
                            Text(
                                text = content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        Text(
                            text = "老师未填写文字说明，可点击下方按钮打开北化在线查看。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    val attachments = detail?.attachments.orEmpty()
                    if (attachments.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "附件（${attachments.size}）",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        attachments.forEach { att ->
                            val isDownloading = downloadingUrl == att.url
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                                    .clickable(enabled = downloadingUrl == null) {
                                        attachmentError = null
                                        lastFailedAttachment = null
                                        downloadingUrl = att.url
                                        attachmentScope.launch {
                                            val result = service.downloadAttachment(
                                                url = att.url,
                                                suggestedName = att.name,
                                                courseLid = hw.lid
                                            )
                                            downloadingUrl = null
                                            result.onSuccess { downloaded ->
                                                if (downloaded.isHtmlPage) {
                                                    onOpenAttachment(att)
                                                } else if (!TheolAttachmentOpener.open(detailContext, downloaded)) {
                                                    lastFailedAttachment = att
                                                    attachmentError = "未找到可打开「${downloaded.fileName}」的应用"
                                                }
                                            }.onFailure { err ->
                                                lastFailedAttachment = att
                                                attachmentError = err.message ?: "附件下载失败，请稍后重试"
                                            }
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AttachFile,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (isDownloading) "${att.name}（正在下载…）" else att.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isDownloading) {
                                    GlassLoadingIndicator(size = 18.dp)
                                } else {
                                    Icon(
                                        imageVector = Icons.Outlined.OpenInNew,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                        attachmentError?.let { errMsg ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = errMsg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                lastFailedAttachment?.let { failed ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(
                                            onClick = { onOpenAttachment(failed) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "在网页中打开",
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun resolveSafeTestUrl(test: TheolTestItem): String {
    val raw = test.testUrl.trim()
    if (raw.isNotEmpty() && !raw.contains("test_start.jsp", ignoreCase = true)) {
        return if (raw.startsWith("http://course.buct.edu.cn", ignoreCase = true)) {
            "https://" + raw.removePrefix("http://")
        } else {
            raw
        }
    }
    if (test.lid.isNotBlank()) {
        val encodedLid = URLEncoder.encode(test.lid.trim(), "UTF-8")
        return "https://course.buct.edu.cn/meol/common/question/test/student/list.jsp" +
            "?sortColumn=createTime&status=1&tagbug=client&sortDirection=-1&strStyle=new03" +
            "&cateId=$encodedLid&pagingPage=1&pagingNumberPer=30"
    }
    return "https://course.buct.edu.cn/meol/welcomepage/student/index.jsp"
}

@Composable
private fun TestGlassCard(
    test: TheolTestItem,
    onOpenDetail: () -> Unit,
    onOpenTest: () -> Unit,
    onHideTest: () -> Unit,
    onBlacklistCourse: () -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    val badgeColor = when {
        test.canStart -> Color(0xFFFF453A)
        test.hasResult || test.status == "已完成" -> Color(0xFF34C759)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .clickable(onClick = onOpenDetail)
            .padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = test.courseName.ifBlank { "北化在线课程" },
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
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(badgeColor.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = test.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor
                    )
                }
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
                        DropdownMenuItem(
                            text = { Text("查看测验详情") },
                            onClick = {
                                menuExpanded = false
                                onOpenDetail()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("直接进入测验") },
                            onClick = {
                                menuExpanded = false
                                onOpenTest()
                            }
                        )
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "时间：${test.startTime} ~ ${test.endTime}${if (test.duration.isNotBlank()) " · 时长 ${test.duration}" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "查看详情 ›",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun TestDetailDialog(
    test: TheolTestItem,
    service: TheolSyncService,
    onDismiss: () -> Unit,
    onOpenTestList: () -> Unit,
    onOpenResult: (String) -> Unit,
    onOpenCourseHome: () -> Unit
) {
    var detail by remember(test.lid, test.testId) { mutableStateOf<TheolTestDetail?>(null) }
    var loading by remember(test.lid, test.testId) { mutableStateOf(true) }
    var errorMsg by remember(test.lid, test.testId) { mutableStateOf<String?>(null) }
    var reloadTick by remember(test.lid, test.testId) { mutableIntStateOf(0) }

    LaunchedEffect(test.lid, test.testId, reloadTick) {
        loading = true
        errorMsg = null
        val res = service.fetchTestDetail(test, forceRefresh = reloadTick > 0)
        if (res.isSuccess) {
            detail = res.getOrNull()
        } else {
            errorMsg = res.exceptionOrNull()?.message ?: "加载测验详情失败"
        }
        loading = false
    }

    val statusColor = when {
        test.canStart -> Color(0xFFFF453A)
        test.hasResult || test.status == "已完成" -> Color(0xFF34C759)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val startDisplay = detail?.startTime?.ifBlank { null } ?: test.startTime.ifBlank { "未标注" }
    val endDisplay = detail?.endTime?.ifBlank { null } ?: test.endTime.ifBlank { "未标注" }
    val durationDisplay = detail?.duration?.ifBlank { null } ?: test.duration

    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = test.courseName.ifBlank { "北化在线课程" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = detail?.title?.ifBlank { null } ?: test.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "进入测验",
                onClick = onOpenTestList,
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            SystemSecondaryButton(
                text = "关闭",
                onClick = onDismiss,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(statusColor.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = test.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor
                    )
                }
                if (test.allowedAttempts.isNotBlank()) {
                    Text(
                        text = "允许次数：${test.allowedAttempts}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "开放时间：$startDisplay ~ $endDisplay",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (durationDisplay.isNotBlank()) {
                    Text(
                        text = "考试时长：$durationDisplay",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                detail?.totalScore?.takeIf { it.isNotBlank() }?.let { total ->
                    Text(
                        text = "测验总分：$total",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                detail?.questionCount?.takeIf { it.isNotBlank() }?.let { count ->
                    Text(
                        text = "题目数量：$count",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (test.resultHref.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        .clickable { onOpenResult(test.resultHref) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "查看答题结果与批改详情",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = Icons.Outlined.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            if (test.lid.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        .clickable { onOpenCourseHome() }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "进入课程主页",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = Icons.Outlined.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            SystemDivider(alpha = 0.35f)

            when {
                loading -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassLoadingIndicator(size = 24.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "正在获取测验说明…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                errorMsg != null -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "未能读取详细说明：$errorMsg",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFF453A)
                        )
                        Text(
                            text = "仍可点击下方「进入测验」直接前往北化在线测验列表页。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = { reloadTick++ },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("重试拉取详情")
                        }
                    }
                }
                else -> {
                    val desc = detail?.description.orEmpty()
                    if (desc.isNotBlank()) {
                        SelectionContainer {
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        Text(
                            text = "老师未填写额外说明，可点击下方按钮打开北化在线测验列表页查看或答题。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
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
    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "课程黑名单与已隐藏项",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "完成",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
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
                    SystemDivider(alpha = 0.35f)
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
    }
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
private fun TheolNativeLoginDialog(
    syncing: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, CharArray) -> Unit
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    fun submit() {
        if (syncing || username.isBlank() || password.isEmpty()) return
        val chars = password.toCharArray()
        password = ""
        onSubmit(username, chars)
    }

    SystemDialog(
        onDismissRequest = { if (!syncing) onDismiss() },
        title = {
            Text(
                text = "原生登录北化在线",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        confirmButton = {
            SystemPrimaryButton(
                text = if (syncing) "同步中…" else "登录并同步",
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
                text = "直连 sidportal.buct.edu.cn 完成 AES 原生认证，密码仅在内存加密后即刻清零，绝不落盘。",
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
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AddLocalTodoDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var course by remember { mutableStateOf("") }

    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "新建本地待办",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "添加",
                enabled = title.isNotBlank(),
                onClick = { onAdd(title.trim(), course.trim()) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            SystemSecondaryButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = "待办 / 作业标题",
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            GlassTextField(
                value = course,
                onValueChange = { course = it },
                placeholder = "所属课程（选填）",
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ExperimentReportGlassCard(
    item: SjcxExperimentReportItem,
    enabled: Boolean,
    onDownloadTemplate: () -> Unit,
    onDownloadSubmitted: () -> Unit,
    onUploadPdf: () -> Unit
) {
    val badgeColor = when (item.state) {
        2 -> Color(0xFF0A84FF)
        3 -> Color(0xFF34C759)
        4 -> Color(0xFFFF453A)
        else -> if (item.canUpload) Color(0xFFFF9F0A) else MaterialTheme.colorScheme.onSurfaceVariant
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
                    text = item.stateLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = badgeColor
                )
            }
        }

        Text(
            text = item.expName,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )

        val metaParts = buildList {
            if (item.expCode.isNotBlank()) add("实验编号：${item.expCode}")
            add("格式要求：PDF")
            if (item.templateName.isNotBlank()) add("模板：${item.templateName}")
        }
        Text(
            text = metaParts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (item.uploadedDocName.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                    .clickable(enabled = enabled, onClick = onDownloadSubmitted)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.AttachFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "已交报告：${item.uploadedDocName}",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "查看/下载",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (item.opinion.isNotBlank()) {
            Text(
                text = "教师评语/意见：${item.opinion}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFF453A)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (item.templateName.isNotBlank()) {
                SystemActionButton(
                    text = "下载模板",
                    icon = Icons.Outlined.Download,
                    primary = false,
                    enabled = enabled,
                    onClick = onDownloadTemplate
                )
            }
            if (item.state != 3) {
                SystemActionButton(
                    text = when {
                        !item.canUpload -> "未开放上传"
                        item.uploadedDocName.isNotBlank() -> "重新上传 PDF"
                        else -> "上传报告 PDF"
                    },
                    icon = Icons.Outlined.UploadFile,
                    primary = true,
                    enabled = enabled && item.canUpload,
                    onClick = onUploadPdf
                )
            }
        }
    }
}

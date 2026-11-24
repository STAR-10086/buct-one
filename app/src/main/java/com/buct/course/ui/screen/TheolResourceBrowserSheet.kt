package com.buct.course.ui.screen

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewAgenda
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.buct.course.TheolAttachmentOpener
import com.buct.course.TheolCourseDetailActivity
import com.buct.course.TheolSubmitWebViewActivity
import com.buct.course.ui.system.GlassLoadingIndicator
import com.buct.course.ui.system.GlassPageScaffold
import com.buct.course.ui.system.GlassTextField
import com.buct.course.ui.system.GlassToaster
import com.buct.course.ui.system.InsetGroupedRow
import com.buct.course.ui.system.InsetGroupedSection
import com.buct.course.ui.system.SystemCompactSegmentedControl
import com.buct.course.ui.system.SystemIconButton
import com.buct.course.ui.system.SystemSecondaryButton
import com.buct.one.data.model.TheolCourseInfo
import com.buct.one.data.model.TheolCourseUnit
import com.buct.one.data.model.TheolResourceItem
import com.buct.one.data.model.TheolResourcePage
import com.buct.one.data.sync.TheolSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * 北化在线（THEOL / MEOL）一级页：在读课程列表 + 搜索栏。
 * 点击具体课程后启动独立 [TheolCourseDetailActivity]，享受系统级跨 Activity 预测性返回手势。
 */
@Composable
fun TheolCourseScreen(
    service: TheolSyncService,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val slice by service.theolState.collectAsState()
    val filterPrefs by service.filterPrefs.collectAsState()
    val syncing by service.syncing.collectAsState()

    val courses = remember(slice.data, filterPrefs) {
        val snap = slice.data
        val map = LinkedHashMap<String, TheolCourseInfo>()
        snap?.courses.orEmpty().forEach { c ->
            if (c.lid.isNotBlank() && !filterPrefs.isCourseBlacklisted(c.lid, c.courseName)) {
                map.putIfAbsent(c.lid, c)
            }
        }
        snap?.homeworks.orEmpty().forEach { hw ->
            if (hw.lid.isNotBlank() && !filterPrefs.isCourseBlacklisted(hw.lid, hw.courseName)) {
                map.putIfAbsent(hw.lid, TheolCourseInfo(hw.lid, hw.courseName, "course", ""))
            }
        }
        snap?.tests.orEmpty().forEach { t ->
            if (t.lid.isNotBlank() && !filterPrefs.isCourseBlacklisted(t.lid, t.courseName)) {
                map.putIfAbsent(t.lid, TheolCourseInfo(t.lid, t.courseName, "course", ""))
            }
        }
        map.values.toList()
    }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    val filteredCourses = remember(courses, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            courses
        } else {
            courses.filter {
                it.courseName.contains(q, ignoreCase = true) || it.lid.contains(q, ignoreCase = true)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (courses.size <= 25 && service.canAutoSync()) {
            service.refreshFullCourseCatalog(userInitiated = false)
        }
    }

    GlassPageScaffold(
        title = "北化在线",
        subtitle = "在读课程 · 单元学习与课程资源",
        onBack = onBack,
        actions = {
            if (syncing) {
                GlassLoadingIndicator(size = 38.dp)
            } else {
                SystemIconButton(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = "刷新在读课程",
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = {
                        scope.launch {
                            service.refreshFullCourseCatalog(userInitiated = true)
                                .onSuccess { list ->
                                    GlassToaster.show("已同步 ${list.size} 门在读课程")
                                }
                                .onFailure { err ->
                                    GlassToaster.show(err.message ?: "刷新课程目录失败")
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
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "搜索在读课程名称或编号",
                leadingIcon = Icons.Outlined.Search,
                trailing = if (searchQuery.isNotEmpty()) {
                    {
                        SystemIconButton(
                            icon = Icons.Outlined.Close,
                            contentDescription = "清除搜索",
                            onClick = { searchQuery = "" }
                        )
                    }
                } else {
                    null
                }
            )

            if (filteredCourses.isEmpty()) {
                InsetGroupedSection(header = "在读课程") {
                    Box(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (courses.isEmpty()) {
                                "暂未同步到北化在线在读课程，请点击右上角刷新或先完成统一身份认证登录。"
                            } else {
                                "未找到匹配「$searchQuery」的课程。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                InsetGroupedSection(
                    header = "在读课程（${filteredCourses.size} 门）",
                    footer = "选择课程后可浏览「单元学习」与「课程资源」，点击文件即可在线预览。"
                ) {
                    filteredCourses.forEachIndexed { index, course ->
                        InsetGroupedRow(
                            icon = Icons.Outlined.MenuBook,
                            iconTint = Color(0xFF0A84FF),
                            title = course.courseName.ifBlank { "课程 #${course.lid}" },
                            subtitle = "课程编号 ${course.lid}",
                            showDivider = index != filteredCourses.lastIndex,
                            onClick = {
                                TheolCourseDetailActivity.launchCourse(
                                    context = context,
                                    lid = course.lid,
                                    courseName = course.courseName
                                )
                            },
                            trailing = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * 北化在线二级页（由独立 [TheolCourseDetailActivity] 承载）：课程学习中心（单元学习 / 课程资源）。
 */
@Composable
fun CourseLearningCenterScreen(
    course: TheolCourseInfo,
    service: TheolSyncService,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var courseTab by rememberSaveable(course.lid) { mutableIntStateOf(0) } // 0=单元学习 1=课程资源
    var units by remember(course.lid) { mutableStateOf<List<TheolCourseUnit>>(emptyList()) }
    var rootResourceFolder by remember(course.lid) { mutableStateOf<TheolResourcePage?>(null) }

    var loading by remember(course.lid) { mutableStateOf(false) }
    var downloadingUrl by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember(course.lid) { mutableStateOf<String?>(null) }
    var retryAction by remember(course.lid) { mutableStateOf<(() -> Unit)?>(null) }

    fun loadUnits(forceRefresh: Boolean = false) {
        if (loading) return
        scope.launch {
            loading = true
            errorMsg = null
            retryAction = { loadUnits(forceRefresh = true) }
            val res = service.fetchCourseUnits(course.lid, forceRefresh = forceRefresh)
            loading = false
            res.onSuccess { list ->
                units = list
                retryAction = null
            }.onFailure { err ->
                errorMsg = err.message ?: "读取单元学习目录失败"
            }
        }
    }

    fun loadRootResources(forceRefresh: Boolean = false) {
        if (loading) return
        scope.launch {
            loading = true
            errorMsg = null
            retryAction = { loadRootResources(forceRefresh = true) }
            val res = service.fetchCourseResources(
                lid = course.lid,
                courseName = course.courseName,
                folderUrl = null,
                folderTitle = null,
                forceRefresh = forceRefresh
            )
            loading = false
            res.onSuccess { page ->
                rootResourceFolder = page
                retryAction = null
            }.onFailure { err ->
                errorMsg = err.message ?: "读取课程资源失败"
            }
        }
    }

    LaunchedEffect(course.lid) {
        loadUnits()
    }

    GlassPageScaffold(
        title = course.courseName.ifBlank { "北化在线" },
        subtitle = if (courseTab == 0) "单元学习（${units.size} 个单元）" else "课程资源",
        onBack = onBack,
        actions = {
            SystemIconButton(
                icon = Icons.Outlined.OpenInNew,
                contentDescription = "网页打开课程",
                onClick = {
                    val courseUrl = "https://course.buct.edu.cn/meol/jpk/course/layout/newpage/index.jsp?courseId=${URLEncoder.encode(course.lid, "UTF-8")}"
                    TheolSubmitWebViewActivity.launch(
                        context = context,
                        url = courseUrl,
                        title = course.courseName.ifBlank { "课程主页" },
                        courseName = course.courseName,
                        courseLid = course.lid
                    )
                }
            )
            if (loading) {
                GlassLoadingIndicator(size = 38.dp)
            } else {
                SystemIconButton(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = "刷新",
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = {
                        if (courseTab == 0) loadUnits(forceRefresh = true)
                        else loadRootResources(forceRefresh = true)
                    }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SystemCompactSegmentedControl(
                    options = listOf("单元学习", "课程资源"),
                    selectedIndex = courseTab,
                    onSelect = { idx ->
                        if (courseTab != idx) {
                            courseTab = idx
                            errorMsg = null
                            retryAction = null
                            if (idx == 0 && units.isEmpty()) {
                                loadUnits()
                            } else if (idx == 1 && rootResourceFolder == null) {
                                loadRootResources()
                            }
                        }
                    }
                )
            }

            when {
                loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        GlassLoadingIndicator(size = 44.dp)
                    }
                }
                errorMsg != null -> {
                    ResourceErrorState(
                        errorMsg = errorMsg.orEmpty(),
                        onRetry = retryAction
                    )
                }
                courseTab == 0 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (units.isEmpty()) {
                            InsetGroupedSection(header = "单元学习") {
                                Box(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "该课程暂未发布「单元学习」目录，可切换至「课程资源」查看课件。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            InsetGroupedSection(
                                header = "课程单元（${units.size} 节）",
                                footer = "点击单元查看对应课件、视频与学习资料。"
                            ) {
                                units.forEachIndexed { index, unit ->
                                    InsetGroupedRow(
                                        icon = Icons.Outlined.ViewAgenda,
                                        iconTint = Color(0xFF5E5CE6),
                                        title = unit.title,
                                        subtitle = "单元栏目 #${unit.columnId}",
                                        showDivider = index != units.lastIndex,
                                        onClick = {
                                            TheolCourseDetailActivity.launchUnit(
                                                context = context,
                                                lid = course.lid,
                                                courseName = course.courseName,
                                                unit = unit
                                            )
                                        },
                                        trailing = {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
                else -> {
                    val page = rootResourceFolder
                    if (page == null) {
                        InsetGroupedSection(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            header = "课程资源"
                        ) {
                            Box(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "暂无课程资源目录，请点击右上角刷新。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        ResourcePageContent(
                            page = page,
                            emptyText = "当前目录下暂无公开课件或教学资源。",
                            downloadingUrl = downloadingUrl,
                            onOpenFolder = { folder ->
                                TheolCourseDetailActivity.launchFolder(
                                    context = context,
                                    lid = course.lid,
                                    courseName = course.courseName,
                                    folderUrl = folder.url,
                                    folderTitle = folder.name
                                )
                            },
                            onPreviewFile = { item -> openTheolResourcePreview(context, course.lid, course.courseName, item) },
                            onDownloadFile = { item ->
                                downloadTheolResourceFile(
                                    context = context,
                                    scope = scope,
                                    service = service,
                                    lid = course.lid,
                                    item = item,
                                    onBusyChange = { downloadingUrl = it }
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 北化在线单元详情页（由独立 [TheolCourseDetailActivity] 承载）。
 */
@Composable
fun TheolUnitResourcesScreen(
    lid: String,
    courseName: String,
    unit: TheolCourseUnit,
    service: TheolSyncService,
    onBack: () -> Unit
) {
    ResourceDetailScaffold(
        lid = lid,
        courseName = courseName,
        initialTitle = unit.title,
        subtitle = "${courseName.ifBlank { "北化在线" }} · 单元学习",
        emptyText = "该单元下暂无公开课件或学习资料。",
        service = service,
        onBack = onBack,
        fetcher = { forceRefresh ->
            service.fetchUnitResources(
                lid = lid,
                courseName = courseName,
                unit = unit,
                forceRefresh = forceRefresh
            )
        }
    )
}

/**
 * 北化在线课程资源子目录页（由独立 [TheolCourseDetailActivity] 承载）。
 */
@Composable
fun TheolFolderResourcesScreen(
    lid: String,
    courseName: String,
    folderUrl: String,
    folderTitle: String,
    service: TheolSyncService,
    onBack: () -> Unit
) {
    ResourceDetailScaffold(
        lid = lid,
        courseName = courseName,
        initialTitle = folderTitle.ifBlank { "课程资源" },
        subtitle = "${courseName.ifBlank { "北化在线" }} · 课程资源",
        emptyText = "当前目录下暂无公开课件或教学资源。",
        service = service,
        onBack = onBack,
        fetcher = { forceRefresh ->
            service.fetchCourseResources(
                lid = lid,
                courseName = courseName,
                folderUrl = folderUrl,
                folderTitle = folderTitle,
                forceRefresh = forceRefresh
            )
        }
    )
}

@Composable
private fun ResourceDetailScaffold(
    lid: String,
    courseName: String,
    initialTitle: String,
    subtitle: String,
    emptyText: String,
    service: TheolSyncService,
    onBack: () -> Unit,
    fetcher: suspend (forceRefresh: Boolean) -> Result<TheolResourcePage>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<TheolResourcePage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var downloadingUrl by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    fun load(forceRefresh: Boolean) {
        if (loading) return
        scope.launch {
            loading = true
            errorMsg = null
            val res = fetcher(forceRefresh)
            loading = false
            res.onSuccess { page = it }
                .onFailure { errorMsg = it.message ?: "读取课程资料失败" }
        }
    }

    LaunchedEffect(lid, initialTitle) {
        load(forceRefresh = false)
    }

    GlassPageScaffold(
        title = page?.folderTitle?.ifBlank { initialTitle } ?: initialTitle,
        subtitle = subtitle,
        onBack = onBack,
        actions = {
            if (loading) {
                GlassLoadingIndicator(size = 38.dp)
            } else {
                SystemIconButton(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = "刷新",
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = { load(forceRefresh = true) }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        GlassLoadingIndicator(size = 44.dp)
                    }
                }
                errorMsg != null -> {
                    ResourceErrorState(
                        errorMsg = errorMsg.orEmpty(),
                        onRetry = { load(forceRefresh = true) }
                    )
                }
                page != null -> {
                    ResourcePageContent(
                        page = page!!,
                        emptyText = emptyText,
                        downloadingUrl = downloadingUrl,
                        onOpenFolder = { folder ->
                            TheolCourseDetailActivity.launchFolder(
                                context = context,
                                lid = lid,
                                courseName = courseName,
                                folderUrl = folder.url,
                                folderTitle = folder.name
                            )
                        },
                        onPreviewFile = { item -> openTheolResourcePreview(context, lid, courseName, item) },
                        onDownloadFile = { item ->
                            downloadTheolResourceFile(
                                context = context,
                                scope = scope,
                                service = service,
                                lid = lid,
                                item = item,
                                onBusyChange = { downloadingUrl = it }
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ResourceErrorState(
    errorMsg: String,
    onRetry: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = errorMsg,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            SystemSecondaryButton(
                text = "重试",
                onClick = onRetry
            )
        }
    }
}

private fun openTheolResourcePreview(
    context: Context,
    lid: String,
    courseName: String,
    item: TheolResourceItem
) {
    val targetUrl = item.previewUrl.ifBlank { item.url }
    TheolSubmitWebViewActivity.launch(
        context = context,
        url = targetUrl,
        title = item.name,
        courseName = courseName,
        courseLid = lid
    )
}

private fun downloadTheolResourceFile(
    context: Context,
    scope: CoroutineScope,
    service: TheolSyncService,
    lid: String,
    item: TheolResourceItem,
    onBusyChange: (String?) -> Unit
) {
    scope.launch {
        onBusyChange(item.url)
        GlassToaster.show("正在下载 ${item.name}…")
        val res = service.downloadResourceFile(lid, item)
        onBusyChange(null)
        res.onSuccess { file ->
            if (file.isHtmlPage) {
                GlassToaster.show("该资源暂不支持直接下载，请点击条目在线预览")
            } else {
                val sizeHint = TheolAttachmentOpener.formatFileSize(file.sizeBytes)
                GlassToaster.show("已下载 ${file.fileName}（$sizeHint）")
                TheolAttachmentOpener.open(context, file)
            }
        }.onFailure { err ->
            GlassToaster.show(err.message ?: "下载课件失败")
        }
    }
}

@Composable
private fun ResourcePageContent(
    page: TheolResourcePage,
    emptyText: String,
    downloadingUrl: String?,
    onOpenFolder: (TheolResourceItem) -> Unit,
    onPreviewFile: (TheolResourceItem) -> Unit,
    onDownloadFile: (TheolResourceItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (page.items.isEmpty()) {
            InsetGroupedSection(header = page.folderTitle) {
                Box(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = emptyText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            InsetGroupedSection(
                header = "${page.folderTitle}（${page.items.size} 项）",
                footer = "点击文件直接在线预览；点击右侧下载图标原生下载并用本机应用打开。"
            ) {
                page.items.forEachIndexed { index, item ->
                    val meta = listOf(
                        if (item.isFolder) "文件夹" else item.fileExt.uppercase().ifBlank { "文件" },
                        item.sizeText,
                        item.updatedAt
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                    val isBusy = downloadingUrl == item.url
                    InsetGroupedRow(
                        icon = if (item.isFolder) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
                        iconTint = if (item.isFolder) Color(0xFFFF9F0A) else Color(0xFF34C759),
                        title = item.name,
                        subtitle = meta,
                        showDivider = index != page.items.lastIndex,
                        onClick = {
                            if (item.isFolder) {
                                onOpenFolder(item)
                            } else {
                                onPreviewFile(item)
                            }
                        },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isBusy) {
                                    GlassLoadingIndicator(size = 24.dp)
                                } else if (item.isFolder) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    SystemIconButton(
                                        icon = Icons.Outlined.Download,
                                        contentDescription = "原生下载",
                                        tint = MaterialTheme.colorScheme.primary,
                                        chip = false,
                                        onClick = {
                                            if (downloadingUrl == null) {
                                                onDownloadFile(item)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

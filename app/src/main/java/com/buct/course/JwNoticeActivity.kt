package com.buct.course

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.buct.course.manager.AppThemeCoordinator
import com.buct.course.manager.UserManager
import com.buct.course.ui.screen.GlassFilterChip
import com.buct.course.ui.system.GlassLoadingIndicator
import com.buct.course.ui.system.GlassPageScaffold
import com.buct.course.ui.system.GlassSubpage
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
import com.buct.course.ui.system.rememberGlassDarkTheme
import com.buct.course.ui.theme.CourseSelectorTheme
import com.buct.one.data.model.JwAnnouncementDetail
import com.buct.one.data.model.JwAnnouncementItem
import com.buct.one.data.model.JwReminderCategory
import com.buct.one.data.model.JwReminderItem
import com.buct.one.data.model.RefreshState
import com.buct.one.data.model.jwReminderCategoryLabel
import com.buct.one.data.sync.JwNoticeSyncService
import kotlinx.coroutines.launch
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 教务系统调停课消息与教务通知公告（jwglxt.buct.edu.cn）原生液态玻璃界面。
 * 归属教务/课表域独立入口，不与工作台或校内综合资讯混放。
 */
class JwNoticeActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialTab = intent.getIntExtra(EXTRA_INITIAL_TAB, 0).coerceIn(0, 1)
        setContent {
            CourseSelectorTheme {
                GlassWindowHost {
                    JwNoticeScreen(
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
                Intent(context, JwNoticeActivity::class.java).apply {
                    putExtra(EXTRA_INITIAL_TAB, initialTab.coerceIn(0, 1))
                }
            )
        }
    }
}

@Composable
private fun JwNoticeScreen(
    initialTab: Int,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember(context) { JwNoticeSyncService.getInstance(context) }

    val slice by service.noticeState.collectAsState()
    val syncing by service.syncing.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) } // 0=调课与消息 1=教务公告
    var reminderFilter by rememberSaveable { mutableIntStateOf(0) } // 0=全部 1=调停补课 2=未读
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showLoginDialog by rememberSaveable { mutableStateOf(false) }
    var feedbackMsg by remember { mutableStateOf<String?>(null) }

    var remoteReminders by remember { mutableStateOf<List<JwReminderItem>?>(null) }
    var remoteReminderTotal by remember { mutableIntStateOf(0) }
    var remoteReminderPage by remember { mutableIntStateOf(1) }
    var activeRemoteReminderQuery by remember { mutableStateOf("") }

    var remoteAnnouncements by remember { mutableStateOf<List<JwAnnouncementItem>?>(null) }
    var remoteAnnouncementTotal by remember { mutableIntStateOf(0) }
    var remoteAnnouncementPage by remember { mutableIntStateOf(1) }
    var activeRemoteAnnouncementQuery by remember { mutableStateOf("") }

    var loadingMore by remember { mutableStateOf(false) }
    var selectedReminder by remember { mutableStateOf<JwReminderItem?>(null) }

    var selectedAnnouncement by remember { mutableStateOf<JwAnnouncementItem?>(null) }
    var activeDetail by remember { mutableStateOf<JwAnnouncementDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val ageMs = System.currentTimeMillis() - slice.provenance.capturedAt
        if (service.canAutoSync() && (
                slice.provenance.refreshState == RefreshState.NEVER ||
                    slice.provenance.refreshState == RefreshState.STALE ||
                    slice.provenance.refreshState == RefreshState.FAILED_FALLBACK ||
                    !slice.lastError.isNullOrBlank() ||
                    ageMs > 15 * 60_000L
                )
        ) {
            service.refresh(userInitiated = false)
        }
    }

    val snapshot = slice.data

    // 详情态需要同步本地未读标记变化（例如刚读取详情页完成即变为已读）
    val currentReminders = remember(snapshot?.reminders, remoteReminders) {
        val base = remoteReminders ?: snapshot?.reminders.orEmpty()
        val readIds = snapshot?.reminders?.filter { it.isRead }?.mapTo(HashSet()) { it.id }.orEmpty()
        if (readIds.isEmpty()) base else base.map { if (!it.isRead && it.id in readIds) it.copy(isRead = true) else it }
    }
    val currentAnnouncements = remember(snapshot?.announcements, remoteAnnouncements) {
        val base = remoteAnnouncements ?: snapshot?.announcements.orEmpty()
        val readIds = snapshot?.announcements?.filter { it.isRead }?.mapTo(HashSet()) { it.id }.orEmpty()
        if (readIds.isEmpty()) base else base.map { if (!it.isRead && it.id in readIds) it.copy(isRead = true) else it }
    }

    LaunchedEffect(selectedAnnouncement?.id) {
        val target = selectedAnnouncement ?: return@LaunchedEffect
        activeDetail = null
        detailError = null
        detailLoading = true
        val res = service.fetchAnnouncementDetail(target)
        detailLoading = false
        res.onSuccess { detail ->
            activeDetail = detail
        }.onFailure { err ->
            detailError = err.message ?: "读取教务公告正文失败"
        }
    }

    fun executeRemoteSearch() {
        val q = searchQuery.trim()
        if (loadingMore) return
        scope.launch {
            loadingMore = true
            feedbackMsg = null
            if (selectedTab == 0) {
                val res = service.loadMoreReminders(page = 1, keyword = q)
                loadingMore = false
                res.onSuccess { (total, items) ->
                    remoteReminders = items
                    remoteReminderTotal = total
                    remoteReminderPage = 1
                    activeRemoteReminderQuery = q
                }.onFailure { err ->
                    val msg = err.message ?: "搜索教务消息失败"
                    feedbackMsg = msg
                    GlassToaster.show(msg)
                }
            } else {
                val res = service.loadMoreAnnouncements(page = 1, keyword = q)
                loadingMore = false
                res.onSuccess { (total, items) ->
                    remoteAnnouncements = items
                    remoteAnnouncementTotal = total
                    remoteAnnouncementPage = 1
                    activeRemoteAnnouncementQuery = q
                }.onFailure { err ->
                    val msg = err.message ?: "搜索教务公告失败"
                    feedbackMsg = msg
                    GlassToaster.show(msg)
                }
            }
        }
    }

    fun executeLoadMore() {
        if (loadingMore) return
        scope.launch {
            loadingMore = true
            feedbackMsg = null
            if (selectedTab == 0) {
                val nextPage = remoteReminderPage + 1
                val res = service.loadMoreReminders(page = nextPage, keyword = activeRemoteReminderQuery)
                loadingMore = false
                res.onSuccess { (total, items) ->
                    val existing = remoteReminders ?: snapshot?.reminders.orEmpty()
                    val existingIds = existing.mapTo(HashSet()) { it.id }
                    remoteReminders = existing + items.filter { it.id !in existingIds }
                    remoteReminderTotal = total
                    remoteReminderPage = nextPage
                }.onFailure { err ->
                    GlassToaster.show(err.message ?: "加载更多消息失败")
                }
            } else {
                val nextPage = remoteAnnouncementPage + 1
                val res = service.loadMoreAnnouncements(page = nextPage, keyword = activeRemoteAnnouncementQuery)
                loadingMore = false
                res.onSuccess { (total, items) ->
                    val existing = remoteAnnouncements ?: snapshot?.announcements.orEmpty()
                    val existingIds = existing.mapTo(HashSet()) { it.id }
                    remoteAnnouncements = existing + items.filter { it.id !in existingIds }
                    remoteAnnouncementTotal = total
                    remoteAnnouncementPage = nextPage
                }.onFailure { err ->
                    GlassToaster.show(err.message ?: "加载更多公告失败")
                }
            }
        }
    }

    val statusText = remember(slice.provenance.capturedAt, slice.provenance.refreshState, syncing) {
        when {
            syncing -> "正在同步 jwglxt.buct.edu.cn…"
            slice.provenance.capturedAt > 0L -> {
                val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                "更新于 ${fmt.format(Date(slice.provenance.capturedAt))}"
            }
            else -> "教务系统（jwglxt.buct.edu.cn）"
        }
    }

    val unreadReminderCount = remember(currentReminders) {
        currentReminders.count { !it.isRead }
    }

    GlassPageScaffold(
        title = "教务消息与公告",
        subtitle = statusText,
        onBack = onBack,
        actions = {
            if (selectedTab == 0 && unreadReminderCount > 0) {
                SystemIconButton(
                    icon = Icons.Outlined.DoneAll,
                    contentDescription = "全部标为已读",
                    onClick = {
                        service.markAllRemindersRead()
                        GlassToaster.show("已将消息全部标为已读")
                    }
                )
            }
            SystemIconButton(
                icon = Icons.Outlined.Search,
                contentDescription = "搜索",
                tint = if (searchVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                onClick = {
                    searchVisible = !searchVisible
                    if (!searchVisible) {
                        searchQuery = ""
                        if (selectedTab == 0) {
                            remoteReminders = null
                            remoteReminderPage = 1
                            activeRemoteReminderQuery = ""
                        } else {
                            remoteAnnouncements = null
                            remoteAnnouncementPage = 1
                            activeRemoteAnnouncementQuery = ""
                        }
                    }
                }
            )
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
                            remoteReminders = null
                            remoteReminderPage = 1
                            activeRemoteReminderQuery = ""
                            remoteAnnouncements = null
                            remoteAnnouncementPage = 1
                            activeRemoteAnnouncementQuery = ""
                            val res = service.refresh(userInitiated = true)
                            if (res.isFailure) {
                                val err = res.exceptionOrNull()?.message ?: "教务消息刷新失败"
                                feedbackMsg = err
                                GlassToaster.show(err)
                                if (isJwAuthError(err)) {
                                    showLoginDialog = true
                                }
                            } else {
                                GlassToaster.show("教务消息与公告已更新")
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
                options = listOf(
                    if (unreadReminderCount > 0) "调课与消息($unreadReminderCount)" else "调课与消息",
                    "教务公告"
                ),
                selectedIndex = selectedTab,
                onSelect = {
                    selectedTab = it
                    feedbackMsg = null
                }
            )

            AnimatedVisibility(visible = searchVisible) {
                GlassTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = if (selectedTab == 0) "搜索消息标题或内容，回车查全部…" else "搜索教务公告标题，回车查全部…",
                    leadingIcon = Icons.Outlined.Search,
                    trailing = if (searchQuery.isNotEmpty()) {
                        {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SystemIconButton(
                                    icon = Icons.Outlined.Search,
                                    contentDescription = "远程搜索",
                                    chip = false,
                                    enabled = !loadingMore,
                                    onClick = ::executeRemoteSearch
                                )
                                SystemIconButton(
                                    icon = Icons.Outlined.Close,
                                    contentDescription = "清空",
                                    chip = false,
                                    onClick = {
                                        searchQuery = ""
                                        if (selectedTab == 0 && activeRemoteReminderQuery.isNotEmpty()) {
                                            remoteReminders = null
                                            remoteReminderPage = 1
                                            activeRemoteReminderQuery = ""
                                        } else if (selectedTab == 1 && activeRemoteAnnouncementQuery.isNotEmpty()) {
                                            remoteAnnouncements = null
                                            remoteAnnouncementPage = 1
                                            activeRemoteAnnouncementQuery = ""
                                        }
                                    }
                                )
                            }
                        }
                    } else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { executeRemoteSearch() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (selectedTab == 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlassFilterChip(
                        label = "全部",
                        selected = reminderFilter == 0,
                        onClick = { reminderFilter = 0 },
                        compact = true
                    )
                    GlassFilterChip(
                        label = "调停补课",
                        selected = reminderFilter == 1,
                        onClick = { reminderFilter = 1 },
                        compact = true
                    )
                    GlassFilterChip(
                        label = if (unreadReminderCount > 0) "未读 ($unreadReminderCount)" else "未读",
                        selected = reminderFilter == 2,
                        onClick = { reminderFilter = 2 },
                        compact = true
                    )
                }

                val filteredReminders = remember(currentReminders, reminderFilter, searchQuery, activeRemoteReminderQuery) {
                    val q = searchQuery.trim()
                    currentReminders.filter { item ->
                        val matchesCategory = when (reminderFilter) {
                            1 -> item.category == JwReminderCategory.RESCHEDULE ||
                                item.category == JwReminderCategory.MAKEUP ||
                                item.category == JwReminderCategory.CANCEL
                            2 -> !item.isRead
                            else -> true
                        }
                        val matchesQuery = q.isEmpty() || q == activeRemoteReminderQuery ||
                            item.title.contains(q, ignoreCase = true) ||
                            item.content.contains(q, ignoreCase = true)
                        matchesCategory && matchesQuery
                    }
                }
                val totalReminders = if (remoteReminders != null) remoteReminderTotal else (snapshot?.reminderTotal ?: 0)
                val canLoadMoreReminders = remoteReminders?.size?.let { it < totalReminders }
                    ?: ((snapshot?.reminders?.size ?: 0) < totalReminders)

                if (filteredReminders.isEmpty()) {
                    JwEmptyBox(
                        title = when {
                            slice.provenance.refreshState == RefreshState.NEVER && !syncing -> "尚未同步教务消息"
                            reminderFilter == 2 -> "暂无未读消息"
                            reminderFilter == 1 -> "暂无调停补课通知"
                            searchQuery.isNotBlank() -> "未找到匹配的消息"
                            else -> "暂无教务消息"
                        },
                        message = if (slice.provenance.refreshState == RefreshState.NEVER && !isJwAuthError(bannerError.orEmpty())) {
                            "点击右上角刷新按钮拉取教务系统消息（调课、补课、停课及考试提醒）。"
                        } else if (isJwAuthError(bannerError.orEmpty())) {
                            "统一身份认证会话已失效，请点击下方按钮重新登录后同步。"
                        } else {
                            "当前筛选条件下没有教务消息。"
                        },
                        showLoginButton = slice.provenance.refreshState == RefreshState.NEVER || isJwAuthError(bannerError.orEmpty()),
                        onLoginClick = { showLoginDialog = true }
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)
                    ) {
                        items(
                            items = filteredReminders,
                            key = { it.id }
                        ) { item ->
                            JwReminderCard(
                                item = item,
                                onClick = {
                                    if (!item.isRead) {
                                        service.markReminderRead(item.id)
                                    }
                                    selectedReminder = item.copy(isRead = true)
                                }
                            )
                        }
                        if (canLoadMoreReminders && searchQuery.trim() == activeRemoteReminderQuery) {
                            item(key = "load_more_reminders") {
                                SystemSecondaryButton(
                                    text = if (loadingMore) "正在加载…" else "加载更多（已显示 ${currentReminders.size}/$totalReminders）",
                                    enabled = !loadingMore,
                                    onClick = ::executeLoadMore,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                val filteredAnnouncements = remember(currentAnnouncements, searchQuery, activeRemoteAnnouncementQuery) {
                    val q = searchQuery.trim()
                    if (q.isEmpty() || q == activeRemoteAnnouncementQuery) {
                        currentAnnouncements
                    } else {
                        currentAnnouncements.filter { a ->
                            a.title.contains(q, ignoreCase = true) ||
                                a.publisher.contains(q, ignoreCase = true)
                        }
                    }
                }
                val totalAnnouncements = if (remoteAnnouncements != null) remoteAnnouncementTotal else (snapshot?.announcementTotal ?: 0)
                val canLoadMoreAnnouncements = remoteAnnouncements?.size?.let { it < totalAnnouncements }
                    ?: ((snapshot?.announcements?.size ?: 0) < totalAnnouncements)

                if (filteredAnnouncements.isEmpty()) {
                    JwEmptyBox(
                        title = when {
                            slice.provenance.refreshState == RefreshState.NEVER && !syncing -> "尚未同步教务公告"
                            searchQuery.isNotBlank() -> "未找到匹配的公告"
                            else -> "暂无教务通知公告"
                        },
                        message = if (isJwAuthError(bannerError.orEmpty())) {
                            "统一身份认证会话已失效，请重新登录后查看。"
                        } else {
                            "点击右上角刷新按钮获取 jwglxt.buct.edu.cn 最新教务公告。"
                        },
                        showLoginButton = slice.provenance.refreshState == RefreshState.NEVER || isJwAuthError(bannerError.orEmpty()),
                        onLoginClick = { showLoginDialog = true }
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)
                    ) {
                        items(
                            items = filteredAnnouncements,
                            key = { it.id }
                        ) { item ->
                            JwAnnouncementCard(
                                item = item,
                                onClick = {
                                    if (item.externalUrl.isNotBlank()) {
                                        service.markAnnouncementRead(item.id)
                                        openExternalNoticeUrl(context, item.externalUrl)
                                    } else {
                                        selectedAnnouncement = item
                                    }
                                }
                            )
                        }
                        if (canLoadMoreAnnouncements && searchQuery.trim() == activeRemoteAnnouncementQuery) {
                            item(key = "load_more_announcements") {
                                SystemSecondaryButton(
                                    text = if (loadingMore) "正在加载…" else "加载更多（已显示 ${currentAnnouncements.size}/$totalAnnouncements）",
                                    enabled = !loadingMore,
                                    onClick = ::executeLoadMore,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selectedAnnouncement?.let { target ->
        GlassSubpage(onDismiss = { selectedAnnouncement = null }) { close ->
            JwAnnouncementDetailView(
                item = target,
                detail = activeDetail,
                loading = detailLoading,
                error = detailError,
                onBack = close,
                onRetry = {
                    scope.launch {
                        detailLoading = true
                        detailError = null
                        val res = service.fetchAnnouncementDetail(target)
                        detailLoading = false
                        res.onSuccess { activeDetail = it }
                            .onFailure { detailError = it.message ?: "读取教务公告正文失败" }
                    }
                }
            )
        }
    }

    selectedReminder?.let { reminder ->
        JwReminderDetailDialog(
            item = reminder,
            onDismiss = { selectedReminder = null }
        )
    }

    if (showLoginDialog) {
        JwSidLoginDialog(
            syncing = syncing,
            onDismiss = { showLoginDialog = false },
            onSubmit = { username, passwordChars ->
                scope.launch {
                    val res = service.loginAndSync(username, passwordChars)
                    res.onSuccess {
                        showLoginDialog = false
                        feedbackMsg = null
                        GlassToaster.show("登录成功，已同步教务消息与公告")
                    }.onFailure { err ->
                        GlassToaster.show(err.message ?: "统一身份认证登录失败")
                    }
                }
            }
        )
    }
}

@Composable
private fun JwEmptyBox(
    title: String,
    message: String,
    showLoginButton: Boolean,
    onLoginClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SystemEmptyState(
                title = title,
                message = message,
                icon = Icons.Outlined.Notifications
            )
            if (showLoginButton) {
                SystemPrimaryButton(
                    text = "登录统一认证",
                    onClick = onLoginClick
                )
            }
        }
    }
}

@Composable
private fun JwReminderCard(
    item: JwReminderItem,
    onClick: () -> Unit
) {
    val cardShape = RoundedCornerShape(18.dp)
    val badgeColor = categoryColor(item.category)
    val label = jwReminderCategoryLabel(item.category)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), cardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!item.isRead) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF3B30))
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(badgeColor.copy(alpha = 0.14f))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = badgeColor
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = formatCompactJwTime(item.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = if (item.isRead) FontWeight.Medium else FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        if (item.content.isNotBlank() && item.content != item.title) {
            Text(
                text = item.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun JwAnnouncementCard(
    item: JwAnnouncementItem,
    onClick: () -> Unit
) {
    val cardShape = RoundedCornerShape(18.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), cardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!item.isRead) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF3B30))
                )
            }
            if (item.isPinned) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFF9500).copy(alpha = 0.14f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "置顶",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color(0xFFFF9500)
                    )
                }
            }
            if (item.externalUrl.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = "外链",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (item.publishedAt.isNotBlank()) {
                Text(
                    text = item.publishedAt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }

        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = if (item.isRead) FontWeight.Medium else FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 20.sp
        )

        if (item.publisher.isNotBlank()) {
            Text(
                text = "发布：${item.publisher}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun JwReminderDetailDialog(
    item: JwReminderItem,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val badgeColor = categoryColor(item.category)
    val label = jwReminderCategoryLabel(item.category)

    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeColor.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = badgeColor
                    )
                }
                Text(
                    text = "教务消息详情",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "复制内容",
                onClick = {
                    val copied = "${item.title}\n${item.content}".trim()
                    clipboard.setText(AnnotatedString(copied))
                    GlassToaster.show("已复制消息内容")
                },
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SelectionContainer {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (item.createdAt.isNotBlank()) {
                Text(
                    text = "时间：${item.createdAt}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SystemDivider()
            SelectionContainer {
                Text(
                    text = item.content.ifBlank { item.title },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 22.sp
                )
            }
        }
    }
}

@Composable
private fun JwAnnouncementDetailView(
    item: JwAnnouncementItem,
    detail: JwAnnouncementDetail?,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit
) {
    val cardShape = RoundedCornerShape(20.dp)
    val clipboard = LocalClipboardManager.current
    val resolvedTitle = detail?.title?.ifBlank { item.title } ?: item.title
    val resolvedPublisher = detail?.publisher?.ifBlank { item.publisher } ?: item.publisher
    val resolvedTime = detail?.publishedAt?.ifBlank { item.publishedAt } ?: item.publishedAt
    val resolvedViews = detail?.viewCount.orEmpty()
    val htmlToRender = detail?.bodyHtml?.takeIf { it.isNotBlank() } ?: item.inlineHtml.takeIf { it.isNotBlank() }.orEmpty()
    val plainToRender = detail?.plainText.orEmpty()

    GlassPageScaffold(
        title = "公告正文",
        subtitle = resolvedPublisher.ifBlank { "北京化工大学教务处" },
        onBack = onBack,
        actions = {
            if (plainToRender.isNotBlank()) {
                SystemIconButton(
                    icon = Icons.Outlined.ContentCopy,
                    contentDescription = "复制正文",
                    onClick = {
                        clipboard.setText(AnnotatedString("$resolvedTitle\n\n$plainToRender"))
                        GlassToaster.show("已复制公告正文")
                    }
                )
            }
            if (loading) {
                GlassLoadingIndicator(size = 36.dp)
            } else {
                SystemIconButton(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = "重新加载",
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = onRetry
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    top = padding.calculateTopPadding() + 8.dp,
                    start = 16.dp,
                    end = 16.dp,
                    bottom = padding.calculateBottomPadding() + 28.dp
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(glassSurfaceColor())
                    .border(0.5.dp, glassBorderColor(), cardShape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = resolvedTitle,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                val metaParts = buildList {
                    if (resolvedPublisher.isNotBlank()) add("发布：$resolvedPublisher")
                    if (resolvedTime.isNotBlank()) add(resolvedTime)
                    if (resolvedViews.isNotBlank()) add("浏览 $resolvedViews")
                }
                if (metaParts.isNotEmpty()) {
                    Text(
                        text = metaParts.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            when {
                htmlToRender.isNotBlank() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(cardShape)
                            .background(glassSurfaceColor())
                            .border(0.5.dp, glassBorderColor(), cardShape)
                            .padding(12.dp)
                    ) {
                        SafeJwHtmlView(
                            htmlBody = htmlToRender,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 180.dp)
                        )
                    }
                }

                loading && detail == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(cardShape)
                            .background(glassSurfaceColor())
                            .border(0.5.dp, glassBorderColor(), cardShape)
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            GlassLoadingIndicator(size = 32.dp)
                            Text(
                                text = "正在加载公告正文…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                !error.isNullOrBlank() && detail == null -> {
                    SystemEmptyState(
                        title = "公告加载失败",
                        message = error,
                        icon = Icons.Outlined.Campaign
                    )
                }

                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(cardShape)
                            .background(glassSurfaceColor())
                            .border(0.5.dp, glassBorderColor(), cardShape)
                            .padding(16.dp)
                    ) {
                        SelectionContainer {
                            Text(
                                text = plainToRender.ifBlank { "（该公告暂无正文内容）" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                lineHeight = 22.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SafeJwHtmlView(
    htmlBody: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val darkTheme = rememberGlassDarkTheme()
    val wrappedHtml = remember(htmlBody, darkTheme) {
        wrapJwHtmlDocument(htmlBody, darkTheme)
    }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
            }
            webViewRef = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.apply {
                    javaScriptEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    builtInZoomControls = false
                    displayZoomControls = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                }
                isVerticalScrollBarEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val target = request?.url ?: return true
                        val scheme = target.scheme?.lowercase(Locale.ROOT).orEmpty()
                        if (scheme == "http" || scheme == "https" || scheme == "mailto") {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, target).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            }
                        }
                        return true
                    }
                }
                webViewRef = this
            }
        },
        update = { view ->
            val renderKey = "${darkTheme}:${wrappedHtml.hashCode()}"
            if (view.tag != renderKey) {
                view.tag = renderKey
                view.loadDataWithBaseURL(
                    "https://jwglxt.buct.edu.cn/jwglxt/xtgl/",
                    wrappedHtml,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        }
    )
}

@Composable
private fun JwSidLoginDialog(
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
                text = "登录 sidportal.buct.edu.cn 以换取 jwglxt.buct.edu.cn 会话（凭据仅用于本次换票，学号密码不落盘）。",
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

@Composable
private fun categoryColor(category: JwReminderCategory): Color = when (category) {
    JwReminderCategory.RESCHEDULE -> Color(0xFFFF9500)
    JwReminderCategory.MAKEUP -> Color(0xFF0A84FF)
    JwReminderCategory.CANCEL -> Color(0xFFFF3B30)
    JwReminderCategory.EXAM -> Color(0xFF5856D6)
    JwReminderCategory.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun isJwAuthError(message: String): Boolean =
    message.contains("登录") || message.contains("过期") || message.contains("失效") || message.contains("会话")

private fun formatCompactJwTime(raw: String): String {
    val clean = raw.trim()
    if (clean.length >= 16 && clean[4] == '-' && clean[7] == '-') {
        return clean.substring(5, 16)
    }
    return clean
}

private fun openExternalNoticeUrl(context: Context, rawUrl: String) {
    val resolved = runCatching {
        URI("https://jwglxt.buct.edu.cn/jwglxt/xtgl/").resolve(rawUrl.trim()).toString()
    }.getOrDefault(rawUrl.trim())
    val uri = Uri.parse(resolved)
    val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
    if (scheme != "http" && scheme != "https") {
        GlassToaster.show("不支持的外链地址")
        return
    }
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    } catch (_: ActivityNotFoundException) {
        GlassToaster.show("未找到可打开外链的浏览器")
    }
}

private fun wrapJwHtmlDocument(bodyHtml: String, darkTheme: Boolean): String {
    val textColor = if (darkTheme) "#ECEEF2" else "#1A1D24"
    val linkColor = if (darkTheme) "#64D2FF" else "#0A84FF"
    val scheme = if (darkTheme) "dark" else "light"
    val injectedStyle = """
        <style>
            :root { color-scheme: $scheme; }
            html, body {
                margin: 0;
                padding: 0;
                background: transparent !important;
                color: $textColor;
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                font-size: 14px;
                line-height: 1.65;
                word-break: break-word;
                overflow-wrap: break-word;
            }
            img, table, pre, video { max-width: 100% !important; height: auto !important; }
            a { color: $linkColor; }
            table { border-collapse: collapse; }
            td, th { padding: 4px 6px; }
        </style>
    """.trimIndent()
    return if (bodyHtml.contains("<html", ignoreCase = true) || bodyHtml.contains("<head", ignoreCase = true)) {
        val headCloseIdx = bodyHtml.indexOf("</head>", ignoreCase = true)
        if (headCloseIdx >= 0) {
            bodyHtml.substring(0, headCloseIdx) + injectedStyle + bodyHtml.substring(headCloseIdx)
        } else {
            injectedStyle + bodyHtml
        }
    } else {
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">$injectedStyle</head><body>$bodyHtml</body></html>"
    }
}

package com.buct.course

import android.content.ActivityNotFoundException
import android.content.ClipData
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
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.buct.course.manager.AppThemeCoordinator
import com.buct.course.manager.UserManager
import com.buct.course.ui.system.GlassLoadingIndicator
import com.buct.course.ui.system.GlassPageScaffold
import com.buct.course.ui.system.GlassTextField
import com.buct.course.ui.system.GlassToaster
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
import com.buct.one.data.model.MailAttachment
import com.buct.one.data.model.MailDownloadedFile
import com.buct.one.data.model.MailFolder
import com.buct.one.data.model.MailMessageDetail
import com.buct.one.data.model.MailMessageSummary
import com.buct.one.data.model.RefreshState
import com.buct.one.data.sync.MailSyncService
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 北化校园邮箱（mail.buct.edu.cn / 网易企业邮箱只读收件）原生液态玻璃界面。
 * 通过统一身份认证（sidportal）无感换票登录，支持文件夹切换、未读筛选、关键词搜索、安全富文本读信与附件下载。
 */
class MailActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialTab = intent.getIntExtra(EXTRA_INITIAL_TAB, 0).coerceIn(0, 2)
        setContent {
            CourseSelectorTheme {
                MailScreen(
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
                Intent(context, MailActivity::class.java).apply {
                    putExtra(EXTRA_INITIAL_TAB, initialTab.coerceIn(0, 2))
                }
            )
        }
    }
}

@Composable
private fun MailScreen(
    initialTab: Int,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember(context) { MailSyncService.getInstance(context) }

    val slice by service.mailState.collectAsState()
    val syncing by service.syncing.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) } // 0=收件箱 1=未读 2=文件夹
    var selectedFolderId by rememberSaveable { mutableIntStateOf(1) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showLoginDialog by rememberSaveable { mutableStateOf(false) }
    var feedbackMsg by remember { mutableStateOf<String?>(null) }

    var extraFolderMessages by remember { mutableStateOf<List<MailMessageSummary>>(emptyList()) }
    var extraFolderTotal by remember { mutableIntStateOf(0) }
    var inboxPaginatedMessages by remember { mutableStateOf<List<MailMessageSummary>?>(null) }
    var inboxPaginatedTotal by remember { mutableIntStateOf(0) }
    var folderLoading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }

    var selectedMessage by remember { mutableStateOf<MailMessageSummary?>(null) }
    var activeDetail by remember { mutableStateOf<MailMessageDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var downloadingPartId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val ageMs = System.currentTimeMillis() - slice.provenance.capturedAt
        if (service.canAutoSync() && (
                slice.provenance.refreshState == RefreshState.NEVER ||
                    slice.provenance.refreshState == RefreshState.FAILED_FALLBACK ||
                    !slice.lastError.isNullOrBlank() ||
                    ageMs > 5 * 60_000L
                )
        ) {
            service.refresh()
        }
    }

    val snapshot = slice.data
    val folders = snapshot?.folders.orEmpty()
    val folderChoices = remember(folders) {
        folders.filter { it.id != 2 && it.id > 0 }
            .ifEmpty { listOf(MailFolder(id = 1, name = "收件箱")) }
    }

    LaunchedEffect(folders) {
        if (selectedFolderId == 1) {
            folderChoices.firstOrNull { it.id != 1 }?.let { firstOther ->
                if (folders.none { it.id == selectedFolderId }) {
                    selectedFolderId = firstOther.id
                }
            }
        }
    }

    LaunchedEffect(selectedTab, selectedFolderId) {
        if (selectedTab == 0) {
            feedbackMsg = null
            return@LaunchedEffect
        }
        val targetFolder = if (selectedTab == 1) 1 else selectedFolderId
        val unreadOnly = selectedTab == 1
        folderLoading = true
        feedbackMsg = null
        val res = service.loadFolderMessages(
            folderId = targetFolder,
            start = 0,
            limit = 30,
            unreadOnly = unreadOnly
        )
        folderLoading = false
        res.onSuccess { (items, total) ->
            extraFolderMessages = items
            extraFolderTotal = total
        }.onFailure { err ->
            extraFolderMessages = emptyList()
            extraFolderTotal = 0
            feedbackMsg = err.message ?: "加载邮件列表失败"
        }
    }

    LaunchedEffect(slice) {
        if (selectedTab == 0) {
            inboxPaginatedMessages = null
        }
    }

    LaunchedEffect(selectedMessage?.id) {
        val target = selectedMessage ?: return@LaunchedEffect
        activeDetail = null
        detailError = null
        detailLoading = true
        val res = service.fetchMessageDetail(target.id)
        detailLoading = false
        res.onSuccess { detail ->
            activeDetail = detail
            if (!target.isRead) {
                selectedMessage = target.copy(isRead = true)
                extraFolderMessages = extraFolderMessages.map {
                    if (it.id == target.id) it.copy(isRead = true) else it
                }
                inboxPaginatedMessages = inboxPaginatedMessages?.map {
                    if (it.id == target.id) it.copy(isRead = true) else it
                }
                launch { service.setMessageRead(target.id, true) }
            }
        }.onFailure { err ->
            detailError = err.message ?: "读取邮件正文失败"
        }
    }

    if (selectedMessage != null) {
        val currentMsg = selectedMessage!!
        BackHandler { selectedMessage = null }
        MailDetailView(
            message = currentMsg,
            detail = activeDetail,
            loading = detailLoading,
            error = detailError,
            downloadingPartId = downloadingPartId,
            onBack = { selectedMessage = null },
            onReload = {
                scope.launch {
                    detailLoading = true
                    detailError = null
                    val res = service.fetchMessageDetail(currentMsg.id, forceRefresh = true)
                    detailLoading = false
                    res.onSuccess { activeDetail = it }
                        .onFailure { detailError = it.message ?: "读取邮件正文失败" }
                }
            },
            onToggleRead = {
                val targetRead = !currentMsg.isRead
                scope.launch {
                    val res = service.setMessageRead(currentMsg.id, targetRead)
                    if (res.isSuccess) {
                        selectedMessage = currentMsg.copy(isRead = targetRead)
                        extraFolderMessages = extraFolderMessages.map {
                            if (it.id == currentMsg.id) it.copy(isRead = targetRead) else it
                        }
                        inboxPaginatedMessages = inboxPaginatedMessages?.map {
                            if (it.id == currentMsg.id) it.copy(isRead = targetRead) else it
                        }
                        GlassToaster.show(if (targetRead) "已标为已读" else "已标为未读")
                    } else {
                        GlassToaster.show(res.exceptionOrNull()?.message ?: "状态更新失败")
                    }
                }
            },
            onDownloadAttachment = { attachment ->
                if (downloadingPartId == null) {
                    scope.launch {
                        downloadingPartId = attachment.partId
                        val res = service.downloadAttachment(currentMsg.id, attachment)
                        downloadingPartId = null
                        res.onSuccess { file ->
                            openMailAttachment(context, file)
                        }.onFailure { err ->
                            GlassToaster.show(err.message ?: "附件下载失败")
                        }
                    }
                }
            }
        )
        return
    }

    val rawMessages = when (selectedTab) {
        0 -> inboxPaginatedMessages ?: snapshot?.inboxMessages.orEmpty()
        else -> extraFolderMessages
    }
    val totalCount = when (selectedTab) {
        0 -> if (inboxPaginatedMessages != null) inboxPaginatedTotal else (snapshot?.inboxTotal ?: 0)
        else -> extraFolderTotal
    }
    val displayedMessages = remember(rawMessages, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            rawMessages
        } else {
            rawMessages.filter { m ->
                m.subject.contains(q, ignoreCase = true) ||
                    m.fromName.contains(q, ignoreCase = true) ||
                    m.fromAddress.contains(q, ignoreCase = true) ||
                    m.summary.contains(q, ignoreCase = true)
            }
        }
    }

    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }
    val accountLabel = snapshot?.account?.displayEmail?.ifBlank { snapshot.account.email }.orEmpty()
    val statusText = when {
        syncing || folderLoading -> "正在同步校园邮箱…"
        slice.provenance.refreshState == RefreshState.NEVER -> "尚未同步 · 免密复用统一身份认证"
        slice.provenance.refreshState == RefreshState.FAILED_FALLBACK ->
            "刷新失败，展示历史快照 · ${timeFmt.format(Date(slice.provenance.capturedAt))}"
        accountLabel.isNotBlank() ->
            "$accountLabel · ${timeFmt.format(Date(slice.provenance.capturedAt))}"
        else -> "更新于 ${timeFmt.format(Date(slice.provenance.capturedAt))}"
    }

    GlassPageScaffold(
        title = "校园邮箱",
        subtitle = statusText,
        onBack = onBack,
        actions = {
            SystemIconButton(
                icon = Icons.Outlined.Search,
                contentDescription = "搜索邮件",
                tint = if (searchVisible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                onClick = {
                    searchVisible = !searchVisible
                    if (!searchVisible) searchQuery = ""
                }
            )
            SystemIconButton(
                icon = Icons.Outlined.Login,
                contentDescription = "统一身份登录",
                enabled = !syncing,
                onClick = { showLoginDialog = true }
            )
            if (syncing || folderLoading) {
                GlassLoadingIndicator(size = 38.dp)
            } else {
                SystemIconButton(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = "刷新",
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = {
                        scope.launch {
                            feedbackMsg = null
                            inboxPaginatedMessages = null
                            val res = service.refresh(userInitiated = true)
                            if (res.isFailure) {
                                val err = res.exceptionOrNull()?.message ?: "校园邮箱刷新失败"
                                feedbackMsg = err
                                GlassToaster.show(err)
                                if (isMailAuthError(err)) {
                                    showLoginDialog = true
                                }
                            } else {
                                if (selectedTab != 0) {
                                    val targetFolder = if (selectedTab == 1) 1 else selectedFolderId
                                    val folderRes = service.loadFolderMessages(
                                        folderId = targetFolder,
                                        start = 0,
                                        limit = 30,
                                        unreadOnly = selectedTab == 1,
                                        userInitiated = true
                                    )
                                    folderRes.onSuccess { (items, total) ->
                                        extraFolderMessages = items
                                        extraFolderTotal = total
                                    }
                                }
                                GlassToaster.show("校园邮箱已更新")
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

            val unreadCount = snapshot?.unreadTotal ?: 0
            SystemCompactSegmentedControl(
                options = listOf(
                    "收件箱",
                    if (unreadCount > 0) "未读($unreadCount)" else "未读",
                    "文件夹"
                ),
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it }
            )

            AnimatedVisibility(visible = selectedTab == 2 && folderChoices.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    folderChoices.forEach { folder ->
                        val active = folder.id == selectedFolderId
                        val chipShape = RoundedCornerShape(50)
                        val label = if (folder.unreadCount > 0) {
                            "${folder.name} (${folder.unreadCount})"
                        } else {
                            folder.name
                        }
                        Box(
                            modifier = Modifier
                                .clip(chipShape)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary
                                    else glassSurfaceColor()
                                )
                                .border(
                                    0.5.dp,
                                    if (active) Color.Transparent else glassBorderColor(),
                                    chipShape
                                )
                                .clickable { selectedFolderId = folder.id }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = searchVisible) {
                GlassTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = "搜索主题、发件人或摘要…",
                    leadingIcon = Icons.Outlined.Search,
                    trailing = if (searchQuery.isNotEmpty()) {
                        {
                            SystemIconButton(
                                icon = Icons.Outlined.Close,
                                contentDescription = "清空搜索",
                                onClick = { searchQuery = "" }
                            )
                        }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (displayedMessages.isEmpty()) {
                    item {
                        val isNever = slice.provenance.refreshState == RefreshState.NEVER && selectedTab == 0
                        SystemEmptyState(
                            title = when {
                                syncing || folderLoading -> "正在拉取邮件…"
                                searchQuery.isNotBlank() -> "未找到匹配邮件"
                                isNever -> "尚未同步校园邮箱"
                                selectedTab == 1 -> "暂无未读邮件"
                                else -> "当前文件夹暂无邮件"
                            },
                            message = when {
                                syncing || folderLoading -> "正在通过统一身份认证同步最新收件箱，请稍候。"
                                searchQuery.isNotBlank() -> "换个关键词试试，或清空搜索条件。"
                                isNever -> "将自动复用统一身份认证拉取 mail.buct.edu.cn 收件箱；若需验证请点击右上角登录图标。"
                                else -> "点击右上角刷新图标可立即检查新邮件。"
                            },
                            icon = Icons.Outlined.MailOutline
                        )
                    }
                } else {
                    items(displayedMessages, key = { it.id }) { msg ->
                        MailMessageCard(
                            message = msg,
                            onClick = { selectedMessage = msg }
                        )
                    }

                    if (searchQuery.isBlank() && rawMessages.size < totalCount) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                SystemSecondaryButton(
                                    text = if (loadingMore) "正在加载更多…" else "加载更多（已显示 ${rawMessages.size} / $totalCount）",
                                    enabled = !loadingMore,
                                    onClick = {
                                        if (loadingMore) return@SystemSecondaryButton
                                        val targetFolder = if (selectedTab == 2) selectedFolderId else 1
                                        val unreadOnly = selectedTab == 1
                                        scope.launch {
                                            loadingMore = true
                                            val res = service.loadFolderMessages(
                                                folderId = targetFolder,
                                                start = rawMessages.size,
                                                limit = 30,
                                                unreadOnly = unreadOnly,
                                                userInitiated = true
                                            )
                                            loadingMore = false
                                            res.onSuccess { (moreItems, total) ->
                                                val merged = (rawMessages + moreItems).distinctBy { it.id }
                                                if (selectedTab == 0) {
                                                    inboxPaginatedMessages = merged
                                                    inboxPaginatedTotal = total
                                                } else {
                                                    extraFolderMessages = merged
                                                    extraFolderTotal = total
                                                }
                                            }.onFailure { err ->
                                                GlassToaster.show(err.message ?: "加载更多失败")
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLoginDialog) {
        MailLoginDialog(
            syncing = syncing,
            onDismiss = { showLoginDialog = false },
            onSubmit = { username, passwordChars ->
                scope.launch {
                    feedbackMsg = null
                    val res = service.loginAndSync(username, passwordChars)
                    if (res.isSuccess) {
                        showLoginDialog = false
                        GlassToaster.show("登录成功并已同步校园邮箱")
                    } else {
                        val err = res.exceptionOrNull()?.message ?: "统一身份认证登录失败"
                        feedbackMsg = err
                        GlassToaster.show(err)
                    }
                }
            }
        )
    }
}

@Composable
private fun MailMessageCard(
    message: MailMessageSummary,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    val senderDisplay = message.fromName.ifBlank { message.fromAddress }.ifBlank { "未知发件人" }
    val avatarChar = senderDisplay.firstOrNull()?.uppercaseChar()?.toString() ?: "邮"
    val timeLabel = formatCompactMailTime(message.receivedDate.ifBlank { message.sentDate })

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (!message.isRead) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = avatarChar,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = if (!message.isRead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!message.isRead) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF453A))
                        )
                    }
                    Text(
                        text = senderDisplay,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = if (!message.isRead) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            Text(
                text = message.subject.ifBlank { "(无主题)" },
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (!message.isRead) FontWeight.Bold else FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.summary.ifBlank { "点击查看邮件正文" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 6.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (message.hasAttachments) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AttachFile,
                            contentDescription = "含附件",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp)
                        )
                        val count = message.attachments.count { !it.inlined }
                        if (count > 0) {
                            Text(
                                text = count.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MailDetailView(
    message: MailMessageSummary,
    detail: MailMessageDetail?,
    loading: Boolean,
    error: String?,
    downloadingPartId: String?,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onToggleRead: () -> Unit,
    onDownloadAttachment: (MailAttachment) -> Unit
) {
    val senderHeader = detail?.fromList?.firstOrNull()?.takeIf { it.isNotBlank() }
        ?: message.fromName.ifBlank { message.fromAddress }.ifBlank { "未知发件人" }
    val dateHeader = detail?.sentDate?.takeIf { it.isNotBlank() }
        ?: message.sentDate.ifBlank { message.receivedDate }

    GlassPageScaffold(
        title = "邮件正文",
        subtitle = "$senderHeader · ${formatCompactMailTime(dateHeader)}",
        onBack = onBack,
        actions = {
            SystemIconButton(
                icon = if (message.isRead) Icons.Outlined.MarkEmailUnread else Icons.Outlined.MarkEmailRead,
                contentDescription = if (message.isRead) "标为未读" else "标为已读",
                onClick = onToggleRead
            )
            if (loading) {
                GlassLoadingIndicator(size = 38.dp)
            } else {
                SystemIconButton(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = "重新加载",
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = onReload
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    top = padding.calculateTopPadding() + 6.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                    start = 16.dp,
                    end = 16.dp
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val cardShape = RoundedCornerShape(22.dp)
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
                        text = detail?.subject?.ifBlank { message.subject } ?: message.subject,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                SystemDivider()
                MetaRow(label = "发件人", value = detail?.fromList?.joinToString("、")?.ifBlank { senderHeader } ?: senderHeader)
                val toText = detail?.toList?.joinToString("、")?.ifBlank { message.toRaw } ?: message.toRaw
                if (toText.isNotBlank()) {
                    MetaRow(label = "收件人", value = toText)
                }
                if (dateHeader.isNotBlank()) {
                    MetaRow(label = "时间", value = dateHeader)
                }
            }

            val attachments = (detail?.attachments ?: message.attachments).filter { !it.inlined }
            if (attachments.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(cardShape)
                        .background(glassSurfaceColor())
                        .border(0.5.dp, glassBorderColor(), cardShape)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "附件（${attachments.size}）",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    attachments.forEachIndexed { idx, att ->
                        if (idx > 0) SystemDivider()
                        val isDownloading = downloadingPartId == att.partId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = downloadingPartId == null) { onDownloadAttachment(att) }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AttachFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = att.filename,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = formatMailFileSize(att.sizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isDownloading) {
                                GlassLoadingIndicator(size = 24.dp)
                            } else {
                                Text(
                                    text = "打开",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            when {
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
                                text = "正在加载邮件正文…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                !error.isNullOrBlank() && detail == null -> {
                    SystemEmptyState(
                        title = "正文加载失败",
                        message = error,
                        icon = Icons.Outlined.MailOutline
                    )
                }

                detail != null && detail.htmlContent.isNotBlank() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(cardShape)
                            .background(glassSurfaceColor())
                            .border(0.5.dp, glassBorderColor(), cardShape)
                            .padding(12.dp)
                    ) {
                        SafeMailHtmlView(
                            htmlBody = detail.htmlContent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 180.dp)
                        )
                    }
                }

                else -> {
                    val plain = detail?.textContent?.ifBlank { message.summary } ?: message.summary
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
                                text = plain.ifBlank { "（该邮件暂无正文内容）" },
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
private fun SafeMailHtmlView(
    htmlBody: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val darkTheme = rememberGlassDarkTheme()
    val wrappedHtml = remember(htmlBody, darkTheme) {
        wrapMailHtmlDocument(htmlBody, darkTheme)
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
                    "https://mailh.qiye.163.com/",
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
private fun MetaRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 1.dp)
        )
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
    }
}

@Composable
private fun MailLoginDialog(
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
                text = "登录 sidportal.buct.edu.cn 以换取 mail.buct.edu.cn 会话并同步校园收件箱（凭据仅用于本次换票，学号密码不落盘）。",
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

private fun isMailAuthError(message: String): Boolean =
    message.contains("登录") || message.contains("过期") || message.contains("失效") || message.contains("会话")

private fun formatCompactMailTime(raw: String): String {
    val clean = raw.trim()
    if (clean.length >= 16 && clean[4] == '-' && clean[7] == '-') {
        return clean.substring(5, 16)
    }
    return clean
}

private fun formatMailFileSize(bytes: Long): String {
    if (bytes <= 0L) return "未知大小"
    if (bytes < 1024L) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1f MB", mb)
}

private fun openMailAttachment(context: Context, file: MailDownloadedFile) {
    val target = File(file.filePath)
    if (!target.exists() || !target.isFile) {
        GlassToaster.show("附件文件不存在")
        return
    }
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        target
    )
    val mime = file.mimeType.ifBlank { "*/*" }
    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(viewIntent)
    } catch (_: ActivityNotFoundException) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.fileName)
            putExtra(Intent.EXTRA_TITLE, file.fileName)
            clipData = ClipData.newRawUri(file.fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "打开或保存「${file.fileName}」").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
            .onFailure { GlassToaster.show("未找到可打开该附件的应用") }
    } catch (_: SecurityException) {
        GlassToaster.show("打开附件受限，请稍后重试")
    }
}

private fun wrapMailHtmlDocument(bodyHtml: String, darkTheme: Boolean): String {
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
            blockquote {
                margin: 8px 0;
                padding-left: 10px;
                border-left: 2px solid $linkColor;
                opacity: 0.85;
            }
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

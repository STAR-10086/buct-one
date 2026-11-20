package com.buct.course

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.buct.course.manager.AppThemeCoordinator
import com.buct.course.manager.UserManager
import com.buct.course.schedule.ScheduleDates
import com.buct.course.ui.system.GlassLoadingIndicator
import com.buct.course.ui.system.GlassPageScaffold
import com.buct.course.ui.system.GlassStatChip
import com.buct.course.ui.system.GlassTextField
import com.buct.course.ui.system.GlassToaster
import com.buct.course.ui.system.GlassWindowHost
import com.buct.course.ui.system.SystemCompactSegmentedControl
import com.buct.course.ui.system.SystemDialog
import com.buct.course.ui.system.SystemEmptyState
import com.buct.course.ui.system.SystemIconButton
import com.buct.course.ui.system.SystemPrimaryButton
import com.buct.course.ui.system.SystemSecondaryButton
import com.buct.course.ui.system.glassBorderColor
import com.buct.course.ui.system.glassSurfaceColor
import com.buct.course.ui.theme.CourseSelectorTheme
import com.buct.one.data.model.PortalBookItem
import com.buct.one.data.model.PortalNewsArticle
import com.buct.one.data.model.PortalNewsColumn
import com.buct.one.data.model.PortalNewsColumnPage
import com.buct.one.data.model.RefreshState
import com.buct.one.data.sync.PortalSyncService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * i北化轻量化内容页：支持「校内综合资讯」与「图书借阅与待还」两种独立模式。
 */
class PortalActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getIntExtra(EXTRA_MODE, MODE_NEWS)
        val initialCol = intent.getIntExtra(EXTRA_NEWS_COLUMN, 0).coerceIn(0, PortalNewsColumn.entries.lastIndex)
        setContent {
            CourseSelectorTheme {
                GlassWindowHost {
                    when (mode) {
                        MODE_LIBRARY -> PortalLibraryScreen(onBack = { finish() })
                        else -> PortalNewsScreen(initialColumnIndex = initialCol, onBack = { finish() })
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_MODE = "extra_mode"
        private const val EXTRA_NEWS_COLUMN = "extra_news_column"
        private const val MODE_NEWS = 0
        private const val MODE_LIBRARY = 1

        fun launchNews(context: Context, initialColumnIndex: Int = 0) {
            context.startActivity(
                Intent(context, PortalActivity::class.java).apply {
                    putExtra(EXTRA_MODE, MODE_NEWS)
                    putExtra(EXTRA_NEWS_COLUMN, initialColumnIndex.coerceIn(0, PortalNewsColumn.entries.lastIndex))
                }
            )
        }

        fun launchLibrary(context: Context) {
            context.startActivity(
                Intent(context, PortalActivity::class.java).apply {
                    putExtra(EXTRA_MODE, MODE_LIBRARY)
                }
            )
        }
    }
}

internal fun portalBookDaysRemaining(dueDate: String): Int? {
    val dueMillis = ScheduleDates.parseDateMillis(dueDate.trim()) ?: return null
    val todayMidnight = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return ((dueMillis - todayMidnight) / 86_400_000L).toInt()
}

@Composable
private fun PortalNewsScreen(
    initialColumnIndex: Int,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember(context) { PortalSyncService.getInstance(context) }

    val slice by service.newsState.collectAsState()
    val syncing by service.newsSyncing.collectAsState()

    val columns = PortalNewsColumn.entries
    var selectedColIdx by rememberSaveable { mutableIntStateOf(initialColumnIndex) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showLoginDialog by rememberSaveable { mutableStateOf(false) }
    var feedbackMsg by remember { mutableStateOf<String?>(null) }

    var extraPages by remember { mutableStateOf<Map<String, PortalNewsColumnPage>>(emptyMap()) }
    var loadingMore by remember { mutableStateOf(false) }
    var selectedArticle by remember { mutableStateOf<PortalNewsArticle?>(null) }

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
            service.refreshNews(userInitiated = false)
        }
    }

    if (selectedArticle != null) {
        val article = selectedArticle!!
        BackHandler { selectedArticle = null }
        PortalNewsDetailView(
            article = article,
            onBack = { selectedArticle = null }
        )
        return
    }

    val currentColumn = columns[selectedColIdx]
    val activePage = extraPages[currentColumn.columnId] ?: slice.data?.columns?.get(currentColumn.columnId)
    val rawArticles = activePage?.articles.orEmpty()
    val totalCount = activePage?.totalCount ?: 0

    val displayedArticles = remember(rawArticles, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            rawArticles
        } else {
            rawArticles.filter { a ->
                a.title.contains(q, ignoreCase = true) ||
                    a.summary.contains(q, ignoreCase = true) ||
                    a.sourceOrg.contains(q, ignoreCase = true)
            }
        }
    }

    val statusText = remember(slice.provenance.capturedAt, syncing) {
        when {
            syncing -> "正在同步 mnews.buct.edu.cn…"
            slice.provenance.capturedAt > 0L -> {
                val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                "更新于 ${fmt.format(Date(slice.provenance.capturedAt))}"
            }
            else -> "i北化（mnews.buct.edu.cn）"
        }
    }

    GlassPageScaffold(
        title = "校内综合资讯",
        subtitle = statusText,
        onBack = onBack,
        actions = {
            SystemIconButton(
                icon = Icons.Outlined.Search,
                contentDescription = "搜索资讯",
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
                            extraPages = emptyMap()
                            val res = service.refreshNews(userInitiated = true)
                            if (res.isFailure) {
                                val err = res.exceptionOrNull()?.message ?: "校内资讯刷新失败"
                                feedbackMsg = err
                                GlassToaster.show(err)
                                if (isPortalAuthError(err)) showLoginDialog = true
                            } else {
                                GlassToaster.show("校内资讯已更新")
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
                PortalErrorBanner(bannerError)
            }

            SystemCompactSegmentedControl(
                options = columns.map { it.label },
                selectedIndex = selectedColIdx,
                onSelect = {
                    selectedColIdx = it
                    feedbackMsg = null
                }
            )

            AnimatedVisibility(visible = searchVisible) {
                GlassTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = "筛选当前栏目标题、摘要或发布单位…",
                    leadingIcon = Icons.Outlined.Search,
                    trailing = if (searchQuery.isNotEmpty()) {
                        {
                            SystemIconButton(
                                icon = Icons.Outlined.Close,
                                contentDescription = "清空",
                                chip = false,
                                onClick = { searchQuery = "" }
                            )
                        }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (displayedArticles.isEmpty()) {
                PortalEmptyBox(
                    icon = Icons.Outlined.Campaign,
                    title = when {
                        slice.provenance.refreshState == RefreshState.NEVER && !syncing -> "尚未同步校内资讯"
                        searchQuery.isNotBlank() -> "未找到匹配的资讯"
                        else -> "当前栏目暂无内容"
                    },
                    message = if (isPortalAuthError(bannerError.orEmpty())) {
                        "统一身份认证会话已失效，请重新登录后获取「${currentColumn.label}」。"
                    } else if (slice.provenance.refreshState == RefreshState.NEVER) {
                        "来源：${currentColumn.sourceHint}，点击右上角刷新同步最新资讯。"
                    } else {
                        "来源：${currentColumn.sourceHint}。"
                    },
                    showLoginButton = slice.provenance.refreshState == RefreshState.NEVER || isPortalAuthError(bannerError.orEmpty()),
                    onLoginClick = { showLoginDialog = true }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)
                ) {
                    items(
                        items = displayedArticles,
                        key = { "${it.columnId}:${it.id}" }
                    ) { article ->
                        PortalNewsArticleCard(
                            article = article,
                            fallbackSource = currentColumn.sourceHint,
                            onClick = { selectedArticle = article }
                        )
                    }
                    if (rawArticles.size < totalCount && searchQuery.isBlank()) {
                        item(key = "load_more_${currentColumn.columnId}") {
                            SystemSecondaryButton(
                                text = if (loadingMore) "正在加载…" else "加载更多（已显示 ${rawArticles.size}/$totalCount）",
                                enabled = !loadingMore,
                                onClick = {
                                    if (loadingMore) return@SystemSecondaryButton
                                    scope.launch {
                                        loadingMore = true
                                        val res = service.loadMoreNewsColumn(
                                            columnId = currentColumn.columnId,
                                            beginIndex = rawArticles.size,
                                            pageSize = 15
                                        )
                                        loadingMore = false
                                        res.onSuccess { nextPage ->
                                            val existingIds = rawArticles.mapTo(HashSet()) { it.id }
                                            val mergedArticles = rawArticles + nextPage.articles.filter { it.id !in existingIds }
                                            extraPages = extraPages + (
                                                currentColumn.columnId to PortalNewsColumnPage(
                                                    columnId = currentColumn.columnId,
                                                    totalCount = maxOf(totalCount, nextPage.totalCount),
                                                    articles = mergedArticles
                                                )
                                            )
                                        }.onFailure { err ->
                                            GlassToaster.show(err.message ?: "加载更多资讯失败")
                                        }
                                    }
                                },
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

    if (showLoginDialog) {
        PortalSidLoginDialog(
            syncing = syncing,
            onDismiss = { showLoginDialog = false },
            onSubmit = { username, chars ->
                scope.launch {
                    val res = service.loginAndSyncPortal(username, chars)
                    res.onSuccess {
                        showLoginDialog = false
                        feedbackMsg = null
                        extraPages = emptyMap()
                        GlassToaster.show("登录成功，已同步门户数据")
                    }.onFailure { err ->
                        GlassToaster.show(err.message ?: "统一身份认证登录失败")
                    }
                }
            }
        )
    }
}

@Composable
private fun PortalNewsArticleCard(
    article: PortalNewsArticle,
    fallbackSource: String,
    onClick: () -> Unit
) {
    val cardShape = RoundedCornerShape(18.dp)
    val preview = article.summary.ifBlank { article.content }
    val dateLabel = remember(article.publishTimestampMs) {
        formatNewsDate(article.publishTimestampMs, includeTime = false)
    }

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
        val hasBadge = article.isTop || article.isHot || article.isNewest || dateLabel.isNotBlank()
        if (hasBadge) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (article.isTop) MiniTagBadge("置顶", Color(0xFFFF9500))
                if (article.isHot) MiniTagBadge("热", Color(0xFFFF3B30))
                if (article.isNewest) MiniTagBadge("新", Color(0xFF34C759))
                Spacer(modifier = Modifier.weight(1f))
                if (dateLabel.isNotBlank()) {
                    Text(
                        text = dateLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }

        Text(
            text = article.title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 20.sp
        )

        if (preview.isNotBlank()) {
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 18.sp
            )
        }

        val sourceLabel = article.sourceOrg.ifBlank { fallbackSource }
        Text(
            text = sourceLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PortalNewsDetailView(
    article: PortalNewsArticle,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val cardShape = RoundedCornerShape(20.dp)
    val bodyText = article.content.ifBlank { article.summary }
    val dateText = remember(article.publishTimestampMs) {
        formatNewsDate(article.publishTimestampMs, includeTime = true)
    }

    GlassPageScaffold(
        title = "资讯正文",
        subtitle = article.sourceOrg.ifBlank { "i北化校内资讯" },
        onBack = onBack,
        actions = {
            if (bodyText.isNotBlank()) {
                SystemIconButton(
                    icon = Icons.Outlined.ContentCopy,
                    contentDescription = "复制正文",
                    onClick = {
                        clipboard.setText(AnnotatedString("${article.title}\n\n$bodyText"))
                        GlassToaster.show("已复制正文内容")
                    }
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
                        text = article.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                val metaParts = buildList {
                    if (article.sourceOrg.isNotBlank()) add("来源：${article.sourceOrg}")
                    if (dateText.isNotBlank()) add(dateText)
                    if (article.visitCount.isNotBlank()) add("阅读 ${article.visitCount}")
                }
                if (metaParts.isNotEmpty()) {
                    Text(
                        text = metaParts.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

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
                        text = bodyText.ifBlank { "该资讯正文未提供纯文本摘要，请点击下方按钮在浏览器查看原文。" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 23.sp
                    )
                }
            }

            if (article.publicUrl.isNotBlank()) {
                SystemPrimaryButton(
                    text = "在浏览器打开原文",
                    onClick = { openPublicPortalUrl(context, article.publicUrl) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun PortalLibraryScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember(context) { PortalSyncService.getInstance(context) }

    val slice by service.libraryState.collectAsState()
    val syncing by service.librarySyncing.collectAsState()
    var showLoginDialog by rememberSaveable { mutableStateOf(false) }
    var feedbackMsg by remember { mutableStateOf<String?>(null) }

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
            service.refreshLibrary(userInitiated = false)
        }
    }

    val statusText = remember(slice.provenance.capturedAt, syncing) {
        when {
            syncing -> "正在同步借阅清单…"
            slice.provenance.capturedAt > 0L -> {
                val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                "更新于 ${fmt.format(Date(slice.provenance.capturedAt))}"
            }
            else -> "i北化图书馆在借数据"
        }
    }

    GlassPageScaffold(
        title = "图书借阅与待还",
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
                            val res = service.refreshLibrary(userInitiated = true)
                            if (res.isFailure) {
                                val err = res.exceptionOrNull()?.message ?: "图书借阅刷新失败"
                                feedbackMsg = err
                                GlassToaster.show(err)
                                if (isPortalAuthError(err)) showLoginDialog = true
                            } else {
                                GlassToaster.show("在借图书已更新")
                            }
                        }
                    }
                )
            }
        }
    ) { padding ->
        val data = slice.data
        val state = slice.provenance.refreshState
        val bannerError = feedbackMsg ?: slice.lastError

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding() + 4.dp,
                    start = 16.dp,
                    end = 16.dp
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!bannerError.isNullOrBlank()) {
                PortalErrorBanner(bannerError)
            }

            when {
                state == RefreshState.NEVER && data == null -> {
                    PortalEmptyBox(
                        icon = Icons.Outlined.MenuBook,
                        title = "尚未同步图书借阅",
                        message = if (isPortalAuthError(bannerError.orEmpty())) {
                            "统一身份认证会话已失效，请重新登录后查询待还书信息。"
                        } else {
                            "点击右上角刷新按钮同步 i北化当前在借图书与应还日期。"
                        },
                        showLoginButton = true,
                        onLoginClick = { showLoginDialog = true }
                    )
                }

                data != null && (state == RefreshState.EMPTY_CONFIRMED || data.books.isEmpty()) -> {
                    LibrarySummaryHeader(totalCount = data.totalCount, books = data.books)
                    PortalEmptyBox(
                        icon = Icons.Outlined.MenuBook,
                        title = "当前暂无待还图书",
                        message = "已确认与 i北化同步完成，名下目前没有未归还图书。",
                        showLoginButton = false,
                        onLoginClick = {}
                    )
                }

                data != null -> {
                    LibrarySummaryHeader(totalCount = data.totalCount, books = data.books)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)
                    ) {
                        items(
                            items = data.books,
                            key = { "${it.barcode}:${it.title}" }
                        ) { book ->
                            PortalBookCard(book)
                        }
                    }
                }
            }
        }
    }

    if (showLoginDialog) {
        PortalSidLoginDialog(
            syncing = syncing,
            onDismiss = { showLoginDialog = false },
            onSubmit = { username, chars ->
                scope.launch {
                    val res = service.loginAndSyncPortal(username, chars)
                    res.onSuccess {
                        showLoginDialog = false
                        feedbackMsg = null
                        GlassToaster.show("登录成功，已同步图书与资讯")
                    }.onFailure { err ->
                        GlassToaster.show(err.message ?: "统一身份认证登录失败")
                    }
                }
            }
        )
    }
}

@Composable
private fun LibrarySummaryHeader(
    totalCount: Int,
    books: List<PortalBookItem>
) {
    val overdueCount = remember(books) {
        books.count { (portalBookDaysRemaining(it.dueDate) ?: Int.MAX_VALUE) < 0 }
    }
    val soonDueCount = remember(books) {
        books.count {
            val d = portalBookDaysRemaining(it.dueDate)
            d != null && d in 0..7
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        GlassStatChip(
            value = totalCount.toString(),
            label = "在借总本数",
            modifier = Modifier.weight(1f),
            valueColor = MaterialTheme.colorScheme.primary
        )
        GlassStatChip(
            value = soonDueCount.toString(),
            label = "7天内到期",
            modifier = Modifier.weight(1f),
            valueColor = if (soonDueCount > 0) Color(0xFFFF9500) else MaterialTheme.colorScheme.onSurface
        )
        GlassStatChip(
            value = overdueCount.toString(),
            label = "已逾期",
            modifier = Modifier.weight(1f),
            valueColor = if (overdueCount > 0) Color(0xFFFF3B30) else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun PortalBookCard(book: PortalBookItem) {
    val cardShape = RoundedCornerShape(18.dp)
    val daysLeft = remember(book.dueDate) { portalBookDaysRemaining(book.dueDate) }
    val (statusLabel, statusColor) = when {
        daysLeft == null -> "借阅中" to Color(0xFF34C759)
        daysLeft < 0 -> "已逾期 ${-daysLeft} 天" to Color(0xFFFF3B30)
        daysLeft == 0 -> "今天到期" to Color(0xFFFF9500)
        daysLeft <= 7 -> "剩 $daysLeft 天到期" to Color(0xFFFF9500)
        else -> "剩 $daysLeft 天" to Color(0xFF34C759)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), cardShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MiniTagBadge(statusLabel, statusColor)
            if (book.barcode.isNotBlank()) {
                Text(
                    text = "条码 ${book.barcode}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }

        Text(
            text = book.title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "借书：${book.borrowDate.ifBlank { "--" }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "应还：${book.dueDate.ifBlank { "--" }}",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = if (daysLeft != null && daysLeft <= 7) statusColor else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun PortalSidLoginDialog(
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
                text = "登录 sidportal.buct.edu.cn 以换取 i北化与资讯服务会话（凭据仅用于本次换票，学号密码不落盘）。",
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
private fun PortalErrorBanner(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFFF453A).copy(alpha = 0.12f))
            .border(0.5.dp, Color(0xFFFF453A).copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFFF453A)
        )
    }
}

@Composable
private fun PortalEmptyBox(
    icon: ImageVector,
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
                icon = icon
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
private fun MiniTagBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = color,
            maxLines = 1
        )
    }
}

private fun formatNewsDate(timestampMs: Long, includeTime: Boolean): String {
    if (timestampMs <= 0L) return ""
    val pattern = if (includeTime) "yyyy-MM-dd HH:mm" else "MM-dd"
    return SimpleDateFormat(pattern, Locale.CHINA).format(Date(timestampMs))
}

private fun isPortalAuthError(message: String): Boolean =
    message.contains("登录") || message.contains("过期") || message.contains("失效") || message.contains("会话")

private fun openPublicPortalUrl(context: Context, rawUrl: String) {
    val clean = rawUrl.trim()
    val uri = Uri.parse(clean)
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
        GlassToaster.show("未找到可打开网页的浏览器")
    }
}

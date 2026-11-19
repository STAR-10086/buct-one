package com.buct.course

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buct.course.manager.AppThemeCoordinator
import com.buct.course.manager.UserManager
import com.buct.course.ui.system.SystemIconButton
import com.buct.course.ui.system.SystemTopBar
import com.buct.course.ui.system.glassBorderColor
import com.buct.course.ui.system.glassSurfaceColor
import com.buct.course.ui.theme.CourseSelectorTheme
import com.buct.one.data.model.CardSnapshot
import com.buct.one.data.model.CardTransaction
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.RefreshState
import com.buct.one.data.model.formatFenToYuan
import com.buct.one.data.sync.CardSyncService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class EcardRangeFilter(val label: String) {
    DAYS_7("近 7 天"),
    THIS_MONTH("本月"),
    DAYS_30("近 30 天");

    fun resolveDates(): Pair<String, String> = when (this) {
        DAYS_7 -> CardSyncService.computeRecentRange(7)
        THIS_MONTH -> CardSyncService.computeCurrentMonthRange()
        DAYS_30 -> CardSyncService.computeRecentRange(30)
    }
}

/**
 * 原生校园卡中心（ecard.buct.edu.cn）。
 * 独立于统一身份认证（sidportal），支持独立登录、实时余额、隐私遮罩、消费流水筛选分页与企微快捷跳转。
 */
class EcardActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cardService = CardSyncService.getInstance(this)
        val userManager = UserManager.getInstance().apply { init(this@EcardActivity) }

        setContent {
            CourseSelectorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val cardSlice by cardService.cardState.collectAsState()
                    val syncing by cardService.syncing.collectAsState()
                    val hasSession by cardService.hasSession.collectAsState()
                    val scope = rememberCoroutineScope()

                    var forceLoginScreen by remember { mutableStateOf(false) }
                    var allowCachedPreview by remember { mutableStateOf(false) }
                    var selectedRange by remember { mutableStateOf(EcardRangeFilter.DAYS_30) }
                    var currentPage by remember { mutableIntStateOf(0) }
                    var showLogoutConfirm by remember { mutableStateOf(false) }

                    LaunchedEffect(Unit) {
                        if (hasSession && !forceLoginScreen) {
                            val (start, end) = selectedRange.resolveDates()
                            currentPage = 0
                            cardService.refresh(startDate = start, endDate = end, pageSize = PAGE_SIZE)
                        }
                    }

                    val showLoginScreen = forceLoginScreen || (!hasSession && (cardSlice.data == null || !allowCachedPreview))

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                            .imePadding()
                    ) {
                        SystemTopBar(
                            title = if (showLoginScreen) "登录校园卡" else "校园一卡通",
                            subtitle = if (showLoginScreen) "ecard.buct.edu.cn · 独立认证" else "实时余额与消费流水",
                            backdrop = null,
                            navigationIcon = {
                                SystemIconButton(
                                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回",
                                    onClick = {
                                        if (forceLoginScreen && cardSlice.data != null) {
                                            forceLoginScreen = false
                                        } else {
                                            finish()
                                        }
                                    }
                                )
                            },
                            actions = {
                                if (!showLoginScreen) {
                                    SystemIconButton(
                                        icon = Icons.Default.Refresh,
                                        contentDescription = "刷新",
                                        enabled = !syncing,
                                        onClick = {
                                            if (hasSession) {
                                                val (start, end) = selectedRange.resolveDates()
                                                currentPage = 0
                                                scope.launch {
                                                    cardService.refresh(startDate = start, endDate = end, pageSize = PAGE_SIZE)
                                                }
                                            } else {
                                                forceLoginScreen = true
                                            }
                                        }
                                    )
                                    SystemIconButton(
                                        icon = Icons.AutoMirrored.Outlined.Logout,
                                        contentDescription = "退出校园卡登录",
                                        enabled = !syncing,
                                        onClick = { showLogoutConfirm = true }
                                    )
                                }
                            }
                        )

                        if (syncing) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                            )
                        }

                        if (showLoginScreen) {
                            val prefillId = remember {
                                cardService.getSavedAccountId()
                                    .ifBlank { userManager.studentId.orEmpty() }
                                    .ifBlank { userManager.username.orEmpty() }
                            }
                            EcardLoginPane(
                                initialId = prefillId,
                                hasCachedSnapshot = cardSlice.data != null,
                                sessionExpiredMessage = if (!hasSession && cardSlice.data != null) {
                                    cardSlice.lastError ?: "校园卡会话已过期，请重新登录以刷新最新余额"
                                } else null,
                                syncing = syncing,
                                onViewCached = {
                                    forceLoginScreen = false
                                    allowCachedPreview = true
                                },
                                onSubmit = { idserial, passwordChars, onDone ->
                                    scope.launch {
                                        val res = cardService.loginAndSync(
                                            idserial = idserial,
                                            password = passwordChars,
                                            rangeDays = 30,
                                            pageSize = PAGE_SIZE
                                        )
                                        if (res.isSuccess) {
                                            selectedRange = EcardRangeFilter.DAYS_30
                                            currentPage = 0
                                            forceLoginScreen = false
                                            allowCachedPreview = true
                                            onDone(null)
                                        } else {
                                            onDone(res.exceptionOrNull()?.message ?: "登录失败，请检查账号或卡密码")
                                        }
                                    }
                                }
                            )
                        } else {
                            EcardHomePane(
                                slice = cardSlice,
                                hasSession = hasSession,
                                syncing = syncing,
                                selectedRange = selectedRange,
                                onSelectRange = { range ->
                                    if (!hasSession) {
                                        forceLoginScreen = true
                                        return@EcardHomePane
                                    }
                                    selectedRange = range
                                    currentPage = 0
                                    val (start, end) = range.resolveDates()
                                    scope.launch {
                                        cardService.refresh(startDate = start, endDate = end, pageSize = PAGE_SIZE)
                                    }
                                },
                                onRefresh = {
                                    if (!hasSession) {
                                        forceLoginScreen = true
                                        return@EcardHomePane
                                    }
                                    val (start, end) = selectedRange.resolveDates()
                                    currentPage = 0
                                    scope.launch {
                                        cardService.refresh(startDate = start, endDate = end, pageSize = PAGE_SIZE)
                                    }
                                },
                                onLoadMore = {
                                    if (!hasSession || syncing) return@EcardHomePane
                                    val nextPage = currentPage + 1
                                    val (start, end) = selectedRange.resolveDates()
                                    scope.launch {
                                        val res = cardService.loadMore(
                                            startDate = start,
                                            endDate = end,
                                            nextPage = nextPage,
                                            pageSize = PAGE_SIZE
                                        )
                                        if (res.isSuccess) {
                                            currentPage = nextPage
                                        }
                                    }
                                },
                                onRelogin = { forceLoginScreen = true },
                                onOpenWecom = { launchWecomOrWechat(this@EcardActivity) }
                            )
                        }
                    }

                    if (showLogoutConfirm) {
                        AlertDialog(
                            onDismissRequest = { showLogoutConfirm = false },
                            title = { Text("退出校园卡登录") },
                            text = { Text("将清除本地加密保存的校园卡会话与缓存流水记录（不影响教务统一身份认证登录）。") },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showLogoutConfirm = false
                                        scope.launch {
                                            cardService.logout()
                                            allowCachedPreview = false
                                            forceLoginScreen = true
                                        }
                                    }
                                ) {
                                    Text("确认退出", color = MaterialTheme.colorScheme.error)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showLogoutConfirm = false }) {
                                    Text("取消")
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun launchWecomOrWechat(context: Context) {
        val packages = listOf("com.tencent.wework", "com.tencent.mm")
        for (pkg in packages) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching {
                    context.startActivity(intent)
                    return
                }
            }
        }
        Toast.makeText(
            context,
            "未检测到企业微信或微信，请通过企业微信「工作台 → 校园卡服务」充值或刷码",
            Toast.LENGTH_LONG
        ).show()
    }

    companion object {
        private const val PAGE_SIZE = 20
    }
}

@Composable
private fun EcardLoginPane(
    initialId: String,
    hasCachedSnapshot: Boolean,
    sessionExpiredMessage: String?,
    syncing: Boolean,
    onViewCached: () -> Unit,
    onSubmit: (idserial: String, password: CharArray, onDone: (error: String?) -> Unit) -> Unit
) {
    var idInput by remember(initialId) { mutableStateOf(initialId) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    fun triggerLogin() {
        val cleanId = idInput.trim()
        if (cleanId.isEmpty()) {
            localError = "请输入学工号"
            return
        }
        if (passwordInput.isEmpty()) {
            localError = "请输入校园卡查询密码"
            return
        }
        localError = null
        val pwdChars = passwordInput.toCharArray()
        onSubmit(cleanId, pwdChars) { err ->
            if (err == null) {
                passwordInput = ""
            } else {
                localError = err
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val cardShape = RoundedCornerShape(26.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(glassSurfaceColor())
                .border(0.5.dp, glassBorderColor(), cardShape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CreditCard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "北京化工大学 · 一卡通查询平台",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "独立登录通道 · 密码不落盘",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Text(
                text = "校园卡系统（ecard.buct.edu.cn）未接入统一身份认证，默认密码通常为本人身份证后六位；凭据仅在本地 AES+RSA 加密后直连校方服务器，不保存密码明文。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!sessionExpiredMessage.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFFF9500).copy(alpha = 0.14f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = Color(0xFFFF9500),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = sessionExpiredMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            OutlinedTextField(
                value = idInput,
                onValueChange = {
                    idInput = it
                    localError = null
                },
                label = { Text("学工号") },
                placeholder = { Text("请输入学号或教工号") },
                leadingIcon = {
                    Icon(imageVector = Icons.Outlined.Person, contentDescription = null)
                },
                singleLine = true,
                enabled = !syncing,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            )

            OutlinedTextField(
                value = passwordInput,
                onValueChange = {
                    passwordInput = it
                    localError = null
                },
                label = { Text("校园卡查询密码") },
                placeholder = { Text("默认一般为身份证后六位") },
                leadingIcon = {
                    Icon(imageVector = Icons.Outlined.Lock, contentDescription = null)
                },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                        )
                    }
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                enabled = !syncing,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { triggerLogin() }),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            )

            localError?.let { errMsg ->
                Text(
                    text = errMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Button(
                onClick = { triggerLogin() },
                enabled = !syncing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (syncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在安全登录并同步…")
                } else {
                    Text("登录并同步校园卡")
                }
            }

            if (hasCachedSnapshot) {
                OutlinedButton(
                    onClick = onViewCached,
                    enabled = !syncing,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("先查看本地缓存数据")
                }
            }
        }
    }
}

@Composable
private fun EcardHomePane(
    slice: DomainSlice<CardSnapshot>,
    hasSession: Boolean,
    syncing: Boolean,
    selectedRange: EcardRangeFilter,
    onSelectRange: (EcardRangeFilter) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRelogin: () -> Unit,
    onOpenWecom: () -> Unit
) {
    val snapshot = slice.data
    var hideBalance by remember { mutableStateOf(false) }

    if (snapshot == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Text(
                    text = "正在同步校园卡数据…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val account = snapshot.account
    val isNormal = account.statusCode == "1"
    val capturedText = remember(slice.provenance.capturedAt) {
        if (slice.provenance.capturedAt > 0L) {
            SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(slice.provenance.capturedAt))
        } else {
            "刚刚"
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. 液态渐变校园卡 Hero 卡
        item {
            val heroShape = RoundedCornerShape(24.dp)
            val heroBrush = Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.82f),
                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.88f)
                )
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(heroShape)
                    .background(heroBrush)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "北京化工大学 · ${account.cardTypeName}",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${account.holderName} · ${account.maskedId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.86f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (isNormal) Color(0xFF34C759).copy(alpha = 0.25f)
                                else Color(0xFFFF453A).copy(alpha = 0.30f)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = account.statusLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "当前可用余额",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.80f)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (hideBalance) "¥ ****" else "¥ ${formatFenToYuan(account.balanceFen)}",
                            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                            .clickable { hideBalance = !hideBalance },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (hideBalance) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (hideBalance) "显示余额" else "隐藏余额",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "单笔限额 ¥${formatFenToYuan(account.singleLimitFen)} · 累计 ¥${formatFenToYuan(account.cumulativeLimitFen)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.82f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "同步于 $capturedText",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.82f)
                    )
                }
            }
        }

        // 2. 会话失效 / 失败回退告警横幅
        if (!hasSession || slice.provenance.refreshState == RefreshState.FAILED_FALLBACK || !slice.lastError.isNullOrBlank()) {
            item {
                val warnColor = if (!hasSession) Color(0xFFFF9500) else Color(0xFFFF453A)
                val warnText = slice.lastError ?: "校园卡会话已过期，当前展示历史缓存"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(warnColor.copy(alpha = 0.12f))
                        .border(0.5.dp, warnColor.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = warnColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = warnText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (!hasSession) {
                        TextButton(
                            onClick = onRelogin,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("重新登录")
                        }
                    }
                }
            }
        }

        // 3. 快捷操作行
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !syncing,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (hasSession) "刷新余额流水" else "重新登录")
                }
                Button(
                    onClick = onOpenWecom,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("企微充值 / 刷码")
                }
            }
        }

        // 4. 流水日期筛选与统计
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    EcardRangeFilter.entries.forEach { range ->
                        FilterChip(
                            selected = selectedRange == range,
                            onClick = { onSelectRange(range) },
                            label = { Text(range.label) },
                            enabled = !syncing
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "消费与充值流水（共 ${snapshot.totalCount} 笔）",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "${snapshot.queryStartDate} ~ ${snapshot.queryEndDate}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 5. 空列表或流水明细
        if (snapshot.transactions.isEmpty()) {
            item {
                val emptyShape = RoundedCornerShape(22.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(emptyShape)
                        .background(glassSurfaceColor())
                        .border(0.5.dp, glassBorderColor(), emptyShape)
                        .padding(vertical = 32.dp, horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ReceiptLong,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(34.dp)
                    )
                    Text(
                        text = if (slice.provenance.refreshState == RefreshState.NEVER) {
                            "尚未同步到流水记录"
                        } else {
                            "所选时间段暂无消费或充值记录"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            itemsIndexed(
                items = snapshot.transactions,
                key = { index, tx -> "${index}_${tx.id}_${tx.txCode}_${tx.amountFen}" }
            ) { _, tx ->
                EcardTransactionCard(tx = tx, hideAmount = hideBalance)
            }

            if (snapshot.hasMore && hasSession) {
                item {
                    OutlinedButton(
                        onClick = onLoadMore,
                        enabled = !syncing,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        if (syncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("正在加载更多…")
                        } else {
                            Text("加载更多记录（已显示 ${snapshot.transactions.size} / ${snapshot.totalCount}）")
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun EcardTransactionCard(
    tx: CardTransaction,
    hideAmount: Boolean
) {
    val cardShape = RoundedCornerShape(18.dp)
    val amountColor = when {
        tx.amountFen > 0L && (tx.txName.contains("充") || tx.txName.contains("补") || tx.txName.contains("退") || tx.txCode in setOf("2239", "2240", "2241")) ->
            Color(0xFF34C759)
        tx.amountFen < 0L -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), cardShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.ReceiptLong,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = tx.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = buildString {
                append(tx.formattedTime)
                if (tx.txName.isNotBlank() && tx.txName != tx.title) {
                    append(" · ")
                    append(tx.txName)
                }
                if (tx.posCode.isNotBlank() && tx.posCode != "0000") {
                    append(" · 终端 ")
                    append(tx.posCode)
                }
            }
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (hideAmount) "¥ **" else "¥ ${formatFenToYuan(tx.amountFen)}",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = amountColor
            )
            Text(
                text = if (hideAmount) "余额 ****" else "余额 ¥${formatFenToYuan(tx.balanceAfterFen)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

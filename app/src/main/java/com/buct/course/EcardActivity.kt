package com.buct.course

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.buct.course.manager.AppThemeCoordinator
import com.buct.course.manager.UserManager
import com.buct.course.ui.system.GlassLoadingIndicator
import com.buct.course.ui.system.GlassLoadingState
import com.buct.course.ui.system.GlassPageScaffold
import com.buct.course.ui.system.GlassTextField
import com.buct.course.ui.system.GlassWindowHost
import com.buct.course.ui.system.LiquidSwitch
import com.buct.course.ui.system.SystemCompactSegmentedControl
import com.buct.course.ui.system.SystemDestructiveButton
import com.buct.course.ui.system.SystemDialog
import com.buct.course.ui.system.SystemEmptyState
import com.buct.course.ui.system.SystemIconButton
import com.buct.course.ui.system.SystemPrimaryButton
import com.buct.course.ui.system.SystemSecondaryButton
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
 * 统一托管于 [GlassPageScaffold] 液态玻璃体系：动态壁纸折射顶栏、玻璃输入框/分段条与玻璃弹窗。
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
                GlassWindowHost {
                    val cardSlice by cardService.cardState.collectAsState()
                val syncing by cardService.syncing.collectAsState()
                val hasSession by cardService.hasSession.collectAsState()
                val hasSavedPassword by cardService.hasSavedPassword.collectAsState()
                val canAutoSync = hasSession || hasSavedPassword
                val scope = rememberCoroutineScope()

                var forceLoginScreen by remember { mutableStateOf(false) }
                var allowCachedPreview by remember { mutableStateOf(false) }
                var selectedRange by remember { mutableStateOf(EcardRangeFilter.DAYS_30) }
                var currentPage by remember { mutableIntStateOf(0) }
                var showLogoutConfirm by remember { mutableStateOf(false) }

                val webVpnAuthLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { res ->
                    if (res.resultCode == RESULT_OK) {
                        val (start, end) = selectedRange.resolveDates()
                        currentPage = 0
                        scope.launch {
                            cardService.refresh(startDate = start, endDate = end, pageSize = PAGE_SIZE)
                        }
                    }
                }
                fun launchWebVpnAuth() {
                    webVpnAuthLauncher.launch(
                        Intent(this@EcardActivity, LoginActivity::class.java).apply {
                            putExtra(LoginActivity.EXTRA_WEBVPN_AUTH_MODE, true)
                            putExtra(LoginActivity.EXTRA_RETURN_TO_CALLER, true)
                        }
                    )
                }

                LaunchedEffect(Unit) {
                    if (canAutoSync && !forceLoginScreen) {
                        val (start, end) = selectedRange.resolveDates()
                        currentPage = 0
                        cardService.refresh(startDate = start, endDate = end, pageSize = PAGE_SIZE)
                    }
                }

                val showLoginScreen = forceLoginScreen || (!canAutoSync && (cardSlice.data == null || !allowCachedPreview))

                GlassPageScaffold(
                    title = if (showLoginScreen) "登录校园卡" else "校园一卡通",
                    subtitle = if (showLoginScreen) "ecard.buct.edu.cn · Keystore 加密存储" else "实时余额与消费流水",
                    onBack = {
                        if (forceLoginScreen && cardSlice.data != null) {
                            forceLoginScreen = false
                        } else {
                            finish()
                        }
                    },
                    actions = {
                        if (!showLoginScreen) {
                            if (syncing) {
                                GlassLoadingIndicator(size = 38.dp)
                            } else {
                                SystemIconButton(
                                    icon = Icons.Default.Refresh,
                                    contentDescription = "刷新",
                                    tint = MaterialTheme.colorScheme.primary,
                                    onClick = {
                                        if (canAutoSync) {
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
                            }
                            SystemIconButton(
                                icon = Icons.AutoMirrored.Outlined.Logout,
                                contentDescription = "退出校园卡登录",
                                enabled = !syncing,
                                onClick = { showLogoutConfirm = true }
                            )
                        }
                    }
                ) { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .imePadding()
                    ) {
                        AnimatedContent(
                            targetState = showLoginScreen,
                            transitionSpec = {
                                fadeIn(tween(220)) togetherWith fadeOut(tween(160))
                            },
                            label = "ecardPane"
                        ) { loginVisible ->
                        if (loginVisible) {
                            val prefillId = remember {
                                cardService.getSavedAccountId()
                                    .ifBlank { userManager.studentId.orEmpty() }
                                    .ifBlank { userManager.username.orEmpty() }
                            }
                            EcardLoginPane(
                                initialId = prefillId,
                                hasCachedSnapshot = cardSlice.data != null,
                                sessionExpiredMessage = if (!canAutoSync && cardSlice.data != null) {
                                    cardSlice.lastError ?: "校园卡会话已过期，请重新登录以刷新最新余额"
                                } else null,
                                syncing = syncing,
                                topPadding = padding.calculateTopPadding(),
                                bottomPadding = padding.calculateBottomPadding(),
                                onViewCached = {
                                    forceLoginScreen = false
                                    allowCachedPreview = true
                                },
                                onWebVpnAuth = ::launchWebVpnAuth,
                                onSubmit = { idserial, passwordChars, rememberPassword, onDone ->
                                    scope.launch {
                                        val res = cardService.loginAndSync(
                                            idserial = idserial,
                                            password = passwordChars,
                                            rangeDays = 30,
                                            pageSize = PAGE_SIZE,
                                            rememberPassword = rememberPassword
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
                                hasSession = canAutoSync,
                                syncing = syncing,
                                selectedRange = selectedRange,
                                topPadding = padding.calculateTopPadding(),
                                bottomPadding = padding.calculateBottomPadding(),
                                onSelectRange = { range ->
                                    if (!canAutoSync) {
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
                                    if (!canAutoSync) {
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
                                    if (!canAutoSync || syncing) return@EcardHomePane
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
                                onWebVpnAuth = ::launchWebVpnAuth,
                                onOpenWecom = { launchWecomOrWechat(this@EcardActivity) }
                            )
                        }
                        }
                    }

                    if (showLogoutConfirm) {
                        SystemDialog(
                            onDismissRequest = { showLogoutConfirm = false },
                            title = {
                                Text(
                                    text = "退出校园卡登录",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                                )
                            },
                            confirmButton = {
                                SystemDestructiveButton(
                                    text = "确认退出",
                                    onClick = {
                                        showLogoutConfirm = false
                                        scope.launch {
                                            cardService.logout()
                                            allowCachedPreview = false
                                            forceLoginScreen = true
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            dismissButton = {
                                SystemSecondaryButton(
                                    text = "取消",
                                    onClick = { showLogoutConfirm = false },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        ) {
                            Text(
                                text = "将清除本地加密保存的校园卡会话、已记住的查询密码与缓存流水记录（不影响教务统一身份认证登录）。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
                val launched = runCatching {
                    context.startActivity(intent)
                    true
                }.getOrDefault(false)
                if (launched) return
            }
        }
        val schemes = listOf("wxwork://", "weixin://")
        for (uriText in schemes) {
            val schemeIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uriText)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val launched = runCatching {
                context.startActivity(schemeIntent)
                true
            }.getOrDefault(false)
            if (launched) return
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
    topPadding: Dp,
    bottomPadding: Dp,
    onViewCached: () -> Unit,
    onWebVpnAuth: () -> Unit,
    onSubmit: (idserial: String, password: CharArray, rememberPassword: Boolean, onDone: (error: String?) -> Unit) -> Unit
) {
    var idInput by remember(initialId) { mutableStateOf(initialId) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var rememberPassword by remember { mutableStateOf(true) }
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
        onSubmit(cleanId, pwdChars, rememberPassword) { err ->
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
            .padding(
                start = 20.dp,
                end = 20.dp,
                top = topPadding + 12.dp,
                bottom = bottomPadding + 24.dp
            ),
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
                        text = "独立登录通道 · Keystore 本地加密",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Text(
                text = "校园卡系统（ecard.buct.edu.cn）未接入统一身份认证，默认查询密码为本人身份证后六位（含字母时用数字 1 代替）；凭据经 AES+RSA 加密直连校方服务器，开启记住密码后仅用 Android Keystore 加密存于本机且不参与云备份。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!sessionExpiredMessage.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFFF9500).copy(alpha = 0.14f))
                        .border(0.5.dp, Color(0xFFFF9500).copy(alpha = 0.35f), RoundedCornerShape(14.dp))
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

            GlassTextField(
                value = idInput,
                onValueChange = {
                    idInput = it
                    localError = null
                },
                placeholder = "请输入学号或教工号",
                leadingIcon = Icons.Outlined.Person,
                singleLine = true,
                enabled = !syncing,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassTextField(
                    value = passwordInput,
                    onValueChange = {
                        passwordInput = it
                        localError = null
                    },
                    placeholder = "请输入校园卡查询密码",
                    leadingIcon = Icons.Outlined.Lock,
                    trailing = {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { passwordVisible = !passwordVisible }
                        )
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !syncing,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { triggerLogin() }),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "默认查询密码为身份证后六位，若含字母（如 X）请用 1 代替",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                    .clickable(enabled = !syncing) { rememberPassword = !rememberPassword }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "本机加密记住校园卡密码",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "仅用 Android Keystore 加密存本机，会话过期自动续期",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LiquidSwitch(
                    checked = rememberPassword,
                    onCheckedChange = { if (!syncing) rememberPassword = it },
                    enabled = !syncing
                )
            }

            localError?.let { errMsg ->
                Text(
                    text = errMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            val needWebVpnAuth = listOfNotNull(sessionExpiredMessage, localError).any { msg ->
                msg.contains("WebVPN", ignoreCase = true) || msg.contains("二次验证")
            }
            if (needWebVpnAuth) {
                SystemSecondaryButton(
                    text = "去完成 WebVPN 认证",
                    enabled = !syncing,
                    onClick = onWebVpnAuth,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SystemPrimaryButton(
                text = if (syncing) "正在安全登录并同步…" else "登录并同步校园卡",
                enabled = !syncing,
                onClick = { triggerLogin() },
                modifier = Modifier.fillMaxWidth()
            )

            if (hasCachedSnapshot) {
                SystemSecondaryButton(
                    text = "先查看本地缓存数据",
                    enabled = !syncing,
                    onClick = onViewCached,
                    modifier = Modifier.fillMaxWidth()
                )
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
    topPadding: Dp,
    bottomPadding: Dp,
    onSelectRange: (EcardRangeFilter) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRelogin: () -> Unit,
    onWebVpnAuth: () -> Unit,
    onOpenWecom: () -> Unit
) {
    val snapshot = slice.data
    var hideBalance by remember { mutableStateOf(false) }

    if (snapshot == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPadding, bottom = bottomPadding, start = 24.dp, end = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            val errText = slice.lastError
            if (!syncing && !errText.isNullOrBlank()) {
                val needWebVpnAuth = errText.contains("WebVPN", ignoreCase = true) ||
                    errText.contains("二次验证")
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = errText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    if (needWebVpnAuth) {
                        SystemPrimaryButton(
                            text = "去完成 WebVPN 认证",
                            onClick = onWebVpnAuth,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    SystemSecondaryButton(
                        text = "重试同步",
                        onClick = onRefresh,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                GlassLoadingState(text = "正在同步校园卡数据…")
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
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = topPadding + 10.dp,
            bottom = bottomPadding + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. 液态渐变校园卡 Hero 卡
        item {
            val heroShape = RoundedCornerShape(26.dp)
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
                    .border(0.6.dp, Color.White.copy(alpha = 0.28f), heroShape)
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
                val needWebVpnAuth = warnText.contains("WebVPN", ignoreCase = true) ||
                    warnText.contains("二次验证")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(warnColor.copy(alpha = 0.12f))
                        .border(0.5.dp, warnColor.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                        if (!hasSession && !needWebVpnAuth) {
                            TextButton(
                                onClick = onRelogin,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("重新登录")
                            }
                        }
                    }
                    if (needWebVpnAuth) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SystemPrimaryButton(
                                text = "去完成 WebVPN 认证",
                                enabled = !syncing,
                                onClick = onWebVpnAuth,
                                modifier = Modifier.weight(1f)
                            )
                            if (!hasSession) {
                                SystemSecondaryButton(
                                    text = "重登",
                                    enabled = !syncing,
                                    onClick = onRelogin
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. 液态胶囊快捷操作行
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SystemSecondaryButton(
                    text = if (hasSession) "刷新余额" else "重新登录",
                    enabled = !syncing,
                    onClick = onRefresh,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                SystemPrimaryButton(
                    text = "企微充值/刷码",
                    onClick = onOpenWecom,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 4. 流水日期分段筛选与统计
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SystemCompactSegmentedControl(
                    options = EcardRangeFilter.entries.map { it.label },
                    selectedIndex = EcardRangeFilter.entries.indexOf(selectedRange).coerceAtLeast(0),
                    onSelect = { idx ->
                        EcardRangeFilter.entries.getOrNull(idx)?.let(onSelectRange)
                    },
                    enabled = !syncing
                )
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

        // 5. 消费洞察与流水明细
        if (snapshot.transactions.isNotEmpty()) {
            item {
                com.buct.course.ui.screen.EcardInsightsCard(
                    transactions = snapshot.transactions,
                    hideAmount = hideBalance
                )
            }
        }
        if (snapshot.transactions.isEmpty()) {
            item {
                SystemEmptyState(
                    title = if (slice.provenance.refreshState == RefreshState.NEVER) "尚未同步到流水记录" else "暂无消费或充值记录",
                    message = if (slice.provenance.refreshState == RefreshState.NEVER) {
                        "请点击上方「刷新余额」拉取最新交易明细。"
                    } else {
                        "当前所选时间范围内没有查到交易记录。"
                    },
                    icon = Icons.Outlined.ReceiptLong
                )
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
                    SystemSecondaryButton(
                        text = if (syncing) "正在加载更多…" else "加载更多（已显示 ${snapshot.transactions.size} / ${snapshot.totalCount}）",
                        enabled = !syncing,
                        onClick = onLoadMore,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun EcardTransactionCard(
    tx: CardTransaction,
    hideAmount: Boolean
) {
    val cardShape = RoundedCornerShape(20.dp)
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

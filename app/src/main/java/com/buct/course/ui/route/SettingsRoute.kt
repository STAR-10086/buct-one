package com.buct.course.ui.route

import com.buct.course.ui.system.GlassToaster
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.buct.course.ui.system.SystemDialog
import com.buct.course.ui.system.SystemConfirmDialog
import com.buct.course.ui.system.SystemSecondaryButton
import com.buct.course.ui.system.SystemPrimaryButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.buct.course.LoginActivity
import com.buct.course.login.PasswordLoginCallback
import com.buct.course.login.PasswordLoginGatewayFactory
import com.buct.course.manager.AppearanceSettingsManager
import com.buct.course.manager.StartupPagePreferences
import com.buct.course.manager.UserManager
import com.buct.course.network.CourseApiClient
import com.buct.course.ui.screen.SettingsScreen
import com.buct.course.update.UpdateDialog
import com.buct.course.update.rememberUpdateState
import com.buct.course.activation.ActivationManager
import com.buct.course.manager.StudentLimitManager
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.buct.course.manager.UserProfile
import com.buct.course.manager.UserProfileStore
import com.buct.course.ui.system.GlassTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.LinearProgressIndicator
import com.buct.course.ui.system.SystemDivider
import com.buct.course.ui.system.SystemStatusBadge
import com.buct.course.ui.system.SystemTone
import com.buct.course.ui.theme.NeuPrimary
import com.buct.course.ui.theme.Neutral500
import com.buct.course.ui.theme.SemanticSuccess
import com.buct.course.ui.theme.SemanticWarning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    onAccountChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val isDemoMode = remember { UserManager.getInstance().isDemoMode }
    
    var studentName by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf("") }
    var schoolName by remember { mutableStateOf("") }
    
    // UI States
    var showSchoolDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showCreditsDialog by remember { mutableStateOf(false) }
    var showStartupPageDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val startupPagePreferences = remember(context) { StartupPagePreferences.from(context) }
    var startupPage by remember(startupPagePreferences) { mutableStateOf(startupPagePreferences.read()) }
    var showQuotaDialog by remember { mutableStateOf(false) }
    var showAccountManagerDialog by remember { mutableStateOf(false) }
    var pendingPasswordDelete by remember { mutableStateOf<UserManager.AccountRecord?>(null) }
    var pendingAccountDelete by remember { mutableStateOf<UserManager.AccountRecord?>(null) }
    var showWallpaperDialog by remember { mutableStateOf(false) }
    var showFontSizeDialog by remember { mutableStateOf(false) }
    var showThemeDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val currentWallpaperName = com.buct.course.manager.AppearanceSettingsManager.currentWallpaperName

    // 在线同步开关（默认开启，可手动关闭以只使用本地数据）
    var onlineSyncEnabled by remember(context) { mutableStateOf(com.buct.course.manager.SyncSettings.enabled(context)) }
    var analyticsEnabled by remember(context) { mutableStateOf(com.buct.course.analytics.UmamiTracker.isEnabled(context)) }
    
    // Quota States
    var isSuper by remember { mutableStateOf(false) }
    var quotaInfo by remember { mutableStateOf("") }
    var quotaUsedCount by remember { mutableIntStateOf(0) }
    var quotaMaxCount by remember { mutableIntStateOf(0) }
    var quotaBoundNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var quotaAccounts by remember { mutableStateOf<List<UserManager.AccountRecord>>(emptyList()) }
    // 账号管理走全量列表（跨学校）：这里是"管理"，不该被当前学校过滤掉
    var allAccounts by remember { mutableStateOf<List<UserManager.AccountRecord>>(emptyList()) }
    var accountsWithPassword by remember { mutableStateOf<Set<String>>(emptySet()) }
    var currentAccountKey by remember { mutableStateOf("") }
    var canRefreshCookie by remember { mutableStateOf(false) }
    val session by UserManager.getInstance().sessionState.state.collectAsState()
    var cookieUpdateFeedback by remember {
        mutableStateOf<Pair<com.buct.course.manager.SessionToken, com.buct.course.ui.system.SymbolResult>?>(null)
    }
    val cookieUpdateResult = cookieUpdateFeedback?.takeIf { it.first == session.token }?.second
        ?: com.buct.course.ui.system.SymbolResult.None
    val recovery by com.buct.course.utils.SessionRenewer.state.collectAsState()
    val isRefreshingCookie = recovery.token == session.token &&
        recovery.phase == com.buct.course.utils.RecoveryPhase.Restoring
    var showFeedback by remember { mutableStateOf(false) }
    var feedbackUnread by remember { mutableIntStateOf(0) }
    var showWidgetSettings by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var permissionRefreshTick by remember { mutableIntStateOf(0) }
    var widgetCount by remember {
        mutableIntStateOf(com.buct.course.ui.screen.listWidgetInstances(context).size)
    }
    var userProfile by remember {
        mutableStateOf(UserProfileStore.load(context, UserManager.getInstance().currentAccountKey))
    }
    val scope = rememberCoroutineScope()
    val pickAvatarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val targetKey = UserManager.getInstance().currentAccountKey
        scope.launch {
            val updated = withContext(Dispatchers.IO) {
                UserProfileStore.saveAvatarFromUri(context, targetKey, uri)
            }
            if (updated != null) {
                userProfile = updated
                GlassToaster.show("头像已更新")
            } else {
                GlassToaster.show("读取图片失败，请换一张重试")
            }
        }
    }

    val notificationGranted = remember(permissionRefreshTick, showPermissionDialog) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
    val exactAlarmGranted = remember(permissionRefreshTick, showPermissionDialog) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val am = context.getSystemService(android.content.Context.ALARM_SERVICE) as? android.app.AlarmManager
            am?.canScheduleExactAlarms() == true
        } else {
            true
        }
    }
    val permissionSummary = remember(notificationGranted, exactAlarmGranted) {
        val notif = if (notificationGranted) "通知已开启" else "通知未授权"
        val alarm = if (exactAlarmGranted) "精确闹钟已允许" else "精确闹钟未允许"
        "$notif · $alarm"
    }
    val requestNotificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionRefreshTick++
        GlassToaster.show(if (granted) "通知权限已开启" else "未授予通知权限，可在系统设置中开启")
    }

    // 从小组件管理页回来时重新数一遍（用户可能在桌面增删过实例）
    LaunchedEffect(showWidgetSettings) {
        widgetCount = com.buct.course.ui.screen.listWidgetInstances(context).size
    }

    // 已移除意见反馈通道，不再向第三方服务器轮询未读数
    val relogin = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    
    val currentVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) { "1.0.0" }
    }

    val updateState = rememberUpdateState(context)
    var isCheckingUpdate by remember { mutableStateOf(false) }

    fun checkUpdates() {
        if (isCheckingUpdate) return
        isCheckingUpdate = true
        GlassToaster.show("正在检查更新…")
        updateState.checkForUpdate { finished ->
            isCheckingUpdate = false
            when {
                !finished -> GlassToaster.show("检查更新失败，请检查网络后重试")
                updateState.updateInfo() == null -> GlassToaster.show("当前已是最新版本")
            }
        }
    }


    fun refreshAccountUiState() {
        val userManager = UserManager.getInstance()
        val name = userManager.studentName
        val school = userManager.currentSchool

        if (isDemoMode) {
            studentName = name ?: "演示用户"
            deviceId = "LOCAL-DEMO"
            schoolName = school?.name ?: "正方演示大学（演示数据）"
            isSuper = true
            quotaUsedCount = 0
            quotaMaxCount = 0
            quotaBoundNames = emptyList()
            quotaAccounts = emptyList()
            allAccounts = emptyList()
            accountsWithPassword = emptySet()
            currentAccountKey = userManager.currentAccountKey
            userProfile = UserProfileStore.load(context, currentAccountKey)
            canRefreshCookie = false
            quotaInfo = "本地演示"
            return
        }

        studentName = name ?: "同学"
        deviceId = ActivationManager.getSavedDeviceId(context)
        schoolName = school?.name ?: "未选择"

        val maxStudents = ActivationManager.getMaxStudents(context)
        val usedNames = StudentLimitManager.getUsedStudentNames(context)
        val usedCount = StudentLimitManager.getUsedCount(context)
        isSuper = maxStudents <= 0
        quotaUsedCount = usedCount
        quotaMaxCount = maxStudents
        quotaBoundNames = usedNames.toList()
        quotaAccounts = userManager.accountsForCurrentSchool
        allAccounts = userManager.savedAccounts
        accountsWithPassword = allAccounts
            .filter { userManager.hasSavedPassword(it.key) }
            .map { it.key }
            .toSet()
        currentAccountKey = userManager.currentAccountKey
        userProfile = UserProfileStore.load(context, currentAccountKey)
        canRefreshCookie = userManager.loginMode == "password"
        quotaInfo = if (isSuper) {
            "无限制"
        } else {
            "$usedCount / $maxStudents"
        }
    }

    LaunchedEffect(session.token) {
        refreshAccountUiState()
    }
    
    fun performLogout() {
        UserManager.getInstance().clearLoginState()
        val intent = Intent(context, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(intent)
        // If context is not activity, clean task might need validation but usually safe
    }

    fun switchAccount(accountKey: String, onSwitched: () -> Unit) {
        if (accountKey == currentAccountKey) return
        if (UserManager.getInstance().switchToAccount(accountKey)) {
            refreshAccountUiState()
            onSwitched()
            onAccountChanged()
            GlassToaster.show("已切换账号")
        } else {
            GlassToaster.show("账号切换失败，请重新登录")
        }
    }

    /** 只删密码：账号还在，但不再自动续期，下次失效需要手动登录。 */
    fun deleteAccountPassword(record: UserManager.AccountRecord) {
        UserManager.getInstance().deletePassword(record.key)
        refreshAccountUiState()
        GlassToaster.show("已删除该账号保存的密码")
    }

    /** 彻底删号：账号记录 + 已存密码 + 运行期 Cookie + 本地课程缓存。 */
    fun deleteAccountEntirely(record: UserManager.AccountRecord) {
        val userManager = UserManager.getInstance()
        val isCurrent = record.key == userManager.currentAccountKey
        val storageKey = userManager.deleteAccount(record.key)
        if (storageKey.isNotEmpty()) {
            com.buct.course.manager.CourseCacheManager.clearAccountCache(context, storageKey)
        }
        refreshAccountUiState()
        if (isCurrent) {
            // 当前账号被删掉，会话已经没有依据了，直接回登录页
            GlassToaster.show("账号已删除，请重新登录")
            performLogout()
        } else {
            GlassToaster.show("账号已删除")
        }
    }
    
    fun refreshCookieManually() {
        if (isDemoMode || isRefreshingCookie) return
        if (UserManager.getInstance().currentSchool?.academicSystem == "southsoft") {
            relogin.launch(Intent(context, LoginActivity::class.java).apply {
                putExtra("force_relogin", true)
                putExtra(LoginActivity.EXTRA_RETURN_TO_CALLER, true)
            })
            return
        }
        cookieUpdateFeedback = null
        val expected = UserManager.getInstance().sessionState.token
        com.buct.course.utils.SessionRepair.repair(context) { outcome ->
            if (UserManager.getInstance().sessionState.isCurrent(expected)) {
                cookieUpdateFeedback = expected to
                    if (outcome.ok) com.buct.course.ui.system.SymbolResult.Success
                    else com.buct.course.ui.system.SymbolResult.Failure
            }
            if (outcome.ok) {
                refreshAccountUiState()
                GlassToaster.show("登录状态已更新")
            } else {
                GlassToaster.show(outcome.message ?: "更新失败，请稍后重试")
            }
        }
    }
    
    SettingsScreen(
        studentName = studentName,
        studentId = deviceId,
        schoolName = schoolName,
        currentVersion = currentVersion,
        onSchoolSelect = {
            if (isDemoMode) GlassToaster.show("演示学校固定为本地数据源") else showSchoolDialog = true
        },
        onCookieConfig = {
            relogin.launch(Intent(context, LoginActivity::class.java).apply {
                putExtra("force_relogin", true)
                putExtra(LoginActivity.EXTRA_RETURN_TO_CALLER, true)
            })
        },
        onAccountManage = {
            if (isDemoMode) GlassToaster.show("本地演示模式不读取真实账号") else showAccountManagerDialog = true
        },
        savedAccountCount = allAccounts.size,
        onClearCache = { showClearCacheDialog = true },
        onCredits = { showCreditsDialog = true },
        onCheckUpdate = { checkUpdates() },
        isCheckingUpdate = isCheckingUpdate,
        startupPageName = startupPage.label,
        onStartupPageSelect = { showStartupPageDialog = true },
        onLogout = { showLogoutDialog = true },
        onQuotaClick = { showQuotaDialog = true },
        onRefreshCookieClick = { refreshCookieManually() },
        onWallpaperSelect = { showWallpaperDialog = true },
        wallpaperName = currentWallpaperName,
        onFontSizeSelect = { showFontSizeDialog = true },
        onBottomNavSelect = { com.buct.course.ui.screen.BottomNavSettingsDialogState.visible = true },
        bottomNavSummary = run {
            val navRevision = com.buct.course.manager.BottomNavPreferences.revision
            val visible = remember(navRevision) {
                com.buct.course.manager.BottomNavPreferences.visiblePages()
            }
            "当前 ${visible.size} 个标签 · 可排序与隐藏"
        },
        fontSizeName = com.buct.course.manager.FontScaleManager.presets
            .firstOrNull { kotlin.math.abs(it.scale - com.buct.course.manager.FontScaleManager.scale) < 0.005f }
            ?.let { "${it.label} · ${it.percent}%" }
            ?: "${com.buct.course.manager.FontScaleManager.percentOf()}%",
        themeName = AppearanceSettingsManager.themeMode.label,
        onThemeSelect = { showThemeDialog = true },
        glassEffectEnabled = AppearanceSettingsManager.glassEffectEnabled,
        onGlassEffectChange = { AppearanceSettingsManager.updateGlassEffect(it) },
        predictiveBackEnabled = AppearanceSettingsManager.predictiveBackEnabled,
        onPredictiveBackChange = { AppearanceSettingsManager.updatePredictiveBack(it) },
        isSuper = isSuper,
        quotaInfo = quotaInfo,
        canRefreshCookie = canRefreshCookie,
        isRefreshingCookie = isRefreshingCookie,
        analyticsEnabled = analyticsEnabled,
        onAnalyticsChange = {
            analyticsEnabled = it
            com.buct.course.analytics.UmamiTracker.setEnabled(context, it)
            com.buct.course.ui.system.GlassToaster.show(if (it) "匿名使用统计已开启" else "匿名使用统计已关闭")
        },
        onlineSyncEnabled = onlineSyncEnabled,
        onOnlineSyncChange = {
            onlineSyncEnabled = it
            com.buct.course.manager.SyncSettings.setEnabled(context, it)
            if (it) {
                com.buct.course.ui.system.GlassToaster.show("在线同步已开启，进入课表/成绩页将自动下载最新数据")
            } else {
                com.buct.course.ui.system.GlassToaster.show("在线同步已关闭，将使用本地数据，避免教务被顶号")
            }
        },
        feedbackUnread = feedbackUnread,
        onFeedbackClick = {
            feedbackUnread = 0
            showFeedback = true
        },
        widgetCount = widgetCount,
        onWidgetManage = { showWidgetSettings = true },
        permissionSummary = permissionSummary,
        onPermissionClick = { showPermissionDialog = true },
        customNickname = userProfile.nickname,
        signature = userProfile.signature,
        avatarPath = userProfile.avatarPath,
        avatarUpdatedAt = userProfile.avatarUpdatedAt,
        onEditProfileClick = { showProfileDialog = true }
    )

    if (showProfileDialog) {
        ProfileEditDialog(
            initialNickname = userProfile.nickname,
            fallbackName = studentName,
            initialSignature = userProfile.signature,
            avatarPath = userProfile.avatarPath,
            avatarUpdatedAt = userProfile.avatarUpdatedAt,
            onPickAvatar = {
                pickAvatarLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onResetAvatar = {
                userProfile = UserProfileStore.clearAvatar(context, currentAccountKey)
                GlassToaster.show("已恢复默认文字头像")
            },
            onSave = { nick, sig ->
                userProfile = UserProfileStore.saveText(context, currentAccountKey, nick, sig)
                showProfileDialog = false
                GlassToaster.show("个人资料已保存")
            },
            onDismiss = { showProfileDialog = false }
        )
    }

    if (showWidgetSettings) {
        com.buct.course.ui.system.GlassSubpage(onDismiss = { showWidgetSettings = false }) { close ->
            com.buct.course.ui.screen.WidgetSettingsScreen(onBack = close)
        }
    }

    if (showFeedback) {
        com.buct.course.ui.system.GlassSubpage(onDismiss = { showFeedback = false }) { close ->
            com.buct.course.ui.screen.FeedbackScreen(onBack = close)
        }
    }
    val availableUpdate = updateState.updateInfo()
    if (updateState.showDialog() && availableUpdate != null) {
        UpdateDialog(
            updateInfo = availableUpdate,
            currentVersion = currentVersion,
            onDismiss = { updateState.dismiss() },
            onUpdate = { url -> updateState.startDownload(url) },
            downloadProgress = updateState.downloadProgress(),
            isDownloading = updateState.isDownloading()
        )
    }

    if (showThemeDialog) {
        com.buct.course.ui.screen.AppThemeSettingsDialog { showThemeDialog = false }
    }

    if (showStartupPageDialog) {
        com.buct.course.ui.screen.StartupPageSettingsDialog(
            page = startupPage,
            onPageChange = {
                startupPagePreferences.write(it)
                startupPage = it
            },
            onDismiss = { showStartupPageDialog = false }
        )
    }
    if (showWallpaperDialog) {
        com.buct.course.ui.screen.WallpaperSettingsDialog(
            onDismiss = { showWallpaperDialog = false }
        )
    }
    if (showFontSizeDialog) {
        com.buct.course.ui.screen.FontSizeSettingsDialog(
            onDismiss = { showFontSizeDialog = false }
        )
    }

    if (showPermissionDialog) {
        SystemDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = {
                Text(
                    text = "通知与系统权限",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                SystemPrimaryButton(
                    text = "完成",
                    onClick = { showPermissionDialog = false },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "通知权限",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (notificationGranted) "已授予 · 可发送课前/考试/DDL提醒" else "未开启 · 将无法弹出课前和DDL提醒",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && !notificationGranted) {
                                requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                                runCatching { context.startActivity(intent) }
                                    .onFailure {
                                        context.startActivity(
                                            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = android.net.Uri.parse("package:${context.packageName}")
                                            }
                                        )
                                    }
                            }
                        }
                    ) {
                        Text(if (notificationGranted) "系统设置" else "去授权")
                    }
                }

                SystemDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "精确闹钟权限",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (exactAlarmGranted) "已允许 · 准时触发提醒与小组件刷新" else "未允许 · 课前提醒可能延迟",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                                runCatching { context.startActivity(intent) }
                                    .onFailure {
                                        context.startActivity(
                                            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = android.net.Uri.parse("package:${context.packageName}")
                                            }
                                        )
                                    }
                            } else {
                                GlassToaster.show("当前系统版本无需单独授予精确闹钟权限")
                            }
                        }
                    ) {
                        Text(if (exactAlarmGranted) "管理" else "去开启")
                    }
                }

                SystemDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "应用完整权限设置",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "若跳转企业微信/电池优化受限，可在此调整权限与自启动",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                            )
                        }
                    ) {
                        Text("打开")
                    }
                }
            }
        }
    }

    if (showLogoutDialog) {
        SimpleConfirmDialog(
            title = "退出登录",
            text = "确定要退出登录吗？",
            onConfirm = { 
                performLogout() 
                showLogoutDialog = false
            },
            onDismiss = { showLogoutDialog = false }
        )
    }
    
    if (showClearCacheDialog) {
        SimpleConfirmDialog(
            title = "清除缓存",
            text = "确定要清除所有本地缓存数据吗？",
            onConfirm = { 
                GlassToaster.show("缓存已清除")
                showClearCacheDialog = false
            },
            onDismiss = { showClearCacheDialog = false }
        )
    }
    
    if (showCreditsDialog) {
        SystemDialog(
            onDismissRequest = { showCreditsDialog = false },
            title = {
                Text(
                    text = "致谢与关于",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                SystemPrimaryButton(
                    text = "我知道了",
                    onClick = { showCreditsDialog = false },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "BUCT One · 北化一站",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "北京化工大学校园工作台（Android / Kotlin + Compose），由 STAR-10086 维护。\n集成教务课表与桌面小组件、成绩分析与培养方案、北化在线（THEOL/MEOL）原生作业测验同步、校园一卡通、实践教学与第二课堂五育成绩，按 GPL-3.0 开源发布，纯本地存储、零第三方上报。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )

                com.buct.course.ui.system.SystemDivider()

                Text(
                    text = "开源致谢",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "• 本项目仓库：https://github.com/STAR-10086/buct-one\n" +
                            "• 维护者：STAR-10086\n" +
                            "• 许可证：GPL-3.0\n\n" +
                            "感谢以下开源项目提供的基线与参考实现：\n" +
                            "• znjhahaha/zhengfang-apk（液态玻璃与教务课表基线，GPL-3.0）\n" +
                            "• lioanchijian/beihua-kebiao（北化统一认证与教务适配，GPL-3.0）\n" +
                            "• Ling0727-ai/go-buct-course & BUCT-course-remind（北化在线协议与提醒模型，MIT）\n" +
                            "• bakahuiii/THEIA（校园工作台与数据溯源设计，MIT）\n" +
                            "• Kyant0/AndroidLiquidGlass（Backdrop 液态玻璃折射，Apache-2.0）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )

                com.buct.course.ui.system.SystemDivider()

                Text(
                    text = "本软件为开源免费项目（GPL-3.0），仅供个人学习与技术交流使用。所有校园数据与学号密码仅保存在本机；仅在开启「匿名使用统计」时向自托管 Umami 发送不含个人身份的匿名版本与功能热度统计，可在设置中随时关闭。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    lineHeight = 16.sp
                )
            }
        }
    }
    
    if (showQuotaDialog) {
        QuotaStatusDialog(
            deviceId = deviceId,
            isSuper = isSuper,
            usedCount = quotaUsedCount,
            maxCount = quotaMaxCount,
            boundNames = quotaBoundNames,
            accounts = quotaAccounts,
            currentAccountKey = currentAccountKey,
            onSwitchAccount = { accountKey ->
                switchAccount(accountKey) { showQuotaDialog = false }
            },
            onDismiss = { showQuotaDialog = false }
        )
    }

    if (showAccountManagerDialog) {
        AccountManagerDialog(
            accounts = allAccounts,
            currentAccountKey = currentAccountKey,
            accountsWithPassword = accountsWithPassword,
            onSwitchAccount = { accountKey ->
                switchAccount(accountKey) { showAccountManagerDialog = false }
            },
            onDeletePassword = { pendingPasswordDelete = it },
            onDeleteAccount = { pendingAccountDelete = it },
            onDismiss = { showAccountManagerDialog = false }
        )
    }

    pendingPasswordDelete?.let { record ->
        SimpleConfirmDialog(
            title = "删除已保存的密码",
            text = "删除后「${record.displayName}」将无法在登录状态失效时自动续期，" +
                "需要你手动重新登录。账号本身与本地数据不会被删除。",
            confirmText = "删除密码",
            onConfirm = {
                deleteAccountPassword(record)
                pendingPasswordDelete = null
            },
            onDismiss = { pendingPasswordDelete = null }
        )
    }

    pendingAccountDelete?.let { record ->
        SimpleConfirmDialog(
            title = "删除账号",
            text = "将删除「${record.displayName}」的账号记录、已保存的密码、登录状态与本地课程缓存，" +
                "此操作不可恢复。设备绑定名额不会因此释放。",
            confirmText = "删除账号",
            onConfirm = {
                deleteAccountEntirely(record)
                pendingAccountDelete = null
                showAccountManagerDialog = false
            },
            onDismiss = { pendingAccountDelete = null }
        )
    }


    
    if (showSchoolDialog) {
        var animateTrigger by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { animateTrigger = true }

        fun dismiss() {
            animateTrigger = false
        }

        if (!animateTrigger) {
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(300)
                showSchoolDialog = false
            }
        }

        SystemDialog(
            onDismissRequest = { dismiss() },
            title = {
                Text(
                    text = "选择教务系统",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            dismissButton = {
                SystemSecondaryButton(
                    text = "取消",
                    onClick = { dismiss() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            val schools = remember { UserManager.getInstance().supportedSchools }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp) // 限制最大高度，防止学校列表过多时把 Dialog 挤出屏幕外
            ) {
                items(schools) { school ->
                    Text(
                        text = school.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                UserManager.getInstance().clearLoginState()
                                UserManager.getInstance().currentSchool = school
                                GlassToaster.show("已切换到：${school.name}")
                                performLogout()
                                dismiss()
                            }
                            .padding(vertical = 14.dp, horizontal = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun QuotaStatusDialog(
    deviceId: String,
    isSuper: Boolean,
    usedCount: Int,
    maxCount: Int,
    boundNames: List<String>,
    accounts: List<UserManager.AccountRecord>,
    currentAccountKey: String,
    onSwitchAccount: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val safeMax = maxCount.coerceAtLeast(0)
    val safeUsed = usedCount.coerceAtLeast(0)
    val usageRatio = if (!isSuper && safeMax > 0) {
        (safeUsed.toFloat() / safeMax.toFloat()).coerceIn(0f, 1f)
    } else {
        1f
    }
    val statusText = if (isSuper) "超级用户" else "普通用户"
    val quotaText = if (isSuper) "无限制" else "$safeUsed / $safeMax"

    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "当前账号配额",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "设备绑定与名额使用情况",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "知道了",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    QuotaInfoRow(label = "身份", value = statusText)
                    QuotaInfoRow(label = "配额", value = quotaText)
                    if (!isSuper) {
                        LinearProgressIndicator(
                            progress = { usageRatio },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(999.dp)),
                            color = if (usageRatio >= 1f) SemanticWarning else NeuPrimary,
                            trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            SystemDivider(alpha = 0.5f)

            Text("设备 ID：${deviceId.ifBlank { "未获取" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "已绑定账号",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (boundNames.isEmpty()) {
                    Text(
                        text = "当前设备尚未绑定账号。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        boundNames.forEachIndexed { index, name ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        modifier = Modifier.size(24.dp),
                                        color = NeuPrimary.copy(alpha = 0.12f),
                                        shape = CircleShape
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "${index + 1}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = NeuPrimary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (accounts.isNotEmpty()) {
                SystemDivider(alpha = 0.5f)

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "切换账号",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    accounts.forEach { account ->
                        val isCurrent = account.key == currentAccountKey
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isCurrent) { onSwitchAccount(account.key) },
                            color = if (isCurrent) NeuPrimary.copy(alpha = 0.12f)
                                else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = account.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "${account.accountIdText} · ${if (account.loginMode == "password") "密码登录" else "Cookie 登录"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                SystemStatusBadge(
                                    text = if (isCurrent) "当前" else "切换",
                                    tone = if (isCurrent) SystemTone.Info else SystemTone.Neutral
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = "说明：同一设备可绑定不同学校的学生账号，不限制数量；切换账号会同步切换 Cookie 与本地账号上下文。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun AccountManagerDialog(
    accounts: List<UserManager.AccountRecord>,
    currentAccountKey: String,
    accountsWithPassword: Set<String>,
    onSwitchAccount: (String) -> Unit,
    onDeletePassword: (UserManager.AccountRecord) -> Unit,
    onDeleteAccount: (UserManager.AccountRecord) -> Unit,
    onDismiss: () -> Unit
) {
    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "账号管理",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "切换账号、管理已保存的密码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "完成",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (accounts.isEmpty()) {
                Text(
                    text = "本机还没有保存任何账号。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                accounts.forEach { account ->
                    val isCurrent = account.key == currentAccountKey
                    val hasPassword = accountsWithPassword.contains(account.key)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = if (isCurrent) NeuPrimary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = account.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${account.accountIdText} · " +
                                            if (account.loginMode == "password") "密码登录" else "Cookie 登录",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = account.schoolName.ifBlank { "未记录学校" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                SystemStatusBadge(
                                    text = if (hasPassword) "已存密码" else "未存密码",
                                    tone = if (hasPassword) SystemTone.Success else SystemTone.Neutral
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isCurrent) {
                                    SystemStatusBadge(text = "当前账号", tone = SystemTone.Info)
                                } else {
                                    AccountActionButton(
                                        text = "切换",
                                        onClick = { onSwitchAccount(account.key) }
                                    )
                                }
                                Spacer(modifier = Modifier.weight(1f))
                                if (hasPassword) {
                                    AccountActionButton(
                                        text = "删除密码",
                                        onClick = { onDeletePassword(account) }
                                    )
                                }
                                AccountActionButton(
                                    text = "删除账号",
                                    tint = com.buct.course.ui.theme.SemanticDanger,
                                    onClick = { onDeleteAccount(account) }
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = "密码经系统密钥库加密后仅保存在本机，用于登录状态失效时自动续期；" +
                    "退出登录不会删除它。删除账号不会释放设备绑定名额。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}

/** 账号卡里的小动作按钮：轻量胶囊，避免三个实心按钮在一行里互相抢注意力。 */
@Composable
private fun AccountActionButton(
    text: String,
    tint: Color = NeuPrimary,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = tint.copy(alpha = 0.12f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = tint
        )
    }
}

@Composable
private fun QuotaInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun SimpleConfirmDialog(
    title: String, 
    text: String, 
    onConfirm: () -> Unit, 
    onDismiss: () -> Unit,
    confirmText: String = "确定",
    showCancel: Boolean = true
) {
    SystemConfirmDialog(
        title = title,
        text = text,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmText = confirmText,
        showCancel = showCancel
    )
}

@Composable
private fun ProfileEditDialog(
    initialNickname: String,
    fallbackName: String,
    initialSignature: String,
    avatarPath: String,
    avatarUpdatedAt: Long,
    onPickAvatar: () -> Unit,
    onResetAvatar: () -> Unit,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var nickname by remember { mutableStateOf(initialNickname) }
    var signature by remember { mutableStateOf(initialSignature) }
    val hasAvatar = remember(avatarPath, avatarUpdatedAt) {
        avatarPath.isNotBlank() && File(avatarPath).isFile
    }
    val previewName = nickname.trim().ifBlank { fallbackName }.ifBlank { "同" }

    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "个人名片设置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SystemSecondaryButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                SystemPrimaryButton(
                    text = "保存",
                    onClick = { onSave(nickname, signature) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(modifier = Modifier.size(64.dp)) {
                    if (hasAvatar) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(File(avatarPath))
                                .memoryCacheKey("${avatarPath}_$avatarUpdatedAt")
                                .crossfade(true)
                                .build(),
                            contentDescription = "当前头像",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = previewName.take(1),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SystemPrimaryButton(
                        text = "从相册选头像",
                        onClick = onPickAvatar,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (hasAvatar) {
                        TextButton(
                            onClick = onResetAvatar,
                            modifier = Modifier.align(Alignment.Start)
                        ) {
                            Text("恢复默认文字头像")
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "展示昵称（留空则显示教务姓名：$fallbackName）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                GlassTextField(
                    value = nickname,
                    onValueChange = { if (it.length <= 16) nickname = it },
                    placeholder = fallbackName.ifBlank { "北化同学" },
                    singleLine = true
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "个性签名（最多 60 字）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                GlassTextField(
                    value = signature,
                    onValueChange = { if (it.length <= 60) signature = it },
                    placeholder = "写一句校园备忘或个性签名…",
                    singleLine = false,
                    minHeight = 72.dp
                )
            }
        }
    }
}



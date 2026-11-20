package com.buct.course.ui.screen

import com.buct.course.ui.theme.moduleEntrance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ViewWeek
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File
import com.buct.course.ui.system.InsetGroupedRow
import com.buct.course.ui.system.InsetGroupedSection
import com.buct.course.ui.system.LiquidSwitch
import com.buct.course.ui.system.PagePadding
import com.buct.course.ui.system.SectionSpacing
import com.buct.course.ui.system.SystemCard
import com.buct.course.ui.system.SystemStatusBadge
import com.buct.course.ui.system.SystemTone
import com.buct.course.ui.system.SystemTopBar
import com.buct.course.ui.theme.SemanticDanger

@Composable
fun SettingsScreen(
    studentName: String,
    schoolName: String,
    currentVersion: String = "1.0.0",
    onSchoolSelect: () -> Unit,
    onCookieConfig: () -> Unit,
    onAccountManage: () -> Unit = {},
    savedAccountCount: Int = 0,
    onClearCache: () -> Unit,
    onCredits: () -> Unit,
    onCheckUpdate: () -> Unit = {},
    isCheckingUpdate: Boolean = false,
    startupPageName: String = "课程",
    onStartupPageSelect: () -> Unit = {},
    onLogout: () -> Unit,
    onRefreshCookieClick: () -> Unit = {},
    onWallpaperSelect: () -> Unit = {},
    wallpaperName: String = "",
    onFontSizeSelect: () -> Unit = {},
    fontSizeName: String = "标准",
    onBottomNavSelect: () -> Unit = {},
    bottomNavSummary: String = "",
    themeName: String = "跟随系统",
    onThemeSelect: () -> Unit = {},
    glassEffectEnabled: Boolean = true,
    onGlassEffectChange: (Boolean) -> Unit = {},
    predictiveBackEnabled: Boolean = true,
    onPredictiveBackChange: (Boolean) -> Unit = {},
    canRefreshCookie: Boolean = false,
    isRefreshingCookie: Boolean = false,
    onlineSyncEnabled: Boolean = true,
    onOnlineSyncChange: (Boolean) -> Unit = {},
    analyticsEnabled: Boolean = true,
    onAnalyticsChange: (Boolean) -> Unit = {},
    widgetCount: Int = 0,
    onWidgetManage: () -> Unit = {},
    permissionSummary: String = "通知与定时提醒权限",
    onPermissionClick: () -> Unit = {},
    customNickname: String = "",
    signature: String = "",
    avatarPath: String = "",
    avatarUpdatedAt: Long = 0L,
    onEditProfileClick: () -> Unit = {}
) {
    val scrollState = rememberScrollState()
    // 折叠进度随滚动偏移连续变化（约 96px 行程），全程跟手
    val headerCollapse by remember {
        derivedStateOf { (scrollState.value / 96f).coerceIn(0f, 1f) }
    }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Box(Modifier.moduleEntrance(0)) {
            SystemTopBar(
                title = "我的",
                subtitle = "账号、外观与权限偏好",
                collapseFraction = headerCollapse
            )
            }
        }
    ) { padding ->
        // 内容延伸到玻璃顶栏下方，滚动时从顶栏底下穿过（padding 施加在滚动内容内部）
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(
                    start = PagePadding,
                    end = PagePadding,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = com.buct.course.ui.system.LocalAppOverlayBottomInset.current + 24.dp
                ),
            verticalArrangement = Arrangement.spacedBy(SectionSpacing)
        ) {
            SettingsHeader(
                displayName = customNickname.ifBlank { studentName.ifBlank { "同学" } },
                school = schoolName,
                signature = signature,
                avatarPath = avatarPath,
                avatarUpdatedAt = avatarUpdatedAt,
                onEditProfileClick = onEditProfileClick
            )

            InsetGroupedSection(Modifier.moduleEntrance(1), header = "数据与同步") {
                InsetGroupedRow(
                    icon = Icons.Outlined.SystemUpdate,
                    iconTint = if (onlineSyncEnabled) Color(0xFF34C759) else Color(0xFF8E8E93),
                    title = "在线同步",
                    subtitle = if (onlineSyncEnabled) {
                        "已开启：进入课表/成绩页自动拉取最新数据，官方课程将覆盖本地旧数据（自定义课程保留）"
                    } else {
                        "已关闭：仅使用本地数据，避免教务账号被顶号；抢课等在线功能需先开启"
                    },
                    showDivider = canRefreshCookie,
                    trailing = {
                        LiquidSwitch(
                            checked = onlineSyncEnabled,
                            onCheckedChange = onOnlineSyncChange
                        )
                    }
                )
                if (canRefreshCookie) {
                    InsetGroupedRow(
                        icon = Icons.Outlined.Refresh,
                        iconTint = Color(0xFF0A84FF),
                        title = "更新 Cookie",
                        subtitle = if (isRefreshingCookie) "正在刷新教务与统一身份会话…" else "手动续期当前登录态与教务 Cookie",
                        showDivider = false,
                        onClick = {
                            if (!isRefreshingCookie) onRefreshCookieClick()
                        },
                        trailing = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
            }

            InsetGroupedSection(Modifier.moduleEntrance(2), header = "账号与教务") {
                SettingsRow(
                    icon = Icons.Outlined.School,
                    iconTint = Color(0xFF0A84FF),
                    title = "教务系统（本科 / 研究生）",
                    subtitle = schoolName.ifBlank { "未选择系统" },
                    onClick = onSchoolSelect
                )
                SettingsRow(
                    icon = Icons.Outlined.ManageAccounts,
                    iconTint = Color(0xFF32ADE6),
                    title = "账号管理",
                    subtitle = if (savedAccountCount > 0) {
                        "已保存 $savedAccountCount 个账号 · 可切换或删除"
                    } else {
                        "切换账号、删除已存密码与账号"
                    },
                    onClick = onAccountManage
                )
                SettingsRow(
                    icon = Icons.AutoMirrored.Outlined.Login,
                    iconTint = Color(0xFFFF9F0A),
                    title = "重新登录",
                    subtitle = "退出当前会话并返回登录页（保留已存密码）",
                    onClick = onCookieConfig,
                    showDivider = false
                )
            }

            InsetGroupedSection(Modifier.moduleEntrance(3), header = "外观") {
                SettingsRow(
                    icon = Icons.Outlined.Palette,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = "主题",
                    subtitle = themeName,
                    onClick = onThemeSelect
                )
                SettingsRow(
                    icon = Icons.Outlined.Palette,
                    iconTint = Color(0xFFBF5AF2),
                    title = "背景",
                    subtitle = wallpaperName.ifBlank { "选择背景色或图片" },
                    onClick = onWallpaperSelect
                )
                SettingsRow(
                    icon = Icons.Outlined.FormatSize,
                    iconTint = Color(0xFF0A84FF),
                    title = "字体大小",
                    subtitle = fontSizeName,
                    onClick = onFontSizeSelect
                )
                SettingsRow(
                    icon = Icons.Outlined.ViewWeek,
                    iconTint = Color(0xFF30D158),
                    title = "底部导航",
                    subtitle = bottomNavSummary,
                    onClick = onBottomNavSelect
                )
                SettingsRow(
                    icon = Icons.Outlined.Dashboard,
                    iconTint = Color(0xFFFF9F0A),
                    title = "桌面小组件",
                    subtitle = if (widgetCount == 0) {
                        "还没添加 · 点这里看怎么加"
                    } else {
                        "已添加 $widgetCount 个 · 可单独设置名称与账号"
                    },
                    onClick = onWidgetManage
                )
                InsetGroupedRow(
                    icon = Icons.Outlined.AutoAwesome,
                    iconTint = Color(0xFF5AC8FA),
                    title = "液态玻璃",
                    subtitle = if (glassEffectEnabled) {
                        "折射、色散与跟手形变"
                    } else {
                        "已关闭，改用不透明材质，更省电也更清晰"
                    },
                    showDivider = true,
                    trailing = {
                        LiquidSwitch(
                            checked = glassEffectEnabled,
                            onCheckedChange = onGlassEffectChange
                        )
                    }
                )
                InsetGroupedRow(
                    icon = Icons.AutoMirrored.Outlined.Undo,
                    iconTint = Color(0xFF00C7BE),
                    title = "预测性返回手势",
                    subtitle = if (predictiveBackEnabled) {
                        "边缘侧滑返回时跟手预览上一级页面"
                    } else {
                        "已关闭，恢复传统即时返回"
                    },
                    showDivider = false,
                    trailing = {
                        LiquidSwitch(
                            checked = predictiveBackEnabled,
                            onCheckedChange = onPredictiveBackChange
                        )
                    }
                )
            }

            InsetGroupedSection(Modifier.moduleEntrance(3), header = "应用与支持") {
                SettingsRow(
                    icon = Icons.Outlined.Home,
                    iconTint = Color(0xFF5E5CE6),
                    title = "启动首屏",
                    subtitle = "$startupPageName · 下次启动时显示",
                    onClick = onStartupPageSelect
                )
                InsetGroupedRow(
                    icon = Icons.Outlined.SystemUpdate,
                    iconTint = Color(0xFF0A84FF),
                    title = "检查更新",
                    subtitle = if (isCheckingUpdate) {
                        "正在连接更新服务器…"
                    } else {
                        "当前 v$currentVersion · 点击手动检查最新版本"
                    },
                    enabled = !isCheckingUpdate,
                    onClick = {
                        if (!isCheckingUpdate) onCheckUpdate()
                    },
                    trailing = {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                )
                SettingsRow(
                    icon = Icons.Outlined.FavoriteBorder,
                    iconTint = Color(0xFFFF375F),
                    title = "致谢与关于",
                    subtitle = "v$currentVersion · 开源项目致谢与作者声明",
                    onClick = onCredits,
                    showDivider = false
                )
            }

            InsetGroupedSection(Modifier.moduleEntrance(3), header = "数据与安全") {
                SettingsRow(
                    icon = Icons.Outlined.NotificationsActive,
                    iconTint = Color(0xFF34C759),
                    title = "通知与系统权限",
                    subtitle = permissionSummary,
                    onClick = onPermissionClick
                )
                InsetGroupedRow(
                    icon = Icons.Outlined.Dashboard,
                    iconTint = Color(0xFF5E5CE6),
                    title = "匿名使用统计",
                    subtitle = if (analyticsEnabled) {
                        "仅上报匿名随机标识、版本号与功能热度，不含学号等个人信息"
                    } else {
                        "已关闭，不上报任何统计数据"
                    },
                    showDivider = true,
                    trailing = {
                        LiquidSwitch(
                            checked = analyticsEnabled,
                            onCheckedChange = onAnalyticsChange
                        )
                    }
                )
                SettingsRow(
                    icon = Icons.Outlined.Delete,
                    iconTint = Color(0xFFFF9F0A),
                    title = "清除缓存",
                    subtitle = "释放本地存储空间",
                    onClick = onClearCache,
                    showDivider = false
                )
            }

            InsetGroupedSection(Modifier.moduleEntrance(3)) {
                InsetGroupedRow(
                    title = "退出登录",
                    icon = Icons.AutoMirrored.Filled.ExitToApp,
                    iconTint = SemanticDanger,
                    titleColor = SemanticDanger,
                    showDivider = false,
                    onClick = onLogout
                )
            }

            Text(
                text = "BUCT One · $currentVersion",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun SettingsHeader(
    displayName: String,
    school: String,
    signature: String,
    avatarPath: String,
    avatarUpdatedAt: Long,
    onEditProfileClick: () -> Unit
) {
    val context = LocalContext.current
    // 裸玻璃容器（不走 Surface，避免 elevation 阴影在半透色下泛白）
    val heroShape = RoundedCornerShape(24.dp)
    val hasAvatarFile = remember(avatarPath, avatarUpdatedAt) {
        avatarPath.isNotBlank() && File(avatarPath).isFile
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .moduleEntrance(1)
            .clip(heroShape)
            .background(com.buct.course.ui.system.glassSurfaceColor())
            .border(0.5.dp, com.buct.course.ui.system.glassBorderColor(), heroShape)
            .clickable(onClick = onEditProfileClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(modifier = Modifier.size(60.dp)) {
                if (hasAvatarFile) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(File(avatarPath))
                            .memoryCacheKey("${avatarPath}_$avatarUpdatedAt")
                            .crossfade(true)
                            .build(),
                        contentDescription = "用户头像",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), CircleShape)
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
                            text = displayName.take(1).ifBlank { "同" },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PhotoCamera,
                        contentDescription = "更换头像",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = displayName.ifBlank { "同学" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = school.ifBlank { "未选择系统" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "编辑资料",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "编辑资料",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Text(
            text = signature.ifBlank { "点击编辑个性签名与头像…" },
            style = MaterialTheme.typography.bodySmall,
            color = if (signature.isBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 设置行：InsetGroupedRow + 彩色图标 chip + chevron。 */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String? = null,
    showDivider: Boolean = true,
    onClick: () -> Unit
) {
    InsetGroupedRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconTint = iconTint,
        showDivider = showDivider,
        onClick = onClick,
        trailing = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    )
}

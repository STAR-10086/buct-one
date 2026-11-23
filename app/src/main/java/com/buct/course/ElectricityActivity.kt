package com.buct.course

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buct.course.manager.AppThemeCoordinator
import com.buct.course.manager.UserManager
import com.buct.course.ui.screen.GlassFilterChip
import com.buct.course.ui.system.GlassLoadingIndicator
import com.buct.course.ui.system.GlassPageScaffold
import com.buct.course.ui.system.GlassSubpage
import com.buct.course.ui.system.GlassTextField
import com.buct.course.ui.system.GlassToaster
import com.buct.course.ui.system.GlassWindowHost
import com.buct.course.ui.system.LiquidSwitch
import com.buct.course.ui.system.SystemDestructiveButton
import com.buct.course.ui.system.SystemDialog
import com.buct.course.ui.system.SystemEmptyState
import com.buct.course.ui.system.SystemIconButton
import com.buct.course.ui.system.SystemPrimaryButton
import com.buct.course.ui.system.SystemSecondaryButton
import com.buct.course.ui.system.glassBorderColor
import com.buct.course.ui.system.glassSurfaceColor
import com.buct.course.ui.theme.CourseSelectorTheme
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.ElectricityCatalogBuilding
import com.buct.one.data.model.ElectricityCatalogCampus
import com.buct.one.data.model.ElectricityCatalogFloor
import com.buct.one.data.model.ElectricityCatalogRoom
import com.buct.one.data.model.ElectricityReading
import com.buct.one.data.model.ElectricityRoomCatalog
import com.buct.one.data.model.ElectricityRoomTarget
import com.buct.one.data.model.ElectricitySnapshot
import com.buct.one.data.model.electricityTargetBaseRoomName
import com.buct.one.data.model.electricityTargetShortLabel
import com.buct.one.data.model.formatElectricityPrimaryValue
import com.buct.one.data.model.isLowElectricityBalance
import com.buct.one.data.sync.ElectricitySyncService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 宿舍电费独立查询 Activity（对应工作台「宿舍电费」一级模块，支持系统预测性返回）。
 * 在入口授权及查询/绑定页均明确提示：查询电费将与开发者共享学号以避免滥用。
 */
class ElectricityActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UserManager.getInstance().init(this)

        setContent {
            CourseSelectorTheme {
                GlassWindowHost {
                    val context = LocalContext.current
                    val scope = rememberCoroutineScope()
                    val service = remember(context) { ElectricitySyncService.getInstance(context) }

                    val electricitySlice by service.electricityState.collectAsState()
                    val syncing by service.syncing.collectAsState()
                    val consentAccepted by service.consentAccepted.collectAsState()
                    val lowBalanceThreshold by service.lowBalanceThreshold.collectAsState()

                    val studentId = service.currentStudentId()
                    val isDemo = service.isDemoAccount()

                    var showConfigSubpage by remember { mutableStateOf(false) }

                    val boundTargets = electricitySlice.data?.targets.orEmpty()
                    val subtitle = when {
                        !consentAccepted -> "首次使用需确认学号防滥用说明"
                        boundTargets.isEmpty() -> "尚未绑定宿舍房间"
                        else -> electricityTargetShortLabel(boundTargets.first())
                    }

                    GlassPageScaffold(
                        title = "宿舍电费",
                        subtitle = subtitle,
                        onBack = { finish() },
                        actions = {
                            if (consentAccepted) {
                                if (syncing) {
                                    GlassLoadingIndicator(size = 22.dp)
                                } else {
                                    SystemIconButton(
                                        icon = Icons.Default.Refresh,
                                        contentDescription = "刷新电费",
                                        tint = MaterialTheme.colorScheme.primary,
                                        onClick = {
                                            if (boundTargets.isEmpty()) {
                                                showConfigSubpage = true
                                                return@SystemIconButton
                                            }
                                            scope.launch {
                                                val res = service.refresh(userInitiated = true)
                                                res.onSuccess {
                                                    GlassToaster.show("电费数据已更新")
                                                }.onFailure { err ->
                                                    GlassToaster.show(err.message ?: "电费查询失败")
                                                }
                                            }
                                        }
                                    )
                                }
                                SystemIconButton(
                                    icon = Icons.Outlined.Tune,
                                    contentDescription = "绑定与设置",
                                    enabled = !syncing,
                                    onClick = { showConfigSubpage = true }
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
                                targetState = consentAccepted,
                                transitionSpec = {
                                    fadeIn(tween(220)) togetherWith fadeOut(tween(160))
                                },
                                label = "electricityConsentPane"
                            ) { consented ->
                                if (!consented) {
                                    ElectricityConsentGate(
                                        studentId = studentId,
                                        isDemo = isDemo,
                                        padding = padding,
                                        onAccept = {
                                            service.acceptConsent()
                                            if (boundTargets.isEmpty()) {
                                                showConfigSubpage = true
                                            } else {
                                                scope.launch {
                                                    service.refresh(userInitiated = true)
                                                }
                                            }
                                        },
                                        onDecline = { finish() }
                                    )
                                } else {
                                    ElectricityMainPane(
                                        slice = electricitySlice,
                                        studentId = studentId,
                                        syncing = syncing,
                                        lowBalanceThreshold = lowBalanceThreshold,
                                        padding = padding,
                                        onRefresh = {
                                            scope.launch {
                                                val res = service.refresh(userInitiated = true)
                                                res.onSuccess {
                                                    GlassToaster.show("电费数据已更新")
                                                }.onFailure { err ->
                                                    GlassToaster.show(err.message ?: "电费查询失败")
                                                }
                                            }
                                        },
                                        onOpenConfig = { showConfigSubpage = true }
                                    )
                                }
                            }
                        }
                    }

                    if (showConfigSubpage) {
                        GlassSubpage(onDismiss = { showConfigSubpage = false }) { close ->
                            ElectricityConfigSubpage(
                                service = service,
                                studentId = studentId,
                                initialTargets = boundTargets,
                                initialThreshold = lowBalanceThreshold,
                                syncing = syncing,
                                onSaved = { close() },
                                onRevoked = {
                                    service.revokeConsentAndClear()
                                    close()
                                    GlassToaster.show("已撤回共享学号授权并清除本地绑定")
                                },
                                onClose = close
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        fun launch(context: Context) {
            context.startActivity(Intent(context, ElectricityActivity::class.java))
        }
    }
}

@Composable
private fun StudentIdShareNoticeCard(
    studentId: String,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .border(0.6.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Outlined.PrivacyTip,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(18.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = "查询电费将与开发者共享学号以避免滥用",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "请求网关 ele.buct.one 时仅上报学号与请求签名，用于统计查询次数与恶意刷接口拉黑；绝不上传密码、姓名或校园卡消费流水。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (studentId.isNotBlank()) {
                Text(
                    text = "当前关联学号：$studentId",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ElectricityConsentGate(
    studentId: String,
    isDemo: Boolean,
    padding: PaddingValues,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val cardShape = RoundedCornerShape(22.dp)
    val canAccept = !isDemo && studentId.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 24.dp
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(glassSurfaceColor())
                .border(0.6.dp, glassBorderColor(), cardShape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Column {
                    Text(
                        text = "学号共享与防滥用说明",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "使用宿舍电费查询前需阅读并同意",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = "为防止恶意高频扫描学校电费接口导致网关通道失效，本功能需将您的学号随查询请求及签名一同上报至开发者自建网关（ele.buct.one）。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "• 上报内容：仅包含学号（X-Student-Id）、随机安装标识（X-Client-Id）与待查宿舍编号",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "• 使用目的：统计单个学号的查询次数，并对异常高频/滥用账号进行封禁拉黑",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "• 隐私边界：绝不上传您的统一身份认证密码、真实姓名、校园卡密码、Cookie 或流水",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "• 随时撤回：您可随时在右上角「绑定与设置」中撤回同意并清空本地绑定与缓存",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val idStatusText = when {
                isDemo -> "当前处于演示模式，不支持使用真实学号查询宿舍电费"
                studentId.isBlank() -> "当前未获取到有效学号，请先完成统一身份认证登录"
                else -> "本次将共享的学号：$studentId"
            }
            Text(
                text = idStatusText,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (canAccept) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }

        SystemPrimaryButton(
            text = "同意共享学号并继续",
            onClick = onAccept,
            enabled = canAccept,
            modifier = Modifier.fillMaxWidth()
        )
        SystemSecondaryButton(
            text = "暂不查询（返回）",
            onClick = onDecline,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ElectricityMainPane(
    slice: DomainSlice<ElectricitySnapshot>,
    studentId: String,
    syncing: Boolean,
    lowBalanceThreshold: Double,
    padding: PaddingValues,
    onRefresh: () -> Unit,
    onOpenConfig: () -> Unit
) {
    val snapshot = slice.data
    val targets = snapshot?.targets.orEmpty()
    val readingsByKey = remember(snapshot?.readings) {
        snapshot?.readings.orEmpty().associateBy { "${it.target.schoolid}:${it.target.roomid}" }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 28.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StudentIdShareNoticeCard(studentId = studentId)
        }

        if (!slice.lastError.isNullOrBlank()) {
            item {
                val errShape = RoundedCornerShape(16.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(errShape)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.10f))
                        .border(0.6.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f), errShape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = slice.lastError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.error
                        )
                        if (snapshot?.readings?.isNotEmpty() == true) {
                            Text(
                                text = "已保留最近一次成功查询到的电费快照",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        if (targets.isEmpty()) {
            item {
                SystemEmptyState(
                    title = "还没有绑定宿舍房间",
                    message = "内置全校昌平、东区、西区宿舍字典；东区可同时绑定同房间「照明 + 空调」双表。",
                    icon = Icons.Outlined.Bolt,
                    action = {
                        SystemPrimaryButton(
                            text = "选择并绑定宿舍",
                            onClick = onOpenConfig,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                )
            }
        } else {
            items(targets, key = { "${it.schoolid}:${it.roomid}" }) { target ->
                val reading = readingsByKey["${target.schoolid}:${target.roomid}"]
                ElectricityMeterHeroCard(
                    target = target,
                    reading = reading,
                    threshold = lowBalanceThreshold
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SystemPrimaryButton(
                        text = if (syncing) "查询中..." else "立即刷新",
                        onClick = onRefresh,
                        enabled = !syncing,
                        modifier = Modifier.weight(1f)
                    )
                    SystemSecondaryButton(
                        text = "切换宿舍 / 阈值",
                        onClick = onOpenConfig,
                        enabled = !syncing,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TextButton(onClick = onOpenConfig) {
                        Text(
                            text = "低电量提醒阈值：${formatThresholdLabel(lowBalanceThreshold)}（点击调整或撤回授权）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ElectricityMeterHeroCard(
    target: ElectricityRoomTarget,
    reading: ElectricityReading?,
    threshold: Double
) {
    val isLow = reading != null && isLowElectricityBalance(reading, threshold)
    val accentColor = when {
        isLow -> Color(0xFFD97706)
        reading == null -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    val shape = RoundedCornerShape(22.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(
                width = if (isLow) 1.dp else 0.6.dp,
                color = if (isLow) accentColor.copy(alpha = 0.55f) else glassBorderColor(),
                shape = shape
            )
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${target.campusName} · ${target.buildingName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${target.floorName} · ${electricityTargetBaseRoomName(target.roomName)}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (target.meterType.isNotBlank()) {
                val badgeBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(badgeBg)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = target.meterType,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Text(
            text = if (reading != null) formatElectricityPrimaryValue(reading) else "待查询",
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
            color = accentColor,
            maxLines = 1
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isLow) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xFFD97706).copy(alpha = 0.16f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "余量低于 ${formatThresholdLabel(threshold)}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color(0xFFD97706)
                    )
                }
            }
            val metaText = if (reading != null) {
                val sourceTag = if (reading.fromCache) "网关缓存" else "实时回源"
                val timeTag = formatEpochSec(reading.queriedAtEpochSec)
                val statusTag = reading.accountStatus.takeIf { it.isNotBlank() && it != "1" }?.let { " · 状态:$it" }.orEmpty()
                "$sourceTag · $timeTag$statusTag"
            } else {
                "点击「立即刷新」获取最新余量"
            }
            Text(
                text = metaText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private sealed interface RoomPickOption {
    val label: String
    val key: String
    val targets: List<ElectricityCatalogRoom>
}

private data class GroupedRoomOption(
    override val key: String,
    override val label: String,
    override val targets: List<ElectricityCatalogRoom>
) : RoomPickOption

@Composable
private fun ElectricityConfigSubpage(
    service: ElectricitySyncService,
    studentId: String,
    initialTargets: List<ElectricityRoomTarget>,
    initialThreshold: Double,
    syncing: Boolean,
    onSaved: () -> Unit,
    onRevoked: () -> Unit,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<ElectricityRoomCatalog?>(null) }
    var catalogLoading by remember { mutableStateOf(true) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var reloadNonce by remember { mutableStateOf(0) }

    var selectedCampusId by remember { mutableStateOf(initialTargets.firstOrNull()?.schoolid.orEmpty()) }
    var selectedBuildingId by remember { mutableStateOf(initialTargets.firstOrNull()?.buildingid.orEmpty()) }
    var selectedFloorId by remember { mutableStateOf(initialTargets.firstOrNull()?.floorid.orEmpty()) }
    var selectedRoomIds by remember { mutableStateOf(initialTargets.map { it.roomid }.toSet()) }
    var bindBothMeters by remember { mutableStateOf(initialTargets.size > 1 || initialTargets.firstOrNull()?.meterType.isNullOrBlank().not()) }

    var buildingQuery by remember { mutableStateOf("") }
    var roomQuery by remember { mutableStateOf("") }
    var threshold by remember { mutableDoubleStateOf(initialThreshold) }
    var showRevokeDialog by remember { mutableStateOf(false) }

    LaunchedEffect(reloadNonce) {
        catalogLoading = true
        catalogError = null
        service.loadCatalog()
            .onSuccess { loaded ->
                catalog = loaded
                val firstCampus = loaded.campuses.firstOrNull { it.schoolid == selectedCampusId }
                    ?: loaded.campuses.firstOrNull()
                if (firstCampus != null) {
                    selectedCampusId = firstCampus.schoolid
                    val firstBuilding = firstCampus.buildings.firstOrNull { it.buildingid == selectedBuildingId }
                        ?: firstCampus.buildings.firstOrNull()
                    if (firstBuilding != null) {
                        selectedBuildingId = firstBuilding.buildingid
                        val firstFloor = firstBuilding.floors.firstOrNull { it.floorid == selectedFloorId }
                            ?: firstBuilding.floors.firstOrNull()
                        if (firstFloor != null) {
                            selectedFloorId = firstFloor.floorid
                        }
                    }
                }
                catalogLoading = false
            }
            .onFailure { err ->
                catalogError = err.message ?: "读取宿舍字典失败"
                catalogLoading = false
            }
    }

    val selectedCampus: ElectricityCatalogCampus? = catalog?.campuses?.firstOrNull { it.schoolid == selectedCampusId }
    val buildings: List<ElectricityCatalogBuilding> = selectedCampus?.buildings.orEmpty()
    val filteredBuildings = remember(buildings, buildingQuery) {
        val q = buildingQuery.trim()
        if (q.isEmpty()) buildings else buildings.filter { it.name.contains(q, ignoreCase = true) }
    }
    val selectedBuilding: ElectricityCatalogBuilding? = buildings.firstOrNull { it.buildingid == selectedBuildingId }
    val floors: List<ElectricityCatalogFloor> = selectedBuilding?.floors.orEmpty()
    val selectedFloor: ElectricityCatalogFloor? = floors.firstOrNull { it.floorid == selectedFloorId }
    val rooms: List<ElectricityCatalogRoom> = selectedFloor?.rooms.orEmpty()

    val roomOptions: List<RoomPickOption> = remember(rooms, selectedCampus?.hasMeterType, bindBothMeters, roomQuery) {
        val q = roomQuery.trim()
        if (selectedCampus?.hasMeterType == true && bindBothMeters) {
            rooms.groupBy { electricityTargetBaseRoomName(it.name) }
                .entries
                .map { (base, list) ->
                    val sorted = list.sortedBy {
                        when (it.meterType) {
                            "照明" -> 0
                            "空调" -> 1
                            else -> 2
                        }
                    }
                    val suffix = if (sorted.size > 1) " (双表)" else sorted.firstOrNull()?.meterType?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                    GroupedRoomOption(
                        key = "grp:$base",
                        label = "$base$suffix",
                        targets = sorted
                    )
                }
                .filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) }
        } else {
            rooms
                .filter { r ->
                    q.isEmpty() || r.name.contains(q, ignoreCase = true) || r.meterType.contains(q, ignoreCase = true)
                }
                .map { r ->
                    val base = electricityTargetBaseRoomName(r.name)
                    val label = if (r.meterType.isNotBlank()) "$base · ${r.meterType}" else r.name
                    GroupedRoomOption(
                        key = "single:${r.roomid}",
                        label = label,
                        targets = listOf(r)
                    )
                }
        }
    }

    val pendingTargets: List<ElectricityRoomTarget> = remember(
        selectedCampus,
        selectedBuilding,
        selectedFloor,
        rooms,
        selectedRoomIds
    ) {
        if (selectedCampus == null || selectedBuilding == null || selectedFloor == null) {
            emptyList()
        } else {
            rooms.filter { it.roomid in selectedRoomIds }.map { r ->
                ElectricityRoomTarget(
                    schoolid = selectedCampus.schoolid,
                    campusName = selectedCampus.name,
                    buildingid = selectedBuilding.buildingid,
                    buildingName = selectedBuilding.name,
                    floorid = selectedFloor.floorid,
                    floorName = selectedFloor.name,
                    roomid = r.roomid,
                    roomName = r.name,
                    meterType = r.meterType
                )
            }
        }
    }

    GlassPageScaffold(
        title = "宿舍绑定与设置",
        subtitle = "全校三校区离线房间字典",
        onBack = onClose
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                StudentIdShareNoticeCard(studentId = studentId)
            }

            if (catalogLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        GlassLoadingIndicator()
                    }
                }
            } else if (catalogError != null) {
                item {
                    SystemEmptyState(
                        title = "房间字典加载失败",
                        message = catalogError.orEmpty(),
                        action = {
                            SystemSecondaryButton(
                                text = "重试加载",
                                onClick = { reloadNonce++ },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    )
                }
            } else if (catalog != null) {
                item {
                    SectionHeaderLabel("1. 选择校区")
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(catalog!!.campuses, key = { it.schoolid }) { campus ->
                            GlassFilterChip(
                                label = campus.name,
                                selected = campus.schoolid == selectedCampusId,
                                onClick = {
                                    if (selectedCampusId != campus.schoolid) {
                                        selectedCampusId = campus.schoolid
                                        buildingQuery = ""
                                        roomQuery = ""
                                        val nextB = campus.buildings.firstOrNull()
                                        selectedBuildingId = nextB?.buildingid.orEmpty()
                                        selectedFloorId = nextB?.floors?.firstOrNull()?.floorid.orEmpty()
                                        selectedRoomIds = emptySet()
                                    }
                                }
                            )
                        }
                    }
                }

                item {
                    SectionHeaderLabel("2. 选择楼栋 (${filteredBuildings.size})")
                    Spacer(Modifier.height(8.dp))
                    GlassTextField(
                        value = buildingQuery,
                        onValueChange = { buildingQuery = it },
                        placeholder = "搜索楼栋名称，如 樱花苑1号楼",
                        leadingIcon = Icons.Outlined.Search,
                        trailing = if (buildingQuery.isNotEmpty()) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "清空",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable { buildingQuery = "" }
                                )
                            }
                        } else null
                    )
                    Spacer(Modifier.height(8.dp))
                    if (filteredBuildings.isEmpty()) {
                        Text(
                            text = "没有匹配的楼栋",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(filteredBuildings, key = { it.buildingid }) { b ->
                                GlassFilterChip(
                                    label = b.name,
                                    selected = b.buildingid == selectedBuildingId,
                                    onClick = {
                                        if (selectedBuildingId != b.buildingid) {
                                            selectedBuildingId = b.buildingid
                                            roomQuery = ""
                                            selectedFloorId = b.floors.firstOrNull()?.floorid.orEmpty()
                                            selectedRoomIds = emptySet()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                if (floors.isNotEmpty()) {
                    item {
                        SectionHeaderLabel("3. 选择楼层")
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(floors, key = { it.floorid }) { f ->
                                GlassFilterChip(
                                    label = f.name,
                                    selected = f.floorid == selectedFloorId,
                                    onClick = {
                                        if (selectedFloorId != f.floorid) {
                                            selectedFloorId = f.floorid
                                            roomQuery = ""
                                            selectedRoomIds = emptySet()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                if (rooms.isNotEmpty()) {
                    item {
                        SectionHeaderLabel("4. 选择房间 (${roomOptions.size})")
                        Spacer(Modifier.height(8.dp))
                        if (selectedCampus?.hasMeterType == true) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(glassSurfaceColor())
                                    .border(0.5.dp, glassBorderColor(), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "同时绑定同房间照明与空调双表",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "东校区每间宿舍通常包含独立照明表与空调表",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                LiquidSwitch(
                                    checked = bindBothMeters,
                                    onCheckedChange = { checked ->
                                        bindBothMeters = checked
                                        if (checked && selectedRoomIds.isNotEmpty()) {
                                            val chosenRoom = rooms.firstOrNull { it.roomid in selectedRoomIds }
                                            if (chosenRoom != null) {
                                                val base = electricityTargetBaseRoomName(chosenRoom.name)
                                                selectedRoomIds = rooms
                                                    .filter { electricityTargetBaseRoomName(it.name) == base }
                                                    .map { it.roomid }
                                                    .toSet()
                                            }
                                        } else if (!checked && selectedRoomIds.size > 1) {
                                            selectedRoomIds = setOf(selectedRoomIds.first())
                                        }
                                    }
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        GlassTextField(
                            value = roomQuery,
                            onValueChange = { roomQuery = it },
                            placeholder = "搜索房间号，如 102",
                            leadingIcon = Icons.Outlined.Search,
                            trailing = if (roomQuery.isNotEmpty()) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "清空",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clickable { roomQuery = "" }
                                    )
                                }
                            } else null
                        )
                    }

                    if (roomOptions.isEmpty()) {
                        item {
                            Text(
                                text = "当前楼层没有匹配的房间",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        val rows = roomOptions.chunked(3)
                        items(rows, key = { chunk -> chunk.joinToString("|") { it.key } }) { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                for (opt in rowItems) {
                                    val optIds = opt.targets.map { it.roomid }.toSet()
                                    val selected = optIds.isNotEmpty() && optIds.all { it in selectedRoomIds }
                                    RoomPickGridCell(
                                        label = opt.label,
                                        selected = selected,
                                        modifier = Modifier.weight(1f),
                                        onClick = { selectedRoomIds = optIds }
                                    )
                                }
                                repeat(3 - rowItems.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                item {
                    SectionHeaderLabel("5. 低电量提醒阈值")
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (candidate in listOf(10.0, 20.0, 30.0, 50.0)) {
                            GlassFilterChip(
                                label = "${candidate.toInt()}",
                                selected = abs(threshold - candidate) < 0.01,
                                onClick = { threshold = candidate }
                            )
                        }
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val preview = if (pendingTargets.isEmpty()) {
                            "尚未在当前楼层选定房间"
                        } else {
                            "已选：" + pendingTargets.joinToString(" + ") { t ->
                                if (t.meterType.isNotBlank()) {
                                    "${electricityTargetBaseRoomName(t.roomName)}(${t.meterType})"
                                } else {
                                    electricityTargetShortLabel(t)
                                }
                            }
                        }
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = if (pendingTargets.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                        )

                        SystemPrimaryButton(
                            text = if (syncing) "保存并查询中..." else "保存绑定并查询",
                            enabled = pendingTargets.isNotEmpty() && !syncing,
                            onClick = {
                                scope.launch {
                                    val res = service.bindTargetsAndRefresh(pendingTargets, threshold)
                                    res.onSuccess {
                                        GlassToaster.show("宿舍绑定成功，电费已更新")
                                        onSaved()
                                    }.onFailure { err ->
                                        GlassToaster.show(err.message ?: "绑定已保存，但电费查询失败")
                                        onSaved()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        SystemDestructiveButton(
                            text = "撤回共享学号同意并清除绑定",
                            enabled = !syncing,
                            onClick = { showRevokeDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    if (showRevokeDialog) {
        SystemDialog(
            onDismissRequest = { showRevokeDialog = false },
            title = { Text("撤回共享学号授权") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRevokeDialog = false
                        onRevoked()
                    }
                ) {
                    Text("确认撤回", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeDialog = false }) {
                    Text("取消")
                }
            }
        ) {
            Text(
                text = "撤回后将立即清除本机保存的宿舍绑定与电费缓存，且不再向 ele.buct.one 发送您的学号与电费查询请求。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionHeaderLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun RoomPickGridCell(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else glassSurfaceColor()
    val border = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.70f) else glassBorderColor()
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(if (selected) 1.dp else 0.5.dp, border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatThresholdLabel(threshold: Double): String {
    return if (threshold % 1.0 == 0.0) threshold.toInt().toString() else String.format(Locale.US, "%.1f", threshold)
}

private fun formatEpochSec(epochSec: Long): String {
    if (epochSec <= 0L) return "未知时间"
    return SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(epochSec * 1000L))
}

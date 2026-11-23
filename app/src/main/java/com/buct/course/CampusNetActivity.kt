package com.buct.course

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.buct.course.ui.system.GlassToaster
import com.buct.course.ui.system.GlassWindowHost
import com.buct.course.ui.system.SystemEmptyState
import com.buct.course.ui.system.SystemIconButton
import com.buct.course.ui.system.SystemPrimaryButton
import com.buct.course.ui.system.SystemSecondaryButton
import com.buct.course.ui.system.glassBorderColor
import com.buct.course.ui.system.glassSurfaceColor
import com.buct.course.ui.theme.CourseSelectorTheme
import com.buct.one.data.model.CampusNetCheckoutRecord
import com.buct.one.data.model.CampusNetOnlineDevice
import com.buct.one.data.model.CampusNetPayRecord
import com.buct.one.data.model.CampusNetSnapshot
import com.buct.one.data.model.displayCampusNetDuration
import com.buct.one.data.model.displayCampusNetTraffic
import com.buct.one.data.model.formatCampusNetYuan
import com.buct.one.data.sync.CampusNetSyncService
import kotlinx.coroutines.launch

/**
 * 北化校园网网费与账单独立 Activity（余额、钱包、流量、在线设备、缴费清单、月度结算）。
 * 支持跨 Activity 系统级预测性返回手势。
 */
class CampusNetActivity : ComponentActivity() {

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
                    val service = remember(context) { CampusNetSyncService.getInstance(context) }

                    val slice by service.campusNetState.collectAsState()
                    val syncing by service.syncing.collectAsState()

                    val webVpnAuthLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                    ) { res ->
                        if (res.resultCode == RESULT_OK) {
                            scope.launch { service.refresh() }
                        }
                    }

                    fun launchWebVpnAuth() {
                        webVpnAuthLauncher.launch(
                            Intent(this@CampusNetActivity, LoginActivity::class.java).apply {
                                putExtra(LoginActivity.EXTRA_WEBVPN_AUTH_MODE, true)
                                putExtra(LoginActivity.EXTRA_RETURN_TO_CALLER, true)
                            }
                        )
                    }

                    LaunchedEffect(Unit) {
                        service.refresh()
                    }

                    val snapshot = slice.data
                    val subtitle = when {
                        snapshot != null && snapshot.username.isNotBlank() ->
                            "余额 · 流量 · 缴费与月账单（${snapshot.username}）"
                        else -> "余额 · 流量 · 缴费与月账单"
                    }

                    GlassPageScaffold(
                        title = "校园网网费",
                        subtitle = subtitle,
                        onBack = { finish() },
                        actions = {
                            if (syncing) {
                                GlassLoadingIndicator(size = 22.dp)
                            } else {
                                SystemIconButton(
                                    icon = Icons.Default.Refresh,
                                    contentDescription = "刷新网费",
                                    tint = MaterialTheme.colorScheme.primary,
                                    onClick = {
                                        scope.launch {
                                            service.refresh()
                                                .onSuccess { GlassToaster.show("校园网网费数据已刷新") }
                                                .onFailure { err -> GlassToaster.show(err.message ?: "网费查询失败") }
                                        }
                                    }
                                )
                            }
                        }
                    ) { padding ->
                        CampusNetBillingContent(
                            snapshot = snapshot,
                            lastError = slice.lastError,
                            syncing = syncing,
                            padding = padding,
                            onWebVpnAuth = ::launchWebVpnAuth,
                            onRefresh = {
                                scope.launch {
                                    service.refresh()
                                        .onSuccess { GlassToaster.show("校园网网费数据已刷新") }
                                        .onFailure { err -> GlassToaster.show(err.message ?: "网费查询失败") }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    companion object {
        fun launch(context: Context) {
            context.startActivity(Intent(context, CampusNetActivity::class.java))
        }
    }
}

@Composable
private fun CampusNetBillingContent(
    snapshot: CampusNetSnapshot?,
    lastError: String?,
    syncing: Boolean,
    padding: PaddingValues,
    onWebVpnAuth: () -> Unit,
    onRefresh: () -> Unit
) {
    var billTab by remember { mutableIntStateOf(0) }

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
        if (!lastError.isNullOrBlank()) {
            item {
                val errShape = RoundedCornerShape(16.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(errShape)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.10f))
                        .border(0.6.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f), errShape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
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
                                text = lastError,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.error
                            )
                            if (snapshot != null) {
                                Text(
                                    text = "已保留最近一次成功同步的网费与账单快照",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (lastError.contains("WebVPN", ignoreCase = true)) {
                        SystemSecondaryButton(
                            text = "前往 WebVPN 认证",
                            onClick = onWebVpnAuth,
                            enabled = !syncing,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        if (snapshot == null) {
            item {
                SystemEmptyState(
                    title = "暂无校园网账单数据",
                    message = "校内直连或校外自动走 WebVPN 即可免密查询网费余额、已用流量、缴费清单与月度结算账单",
                    icon = Icons.Outlined.Wifi,
                    action = {
                        SystemPrimaryButton(
                            text = if (syncing) "查询中..." else "立即查询",
                            onClick = onRefresh,
                            enabled = !syncing,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                )
            }
        } else {
            item {
                CampusNetHeroBillingCard(snapshot = snapshot)
            }

            if (snapshot.onlineDevices.isNotEmpty()) {
                item {
                    CampusNetDevicesCard(devices = snapshot.onlineDevices)
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassFilterChip(
                        label = "缴费清单 (${snapshot.payRecords.size})",
                        selected = billTab == 0,
                        onClick = { billTab = 0 }
                    )
                    GlassFilterChip(
                        label = "月度结算 (${snapshot.checkoutRecords.size})",
                        selected = billTab == 1,
                        onClick = { billTab = 1 }
                    )
                }
            }

            if (billTab == 0) {
                if (snapshot.payRecords.isEmpty()) {
                    item {
                        EmptyBillHintCard(text = "暂无缴费记录（仅连接校园网内网自服务后可拉取）")
                    }
                } else {
                    items(snapshot.payRecords) { rec ->
                        CampusNetPayRow(record = rec)
                    }
                }
            } else {
                if (snapshot.checkoutRecords.isEmpty()) {
                    item {
                        EmptyBillHintCard(text = "暂无月度结算清单")
                    }
                } else {
                    items(snapshot.checkoutRecords) { rec ->
                        CampusNetCheckoutRow(record = rec)
                    }
                }
            }
        }
    }
}

@Composable
private fun CampusNetHeroBillingCard(snapshot: CampusNetSnapshot) {
    val shape = RoundedCornerShape(22.dp)
    val green = Color(0xFF34C759)
    val statusText = snapshot.accountStatus.ifBlank {
        if (snapshot.isOnline) "已在线" else "未在线"
    }
    val statusColor = when {
        snapshot.isOnline || statusText.contains("正常") -> green
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.6.dp, glassBorderColor(), shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Column {
                    Text(
                        text = snapshot.productsName.ifBlank { "北化校园网账户" },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = snapshot.billingName.ifBlank { snapshot.username.ifBlank { "tree.buct.edu.cn" } },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(statusColor.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = statusColor
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BillingMetricCell(
                label = "本月已消费",
                value = "¥ ${formatCampusNetYuan(snapshot.userCharge)}",
                modifier = Modifier.weight(1f)
            )
            BillingMetricCell(
                label = "产品余额",
                value = "¥ ${formatCampusNetYuan(snapshot.userBalance)}",
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BillingMetricCell(
                label = "电子钱包",
                value = "¥ ${formatCampusNetYuan(snapshot.walletBalance)}",
                modifier = Modifier.weight(1f)
            )
            BillingMetricCell(
                label = "周期已用流量",
                value = displayCampusNetTraffic(snapshot),
                modifier = Modifier.weight(1f)
            )
        }

        val footerParts = buildList {
            val dur = displayCampusNetDuration(snapshot)
            if (dur.isNotBlank()) add("累计时长：$dur")
            if (snapshot.onlineDeviceTotal > 0) add("在线终端：${snapshot.onlineDeviceTotal} 台")
            if (snapshot.onlineIp.isNotBlank()) add("IP：${snapshot.onlineIp}")
        }
        if (footerParts.isNotEmpty()) {
            Text(
                text = footerParts.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BillingMetricCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CampusNetDevicesCard(devices: List<CampusNetOnlineDevice>) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.6.dp, glassBorderColor(), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "当前在线终端 (${devices.size})",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
        devices.forEach { dev ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = dev.ip.ifBlank { "校内终端" },
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    if (dev.loginTime.isNotBlank()) {
                        Text(
                            text = "上线时间：${dev.loginTime}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                if (dev.billingName.isNotBlank()) {
                    Text(
                        text = dev.billingName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun CampusNetPayRow(record: CampusNetPayRecord) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Outlined.ReceiptLong,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = record.payType.ifBlank { "校园网缴费" },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = record.payTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Text(
            text = "+¥ ${record.amountText}",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = Color(0xFF34C759),
            maxLines = 1
        )
    }
}

@Composable
private fun CampusNetCheckoutRow(record: CampusNetCheckoutRecord) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "${record.checkoutTime.take(10)} · ${record.productName.ifBlank { "月度结算" }}",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOf(
                    record.trafficText.takeIf { it.isNotBlank() }?.let { "流量 $it" }.orEmpty(),
                    record.durationText.takeIf { it.isNotBlank() }?.let { "时长 $it" }.orEmpty()
                ).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "¥ ${record.feeText}",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun EmptyBillHintCard(text: String) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .padding(16.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

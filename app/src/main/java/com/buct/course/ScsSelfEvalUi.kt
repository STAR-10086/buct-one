package com.buct.course

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.buct.course.ui.system.GlassLoadingIndicator
import com.buct.course.ui.system.GlassPageScaffold
import com.buct.course.ui.system.GlassProgressBar
import com.buct.course.ui.system.GlassSubpage
import com.buct.course.ui.system.GlassTextField
import com.buct.course.ui.system.GlassToaster
import com.buct.course.ui.system.LocalWallpaperAppearanceColors
import com.buct.course.ui.system.SystemCompactSegmentedControl
import com.buct.course.ui.system.SystemDialog
import com.buct.course.ui.system.SystemDivider
import com.buct.course.ui.system.SystemEmptyState
import com.buct.course.ui.system.SystemIconButton
import com.buct.course.ui.system.SystemPrimaryButton
import com.buct.course.ui.system.SystemSecondaryButton
import com.buct.course.ui.system.glassBorderColor
import com.buct.course.ui.system.glassSurfaceColor
import com.buct.one.data.model.ScsAuditHistoryItem
import com.buct.one.data.model.ScsCurrentEvalWindow
import com.buct.one.data.model.ScsEvalDimension
import com.buct.one.data.model.ScsEvalItem
import com.buct.one.data.model.ScsItemSelfEvalDetail
import com.buct.one.data.model.ScsManualCalculationResult
import com.buct.one.data.model.ScsManualCalculator
import com.buct.one.data.model.ScsManualDimensionRule
import com.buct.one.data.model.ScsManualEntry
import com.buct.one.data.model.ScsOriginDetailRecord
import com.buct.one.data.model.ScsQuickTemplate
import com.buct.one.data.model.ScsSelfEvalSubmitPayload
import com.buct.one.data.sync.ScsSyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal fun scsDimColor(typeId: Int): Color = when (typeId) {
    1 -> Color(0xFFFF6B6B)
    2 -> Color(0xFF4D96FF)
    3 -> Color(0xFF34C759)
    4 -> Color(0xFFAF52DE)
    5 -> Color(0xFFFF9F0A)
    else -> Color(0xFF4D96FF)
}

internal fun scsDimShortName(typeId: Int): String = when (typeId) {
    1 -> "德"
    2 -> "智"
    3 -> "体"
    4 -> "美"
    5 -> "劳"
    else -> "项"
}

internal fun formatScsScore(value: Double): String {
    val rounded = value.toInt()
    return if (kotlin.math.abs(value - rounded) < 0.001) {
        rounded.toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}

internal fun scsAuditStatusMeta(status: String): Pair<String, Color> = when (status.uppercase(Locale.US)) {
    "PEND_APPROVAL", "PENDING" -> "待审核" to Color(0xFFFF9F0A)
    "PASSED", "PASS", "APPROVED" -> "已通过" to Color(0xFF34C759)
    "REJECTED", "REJECT" -> "已拒绝" to Color(0xFFFF453A)
    else -> status.ifBlank { "未知状态" } to Color(0xFF8E8E93)
}

internal data class ScsManualEditorRequest(
    val editingEntry: ScsManualEntry? = null,
    val prefillTypeId: Int = 1,
    val prefillIsBasic: Boolean = true,
    val prefillTitle: String = "",
    val prefillScore: Double = 0.0,
    val prefillLaborHours: Double = 0.0,
    val prefillLinkedItemId: Long? = null,
    val prefillLinkedItemMaxScore: Double? = null,
    val prefillLinkedItemShortDesc: String = "",
    val prefillDateText: String = "",
    val prefillNote: String = ""
)

internal data class ScsSelfEvalTarget(
    val yearId: Int,
    val termId: Int,
    val item: ScsEvalItem,
    val prefillScore: Double? = null,
    val prefillLaborHours: Double? = null,
    val prefillMark: String = ""
)

@Composable
internal fun ScsManualPlannerDashboardCard(
    termLabel: String,
    result: ScsManualCalculationResult,
    includeOfficialBaseline: Boolean,
    hasOfficialTerm: Boolean,
    onToggleIncludeOfficial: () -> Unit,
    onAddClick: () -> Unit,
    onRulesClick: () -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "五育估分总览（$termLabel）",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "按北化 80% 基础 + 20% 拓展双封顶实时测算，辅助安排后续二课报名",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${formatScsScore(result.totalEffectiveScore)} / ${formatScsScore(result.totalMaxScore)}",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                if (result.totalOverflowScore > 0.001) {
                    Text(
                        text = "溢出 ${formatScsScore(result.totalOverflowScore)} 分不计入",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFF9F0A)
                    )
                } else {
                    Text(
                        text = "剩余可加 ${formatScsScore((result.totalMaxScore - result.totalEffectiveScore).coerceAtLeast(0.0))} 分",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (result.recommendedFocus.isNotEmpty()) {
            val recText = result.recommendedFocus.joinToString(" · ") { dim ->
                val extra = if (dim.typeId == 5 && dim.laborHoursTotal < 10.0) {
                    "，工时 ${formatScsScore(dim.laborHoursTotal)}/10h"
                } else ""
                "${dim.shortLabel}育差 ${formatScsScore(dim.remainingTotal)} 分$extra"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.11f))
                    .border(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "后续报名优先补齐：$recText",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        result.dimensions.forEach { dim ->
            val tint = scsDimColor(dim.typeId)
            val ratio = if (dim.totalMax > 0.0) (dim.effectiveTotal / dim.totalMax).toFloat().coerceIn(0f, 1f) else 0f
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${dim.shortLabel} · ${dim.fullName}",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${formatScsScore(dim.effectiveTotal)} / ${formatScsScore(dim.totalMax)}",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = tint
                    )
                }
                GlassProgressBar(progress = ratio, tint = tint, height = 6.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val breakdown = buildString {
                        append("基础 ${formatScsScore(dim.effectiveBasic)}/${formatScsScore(dim.basicMax)}")
                        append(" · 拓展 ${formatScsScore(dim.effectiveExt)}/${formatScsScore(dim.extMax)}")
                        if (dim.typeId == 5 && dim.laborHoursTotal > 0.0) {
                            append(" · 工时 ${formatScsScore(dim.laborHoursTotal)}h")
                        }
                    }
                    Text(
                        text = breakdown,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val overflow = dim.overflowBasic + dim.overflowExt
                    if (overflow > 0.001) {
                        Text(
                            text = "已封顶 (+${formatScsScore(overflow)} 溢出)",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = Color(0xFFFF9F0A)
                        )
                    } else if (dim.remainingTotal <= 0.001) {
                        Text(
                            text = "已满",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = Color(0xFF34C759)
                        )
                    } else {
                        Text(
                            text = "还可加 ${formatScsScore(dim.remainingTotal)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                dim.itemCapWarnings.forEach { warn ->
                    Text(
                        text = "提示：$warn（已按封顶折算）",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFF9F0A)
                    )
                }
            }
        }

        SystemDivider(alpha = 0.3f)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (includeOfficialBaseline && hasOfficialTerm)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else glassSurfaceColor()
                    )
                    .border(
                        0.5.dp,
                        if (includeOfficialBaseline && hasOfficialTerm)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                        else glassBorderColor(),
                        RoundedCornerShape(50)
                    )
                    .clickable(enabled = hasOfficialTerm) { onToggleIncludeOfficial() }
                    .padding(horizontal = 11.dp, vertical = 7.dp)
            ) {
                Text(
                    text = when {
                        !hasOfficialTerm -> "离线估分模式"
                        includeOfficialBaseline -> "叠加官方分：开"
                        else -> "叠加官方分：关"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = if (includeOfficialBaseline && hasOfficialTerm) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(glassSurfaceColor())
                    .border(0.5.dp, glassBorderColor(), RoundedCornerShape(50))
                    .clickable { onRulesClick() }
                    .padding(horizontal = 11.dp, vertical = 7.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "上限规则",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { onAddClick() }
                    .padding(horizontal = 13.dp, vertical = 7.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "记一笔",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

@Composable
internal fun ScsManualQuickTemplatesRow(
    onSelectTemplate: (ScsQuickTemplate) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "北化常用二课快捷记一笔",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ScsManualCalculator.QUICK_TEMPLATES.forEach { tpl ->
                val tint = scsDimColor(tpl.typeId)
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(glassSurfaceColor())
                        .border(0.5.dp, glassBorderColor(), RoundedCornerShape(16.dp))
                        .clickable { onSelectTemplate(tpl) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(tint.copy(alpha = 0.16f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = scsDimShortName(tpl.typeId),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = tint
                            )
                        }
                        Text(
                            text = tpl.title,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }
                    Text(
                        text = tpl.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun ScsManualEntryCard(
    entry: ScsManualEntry,
    canTransferToOfficial: Boolean,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTransferToOfficial: () -> Unit
) {
    val tint = scsDimColor(entry.typeId)
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (entry.enabled) 1f else 0.56f)
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (entry.enabled) tint else Color.Transparent)
                    .border(1.5.dp, if (entry.enabled) tint else glassBorderColor(), CircleShape)
                    .clickable { onToggleEnabled() },
                contentAlignment = Alignment.Center
            ) {
                if (entry.enabled) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "已计入",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(tint.copy(alpha = 0.16f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${scsDimShortName(entry.typeId)}·${if (entry.isBasic) "基础" else "拓展"}",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = tint
                        )
                    }
                    Text(
                        text = entry.title.ifBlank { "未命名活动" },
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (entry.linkedItemShortDesc.isNotBlank()) {
                    Text(
                        text = buildString {
                            append("对应细则：")
                            append(entry.linkedItemShortDesc)
                            entry.linkedItemMaxScore?.takeIf { it > 0.0 }?.let {
                                append("（单项封顶 ${formatScsScore(it)}）")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val meta = buildList {
                    if (entry.dateText.isNotBlank()) add(entry.dateText)
                    if (!entry.enabled) add("暂不计入（试算关闭）")
                    if (entry.note.isNotBlank()) add(entry.note)
                }.joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (entry.score > 0.0 || entry.laborHours <= 0.0) {
                    Text(
                        text = "+${formatScsScore(entry.score)} 分",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = tint
                    )
                }
                if (entry.laborHours > 0.0) {
                    Text(
                        text = "+${formatScsScore(entry.laborHours)} h",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color(0xFFFF9F0A)
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (canTransferToOfficial) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .clickable { onTransferToOfficial() }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Send,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "去官方自评",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(glassSurfaceColor())
                    .border(0.5.dp, glassBorderColor(), RoundedCornerShape(50))
                    .clickable { onEdit() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "编辑",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFFF453A).copy(alpha = 0.12f))
                    .clickable { onDelete() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = Color(0xFFFF453A),
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "删除",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFF453A)
                    )
                }
            }
        }
    }
}

@Composable
internal fun ScsManualEntryEditorSubpage(
    termKey: String,
    termLabel: String,
    request: ScsManualEditorRequest,
    officialItems: List<ScsEvalItem>,
    onDismiss: () -> Unit,
    onSave: (ScsManualEntry) -> Unit
) {
    val existing = request.editingEntry
    val todayText = remember { SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date()) }
    var typeId by remember(request) { mutableIntStateOf(existing?.typeId ?: request.prefillTypeId) }
    var isBasic by remember(request) { mutableStateOf(existing?.isBasic ?: request.prefillIsBasic) }
    var title by remember(request) { mutableStateOf(existing?.title ?: request.prefillTitle) }
    var scoreText by remember(request) {
        val v = existing?.score ?: request.prefillScore
        mutableStateOf(if (v > 0.0) formatScsScore(v) else "")
    }
    var laborHoursText by remember(request) {
        val h = existing?.laborHours ?: request.prefillLaborHours
        mutableStateOf(if (h > 0.0) formatScsScore(h) else "")
    }
    var dateText by remember(request) {
        mutableStateOf((existing?.dateText ?: request.prefillDateText).ifBlank { todayText })
    }
    var note by remember(request) { mutableStateOf(existing?.note ?: request.prefillNote) }
    var linkedItemId by remember(request) { mutableStateOf(existing?.linkedItemId ?: request.prefillLinkedItemId) }
    var linkedItemMaxScore by remember(request) {
        mutableStateOf(existing?.linkedItemMaxScore ?: request.prefillLinkedItemMaxScore)
    }
    var linkedItemShortDesc by remember(request) {
        mutableStateOf(existing?.linkedItemShortDesc ?: request.prefillLinkedItemShortDesc)
    }
    var showRulePicker by remember { mutableStateOf(false) }

    val matchingOfficialItems = remember(officialItems, typeId, isBasic) {
        officialItems.filter { it.typeId == typeId && it.isBasic == isBasic }
    }
    val selectedOfficialItem = remember(officialItems, linkedItemId) {
        linkedItemId?.let { id -> officialItems.firstOrNull { it.id == id } }
    }

    GlassSubpage(onDismiss = onDismiss) { close ->
        GlassPageScaffold(
            title = if (existing == null) "记一笔五育估分" else "编辑估分记录",
            subtitle = termLabel,
            onBack = close,
            bottomBar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LocalWallpaperAppearanceColors.current.solidSurface)
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SystemSecondaryButton(
                            text = "取消",
                            onClick = close,
                            modifier = Modifier.weight(1f)
                        )
                        SystemPrimaryButton(
                            text = "保存记录",
                            enabled = title.trim().isNotEmpty(),
                            onClick = {
                                val parsedScore = scoreText.trim().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                                val parsedHours = if (typeId == 5) {
                                    laborHoursText.trim().toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                                } else 0.0
                                if (parsedScore <= 0.0 && parsedHours <= 0.0) {
                                    GlassToaster.show("请填写预估加分或劳育工时")
                                    return@SystemPrimaryButton
                                }
                                val entry = ScsManualEntry(
                                    id = existing?.id ?: UUID.randomUUID().toString(),
                                    termKey = existing?.termKey ?: termKey,
                                    typeId = typeId,
                                    isBasic = isBasic,
                                    title = title.trim(),
                                    score = parsedScore,
                                    laborHours = parsedHours,
                                    linkedItemId = linkedItemId,
                                    linkedItemMaxScore = linkedItemMaxScore,
                                    linkedItemShortDesc = linkedItemShortDesc,
                                    dateText = dateText.trim(),
                                    note = note.trim(),
                                    enabled = existing?.enabled ?: true,
                                    createdAt = existing?.createdAt ?: System.currentTimeMillis()
                                )
                                onSave(entry)
                                close()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 20.dp,
                        start = 16.dp,
                        end = 16.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 维度选择
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "所属五育维度",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        (1..5).forEach { id ->
                            val active = typeId == id
                            val tint = scsDimColor(id)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (active) tint else glassSurfaceColor())
                                    .border(0.5.dp, if (active) tint else glassBorderColor(), RoundedCornerShape(14.dp))
                                    .clickable {
                                        if (typeId != id) {
                                            typeId = id
                                            linkedItemId = null
                                            linkedItemMaxScore = null
                                            linkedItemShortDesc = ""
                                        }
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = scsDimShortName(id),
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = if (active) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // 基础 / 拓展
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "评定类别（基础 80% / 拓展 20%）",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SystemCompactSegmentedControl(
                        options = listOf("基础评定", "拓展评定"),
                        selectedIndex = if (isBasic) 0 else 1,
                        onSelect = { idx ->
                            val nextBasic = idx == 0
                            if (isBasic != nextBasic) {
                                isBasic = nextBasic
                                linkedItemId = null
                                linkedItemMaxScore = null
                                linkedItemShortDesc = ""
                            }
                        }
                    )
                }

                // 挂靠官方评分细则（可选）
                if (matchingOfficialItems.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(glassSurfaceColor())
                            .border(0.5.dp, glassBorderColor(), RoundedCornerShape(18.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showRulePicker = !showRulePicker },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "关联官方评分细则（可选）",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = linkedItemShortDesc.ifBlank { "点击选择，可查看官方计分规则并自动校验单项封顶" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (linkedItemShortDesc.isNotBlank()) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = if (showRulePicker) "收起" else "选择",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        selectedOfficialItem?.description?.takeIf { it.isNotBlank() }?.let { desc ->
                            Text(
                                text = "官方计分说明：$desc",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        AnimatedVisibility(visible = showRulePicker) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SystemDivider(alpha = 0.3f)
                                if (linkedItemId != null) {
                                    Text(
                                        text = "清除已关联细则",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                        color = Color(0xFFFF453A),
                                        modifier = Modifier
                                            .clickable {
                                                linkedItemId = null
                                                linkedItemMaxScore = null
                                                linkedItemShortDesc = ""
                                                showRulePicker = false
                                            }
                                            .padding(vertical = 4.dp)
                                    )
                                }
                                matchingOfficialItems.forEach { item ->
                                    val selected = item.id == linkedItemId
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(
                                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                                else Color.Transparent
                                            )
                                            .border(
                                                0.5.dp,
                                                if (selected) MaterialTheme.colorScheme.primary else glassBorderColor(),
                                                RoundedCornerShape(12.dp)
                                            )
                                            .clickable {
                                                linkedItemId = item.id
                                                linkedItemMaxScore = item.itemMaxScore
                                                linkedItemShortDesc = item.shortDesc.ifBlank { item.description.take(36) }
                                                if (title.isBlank()) {
                                                    title = item.shortDesc.ifBlank { item.description.take(24) }
                                                }
                                                showRulePicker = false
                                            }
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = item.shortDesc.ifBlank { item.description.take(36) },
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (item.description.isNotBlank() && item.description != item.shortDesc) {
                                            Text(
                                                text = item.description,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 活动名称
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "活动 / 项目名称",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    GlassTextField(
                        value = title,
                        onValueChange = { title = it },
                        placeholder = "例如：宏德讲坛第 XX 期、美育音乐会观众",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 分值与快选
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "预估加分（分）",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    GlassTextField(
                        value = scoreText,
                        onValueChange = { scoreText = it },
                        placeholder = "例如：10（纯记劳育工时可留空或填 0）",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    val quickValues = remember(selectedOfficialItem) {
                        val opts = selectedOfficialItem?.optionalScores?.filter { it > 0.0 }.orEmpty()
                        if (opts.isNotEmpty()) opts else listOf(2.0, 4.0, 5.0, 6.0, 10.0, 15.0, 20.0)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        quickValues.forEach { v ->
                            val label = "+${formatScsScore(v)}"
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(glassSurfaceColor())
                                    .border(0.5.dp, glassBorderColor(), RoundedCornerShape(50))
                                    .clickable { scoreText = formatScsScore(v) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                // 劳育工时
                if (typeId == 5) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "劳育 / 志愿时长（小时，可选）",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        GlassTextField(
                            value = laborHoursText,
                            onValueChange = { laborHoursText = it },
                            placeholder = "基础劳动/志愿满 10h 得 20 分，超出按 0.5 分/h 计拓展",
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(1.0, 2.0, 3.0, 5.0, 10.0).forEach { h ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(Color(0xFFFF9F0A).copy(alpha = 0.14f))
                                        .clickable { laborHoursText = formatScsScore(h) }
                                        .padding(horizontal = 11.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = "+${formatScsScore(h)}h",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = Color(0xFFFF9F0A)
                                    )
                                }
                            }
                        }
                    }
                }

                // 日期与备注
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "日期（可选）",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    GlassTextField(
                        value = dateText,
                        onValueChange = { dateText = it },
                        placeholder = "YYYY-MM-DD",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "备注 / 申报说明备忘（可选，转官方自评时自动带入）",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    GlassTextField(
                        value = note,
                        onValueChange = { note = it },
                        placeholder = "记录活动地点、主办方、已截图或证明材料位置…",
                        singleLine = false,
                        minHeight = 88.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
internal fun ScsManualRulesSubpage(
    officialDimensions: List<ScsEvalDimension>,
    currentCustomRules: List<ScsManualDimensionRule>,
    onDismiss: () -> Unit,
    onSaveRules: (List<ScsManualDimensionRule>) -> Unit
) {
    val initialRules = remember(officialDimensions, currentCustomRules) {
        ScsManualCalculator.resolveRules(officialDimensions, currentCustomRules)
    }
    var basicTexts by remember(initialRules) {
        mutableStateOf(initialRules.associate { it.typeId to formatScsScore(it.basicMax) })
    }
    var extTexts by remember(initialRules) {
        mutableStateOf(initialRules.associate { it.typeId to formatScsScore(it.extMax) })
    }

    GlassSubpage(onDismiss = onDismiss) { close ->
        GlassPageScaffold(
            title = "五育上限规则设置",
            subtitle = "默认采用官方 80% 基础 + 20% 拓展上限",
            onBack = close,
            bottomBar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LocalWallpaperAppearanceColors.current.solidSurface)
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SystemSecondaryButton(
                            text = "恢复官方默认",
                            onClick = {
                                onSaveRules(emptyList())
                                GlassToaster.show("已恢复官方默认上限")
                                close()
                            },
                            modifier = Modifier.weight(1f)
                        )
                        SystemPrimaryButton(
                            text = "保存上限",
                            onClick = {
                                val nextRules = ScsManualCalculator.DEFAULT_RULES.map { def ->
                                    val b = basicTexts[def.typeId]?.trim()?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: def.basicMax
                                    val e = extTexts[def.typeId]?.trim()?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: def.extMax
                                    ScsManualDimensionRule(
                                        typeId = def.typeId,
                                        shortLabel = def.shortLabel,
                                        fullName = def.fullName,
                                        totalMax = (b + e).coerceAtLeast(1.0),
                                        basicMax = b,
                                        extMax = e
                                    )
                                }
                                onSaveRules(nextRules)
                                GlassToaster.show("自定义上限已保存")
                                close()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 20.dp,
                        start = 16.dp,
                        end = 16.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "若本学院对个别维度有微调或你想设置阶段目标分，可在此调整各项基础分与拓展分上限；总上限自动等于「基础 + 拓展」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ScsManualCalculator.DEFAULT_RULES.forEach { def ->
                    val tint = scsDimColor(def.typeId)
                    val bVal = basicTexts[def.typeId]?.toDoubleOrNull() ?: def.basicMax
                    val eVal = extTexts[def.typeId]?.toDoubleOrNull() ?: def.extMax
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(glassSurfaceColor())
                            .border(0.5.dp, glassBorderColor(), RoundedCornerShape(20.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${def.shortLabel} · ${def.fullName}",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = tint
                            )
                            Text(
                                text = "合计 ${formatScsScore(bVal + eVal)} 分",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "基础上限",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                GlassTextField(
                                    value = basicTexts[def.typeId].orEmpty(),
                                    onValueChange = { v -> basicTexts = basicTexts + (def.typeId to v) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "拓展上限",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                GlassTextField(
                                    value = extTexts[def.typeId].orEmpty(),
                                    onValueChange = { v -> extTexts = extTexts + (def.typeId to v) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ScsOfficialSelfEvalWindowCard(
    window: ScsCurrentEvalWindow?,
    termLabel: String,
    onOpenHistory: () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    val isOpen = window?.isOpen == true
    val badgeColor = if (isOpen) Color(0xFF34C759) else Color(0xFFFF9F0A)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(badgeColor.copy(alpha = 0.16f))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isOpen) "自评开放中" else "自评未开放",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = badgeColor
                        )
                    }
                    Text(
                        text = termLabel,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                val timeRange = listOf(
                    window?.startTime?.take(10).orEmpty(),
                    window?.endTime?.take(10).orEmpty()
                ).filter { it.isNotBlank() }.joinToString(" ~ ")
                if (timeRange.isNotBlank()) {
                    Text(
                        text = "窗口期：$timeRange",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!window?.message.isNullOrBlank()) {
                    Text(
                        text = window?.message.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(glassSurfaceColor())
                    .border(0.5.dp, glassBorderColor(), RoundedCornerShape(50))
                    .clickable { onOpenHistory() }
                    .padding(horizontal = 11.dp, vertical = 7.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "评定记录",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
internal fun ScsOfficialEvalItemCard(
    item: ScsEvalItem,
    onClick: () -> Unit
) {
    val tint = scsDimColor(item.typeId)
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .clickable { onClick() }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = item.shortDesc.ifBlank { item.description.take(40) },
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            Column(horizontalAlignment = Alignment.End) {
                val scoreVal = item.score
                Text(
                    text = if (scoreVal != null) "${formatScsScore(scoreVal)} 分" else "待申报",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = if ((scoreVal ?: 0.0) > 0.0) Color(0xFF34C759) else tint
                )
                item.itemMaxScore?.takeIf { it > 0.0 }?.let { cap ->
                    Text(
                        text = "封顶 ${formatScsScore(cap)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (item.description.isNotBlank() && item.description != item.shortDesc) {
            Text(
                text = item.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (item.havePendApproval) {
                ScsMetaTag(text = "待审核", color = Color(0xFFFF9F0A))
            }
            if (item.lowScoreWarn) {
                ScsMetaTag(text = "低分预警", color = Color(0xFFFF453A))
            }
            if (item.laborHoursItem) {
                ScsMetaTag(text = "按工时计分", color = Color(0xFFFF9F0A))
            }
            if (item.imageRequired) {
                ScsMetaTag(text = "需证明图", color = MaterialTheme.colorScheme.primary)
            }
            if (item.reasonRequired) {
                ScsMetaTag(text = "需填说明", color = MaterialTheme.colorScheme.secondary)
            }
            if (item.optionalScores.isNotEmpty()) {
                val preview = item.optionalScores.joinToString("/") { formatScsScore(it) }
                ScsMetaTag(
                    text = "档位：$preview",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = if (item.stuAble) "查看详情 / 发起自评 ›" else "查看评定详情 ›",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ScsMetaTag(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = color
        )
    }
}

private data class LocalSelectedImage(
    val uri: Uri,
    val displayName: String
)

private data class SelfEvalFormMode(
    val title: String,
    val initialScore: Double? = null,
    val initialLaborHours: Double? = null,
    val initialMark: String = "",
    val initialJournalName: String = "",
    val existingCertificates: List<String> = emptyList(),
    val relateOriginDetailId: Long? = null,
    val relateSubmitState: String? = null
)

@Composable
internal fun ScsItemSelfEvalDetailSubpage(
    target: ScsSelfEvalTarget,
    service: ScsSyncService,
    onDismiss: () -> Unit,
    onAddManualFallback: (ScsEvalItem) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<ScsItemSelfEvalDetail?>(null) }
    var statusTab by remember { mutableIntStateOf(0) } // 0=待审核 1=已通过 2=已拒绝
    var formMode by remember { mutableStateOf<SelfEvalFormMode?>(null) }
    var revokeConfirmRecord by remember { mutableStateOf<ScsOriginDetailRecord?>(null) }
    var submitting by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            loading = true
            loadError = null
            val res = service.loadItemSelfEvalDetail(
                yearId = target.yearId,
                termId = target.termId,
                itemId = target.item.id
            )
            if (res.isSuccess) {
                detail = res.getOrNull()
                if (target.prefillScore != null || target.prefillLaborHours != null || target.prefillMark.isNotBlank()) {
                    val d = detail
                    if (d != null && d.unSubmitReason.isBlank() && !d.laborHoursGateBlocked && formMode == null) {
                        formMode = SelfEvalFormMode(
                            title = "新增官方自评",
                            initialScore = target.prefillScore,
                            initialLaborHours = target.prefillLaborHours,
                            initialMark = target.prefillMark
                        )
                    }
                }
            } else {
                loadError = res.exceptionOrNull()?.message ?: "加载自评详情失败"
            }
            loading = false
        }
    }

    LaunchedEffect(target.item.id, target.yearId, target.termId) {
        reload()
    }

    GlassSubpage(onDismiss = onDismiss) { close ->
        val currentForm = formMode
        if (currentForm != null && detail != null) {
            ScsSelfEvalSubmitFormView(
                item = target.item,
                detail = detail!!,
                mode = currentForm,
                submitting = submitting,
                onBack = { formMode = null },
                onSubmit = { score, laborHours, mark, journalName, existingUrls, localImages ->
                    scope.launch {
                        submitting = true
                        try {
                            val uploadedUrls = mutableListOf<String>()
                            uploadedUrls.addAll(existingUrls)
                            for (img in localImages) {
                                val bytes = withContext(Dispatchers.IO) {
                                    readAndCompressImageUnder4Mb(context, img.uri)
                                }
                                val upRes = service.uploadProofImage(
                                    fileName = img.displayName,
                                    mimeType = "image/jpeg",
                                    bytes = bytes
                                )
                                if (upRes.isFailure) {
                                    throw upRes.exceptionOrNull() ?: IllegalStateException("证明图片上传失败")
                                }
                                uploadedUrls.add(upRes.getOrThrow())
                            }
                            val payload = ScsSelfEvalSubmitPayload(
                                yearId = target.yearId,
                                termId = target.termId,
                                itemId = target.item.id,
                                score = if (detail!!.laborHoursItem) null else score,
                                laborHours = if (detail!!.laborHoursItem) laborHours else null,
                                mark = mark,
                                journalName = journalName,
                                certificateUrls = uploadedUrls,
                                relateOriginDetailId = currentForm.relateOriginDetailId,
                                relateSubmitState = currentForm.relateSubmitState
                            )
                            val submitRes = service.submitSelfEval(payload)
                            if (submitRes.isFailure) {
                                throw submitRes.exceptionOrNull() ?: IllegalStateException("提交自评失败")
                            }
                            GlassToaster.show("已提交至学校系统，等待审核")
                            formMode = null
                            reload()
                        } catch (e: Exception) {
                            GlassToaster.show(e.message ?: "提交失败，请稍后重试")
                        } finally {
                            submitting = false
                        }
                    }
                }
            )
        } else {
            val d = detail
            val canAddNew = d != null &&
                d.unSubmitReason.isBlank() &&
                !d.laborHoursGateBlocked &&
                !(d.singleSubmit && d.records.any {
                    it.auditStatus.equals("PEND_APPROVAL", true) || it.auditStatus.equals("PASSED", true)
                })
            GlassPageScaffold(
                title = "官方自评详情",
                subtitle = "${scsDimShortName(target.item.typeId)}育 · ${if (target.item.isBasic) "基础评定" else "拓展评定"}",
                onBack = close,
                actions = {
                    if (loading || submitting) {
                        GlassLoadingIndicator(size = 36.dp)
                    } else {
                        SystemIconButton(
                            icon = Icons.Outlined.Refresh,
                            contentDescription = "刷新",
                            onClick = ::reload
                        )
                    }
                },
                bottomBar = {
                    if (d != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(LocalWallpaperAppearanceColors.current.solidSurface)
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            val disabledReason = when {
                                d.unSubmitReason.isNotBlank() -> d.unSubmitReason
                                d.laborHoursGateBlocked -> d.laborHoursGateMessage.ifBlank { "未满足前置工时要求" }
                                d.singleSubmit && d.records.any {
                                    it.auditStatus.equals("PEND_APPROVAL", true) || it.auditStatus.equals("PASSED", true)
                                } -> "该项仅允许单次申报"
                                else -> ""
                            }
                            SystemPrimaryButton(
                                text = if (canAddNew) "新增官方自评" else disabledReason.ifBlank { "当前不可申报" },
                                enabled = canAddNew && !submitting,
                                onClick = {
                                    formMode = SelfEvalFormMode(
                                        title = "新增官方自评",
                                        initialScore = target.prefillScore,
                                        initialLaborHours = target.prefillLaborHours,
                                        initialMark = target.prefillMark
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            ) { padding ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            top = padding.calculateTopPadding() + 6.dp,
                            start = 16.dp,
                            end = 16.dp
                        ),
                    contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 细则头部说明卡
                    item {
                        val shape = RoundedCornerShape(22.dp)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .background(glassSurfaceColor())
                                .border(0.5.dp, glassBorderColor(), shape)
                                .padding(15.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = (d?.shortDesc?.ifBlank { null } ?: target.item.shortDesc).ifBlank { "评定细则" },
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val descText = d?.description?.ifBlank { null } ?: target.item.description
                            if (descText.isNotBlank()) {
                                Text(
                                    text = descText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val scoreNow = d?.itemScore ?: target.item.score
                                val maxScore = d?.itemMaxScore ?: target.item.itemMaxScore
                                Text(
                                    text = buildString {
                                        append("当前已评：")
                                        append(if (scoreNow != null) "${formatScsScore(scoreNow)} 分" else "暂无")
                                        if (maxScore != null && maxScore > 0.0) {
                                            append(" / 封顶 ${formatScsScore(maxScore)} 分")
                                        }
                                    },
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (d != null && (d.laborHoursItem || d.requiredHours != null || d.laborHoursApproved != null)) {
                                val approved = d.laborHoursApproved ?: 0.0
                                val req = d.requiredHours ?: 10.0
                                Text(
                                    text = "已审核工时：${formatScsScore(approved)} h / 达标线 ${formatScsScore(req)} h",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                    color = Color(0xFFFF9F0A)
                                )
                            }
                        }
                    }

                    if (loading && d == null) {
                        item {
                            SystemEmptyState(
                                title = "正在获取官方自评详情",
                                message = "正在从 scs.buct.edu.cn 读取该项的审核记录与提交规则…"
                            )
                        }
                    } else if (!loadError.isNullOrBlank() && d == null) {
                        item {
                            val shape = RoundedCornerShape(22.dp)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(shape)
                                    .background(Color(0xFFFF9F0A).copy(alpha = 0.12f))
                                    .border(0.5.dp, Color(0xFFFF9F0A).copy(alpha = 0.35f), shape)
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.WarningAmber,
                                        contentDescription = null,
                                        tint = Color(0xFFFF9F0A),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "学校自评详情接口暂未开放或返回异常",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = "服务端返回：${loadError.orEmpty()}\n\n若本学期学校尚未开放明细填报，你可以先在「估分记账」里按本细则记录分数和备注，等学校开放后再一键转入官方自评。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    SystemSecondaryButton(
                                        text = "重试",
                                        onClick = ::reload,
                                        modifier = Modifier.weight(1f)
                                    )
                                    SystemPrimaryButton(
                                        text = "先记入估分记账",
                                        onClick = {
                                            close()
                                            onAddManualFallback(target.item)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    } else if (d != null) {
                        if (d.laborHoursGateBlocked) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Color(0xFFFF9F0A).copy(alpha = 0.14f))
                                        .border(0.5.dp, Color(0xFFFF9F0A).copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = d.laborHoursGateMessage.ifBlank { "前置劳动/志愿基础工时尚未达标，暂不可申报此拓展项。" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFFF9F0A)
                                    )
                                }
                            }
                        }

                        val pending = d.records.filter { it.auditStatus.equals("PEND_APPROVAL", true) }
                        val passed = d.records.filter { it.auditStatus.equals("PASSED", true) }
                        val rejected = d.records.filter { it.auditStatus.equals("REJECTED", true) }

                        item {
                            SystemCompactSegmentedControl(
                                options = listOf(
                                    "待审核(${pending.size})",
                                    "已通过(${passed.size})",
                                    "已拒绝(${rejected.size})"
                                ),
                                selectedIndex = statusTab,
                                onSelect = { statusTab = it }
                            )
                        }

                        val visibleRecords = when (statusTab) {
                            0 -> pending
                            1 -> passed
                            else -> rejected
                        }

                        if (visibleRecords.isEmpty()) {
                            item {
                                SystemEmptyState(
                                    title = "暂无记录",
                                    message = when (statusTab) {
                                        0 -> "当前没有待审核的申报记录。"
                                        1 -> "当前没有已通过的申报记录。"
                                        else -> "当前没有被拒绝的申报记录。"
                                    }
                                )
                            }
                        } else {
                            items(visibleRecords, key = { it.id }) { rec ->
                                ScsOriginRecordCard(
                                    record = rec,
                                    laborHoursItem = d.laborHoursItem,
                                    unSubmitReason = d.unSubmitReason,
                                    onRevoke = { revokeConfirmRecord = rec },
                                    onRevokeAndEdit = {
                                        formMode = SelfEvalFormMode(
                                            title = "撤销并重新填报",
                                            initialScore = rec.score,
                                            initialLaborHours = rec.laborHours,
                                            initialMark = rec.mark,
                                            initialJournalName = rec.journalName,
                                            existingCertificates = rec.certificates,
                                            relateOriginDetailId = rec.id,
                                            relateSubmitState = "REVOKE_REEDIT"
                                        )
                                    },
                                    onPassReedit = {
                                        formMode = SelfEvalFormMode(
                                            title = "修改已通过申报",
                                            initialScore = rec.score,
                                            initialLaborHours = rec.laborHours,
                                            initialMark = rec.mark,
                                            initialJournalName = rec.journalName,
                                            existingCertificates = rec.certificates,
                                            relateOriginDetailId = rec.id,
                                            relateSubmitState = "PASS_REEDIT"
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    revokeConfirmRecord?.let { rec ->
        SystemDialog(
            onDismissRequest = { if (!submitting) revokeConfirmRecord = null },
            title = {
                Text(
                    text = "确认撤销这条自评？",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            confirmButton = {
                SystemPrimaryButton(
                    text = if (submitting) "撤销中…" else "确认撤销",
                    enabled = !submitting,
                    onClick = {
                        scope.launch {
                            submitting = true
                            val res = service.revokeSelfEval(rec.id)
                            submitting = false
                            if (res.isSuccess) {
                                revokeConfirmRecord = null
                                GlassToaster.show("已撤销该条自评申报")
                                reload()
                            } else {
                                GlassToaster.show(res.exceptionOrNull()?.message ?: "撤销失败")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                SystemSecondaryButton(
                    text = "取消",
                    enabled = !submitting,
                    onClick = { revokeConfirmRecord = null },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            Text(
                text = "撤销后将从学校的待审核列表中删除此条申报记录，后续如需申报可重新提交。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ScsOriginRecordCard(
    record: ScsOriginDetailRecord,
    laborHoursItem: Boolean,
    unSubmitReason: String,
    onRevoke: () -> Unit,
    onRevokeAndEdit: () -> Unit,
    onPassReedit: () -> Unit
) {
    val (statusLabel, statusColor) = scsAuditStatusMeta(record.auditStatus)
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ScsMetaTag(text = statusLabel, color = statusColor)
                if (record.createTime.isNotBlank()) {
                    Text(
                        text = record.createTime.take(16),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = if (laborHoursItem && record.laborHours != null) {
                    "${formatScsScore(record.laborHours)} h"
                } else {
                    record.score?.let { "+${formatScsScore(it)} 分" } ?: "--"
                },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = statusColor
            )
        }

        if (record.journalName.isNotBlank()) {
            Text(
                text = "期刊/作品：${record.journalName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (record.mark.isNotBlank()) {
            Text(
                text = "申报说明：${record.mark}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (record.teacherComments.isNotBlank()) {
            Text(
                text = "审核评语：${record.teacherComments}",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFF9F0A)
            )
        }

        if (record.certificates.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                record.certificates.forEach { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = "证明材料",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(0.5.dp, glassBorderColor(), RoundedCornerShape(10.dp))
                    )
                }
            }
        }

        val canOperate = record.canUpdate && unSubmitReason.isBlank()
        if (canOperate) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (record.auditStatus.equals("PEND_APPROVAL", true)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFFFF453A).copy(alpha = 0.12f))
                            .clickable { onRevoke() }
                            .padding(horizontal = 11.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "撤销",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = Color(0xFFFF453A)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .clickable { onRevokeAndEdit() }
                            .padding(horizontal = 11.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "撤销并重填",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else if (record.auditStatus.equals("PASSED", true)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .clickable { onPassReedit() }
                            .padding(horizontal = 11.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "修改分数",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScsSelfEvalSubmitFormView(
    item: ScsEvalItem,
    detail: ScsItemSelfEvalDetail,
    mode: SelfEvalFormMode,
    submitting: Boolean,
    onBack: () -> Unit,
    onSubmit: (
        score: Double?,
        laborHours: Double?,
        mark: String,
        journalName: String,
        existingUrls: List<String>,
        localImages: List<LocalSelectedImage>
    ) -> Unit
) {
    val options = remember(detail.optionalScores, item.optionalScores) {
        detail.optionalScores.ifEmpty { item.optionalScores }
    }
    var valueText by remember(mode) {
        val initVal = if (detail.laborHoursItem) mode.initialLaborHours else mode.initialScore
        mutableStateOf(initVal?.let { formatScsScore(it) }.orEmpty())
    }
    var mark by remember(mode) { mutableStateOf(mode.initialMark) }
    var journalName by remember(mode) { mutableStateOf(mode.initialJournalName) }
    var existingUrls by remember(mode) { mutableStateOf(mode.existingCertificates) }
    var localImages by remember(mode) { mutableStateOf<List<LocalSelectedImage>>(emptyList()) }
    var showConfirmDialog by remember { mutableStateOf(false) }

    val totalImageCount = existingUrls.size + localImages.size
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val remain = (6 - existingUrls.size - localImages.size).coerceAtLeast(0)
        if (remain <= 0) {
            GlassToaster.show("最多上传 6 张证明图片")
            return@rememberLauncherForActivityResult
        }
        val added = uris.take(remain).mapIndexed { idx, uri ->
            LocalSelectedImage(uri = uri, displayName = "proof_${System.currentTimeMillis()}_$idx.jpg")
        }
        localImages = localImages + added
        if (uris.size > remain) {
            GlassToaster.show("已截取前 $remain 张（上限 6 张）")
        }
    }

    fun validateAndConfirm() {
        val parsedVal = valueText.trim().toDoubleOrNull()
        if (parsedVal == null || parsedVal < 0.0) {
            GlassToaster.show(if (detail.laborHoursItem) "请选择或输入有效劳动时长" else "请选择或输入有效申报分值")
            return
        }
        if (detail.journalNameRequired && journalName.trim().isEmpty()) {
            GlassToaster.show("请填写期刊 / 作品名称")
            return
        }
        if (detail.reasonRequired && mark.trim().isEmpty()) {
            GlassToaster.show("该项要求填写申报说明")
            return
        }
        if (detail.imageRequired && totalImageCount == 0) {
            GlassToaster.show("该项要求至少上传 1 张证明图片")
            return
        }
        showConfirmDialog = true
    }

    GlassPageScaffold(
        title = mode.title,
        subtitle = (detail.shortDesc.ifBlank { item.shortDesc }).take(24),
        onBack = { if (!submitting) onBack() },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LocalWallpaperAppearanceColors.current.solidSurface)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SystemSecondaryButton(
                        text = "取消",
                        enabled = !submitting,
                        onClick = onBack,
                        modifier = Modifier.weight(1f)
                    )
                    SystemPrimaryButton(
                        text = if (submitting) "上传提交中…" else "提交官方自评",
                        enabled = !submitting,
                        onClick = ::validateAndConfirm,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                    start = 16.dp,
                    end = 16.dp
                ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val desc = detail.description.ifBlank { item.description }
            if (desc.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(glassSurfaceColor())
                        .border(0.5.dp, glassBorderColor(), RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 分值 / 工时选择
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (detail.laborHoursItem) "申报劳动时长（小时）*" else "申报分值（分）*",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (options.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        options.forEach { opt ->
                            val optStr = formatScsScore(opt)
                            val active = valueText.trim() == optStr
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (active) MaterialTheme.colorScheme.primary else glassSurfaceColor()
                                    )
                                    .border(
                                        0.5.dp,
                                        if (active) MaterialTheme.colorScheme.primary else glassBorderColor(),
                                        RoundedCornerShape(50)
                                    )
                                    .clickable(enabled = !submitting) { valueText = optStr }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = if (detail.laborHoursItem) "${optStr}h" else "${optStr}分",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
                GlassTextField(
                    value = valueText,
                    onValueChange = { valueText = it },
                    placeholder = if (detail.laborHoursItem) "请选择档位或输入小时数" else "请选择上方官方档位或输入分值",
                    singleLine = true,
                    enabled = !submitting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (detail.journalNameRequired || journalName.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = if (detail.journalNameRequired) "期刊 / 作品名称 *" else "期刊 / 作品名称（可选）",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    GlassTextField(
                        value = journalName,
                        onValueChange = { journalName = it },
                        placeholder = "请输入发表期刊或作品名称",
                        singleLine = true,
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (detail.reasonRequired) "申报说明 *" else "申报说明（可选）",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                GlassTextField(
                    value = mark,
                    onValueChange = { mark = it },
                    placeholder = "简要说明活动时间、名称、获奖或参与情况",
                    singleLine = false,
                    minHeight = 96.dp,
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 证明图片上传区
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (detail.imageRequired) "证明图片 *（$totalImageCount/6）" else "证明图片（$totalImageCount/6，单张≤4MB）",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (totalImageCount < 6) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .clickable(enabled = !submitting) { imagePicker.launch("image/*") }
                                .padding(horizontal = 11.dp, vertical = 5.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AddPhotoAlternate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "选择图片",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                if (totalImageCount > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        existingUrls.forEachIndexed { idx, url ->
                            Box(modifier = Modifier.size(76.dp)) {
                                AsyncImage(
                                    model = url,
                                    contentDescription = "已上传证明",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(0.5.dp, glassBorderColor(), RoundedCornerShape(12.dp))
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(3.dp)
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.65f))
                                        .clickable(enabled = !submitting) {
                                            existingUrls = existingUrls.filterIndexed { i, _ -> i != idx }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "移除",
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                        localImages.forEachIndexed { idx, img ->
                            Box(modifier = Modifier.size(76.dp)) {
                                AsyncImage(
                                    model = img.uri,
                                    contentDescription = "待上传证明",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(0.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(3.dp)
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.65f))
                                        .clickable(enabled = !submitting) {
                                            localImages = localImages.filterIndexed { i, _ -> i != idx }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "移除",
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showConfirmDialog) {
        SystemDialog(
            onDismissRequest = { if (!submitting) showConfirmDialog = false },
            title = {
                Text(
                    text = "确认提交官方自评？",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            confirmButton = {
                SystemPrimaryButton(
                    text = "确认提交",
                    enabled = !submitting,
                    onClick = {
                        showConfirmDialog = false
                        val v = valueText.trim().toDoubleOrNull()
                        onSubmit(
                            if (detail.laborHoursItem) null else v,
                            if (detail.laborHoursItem) v else null,
                            mark.trim(),
                            journalName.trim(),
                            existingUrls,
                            localImages
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                SystemSecondaryButton(
                    text = "再检查下",
                    enabled = !submitting,
                    onClick = { showConfirmDialog = false },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            Text(
                text = "即将上传 ${localImages.size} 张图片并直接提交到 scs.buct.edu.cn，记录将进入「待审核」状态（审核前支持撤销）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun ScsAuditHistorySubpage(
    yearId: Int,
    termId: Int,
    termLabel: String,
    service: ScsSyncService,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var histories by remember { mutableStateOf<List<ScsAuditHistoryItem>>(emptyList()) }

    fun reload() {
        scope.launch {
            loading = true
            error = null
            val res = service.loadAuditHistories(yearId = yearId, termId = termId)
            if (res.isSuccess) {
                histories = res.getOrDefault(emptyList())
            } else {
                error = res.exceptionOrNull()?.message ?: "加载评定记录失败"
            }
            loading = false
        }
    }

    LaunchedEffect(yearId, termId) {
        reload()
    }

    GlassSubpage(onDismiss = onDismiss) { close ->
        GlassPageScaffold(
            title = "官方评定记录",
            subtitle = termLabel,
            onBack = close,
            actions = {
                if (loading) {
                    GlassLoadingIndicator(size = 36.dp)
                } else {
                    SystemIconButton(
                        icon = Icons.Outlined.Refresh,
                        contentDescription = "刷新",
                        onClick = ::reload
                    )
                }
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = padding.calculateTopPadding() + 6.dp,
                        start = 16.dp,
                        end = 16.dp
                    ),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (loading && histories.isEmpty()) {
                    item {
                        SystemEmptyState(
                            title = "正在加载评定记录",
                            message = "正在查询 $termLabel 的自评与审核流水…"
                        )
                    }
                } else if (!error.isNullOrBlank()) {
                    item {
                        SystemEmptyState(
                            title = "加载失败",
                            message = error.orEmpty()
                        )
                    }
                } else if (histories.isEmpty()) {
                    item {
                        SystemEmptyState(
                            title = "暂无评定记录",
                            message = "$termLabel 暂无自评或审核操作流水。"
                        )
                    }
                } else {
                    items(histories, key = { "${it.id}_${it.createTime}" }) { h ->
                        val (statusLabel, statusColor) = scsAuditStatusMeta(h.auditStatus)
                        val shape = RoundedCornerShape(18.dp)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .background(glassSurfaceColor())
                                .border(0.5.dp, glassBorderColor(), shape)
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (h.typeName.isNotBlank()) {
                                        ScsMetaTag(text = h.typeName, color = MaterialTheme.colorScheme.primary)
                                    }
                                    ScsMetaTag(text = statusLabel, color = statusColor)
                                }
                                h.score?.let { sc ->
                                    Text(
                                        text = "+${formatScsScore(sc)} 分",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                        color = statusColor
                                    )
                                }
                            }
                            if (h.itemDesc.isNotBlank()) {
                                Text(
                                    text = h.itemDesc,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (h.mark.isNotBlank()) {
                                Text(
                                    text = "说明：${h.mark}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (h.teacherComments.isNotBlank()) {
                                Text(
                                    text = "评语：${h.teacherComments}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFFF9F0A)
                                )
                            }
                            if (h.createTime.isNotBlank()) {
                                Text(
                                    text = h.createTime,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 读取相册图片并在超出 3.6MB 时自动压缩缩放，确保符合官方单张 ≤ 4MB 限制。 */
private fun readAndCompressImageUnder4Mb(context: Context, uri: Uri): ByteArray {
    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalStateException("无法读取所选图片")
    val maxBytes = 3_600_000
    if (raw.size <= maxBytes) return raw

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
    var sampleSize = 1
    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
    while (maxDim / sampleSize > 2048) {
        sampleSize *= 2
    }
    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, decodeOpts)
        ?: throw IllegalStateException("图片解码失败，请更换 JPEG/PNG 图片")
    try {
        var quality = 88
        while (quality >= 40) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val compressed = out.toByteArray()
            if (compressed.size <= maxBytes) return compressed
            quality -= 12
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 35, out)
        return out.toByteArray()
    } finally {
        bmp.recycle()
    }
}

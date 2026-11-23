package com.buct.course.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buct.course.ui.system.glassBorderColor
import com.buct.course.ui.system.glassSurfaceColor
import com.buct.one.data.model.CardTransaction
import com.buct.one.data.model.formatFenToYuan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

internal enum class EcardSpendCategory(
    val label: String,
    val colorHex: Long
) {
    DINING("食堂餐饮", 0xFF0A84FF),
    MARKET("超市购物", 0xFF34C759),
    BATH("洗浴水控", 0xFF30B0C7),
    SERVICE("校内服务", 0xFFFF9F0A),
    OTHER("其他消费", 0xFFAF52DE)
}

internal data class EcardTrendBucket(
    val key: String,
    val label: String,
    val spendFen: Long
)

internal data class EcardCategoryShare(
    val category: EcardSpendCategory,
    val spendFen: Long,
    val count: Int,
    val ratio: Float
)

internal data class EcardSpendingInsights(
    val totalSpendFen: Long,
    val spendCount: Int,
    val avgDailySpendFen: Long,
    val isMonthly: Boolean,
    val buckets: List<EcardTrendBucket>,
    val categories: List<EcardCategoryShare>
)

internal fun classifyEcardMerchant(title: String, txName: String): EcardSpendCategory {
    val text = "$title $txName"
    return when {
        listOf("食堂", "餐厅", "餐饮", "美食", "风味", "清真", "面", "粉", "快餐", "早餐", "麦当劳", "瑞幸", "咖啡", "面包", "饺").any { it in text } ->
            EcardSpendCategory.DINING
        listOf("超市", "便利", "商店", "小卖", "百货", "果", "饮品", "奶茶", "物美", "罗森").any { it in text } ->
            EcardSpendCategory.MARKET
        listOf("浴", "水控", "淋浴", "开水", "直饮", "吹风", "洗衣").any { it in text } ->
            EcardSpendCategory.BATH
        listOf("校车", "班车", "医院", "校医院", "网", "电费", "打印", "复印", "图书", "体育", "场馆", "门禁").any { it in text } ->
            EcardSpendCategory.SERVICE
        else -> EcardSpendCategory.OTHER
    }
}

internal fun computeEcardInsights(transactions: List<CardTransaction>): EcardSpendingInsights {
    val spends = transactions.filter { it.amountFen < 0L }
    if (spends.isEmpty()) {
        return EcardSpendingInsights(0L, 0, 0L, isMonthly = false, emptyList(), emptyList())
    }

    val totalSpendFen = spends.sumOf { abs(it.amountFen) }
    val monthFmt = SimpleDateFormat("yyyy-MM", Locale.US)
    val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    val monthTotals = LinkedHashMap<String, Long>()
    val dayTotals = LinkedHashMap<String, Long>()
    val categoryTotals = LinkedHashMap<EcardSpendCategory, Pair<Long, Int>>()

    for (tx in spends) {
        val amount = abs(tx.amountFen)
        val dateStr = extractIsoDatePrefix(tx.formattedTime)
            ?: tx.timestampMillis.takeIf { it > 0L }?.let { dayFmt.format(Date(it)) }
        if (dateStr != null && dateStr.length >= 10) {
            val monthKey = dateStr.take(7)
            monthTotals[monthKey] = (monthTotals[monthKey] ?: 0L) + amount
            dayTotals[dateStr] = (dayTotals[dateStr] ?: 0L) + amount
        } else if (tx.timestampMillis > 0L) {
            val monthKey = monthFmt.format(Date(tx.timestampMillis))
            val dayKey = dayFmt.format(Date(tx.timestampMillis))
            monthTotals[monthKey] = (monthTotals[monthKey] ?: 0L) + amount
            dayTotals[dayKey] = (dayTotals[dayKey] ?: 0L) + amount
        }

        val cat = classifyEcardMerchant(tx.title, tx.txName)
        val prev = categoryTotals[cat] ?: (0L to 0)
        categoryTotals[cat] = (prev.first + amount) to (prev.second + 1)
    }

    val activeDays = dayTotals.size.coerceAtLeast(1)
    val avgDailySpendFen = totalSpendFen / activeDays

    val isMonthly = monthTotals.size >= 2
    val buckets = if (isMonthly) {
        monthTotals.entries
            .sortedBy { it.key }
            .takeLast(8)
            .map { (key, sum) ->
                val monthNum = key.substringAfter('-', "").toIntOrNull()
                val label = if (monthNum != null) "${monthNum}月" else key
                EcardTrendBucket(key, label, sum)
            }
    } else {
        dayTotals.entries
            .sortedBy { it.key }
            .takeLast(8)
            .map { (key, sum) ->
                val parts = key.split('-')
                val label = if (parts.size == 3) {
                    "${parts[1].toIntOrNull() ?: parts[1]}/${parts[2].toIntOrNull() ?: parts[2]}"
                } else {
                    key.takeLast(5)
                }
                EcardTrendBucket(key, label, sum)
            }
    }

    val categories = categoryTotals.entries
        .map { (cat, pair) ->
            EcardCategoryShare(
                category = cat,
                spendFen = pair.first,
                count = pair.second,
                ratio = if (totalSpendFen > 0L) pair.first.toFloat() / totalSpendFen.toFloat() else 0f
            )
        }
        .sortedByDescending { it.spendFen }

    return EcardSpendingInsights(
        totalSpendFen = totalSpendFen,
        spendCount = spends.size,
        avgDailySpendFen = avgDailySpendFen,
        isMonthly = isMonthly,
        buckets = buckets,
        categories = categories
    )
}

private fun extractIsoDatePrefix(formattedTime: String): String? {
    val clean = formattedTime.trim()
    if (clean.length < 10) return null
    val prefix = clean.take(10).replace('/', '-')
    val parts = prefix.split('-')
    if (parts.size != 3) return null
    if (parts[0].length != 4 || parts[0].toIntOrNull() == null) return null
    if (parts[1].toIntOrNull() !in 1..12 || parts[2].toIntOrNull() !in 1..31) return null
    return prefix
}

@Composable
internal fun EcardInsightsCard(
    transactions: List<CardTransaction>,
    hideAmount: Boolean
) {
    val insights = remember(transactions) { computeEcardInsights(transactions) }
    if (insights.spendCount == 0) return

    val cardShape = RoundedCornerShape(20.dp)
    val primary = MaterialTheme.colorScheme.primary
    val surfaceColor = glassSurfaceColor()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(surfaceColor)
            .border(0.5.dp, glassBorderColor(), cardShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                Icon(
                    imageVector = Icons.Outlined.Insights,
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "消费洞察",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = if (insights.isMonthly) "按月支出趋势" else "近期每日支出",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 统计摘要
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InsightStatItem(
                label = "累计支出",
                value = if (hideAmount) "¥ ****" else "¥${formatFenToYuan(insights.totalSpendFen)}",
                modifier = Modifier.weight(1f)
            )
            InsightStatItem(
                label = "消费笔数",
                value = "${insights.spendCount} 笔",
                modifier = Modifier.weight(1f)
            )
            InsightStatItem(
                label = "日均支出",
                value = if (hideAmount) "¥ ****" else "¥${formatFenToYuan(insights.avgDailySpendFen)}",
                modifier = Modifier.weight(1f)
            )
        }

        // 支出趋势柱状图（Canvas）
        if (insights.buckets.isNotEmpty()) {
            val maxFen = insights.buckets.maxOf { it.spendFen }.coerceAtLeast(1L)
            Row(modifier = Modifier.fillMaxWidth()) {
                insights.buckets.forEach { bucket ->
                    val yuanText = if (hideAmount) "**" else String.format(Locale.US, "%.0f", bucket.spendFen / 100.0)
                    Text(
                        text = yuanText,
                        style = MaterialTheme.typography.labelSmall,
                        color = primary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
            ) {
                val count = insights.buckets.size.coerceAtLeast(1)
                val slotW = size.width / count
                val barW = (slotW * 0.48f).coerceIn(10.dp.toPx(), 26.dp.toPx())
                val radius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                insights.buckets.forEachIndexed { idx, bucket ->
                    val ratio = (bucket.spendFen.toFloat() / maxFen.toFloat()).coerceIn(0.08f, 1f)
                    val barH = size.height * ratio
                    val left = slotW * idx + (slotW - barW) / 2f
                    val top = size.height - barH
                    drawRoundRect(
                        color = primary.copy(alpha = 0.85f),
                        topLeft = Offset(left, top),
                        size = Size(barW, barH),
                        cornerRadius = radius
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                insights.buckets.forEach { bucket ->
                    Text(
                        text = bucket.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 商户分类占比条
        if (insights.categories.isNotEmpty()) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
            ) {
                var offsetX = 0f
                insights.categories.forEach { share ->
                    val w = size.width * share.ratio
                    drawRect(
                        color = Color(share.category.colorHex),
                        topLeft = Offset(offsetX, 0f),
                        size = Size(w, size.height)
                    )
                    offsetX += w
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                insights.categories.forEach { share ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(share.category.colorHex))
                            )
                            Text(
                                text = "${share.category.label}（${share.count}笔）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        val pct = (share.ratio * 100f).toInt()
                        val amtText = if (hideAmount) "¥ **" else "¥${formatFenToYuan(share.spendFen)}"
                        Text(
                            text = "$amtText · $pct%",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightStatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

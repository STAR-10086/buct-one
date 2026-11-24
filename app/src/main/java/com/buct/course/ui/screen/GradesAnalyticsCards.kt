package com.buct.course.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buct.course.model.BuctGpaScale
import com.buct.course.ui.system.glassBorderColor
import com.buct.course.ui.system.glassSurfaceColor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class GpaTrendPoint(
    val label: String,
    val shortLabel: String,
    val gpa: Double,
    val credits: Double,
    val courseCount: Int
)

internal data class TargetGpaEstimate(
    val currentGpa: Double,
    val currentCredits: Double,
    val requiredGpa: Double,
    val achievable: Boolean,
    val alreadyReached: Boolean
)

internal fun buildYearlyGpaTrend(grades: List<GradeItemUi>): List<GpaTrendPoint> {
    val buckets = grades
        .filter { isGradedForGpa(it) }
        .mapNotNull { item ->
            val yearKey = normalizeAcademicYearKey(item.year) ?: return@mapNotNull null
            yearKey to item
        }
        .groupBy({ it.first }, { it.second })
        .entries
        .sortedBy { it.key }
        .map { (yearKey, items) ->
            Triple(fullAcademicYear(yearKey), shortenAcademicYear(yearKey), items)
        }
    return buildCumulativeTrend(buckets)
}

internal fun buildTermGpaTrend(grades: List<GradeItemUi>): List<GpaTrendPoint> {
    val buckets = grades
        .filter { isGradedForGpa(it) }
        .mapNotNull { item ->
            val yearKey = normalizeAcademicYearKey(item.year) ?: return@mapNotNull null
            val termInfo = normalizeTerm(item.term)
            (yearKey to termInfo) to item
        }
        .groupBy({ it.first }, { it.second })
        .entries
        .sortedWith(compareBy({ it.key.first }, { it.key.second.first }, { it.key.second.second }))
        .map { (key, items) ->
            val (yearKey, termInfo) = key
            val (_, shortTerm) = termInfo
            val shortLabel = if (shortTerm.isBlank()) {
                shortenAcademicYear(yearKey)
            } else {
                "${shortenAcademicYear(yearKey)}·$shortTerm"
            }
            val fullLabel = if (shortTerm.isBlank()) {
                fullAcademicYear(yearKey)
            } else {
                "${fullAcademicYear(yearKey)} 第${shortTerm}学期"
            }
            Triple(fullLabel, shortLabel, items)
        }
    return buildCumulativeTrend(buckets)
}

internal fun estimateTargetGpa(
    grades: List<GradeItemUi>,
    officialGpa: String,
    targetGpa: Double,
    plannedCredits: Double
): TargetGpaEstimate {
    var totalCredits = 0.0
    var weightedSum = 0.0
    deduplicateBestAttempts(grades).filter { isGradedForGpa(it) }.forEach { g ->
        val c = g.credits.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: return@forEach
        val p = effectiveGpaPoint(g) ?: return@forEach
        totalCredits += c
        weightedSum += c * p
    }
    val parsedOfficial = officialGpa.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    val currentGpa = parsedOfficial ?: if (totalCredits > 0.0) weightedSum / totalCredits else 0.0
    val baseWeighted = if (parsedOfficial != null && totalCredits > 0.0) {
        parsedOfficial * totalCredits
    } else {
        weightedSum
    }

    val safePlan = plannedCredits.coerceAtLeast(1.0)
    val required = (targetGpa * (totalCredits + safePlan) - baseWeighted) / safePlan
    return TargetGpaEstimate(
        currentGpa = currentGpa,
        currentCredits = totalCredits,
        requiredGpa = required,
        achievable = required <= BuctGpaScale.MAX_GPA + 1e-4,
        alreadyReached = currentGpa >= targetGpa - 1e-6 && totalCredits > 0.0
    )
}

/** 统一正方 `xnm`（如 "2024"）与完整学年串（如 "2024-2025"）为起始年份 "2024"。 */
private fun normalizeAcademicYearKey(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val start = trimmed.substringBefore('-').trim()
    if (start.length == 4 && start.all { it.isDigit() }) return start
    return trimmed
}

private fun fullAcademicYear(yearKey: String): String {
    val y = yearKey.toIntOrNull()
    return if (y != null && yearKey.length == 4) "$y-${y + 1}" else yearKey
}

/** 兼容正方 `xqm`（3=第1学期, 12=第2学期, 16=小学期）与展示文本。 */
private fun normalizeTerm(raw: String): Pair<Int, String> {
    val t = raw.trim()
    return when {
        t == "3" -> 1 to "1"
        t == "12" -> 2 to "2"
        t == "16" -> 3 to "小"
        t.contains("1") || t.contains("一") -> 1 to "1"
        t.contains("2") || t.contains("二") -> 2 to "2"
        t.contains("3") || t.contains("三") || t.contains("小") -> 3 to "小"
        t.isEmpty() -> 9 to ""
        else -> 9 to t.take(2)
    }
}

/** 按时间顺序累计学分与加权绩点：大一算大一，大二算大一+大二，同一门课重修/补考只保留最高绩点。 */
private fun buildCumulativeTrend(
    buckets: List<Triple<String, String, List<GradeItemUi>>>
): List<GpaTrendPoint> {
    val bestByCourse = LinkedHashMap<String, GradeItemUi>()
    return buildList {
        for ((label, shortLabel, items) in buckets) {
            if (items.isEmpty()) continue
            var updated = false
            for (g in items) {
                val c = g.credits.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: continue
                if (effectiveGpaPoint(g) == null || c <= 0.0) continue
                val key = gradeCourseDedupKey(g)
                val prev = bestByCourse[key]
                bestByCourse[key] = if (prev == null) g else pickBetterGradeAttempt(prev, g)
                updated = true
            }
            if (!updated) continue

            var cumulativeCredits = 0.0
            var cumulativeWeighted = 0.0
            var cumulativeCount = 0
            for (g in bestByCourse.values) {
                val c = g.credits.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: continue
                val p = effectiveGpaPoint(g) ?: continue
                cumulativeCredits += c
                cumulativeWeighted += c * p
                cumulativeCount++
            }
            if (cumulativeCredits <= 0.0 || cumulativeCount == 0) continue
            add(
                GpaTrendPoint(
                    label = label,
                    shortLabel = shortLabel,
                    gpa = cumulativeWeighted / cumulativeCredits,
                    credits = cumulativeCredits,
                    courseCount = cumulativeCount
                )
            )
        }
    }
}

private fun shortenAcademicYear(yearKey: String): String {
    val y = yearKey.toIntOrNull()
    if (y != null && yearKey.length == 4) {
        return "${(y % 100).toString().padStart(2, '0')}-${((y + 1) % 100).toString().padStart(2, '0')}"
    }
    val parts = yearKey.split('-')
    if (parts.size == 2 && parts[0].length == 4 && parts[1].length == 4) {
        return "${parts[0].takeLast(2)}-${parts[1].takeLast(2)}"
    }
    return yearKey.take(7)
}

@Composable
internal fun GpaTrendCard(grades: List<GradeItemUi>) {
    var byYear by remember { mutableStateOf(true) }
    val yearlyPoints = remember(grades) { buildYearlyGpaTrend(grades) }
    val termPoints = remember(grades) { buildTermGpaTrend(grades) }
    val points = if (byYear) yearlyPoints else termPoints

    if (yearlyPoints.isEmpty() && termPoints.isEmpty()) return

    val cardShape = RoundedCornerShape(22.dp)
    val primary = MaterialTheme.colorScheme.primary
    val surfaceColor = glassSurfaceColor()
    val outlineColor = MaterialTheme.colorScheme.outlineVariant

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
                    imageVector = Icons.Outlined.ShowChart,
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "累计 GPA 趋势",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassFilterChip(
                    label = "按学年",
                    selected = byYear,
                    onClick = { byYear = true },
                    compact = true
                )
                GlassFilterChip(
                    label = "按学期",
                    selected = !byYear,
                    onClick = { byYear = false },
                    compact = true
                )
            }
        }

        if (points.isEmpty()) {
            Text(
                text = "当前维度暂无足够成绩数据",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val peak = points.maxOf { it.gpa }
            val latest = points.last().gpa
            val delta = if (points.size >= 2) latest - points[points.size - 2].gpa else null
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = String.format(Locale.US, "最新 %.2f", latest),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = primary
                )
                Text(
                    text = String.format(Locale.US, "峰值 %.2f", peak),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (delta != null) {
                    val sign = if (delta >= 0) "+" else "-"
                    val deltaColor = if (delta >= 0) Color(0xFF34C759) else Color(0xFFFF9F0A)
                    Text(
                        text = String.format(Locale.US, "环比 %s%.2f", sign, abs(delta)),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = deltaColor
                    )
                }
            }

            // 顶部数值行
            Row(modifier = Modifier.fillMaxWidth()) {
                points.forEach { pt ->
                    Text(
                        text = String.format(Locale.US, "%.2f", pt.gpa),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = primary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            val minVal = (points.minOf { it.gpa } - 0.35).coerceAtLeast(0.0)
            val maxVal = (points.maxOf { it.gpa } + 0.25).coerceAtMost(BuctGpaScale.MAX_GPA)
            val span = (maxVal - minVal).coerceAtLeast(0.4)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
            ) {
                val topPad = 10.dp.toPx()
                val bottomPad = 10.dp.toPx()
                val chartH = (size.height - topPad - bottomPad).coerceAtLeast(1f)
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)

                for (i in 0..2) {
                    val y = topPad + chartH * (i / 2f)
                    drawLine(
                        color = outlineColor.copy(alpha = 0.45f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dashEffect
                    )
                }

                val slotW = size.width / points.size.coerceAtLeast(1)
                val coords = points.mapIndexed { idx, pt ->
                    val x = slotW * idx + slotW / 2f
                    val norm = ((pt.gpa - minVal) / span).toFloat().coerceIn(0f, 1f)
                    val y = topPad + chartH * (1f - norm)
                    Offset(x, y)
                }

                if (coords.size >= 2) {
                    val linePath = Path().apply {
                        moveTo(coords.first().x, coords.first().y)
                        for (i in 1 until coords.size) {
                            lineTo(coords[i].x, coords[i].y)
                        }
                    }
                    val fillPath = Path().apply {
                        addPath(linePath)
                        lineTo(coords.last().x, topPad + chartH)
                        lineTo(coords.first().x, topPad + chartH)
                        close()
                    }
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(primary.copy(alpha = 0.25f), Color.Transparent),
                            startY = topPad,
                            endY = topPad + chartH
                        )
                    )
                    drawPath(
                        path = linePath,
                        color = primary,
                        style = Stroke(
                            width = 2.5.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                for (pt in coords) {
                    drawCircle(color = primary, radius = 5.dp.toPx(), center = pt)
                    drawCircle(color = Color.White, radius = 2.5.dp.toPx(), center = pt)
                }
            }

            // 底部周期标签行
            Row(modifier = Modifier.fillMaxWidth()) {
                points.forEach { pt ->
                    Text(
                        text = pt.shortLabel,
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
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TargetGpaSimulatorCard(
    grades: List<GradeItemUi>,
    officialGpa: String
) {
    var expanded by remember { mutableStateOf(false) }
    var targetGpa by remember { mutableFloatStateOf(3.5f) }
    var plannedCredits by remember { mutableFloatStateOf(20f) }

    val estimate = remember(grades, officialGpa, targetGpa, plannedCredits) {
        estimateTargetGpa(
            grades = grades,
            officialGpa = officialGpa,
            targetGpa = targetGpa.toDouble(),
            plannedCredits = plannedCredits.toDouble()
        )
    }

    if (estimate.currentCredits <= 0.0) return

    val cardShape = RoundedCornerShape(22.dp)
    val primary = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), cardShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.TrackChanges,
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier.size(18.dp)
                )
                Column {
                    Text(
                        text = "目标 GPA 试算",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "当前 %.2f（%.1f 学分）· 点击展开试算",
                            estimate.currentGpa,
                            estimate.currentCredits
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = String.format(Locale.US, "目标总绩点：%.2f", targetGpa),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(3.0f, 3.3f, 3.5f, 3.8f, 4.0f, 4.33f).forEach { preset ->
                        GlassFilterChip(
                            label = if (preset > 4.0f) String.format(Locale.US, "%.2f", preset) else String.format(Locale.US, "%.1f", preset),
                            selected = abs(targetGpa - preset) < 0.01f,
                            onClick = { targetGpa = preset },
                            compact = true
                        )
                    }
                }
                Slider(
                    value = targetGpa,
                    onValueChange = {
                        targetGpa = if (it >= 4.31f) 4.33f
                        else ((it * 20).roundToInt() / 20f).coerceIn(2.0f, 4.33f)
                    },
                    valueRange = 2.0f..4.33f,
                    colors = SliderDefaults.colors(thumbColor = primary, activeTrackColor = primary)
                )

                Text(
                    text = "后续计划修读学分：${plannedCredits.roundToInt()} 学分",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15f, 20f, 30f, 45f, 60f).forEach { preset ->
                        GlassFilterChip(
                            label = "${preset.toInt()}学分",
                            selected = abs(plannedCredits - preset) < 0.5f,
                            onClick = { plannedCredits = preset },
                            compact = true
                        )
                    }
                }
                Slider(
                    value = plannedCredits,
                    onValueChange = { plannedCredits = it.roundToInt().toFloat().coerceIn(5f, 80f) },
                    valueRange = 5f..80f,
                    colors = SliderDefaults.colors(thumbColor = primary, activeTrackColor = primary)
                )

                val resultColor = when {
                    estimate.alreadyReached -> Color(0xFF34C759)
                    estimate.achievable -> primary
                    else -> Color(0xFFFF3B30)
                }
                val resultText = when {
                    estimate.alreadyReached -> String.format(
                        Locale.US,
                        "已达到目标 %.2f！后续 %d 学分只需保持 %.2f 以上即可稳住。",
                        targetGpa,
                        plannedCredits.roundToInt(),
                        estimate.requiredGpa.coerceAtLeast(0.0)
                    )
                    estimate.achievable -> String.format(
                        Locale.US,
                        "接下来 %d 学分平均绩点需达到 %.2f 才能拉到 %.2f。",
                        plannedCredits.roundToInt(),
                        estimate.requiredGpa,
                        targetGpa
                    )
                    else -> String.format(
                        Locale.US,
                        "需要均绩 %.2f（已超出 4.33 上限），建议增加后续修读学分或适当调低目标。",
                        estimate.requiredGpa
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = resultText,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = resultColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(resultColor.copy(alpha = 0.10f))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
        }
    }
}

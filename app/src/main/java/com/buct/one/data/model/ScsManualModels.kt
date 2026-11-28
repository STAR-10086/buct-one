package com.buct.one.data.model

/**
 * 第二课堂（五育并举）本地自填估分模型与纯函数计分器。
 * 严禁新增任何 import，保持 model 包零依赖门禁。
 *
 * 默认维度上限采用 scs.buct.edu.cn 实测官方 80% 基础 + 20% 拓展结构：
 * - 德：180（基础 144 + 拓展 36）
 * - 智：120（基础 96 + 拓展 24）
 * - 体：100（基础 80 + 拓展 20）
 * - 美：100（基础 80 + 拓展 20）
 * - 劳：100（基础 80 + 拓展 20）
 * 合计 600（基础 480 + 拓展 120）。
 */
data class ScsManualEntry(
    val id: String,
    val termKey: String,
    val typeId: Int,
    val isBasic: Boolean,
    val title: String,
    val score: Double,
    val laborHours: Double = 0.0,
    val linkedItemId: Long? = null,
    val linkedItemMaxScore: Double? = null,
    val linkedItemShortDesc: String = "",
    val dateText: String = "",
    val note: String = "",
    val enabled: Boolean = true,
    val createdAt: Long = 0L
)

data class ScsManualDimensionRule(
    val typeId: Int,
    val shortLabel: String,
    val fullName: String,
    val totalMax: Double,
    val basicMax: Double,
    val extMax: Double
)

data class ScsManualStoreData(
    val entries: List<ScsManualEntry> = emptyList(),
    val includeOfficialBaseline: Boolean = true,
    val customRules: List<ScsManualDimensionRule> = emptyList()
)

data class ScsQuickTemplate(
    val key: String,
    val typeId: Int,
    val isBasic: Boolean,
    val title: String,
    val defaultScore: Double,
    val defaultLaborHours: Double = 0.0,
    val capHint: Double? = null,
    val subtitle: String = ""
)

data class ScsDimensionSummary(
    val typeId: Int,
    val shortLabel: String,
    val fullName: String,
    val basicMax: Double,
    val extMax: Double,
    val totalMax: Double,
    val officialBasic: Double,
    val officialExt: Double,
    val manualBasicRaw: Double,
    val manualExtRaw: Double,
    val effectiveBasic: Double,
    val effectiveExt: Double,
    val effectiveTotal: Double,
    val overflowBasic: Double,
    val overflowExt: Double,
    val remainingBasic: Double,
    val remainingExt: Double,
    val remainingTotal: Double,
    val laborHoursTotal: Double,
    val manualEntryCount: Int,
    val itemCapWarnings: List<String> = emptyList()
)

data class ScsManualCalculationResult(
    val dimensions: List<ScsDimensionSummary>,
    val totalEffectiveScore: Double,
    val totalMaxScore: Double,
    val totalOverflowScore: Double,
    val totalLaborHours: Double,
    val recommendedFocus: List<ScsDimensionSummary>
)

object ScsManualCalculator {

    val DEFAULT_RULES: List<ScsManualDimensionRule> = listOf(
        ScsManualDimensionRule(1, "德", "道德与思想素质", 180.0, 144.0, 36.0),
        ScsManualDimensionRule(2, "智", "学术与科技创新", 120.0, 96.0, 24.0),
        ScsManualDimensionRule(3, "体", "体育与身心健康", 100.0, 80.0, 20.0),
        ScsManualDimensionRule(4, "美", "美学与人文素养", 100.0, 80.0, 20.0),
        ScsManualDimensionRule(5, "劳", "劳动与社会实践", 100.0, 80.0, 20.0)
    )

    /** 依据北化 scs.buct.edu.cn 官方细则整理的常见二课快捷记账模板（离线可直接点选）。 */
    val QUICK_TEMPLATES: List<ScsQuickTemplate> = listOf(
        ScsQuickTemplate("moral_act", 1, true, "德育活动（宏德讲坛/院周/党团日）", 10.0, capHint = 50.0, subtitle = "10分/次 · 封顶50"),
        ScsQuickTemplate("acad_lec", 2, true, "科创专题讲座/报告", 6.0, capHint = 24.0, subtitle = "6分/次 · 封顶24"),
        ScsQuickTemplate("acad_act", 2, true, "学术科创相关活动", 15.0, capHint = 30.0, subtitle = "15分/次 · 封顶30"),
        ScsQuickTemplate("pe_act", 3, true, "体育/心理健康活动", 10.0, capHint = 20.0, subtitle = "10分/次 · 封顶20"),
        ScsQuickTemplate("pe_comp", 3, true, "体育类竞赛（参赛/组织）", 10.0, capHint = 20.0, subtitle = "参赛10/观众2 · 封顶20"),
        ScsQuickTemplate("aes_lec", 4, true, "美育类讲座/报告", 10.0, capHint = 30.0, subtitle = "10分/次 · 封顶30"),
        ScsQuickTemplate("aes_act", 4, true, "美育类活动（演出/参赛）", 10.0, capHint = 30.0, subtitle = "参与10/观众5 · 封顶30"),
        ScsQuickTemplate("aes_venue", 4, false, "美育场馆/展览实践", 4.0, capHint = 20.0, subtitle = "拓展 · 4分/次 · 封顶20"),
        ScsQuickTemplate("lab_lec", 5, true, "劳动实践类讲座/报告", 5.0, capHint = 15.0, subtitle = "5分/次 · 封顶15"),
        ScsQuickTemplate("lab_prac", 5, true, "劳动实践（累计工时）", 0.0, defaultLaborHours = 2.0, capHint = 20.0, subtitle = "满10h得20分，先记时长"),
        ScsQuickTemplate("lab_vol", 5, true, "志愿服务（累计工时）", 0.0, defaultLaborHours = 2.0, capHint = 20.0, subtitle = "满10h得20分，先记时长")
    )

    fun resolveRules(
        officialDimensions: List<ScsEvalDimension>,
        customRules: List<ScsManualDimensionRule>
    ): List<ScsManualDimensionRule> {
        val officialById = officialDimensions.associateBy { it.typeId }
        val customById = customRules.associateBy { it.typeId }
        return DEFAULT_RULES.map { def ->
            val custom = customById[def.typeId]
            val official = officialById[def.typeId]
            when {
                custom != null && custom.totalMax > 0.0 -> custom.copy(
                    shortLabel = def.shortLabel,
                    fullName = def.fullName
                )
                official != null && official.totalMax > 0.0 -> {
                    val basicMax = if (official.basicMax > 0.0) official.basicMax else official.totalMax * 0.8
                    val extMax = if (official.extMax > 0.0) official.extMax else (official.totalMax - basicMax).coerceAtLeast(0.0)
                    ScsManualDimensionRule(
                        typeId = def.typeId,
                        shortLabel = def.shortLabel,
                        fullName = def.fullName,
                        totalMax = official.totalMax,
                        basicMax = basicMax,
                        extMax = extMax
                    )
                }
                else -> def
            }
        }
    }

    /**
     * 按五育 80%/20% 基础+拓展双封顶与关联官方细则单项封顶，计算当前学期预估得分与剩余缺口。
     */
    fun calculate(
        termEntries: List<ScsManualEntry>,
        officialTerm: ScsTermScore?,
        officialDimensions: List<ScsEvalDimension>,
        customRules: List<ScsManualDimensionRule>,
        includeOfficialBaseline: Boolean
    ): ScsManualCalculationResult {
        val rules = resolveRules(officialDimensions, customRules)
        val activeEntries = termEntries.filter { it.enabled }
        val detailByType = officialTerm?.details?.associateBy { it.typeId }.orEmpty()

        val summaries = rules.map { rule ->
            val dimEntries = activeEntries.filter { it.typeId == rule.typeId }
            val officialDetail = detailByType[rule.typeId]

            // 官方基线拆分为基础分 / 拓展分；若仅有维度总分则先归入基础分（不超过基础上限）再溢出到拓展分
            val officialTotalFromTerm = if (includeOfficialBaseline && officialTerm != null) {
                when (rule.typeId) {
                    1 -> officialTerm.moral
                    2 -> officialTerm.academic
                    3 -> officialTerm.physical
                    4 -> officialTerm.aesthetics
                    5 -> officialTerm.labor
                    else -> null
                }
            } else {
                null
            }
            val officialBasic: Double
            val officialExt: Double
            if (!includeOfficialBaseline) {
                officialBasic = 0.0
                officialExt = 0.0
            } else if (officialDetail?.basicScore != null || officialDetail?.extScore != null) {
                officialBasic = (officialDetail.basicScore ?: 0.0).coerceAtLeast(0.0)
                officialExt = (officialDetail.extScore ?: 0.0).coerceAtLeast(0.0)
            } else if (officialTotalFromTerm != null) {
                val safeTotal = officialTotalFromTerm.coerceAtLeast(0.0)
                officialBasic = kotlin.math.min(safeTotal, rule.basicMax)
                officialExt = (safeTotal - officialBasic).coerceAtLeast(0.0)
            } else {
                officialBasic = 0.0
                officialExt = 0.0
            }

            // 对挂靠了同一官方细则（且带单项封顶）的手填条目执行单项封顶统计，并生成超限提示
            val itemCapWarnings = mutableListOf<String>()
            fun sumBucketWithItemCaps(bucketEntries: List<ScsManualEntry>): Pair<Double, Double> {
                val grouped = bucketEntries.groupBy { it.linkedItemId }
                var rawSum = 0.0
                var itemCappedSum = 0.0
                grouped.forEach { (linkedId, list) ->
                    val groupRaw = list.sumOf { it.score.coerceAtLeast(0.0) }
                    rawSum += groupRaw
                    val itemCap = if (linkedId != null) {
                        list.mapNotNull { it.linkedItemMaxScore }.firstOrNull { it > 0.0 }
                    } else {
                        null
                    }
                    if (itemCap != null) {
                        val officialItemScore = if (includeOfficialBaseline) {
                            officialDetail?.items?.firstOrNull { it.id == linkedId }?.score?.coerceAtLeast(0.0) ?: 0.0
                        } else {
                            0.0
                        }
                        val remainingItemCap = (itemCap - officialItemScore).coerceAtLeast(0.0)
                        val effectiveGroup = kotlin.math.min(groupRaw, remainingItemCap)
                        if (groupRaw - effectiveGroup > 0.001) {
                            val label = list.firstOrNull()?.linkedItemShortDesc?.ifBlank { list.first().title }.orEmpty()
                            itemCapWarnings.add("「$label」超出单项封顶 ${formatCompact(itemCap)} 分")
                        }
                        itemCappedSum += effectiveGroup
                    } else {
                        itemCappedSum += groupRaw
                    }
                }
                return rawSum to itemCappedSum
            }

            val (manualBasicRaw, manualBasicAfterItemCap) = sumBucketWithItemCaps(dimEntries.filter { it.isBasic })
            val (manualExtRaw, manualExtAfterItemCap) = sumBucketWithItemCaps(dimEntries.filter { !it.isBasic })

            val combinedBasic = officialBasic + manualBasicAfterItemCap
            val combinedExt = officialExt + manualExtAfterItemCap

            val effectiveBasic = kotlin.math.min(combinedBasic, rule.basicMax)
            val effectiveExt = kotlin.math.min(combinedExt, rule.extMax)
            val effectiveTotal = kotlin.math.min(effectiveBasic + effectiveExt, rule.totalMax)

            val overflowBasic = ((officialBasic + manualBasicRaw) - effectiveBasic).coerceAtLeast(0.0)
            val overflowExt = ((officialExt + manualExtRaw) - effectiveExt).coerceAtLeast(0.0)

            val officialLaborHours = if (includeOfficialBaseline) {
                officialDetail?.items?.sumOf { (it.laborHours ?: 0.0).coerceAtLeast(0.0) } ?: 0.0
            } else {
                0.0
            }
            val manualLaborHours = dimEntries.sumOf { it.laborHours.coerceAtLeast(0.0) }

            ScsDimensionSummary(
                typeId = rule.typeId,
                shortLabel = rule.shortLabel,
                fullName = rule.fullName,
                basicMax = rule.basicMax,
                extMax = rule.extMax,
                totalMax = rule.totalMax,
                officialBasic = officialBasic,
                officialExt = officialExt,
                manualBasicRaw = manualBasicRaw,
                manualExtRaw = manualExtRaw,
                effectiveBasic = effectiveBasic,
                effectiveExt = effectiveExt,
                effectiveTotal = effectiveTotal,
                overflowBasic = overflowBasic,
                overflowExt = overflowExt,
                remainingBasic = (rule.basicMax - effectiveBasic).coerceAtLeast(0.0),
                remainingExt = (rule.extMax - effectiveExt).coerceAtLeast(0.0),
                remainingTotal = (rule.totalMax - effectiveTotal).coerceAtLeast(0.0),
                laborHoursTotal = officialLaborHours + manualLaborHours,
                manualEntryCount = dimEntries.size,
                itemCapWarnings = itemCapWarnings
            )
        }

        val totalEffective = summaries.sumOf { it.effectiveTotal }
        val totalMax = summaries.sumOf { it.totalMax }
        val totalOverflow = summaries.sumOf { it.overflowBasic + it.overflowExt }
        val totalLaborHours = summaries.firstOrNull { it.typeId == 5 }?.laborHoursTotal ?: 0.0

        val recommendedFocus = summaries
            .filter { it.remainingTotal > 0.001 }
            .sortedWith(
                compareBy<ScsDimensionSummary> {
                    if (it.totalMax > 0.0) it.effectiveTotal / it.totalMax else 1.0
                }.thenByDescending { it.remainingTotal }
            )
            .take(2)

        return ScsManualCalculationResult(
            dimensions = summaries,
            totalEffectiveScore = totalEffective,
            totalMaxScore = totalMax,
            totalOverflowScore = totalOverflow,
            totalLaborHours = totalLaborHours,
            recommendedFocus = recommendedFocus
        )
    }

    /** 根据活动分类名称粗略推断五育维度（1..5），方便从「二课活动」一键记入估分。 */
    fun guessTypeIdFromText(text: String): Int {
        val s = text.trim()
        return when {
            s.contains("德") || s.contains("思想") || s.contains("党") || s.contains("团") || s.contains("宏德") -> 1
            s.contains("智") || s.contains("学术") || s.contains("科创") || s.contains("科研") || s.contains("竞赛") || s.contains("创新") -> 2
            s.contains("体") || s.contains("运动") || s.contains("心理") || s.contains("跑") -> 3
            s.contains("美") || s.contains("艺术") || s.contains("音乐") || s.contains("书画") || s.contains("文化") -> 4
            s.contains("劳") || s.contains("志愿") || s.contains("实践") || s.contains("公益") || s.contains("宿舍") -> 5
            else -> 1
        }
    }

    private fun formatCompact(v: Double): String {
        val i = v.toInt()
        return if (kotlin.math.abs(v - i) < 0.001) i.toString() else ((v * 10.0).toInt() / 10.0).toString()
    }
}

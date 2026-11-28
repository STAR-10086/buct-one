package com.buct.one.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScsManualCalculatorTest {

    @Test
    fun `default rules match verified BUCT 80-20 five-education caps`() {
        val rules = ScsManualCalculator.resolveRules(emptyList(), emptyList())
        assertEquals(5, rules.size)
        assertEquals(600.0, rules.sumOf { it.totalMax }, 0.001)
        assertEquals(480.0, rules.sumOf { it.basicMax }, 0.001)
        assertEquals(120.0, rules.sumOf { it.extMax }, 0.001)
        assertEquals(180.0, rules[0].totalMax, 0.001)
        assertEquals(144.0, rules[0].basicMax, 0.001)
        assertEquals(36.0, rules[0].extMax, 0.001)
    }

    @Test
    fun `caps basic, ext, and per-item overflow cleanly`() {
        val entries = listOf(
            // 美育基础讲座 4 次 × 10 = 40，超过单项封顶 30
            ScsManualEntry(
                id = "1", termKey = "7:31", typeId = 4, isBasic = true,
                title = "美育讲座 1", score = 20.0,
                linkedItemId = 401L, linkedItemMaxScore = 30.0, linkedItemShortDesc = "美育讲座"
            ),
            ScsManualEntry(
                id = "2", termKey = "7:31", typeId = 4, isBasic = true,
                title = "美育讲座 2", score = 20.0,
                linkedItemId = 401L, linkedItemMaxScore = 30.0, linkedItemShortDesc = "美育讲座"
            ),
            // 美育拓展超过 20 上限
            ScsManualEntry(
                id = "3", termKey = "7:31", typeId = 4, isBasic = false,
                title = "美育比赛", score = 25.0
            ),
            // 已关闭试算的条目不应计入
            ScsManualEntry(
                id = "4", termKey = "7:31", typeId = 4, isBasic = true,
                title = "计划参加的美育活动", score = 10.0, enabled = false
            )
        )

        val result = ScsManualCalculator.calculate(
            termEntries = entries,
            officialTerm = null,
            officialDimensions = emptyList(),
            customRules = emptyList(),
            includeOfficialBaseline = false
        )

        val aesthetics = result.dimensions.first { it.typeId == 4 }
        assertEquals(30.0, aesthetics.effectiveBasic, 0.001)
        assertEquals(20.0, aesthetics.effectiveExt, 0.001)
        assertEquals(50.0, aesthetics.effectiveTotal, 0.001)
        assertEquals(10.0, aesthetics.overflowBasic, 0.001)
        assertEquals(5.0, aesthetics.overflowExt, 0.001)
        assertEquals(1, aesthetics.itemCapWarnings.size)
        assertTrue(aesthetics.itemCapWarnings.first().contains("美育讲座"))
        assertEquals(50.0, aesthetics.remainingTotal, 0.001)
    }

    @Test
    fun `combines official baseline and manual entries and tracks labor hours`() {
        val officialTerm = ScsTermScore(
            termId = 31, yearId = 7, yearName = "2026-2027", termName = "第一学期",
            isFullYear = false, status = 0, totalScore = 40.0,
            moral = 20.0, academic = 0.0, physical = 0.0, aesthetics = 0.0, labor = 20.0,
            details = listOf(
                ScsCategoryDetail(
                    typeId = 5, title = "劳育", basicScore = 20.0, extScore = 0.0,
                    totalScore = 20.0, maxTotalScore = 100.0,
                    items = listOf(
                        ScsEvalItem(
                            id = 501L, typeId = 5, isBasic = true,
                            shortDesc = "劳动实践", description = "满10h得20分",
                            score = 20.0, laborHours = 6.0, stuAble = true
                        )
                    )
                )
            )
        )
        val entries = listOf(
            ScsManualEntry(
                id = "l1", termKey = "7:31", typeId = 5, isBasic = true,
                title = "校内志愿服务", score = 10.0, laborHours = 4.5
            )
        )

        val result = ScsManualCalculator.calculate(
            termEntries = entries,
            officialTerm = officialTerm,
            officialDimensions = emptyList(),
            customRules = emptyList(),
            includeOfficialBaseline = true
        )

        val labor = result.dimensions.first { it.typeId == 5 }
        assertEquals(30.0, labor.effectiveBasic, 0.001)
        assertEquals(10.5, labor.laborHoursTotal, 0.001)
        assertEquals(10.5, result.totalLaborHours, 0.001)
        assertTrue(result.recommendedFocus.isNotEmpty())
    }
}

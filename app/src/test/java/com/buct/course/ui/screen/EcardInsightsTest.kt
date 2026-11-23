package com.buct.course.ui.screen

import com.buct.one.data.model.CardTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EcardInsightsTest {

    private fun tx(
        id: String,
        title: String,
        amountFen: Long,
        formattedTime: String
    ) = CardTransaction(
        id = id,
        title = title,
        txName = "消费",
        txCode = "2201",
        posCode = "0001",
        timestampMillis = 0L,
        formattedTime = formattedTime,
        amountFen = amountFen,
        balanceAfterFen = 5000L
    )

    @Test
    fun `classifyEcardMerchant maps typical BUCT merchants`() {
        assertEquals(EcardSpendCategory.DINING, classifyEcardMerchant("东区第一食堂二楼", "餐费"))
        assertEquals(EcardSpendCategory.MARKET, classifyEcardMerchant("物美校园超市", "消费"))
        assertEquals(EcardSpendCategory.BATH, classifyEcardMerchant("昌平浴室水控", "消费"))
        assertEquals(EcardSpendCategory.SERVICE, classifyEcardMerchant("校医院挂号", "消费"))
        assertEquals(EcardSpendCategory.OTHER, classifyEcardMerchant("未知商户", "消费"))
    }

    @Test
    fun `computeEcardInsights aggregates spends and skips recharges`() {
        val list = listOf(
            tx("1", "一食堂风味窗口", -1500L, "2026-10-12 12:10"),
            tx("2", "校园超市", -500L, "2026-11-01 18:20"),
            tx("3", "圈存充值", 10000L, "2026-11-01 18:00")
        )
        val insights = computeEcardInsights(list)
        assertEquals(2000L, insights.totalSpendFen)
        assertEquals(2, insights.spendCount)
        assertTrue(insights.isMonthly)
        assertEquals(listOf("10月", "11月"), insights.buckets.map { it.label })
        assertEquals(EcardSpendCategory.DINING, insights.categories.first().category)
        assertEquals(0.75f, insights.categories.first().ratio, 0.01f)
    }

    @Test
    fun `computeEcardInsights uses daily buckets within single month`() {
        val list = listOf(
            tx("1", "食堂", -1200L, "2026-11-20 12:00"),
            tx("2", "食堂", -800L, "2026-11-21 12:00")
        )
        val insights = computeEcardInsights(list)
        assertFalse(insights.isMonthly)
        assertEquals(listOf("11/20", "11/21"), insights.buckets.map { it.label })
    }
}

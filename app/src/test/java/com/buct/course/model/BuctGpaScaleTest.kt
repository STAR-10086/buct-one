package com.buct.course.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuctGpaScaleTest {

    @Test
    fun `letter grades map to exact 4_33 scale`() {
        val expected = mapOf(
            "A+" to 4.33,
            "A" to 4.00,
            "A-" to 3.67,
            "B+" to 3.33,
            "B" to 3.00,
            "B-" to 2.67,
            "C+" to 2.33,
            "C" to 2.00,
            "C-" to 1.67,
            "D+" to 1.33,
            "D" to 1.00,
            "F" to 0.00
        )
        expected.forEach { (letter, point) ->
            assertEquals("letter $letter", point, BuctGpaScale.gradePointFromScore(letter)!!, 1e-6)
        }
    }

    @Test
    fun `numeric score boundaries map to exact 4_33 scale`() {
        val boundaries = listOf(
            "100" to 4.33, "95" to 4.33,
            "94" to 4.00, "90" to 4.00,
            "89" to 3.67, "85" to 3.67,
            "84" to 3.33, "82" to 3.33,
            "81" to 3.00, "78" to 3.00,
            "77" to 2.67, "75" to 2.67,
            "74" to 2.33, "72" to 2.33,
            "71" to 2.00, "68" to 2.00,
            "67" to 1.67, "64" to 1.67,
            "63" to 1.33, "61" to 1.33,
            "60" to 1.00,
            "59" to 0.00, "0" to 0.00
        )
        boundaries.forEach { (score, point) ->
            assertEquals("score $score", point, BuctGpaScale.gradePointFromScore(score)!!, 1e-6)
        }
    }

    @Test
    fun `pass fail and blank grades are excluded from GPA`() {
        listOf("P", "p", "U", "u", "Pass", "Unpass", "合格", "不合格", "通过", "不通过", "", "--").forEach {
            assertNull("grade '$it' should not have GPA", BuctGpaScale.gradePointFromScore(it))
        }
    }

    @Test
    fun `credit earned accepts passing grades including P and rejects U F blank`() {
        listOf("P", "p", "Pass", "合格", "及格", "通过", "A+", "D", "60", "95").forEach {
            assertTrue("grade '$it' should earn credit", BuctGpaScale.isCreditEarned(it))
        }
        listOf("U", "u", "Unpass", "不合格", "不及格", "不通过", "未通过", "F", "59", "", "--", "未出分", "缓考", "缺考").forEach {
            assertFalse("grade '$it' should not earn credit", BuctGpaScale.isCreditEarned(it))
        }
    }
}

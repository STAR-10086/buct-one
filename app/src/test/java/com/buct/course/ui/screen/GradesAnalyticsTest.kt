package com.buct.course.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GradesAnalyticsTest {

    private fun grade(
        name: String,
        credits: String,
        gpa: String,
        year: String,
        term: String,
        type: String = "必修"
    ) = GradeItemUi(
        courseName = name,
        grade = "85",
        credits = credits,
        gpa = gpa,
        courseType = type,
        year = year,
        term = term
    )

    @Test
    fun `buildYearlyGpaTrend computes credit weighted GPA sorted by year`() {
        val grades = listOf(
            grade("高数上", "4.0", "3.0", "2023-2024", "1"),
            grade("线代", "2.0", "4.0", "2023-2024", "2"),
            grade("大物", "3.0", "3.7", "2024-2025", "1"),
            grade("体育(1)", "1.0", "4.0", "2023-2024", "1", type = "体育")
        )
        val trend = buildYearlyGpaTrend(grades)
        assertEquals(2, trend.size)
        assertEquals("23-24", trend[0].shortLabel)
        assertEquals((4.0 * 3.0 + 2.0 * 4.0) / 6.0, trend[0].gpa, 0.001)
        assertEquals("24-25", trend[1].shortLabel)
        assertEquals(3.7, trend[1].gpa, 0.001)
    }

    @Test
    fun `buildTermGpaTrend orders terms chronologically`() {
        val grades = listOf(
            grade("课程B", "2.0", "3.5", "2024-2025", "2"),
            grade("课程A", "2.0", "3.0", "2024-2025", "1")
        )
        val trend = buildTermGpaTrend(grades)
        assertEquals(listOf("24-25·1", "24-25·2"), trend.map { it.shortLabel })
    }

    @Test
    fun `estimateTargetGpa calculates required GPA and achievability`() {
        val grades = listOf(
            grade("课程1", "10.0", "3.0", "2024-2025", "1")
        )
        val feasible = estimateTargetGpa(grades, officialGpa = "3.00", targetGpa = 3.4, plannedCredits = 10.0)
        assertTrue(feasible.achievable)
        assertFalse(feasible.alreadyReached)
        assertEquals(3.8, feasible.requiredGpa, 0.001)

        val impossible = estimateTargetGpa(grades, officialGpa = "3.00", targetGpa = 3.9, plannedCredits = 10.0)
        assertFalse(impossible.achievable)
    }
}

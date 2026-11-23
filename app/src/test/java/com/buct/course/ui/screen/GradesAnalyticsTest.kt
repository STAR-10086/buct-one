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
        type: String = "必修",
        score: String = "85",
        code: String = "",
        examNature: String = "正常考试"
    ) = GradeItemUi(
        courseName = name,
        grade = score,
        credits = credits,
        gpa = gpa,
        courseType = type,
        year = year,
        term = term,
        courseCode = code,
        examNature = examNature
    )

    @Test
    fun `buildYearlyGpaTrend computes cumulative credit weighted GPA and skips ungraded`() {
        val grades = listOf(
            grade("高数上", "4.0", "3.0", "2023", "3"),
            grade("线代", "2.0", "4.0", "2023-2024", "12"),
            grade("大物", "3.0", "3.7", "2024", "3"),
            grade("在读未出分", "3.0", "0", "2024", "3", score = "--"),
            grade("体育(1)", "1.0", "4.0", "2023", "3", type = "体育")
        )
        val trend = buildYearlyGpaTrend(grades)
        assertEquals(2, trend.size)
        assertEquals("23-24", trend[0].shortLabel)
        val y1Weighted = 4.0 * 3.0 + 2.0 * 4.0
        assertEquals(y1Weighted / 6.0, trend[0].gpa, 0.001)
        assertEquals("24-25", trend[1].shortLabel)
        assertEquals((y1Weighted + 3.0 * 3.7) / 9.0, trend[1].gpa, 0.001)
    }

    @Test
    fun `buildTermGpaTrend decodes ZF xqm codes and accumulates GPA`() {
        val grades = listOf(
            grade("课程B", "2.0", "3.6", "2024", "12"),
            grade("课程C", "1.0", "3.9", "2024", "16"),
            grade("课程A", "2.0", "3.0", "2024", "3")
        )
        val trend = buildTermGpaTrend(grades)
        assertEquals(listOf("24-25·1", "24-25·2", "24-25·小"), trend.map { it.shortLabel })
        assertEquals(3.0, trend[0].gpa, 0.001)
        assertEquals(3.3, trend[1].gpa, 0.001)
        assertEquals((2.0 * 3.0 + 2.0 * 3.6 + 1.0 * 3.9) / 5.0, trend[2].gpa, 0.001)
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

    @Test
    fun `isGpaExcludedGrade includes PHE military courses and excludes PE and quality electives`() {
        val militarySkill = grade("军事技能", "2.0", "4.0", "2024", "3", type = "实践环节必修", score = "A", code = "PHE19200P")
        val militaryTheory = grade("军事理论", "2.0", "3.0", "2024", "12", type = "公共基础必修", score = "78", code = "PHE10200T")
        val pe = grade("体育(Ⅰ)", "1.0", "2.67", "2024", "3", type = "公共基础必修", score = "B-", code = "PHE10001T")
        val quality = grade("摄影艺术", "2.0", "0.0", "2024", "3", type = "素质教育课程选修", score = "合格", code = "ART10001G")

        assertFalse(isGpaExcludedGrade(militarySkill))
        assertFalse(isGpaExcludedGrade(militaryTheory))
        assertTrue(isGpaExcludedGrade(pe))
        assertTrue(isGpaExcludedGrade(quality))
    }

    @Test
    fun `retake and makeup replace failed attempt without double counting`() {
        val grades = listOf(
            // Year 1: Course A failed (0.0), Course B passed (3.0)
            grade("CourseA", "4.0", "0.0", "2024", "12", score = "48", code = "MAT1001"),
            grade("CourseB", "2.0", "3.0", "2024", "3", score = "80", code = "ENG1001"),
            // Year 2: Course A makeup passed (1.0), Course C retake passed (2.0) after Year 1 fail
            grade("CourseA", "4.0", "", "2025", "3", score = "60", code = "MAT1001", examNature = "补考一"),
            grade("CourseC", "5.0", "0.0", "2024", "12", score = "45", code = "MAT1002"),
            grade("CourseC", "5.0", "2.0", "2025", "12", score = "70", code = "MAT1002", examNature = "重修")
        )
        val trend = buildYearlyGpaTrend(grades)
        assertEquals(2, trend.size)
        // Year 1: (4*0 + 2*3 + 5*0) / 11 = 6/11
        assertEquals(6.0 / 11.0, trend[0].gpa, 0.001)
        assertEquals(11.0, trend[0].credits, 0.001)
        // Year 2: CourseA becomes 1.0, CourseC becomes 2.0 -> (4*1 + 2*3 + 5*2) / 11 = 20/11
        assertEquals(20.0 / 11.0, trend[1].gpa, 0.001)
        assertEquals(11.0, trend[1].credits, 0.001)
        assertEquals(3, trend[1].courseCount)

        // Target GPA estimator also deduplicates
        val est = estimateTargetGpa(grades, officialGpa = "", targetGpa = 2.5, plannedCredits = 11.0)
        assertEquals(11.0, est.currentCredits, 0.001)
        assertEquals(20.0 / 11.0, est.currentGpa, 0.001)

        // semesterAverageGpa deduplicates too
        assertEquals("1.82", semesterAverageGpa(grades))
    }

    @Test
    fun `multiple failed attempts of the same course count credits only once`() {
        val grades = listOf(
            grade("Physics", "4.0", "0.0", "2024", "12", score = "56", code = "PHY1001"),
            grade("Physics", "4.0", "0.0", "2025", "3", score = "39", code = "PHY1001", examNature = "补考一"),
            grade("Physics", "4.0", "0.0", "2025", "12", score = "42", code = "PHY1001", examNature = "重修"),
            grade("English", "2.0", "3.0", "2024", "3", score = "80", code = "ENG1001")
        )
        val trend = buildYearlyGpaTrend(grades)
        assertEquals(2, trend.size)
        assertEquals(6.0, trend[0].credits, 0.001)
        assertEquals(6.0, trend[1].credits, 0.001)
        assertEquals(1.0, trend[1].gpa, 0.001)
    }

    @Test
    fun `gradeExamNatureBadge maps retake makeup and deferred exams`() {
        assertEquals("重修" to com.buct.course.ui.system.SystemTone.Warning, gradeExamNatureBadge("重修"))
        assertEquals("补考" to com.buct.course.ui.system.SystemTone.Warning, gradeExamNatureBadge("补考一"))
        assertEquals("缓考" to com.buct.course.ui.system.SystemTone.Info, gradeExamNatureBadge("缓考"))
        assertEquals(null, gradeExamNatureBadge("正常考试"))
        assertEquals(null, gradeExamNatureBadge(""))
    }

    @Test
    fun `isDetailConsistentWithGrade rejects mismatched retake breakdown`() {
        val oldDetail = "平时(30%): 99 | 期中(10%): 60 | 期末(60%): 20"
        val retakeDetail = "平时(30%): 50 | 期末(70%): 78"
        assertFalse(isDetailConsistentWithGrade(oldDetail, "70"))
        assertTrue(isDetailConsistentWithGrade(oldDetail, "48"))
        assertTrue(isDetailConsistentWithGrade(retakeDetail, "70"))
        assertEquals("2", normalizeGradeTerm("12"))
        assertEquals("2025", normalizeGradeYear("2025-2026"))
    }
}

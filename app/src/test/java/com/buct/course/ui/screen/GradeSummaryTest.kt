package com.buct.course.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Test

class GradeSummaryTest {
    private fun grade(
        credits: String,
        gpa: String,
        name: String = "课程",
        score: String = "85"
    ) = GradeItemUi(name, score, credits, gpa, "")

    @Test fun passFailAndBlankGradesDoNotEnterGpa() {
        assertEquals(
            "--",
            semesterAverageGpa(
                listOf(
                    grade("2", "", name = "实验P", score = "P"),
                    grade("3", "0", name = "合格课", score = "合格"),
                    grade("1", "", name = "未出分", score = "--")
                )
            )
        )
    }

    @Test fun missingGpaFallsBackTo433ScaleIncludingZeroForFail() {
        val grades = listOf(
            grade("2", "", name = "高数", score = "96"),  // 4.33
            grade("2", "", name = "大物", score = "55"),  // 0.00
            grade("3", "", name = "选修", score = "P")    // excluded from GPA
        )
        assertEquals("2.17", semesterAverageGpa(grades))
    }

    @Test fun aReportedZeroGradePointRemainsZero() {
        assertEquals("0.00", semesterAverageGpa(listOf(grade("2", "0", score = "50"))))
    }

    @Test fun earnedCreditsIncludesPassedTwoLevelAndExcludesFailAndBlank() {
        val grades = listOf(
            grade("2", "", name = "实验P", score = "P"),
            grade("1.5", "", name = "素质课", score = "合格"),
            grade("3", "0", name = "挂科", score = "F"),
            grade("2", "", name = "不合格", score = "U"),
            grade("4", "", name = "未出分", score = "")
        )
        assertEquals(3.5, earnedCredits(grades), 1e-6)
    }
}

package com.buct.course.model

import java.util.Locale

/**
 * 北化 4.33 制绩点换算与合格/学分判定规则：
 * 95-100 A+ 4.33, 90-94 A 4.00, 85-89 A- 3.67,
 * 82-84 B+ 3.33, 78-81 B 3.00, 75-77 B- 2.67,
 * 72-74 C+ 2.33, 68-71 C 2.00, 64-67 C- 1.67,
 * 61-63 D+ 1.33, 60 D 1.00, <60 F 0.00；
 * 合格(P)、不合格(U)及空白成绩不计入 GPA，但合格可取得学分。
 */
object BuctGpaScale {
    const val MAX_GPA = 4.33

    private val passFailGrades = setOf(
        "合格", "不合格", "通过", "不通过", "未通过",
        "P", "U", "PASS", "UNPASS"
    )

    private val ungradedTokens = setOf(
        "", "--", "NULL", "未出分", "缓考", "缺考", "免修"
    )

    private val failedTokens = setOf(
        "不合格", "不及格", "不通过", "未通过", "U", "UNPASS", "F", "E"
    )

    private val passedNonLetterTokens = setOf(
        "合格", "及格", "通过", "P", "PASS", "优秀", "优", "良好", "良", "中等", "中"
    )

    private val letterGpaMap = mapOf(
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
        "F" to 0.00,
        "E" to 0.00
    )

    /** 判断是否为两级制（合格/不合格/P/U 等不计入 GPA 的成绩）。 */
    fun isPassFailGrade(grade: String): Boolean =
        grade.trim().uppercase(Locale.ROOT) in passFailGrades

    /** 判断该成绩是否可取得学分（合格 P / 及格 / 60+ / A+~D 均取得学分；空白、未出分、不合格 U、F 不取得学分）。 */
    fun isCreditEarned(grade: String): Boolean {
        val raw = grade.trim()
        val upper = raw.uppercase(Locale.ROOT)
        if (upper in ungradedTokens) return false
        if (upper in failedTokens) return false
        if (upper in passedNonLetterTokens) return true
        val letterPoint = letterGpaMap[upper]
        if (letterPoint != null) return letterPoint >= 1.0
        val numeric = raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return false
        return numeric >= 60.0
    }

    /** 按北化 4.33 制将等级或百分制分数换算为绩点；P/U/空白等不计入 GPA 的返回 null。 */
    fun gradePointFromScore(grade: String, numeric: String = ""): Double? {
        if (isPassFailGrade(grade)) return null
        val letter = grade.trim().uppercase(Locale.ROOT)
        letterGpaMap[letter]?.let { return it }

        val score = numeric.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            ?: grade.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return null

        return when {
            score >= 95.0 -> 4.33
            score >= 90.0 -> 4.00
            score >= 85.0 -> 3.67
            score >= 82.0 -> 3.33
            score >= 78.0 -> 3.00
            score >= 75.0 -> 2.67
            score >= 72.0 -> 2.33
            score >= 68.0 -> 2.00
            score >= 64.0 -> 1.67
            score >= 61.0 -> 1.33
            score >= 60.0 -> 1.00
            else -> 0.00
        }
    }

    fun formatGradePoint(point: Double): String = String.format(Locale.ROOT, "%.2f", point)
}

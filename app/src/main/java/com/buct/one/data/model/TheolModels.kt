package com.buct.one.data.model

/**
 * 北化在线（THEOL / MEOL）课程、作业、测验与本地自定义待办的纯数据模型（零依赖）。
 */
data class TheolCourseInfo(
    val lid: String,
    val courseName: String,
    val type: String, // "homework" | "test" | "course"
    val url: String
)

data class TheolHomeworkItem(
    val hwtid: String,
    val lid: String,
    val courseName: String,
    val title: String,
    val deadline: String,
    val deadlineMillis: Long? = null,
    val score: String = "",
    val publisher: String = "",
    val canSubmit: Boolean = false,
    val hasResult: Boolean = false,
    val isGroup: Boolean = false,
    val status: String = "",
    val detailHref: String = "",
    val submitHref: String = ""
)

data class TheolTestItem(
    val testId: String,
    val lid: String,
    val courseName: String,
    val title: String,
    val startTime: String,
    val endTime: String,
    val endTimeMillis: Long? = null,
    val duration: String = "",
    val allowedAttempts: String = "",
    val canStart: Boolean = false,
    val hasResult: Boolean = false,
    val status: String = "",
    val testUrl: String = ""
)

data class TheolSnapshot(
    val homeworks: List<TheolHomeworkItem> = emptyList(),
    val tests: List<TheolTestItem> = emptyList(),
    val failedCourseCount: Int = 0
)

/**
 * 用户本地自定义待办 / 作业提醒（参考 BUCT-course-remind 模型）。
 * 支持三档提醒：DDL 前 24h / 12h / 1h，软删除进回收站。
 */
data class LocalTodoItem(
    val id: String,
    val title: String,
    val courseName: String = "",
    val deadlineMillis: Long? = null,
    val priority: Int = 1, // 0=低 1=普通 2=高
    val completed: Boolean = false,
    val deleted: Boolean = false,
    val remind24h: Boolean = true,
    val remind12h: Boolean = true,
    val remind1h: Boolean = true,
    val createdAt: Long = 0L
)

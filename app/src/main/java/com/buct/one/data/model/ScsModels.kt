package com.buct.one.data.model

/**
 * 第二课堂（scs.buct.edu.cn / eapi.buct.edu.cn）纯数据模型（零依赖）。
 * 严禁新增任何 import，保持 model 包零依赖门禁。
 */
data class ScsEvalDimension(
    val typeId: Int,
    val name: String,
    val totalMax: Double,
    val basicMax: Double,
    val extMax: Double
)

data class ScsEvalItem(
    val id: Long,
    val typeId: Int,
    val isBasic: Boolean,
    val shortDesc: String,
    val description: String,
    val score: Double?,
    val mark: String = "",
    val teacherComments: String = "",
    val laborHours: Double? = null,
    val stuAble: Boolean = false,
    val optionalScores: List<Double> = emptyList(),
    val itemMaxScore: Double? = null,
    val reasonRequired: Boolean = false,
    val imageRequired: Boolean = false,
    val journalNameRequired: Boolean = false,
    val laborHoursItem: Boolean = false,
    val laborHoursRule: String = "",
    val havePendApproval: Boolean = false,
    val lowScoreWarn: Boolean = false,
    val unSubmitReason: String = "",
    val problemNumber: Int = 0
)

data class ScsCategoryDetail(
    val typeId: Int,
    val title: String,
    val basicScore: Double?,
    val extScore: Double?,
    val totalScore: Double?,
    val maxTotalScore: Double?,
    val items: List<ScsEvalItem> = emptyList()
)

data class ScsTermScore(
    val termId: Int,
    val yearId: Int,
    val yearName: String,
    val termName: String,
    val isFullYear: Boolean,
    val status: Int,
    val totalScore: Double?,
    val moral: Double?,
    val academic: Double?,
    val physical: Double?,
    val aesthetics: Double?,
    val labor: Double?,
    val isOpenSelfEva: Boolean = false,
    val details: List<ScsCategoryDetail> = emptyList()
)

data class ScsCurrentEvalWindow(
    val yearId: Int,
    val termId: Int,
    val yearName: String,
    val termName: String,
    val isOpen: Boolean,
    val startTime: String = "",
    val endTime: String = "",
    val message: String = ""
)

data class ScsActivityItem(
    val id: String,
    val title: String,
    val categoryName: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val location: String = "",
    val score: String = "",
    val status: String = ""
)

data class ScsSnapshot(
    val studentName: String = "",
    val studentId: String = "",
    val collegeName: String = "",
    val className: String = "",
    val gradeName: String = "",
    val currentWindow: ScsCurrentEvalWindow? = null,
    val dimensions: List<ScsEvalDimension> = emptyList(),
    val terms: List<ScsTermScore> = emptyList(),
    val activities: List<ScsActivityItem> = emptyList()
)

/** 官方单项自评申报/审核记录（来自 getStudentWebItemDetail -> studentDetails[]）。 */
data class ScsOriginDetailRecord(
    val id: Long,
    val itemId: Long,
    val score: Double?,
    val laborHours: Double? = null,
    val mark: String = "",
    val journalName: String = "",
    val certificates: List<String> = emptyList(),
    val auditStatus: String = "",
    val teacherComments: String = "",
    val createTime: String = "",
    val updateTime: String = "",
    val source: String = "",
    val canUpdate: Boolean = false,
    val canAudit: Boolean = false
)

/** 官方单项自评详情（来自 POST /scOriginScoreDetail/student/getStudentWebItemDetail）。 */
data class ScsItemSelfEvalDetail(
    val itemId: Long,
    val yearId: Int,
    val termId: Int,
    val shortDesc: String = "",
    val description: String = "",
    val itemScore: Double? = null,
    val itemMaxScore: Double? = null,
    val optionalScores: List<Double> = emptyList(),
    val reasonRequired: Boolean = false,
    val imageRequired: Boolean = false,
    val journalNameRequired: Boolean = false,
    val laborHoursItem: Boolean = false,
    val laborHoursApproved: Double? = null,
    val requiredHours: Double? = null,
    val laborHoursGateBlocked: Boolean = false,
    val laborHoursGateMessage: String = "",
    val singleSubmit: Boolean = false,
    val unSubmitReason: String = "",
    val records: List<ScsOriginDetailRecord> = emptyList()
)

/** 官方自评提交载荷（对应 POST /scOriginScoreDetail/student/updateOrCreateOriginDetail）。 */
data class ScsSelfEvalSubmitPayload(
    val yearId: Int,
    val termId: Int,
    val itemId: Long,
    val score: Double? = null,
    val laborHours: Double? = null,
    val mark: String = "",
    val journalName: String = "",
    val certificateUrls: List<String> = emptyList(),
    val relateOriginDetailId: Long? = null,
    val relateSubmitState: String? = null
)

/** 官方评定审核记录（来自 POST /scAuditOriginHistory/listHistories）。 */
data class ScsAuditHistoryItem(
    val id: Long,
    val itemDesc: String = "",
    val typeName: String = "",
    val score: Double? = null,
    val laborHours: Double? = null,
    val auditStatus: String = "",
    val mark: String = "",
    val teacherComments: String = "",
    val createTime: String = ""
)

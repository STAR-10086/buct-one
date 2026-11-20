package com.buct.course.academic

import com.buct.course.model.SchoolConfig

data class AcademicSystemSupport(val system: AcademicSystem, val name: String, val login: String, val limits: String)

object AcademicCapabilities {
    val SUPPORTED_SYSTEMS: String get() = "支持" + systems.joinToString("、") { it.name }
    const val ACCOUNT_LIMIT = "可添加不同学校，不限制学生账号数量；同一时间只运行当前账号的抢课任务。"
    const val SCHOOL_LIMIT = "选课时间、名额、学分、年级专业及课程冲突由学校决定。学校未开放的操作不能提交，未公布的数据不会补造。"
    val systems = listOf(
        AcademicSystemSupport(AcademicSystem.SOUTHSOFT, "南软研究生", "仅支持学号 + 密码 + 图片验证码；系统仅限校园网/VPN 访问。",
            "支持公共课、专业课、全校课程的查询与选退课；选课验证码、轮次和学分限制以学校返回为准。")
    )
    val selectableSystems: List<AcademicSystem> = listOf(AcademicSystem.AUTO) + systems.map { it.system }
    fun selectionIndex(id: String?): Int = selectableSystems.indexOf(system(id) ?: AcademicSystem.AUTO).coerceAtLeast(0)
    fun selectionLabel(type: AcademicSystem): String = if (type == AcademicSystem.AUTO) "自动识别" else name(type.id)
    fun selectedTypeId(currentId: String, selected: AcademicSystem): String =
        if (currentId == AcademicSystem.LEGACY_ZF.id && selected == AcademicSystem.ZF) currentId else selected.id
    fun system(id: String?): AcademicSystem? = AcademicSystem.fromId(id)?.let {
        if (it == AcademicSystem.LEGACY_ZF) AcademicSystem.ZF else it
    }
    fun support(id: String?): AcademicSystemSupport? = systems.firstOrNull { it.system == system(id) }
    fun name(id: String?): String = support(id)?.name ?: "自动识别教务系统"
    fun supportsParallel(school: SchoolConfig): Boolean = system(school.academicSystem) == AcademicSystem.ZF
    fun queueLimit(school: SchoolConfig): String = if (supportsParallel(school))
        "当前账号可串行或最多 2 门并行尝试；学校选课规则始终生效。" else
        "当前教务系统按账号串行提交，避免学校会话或表单参数互相覆盖。"
}

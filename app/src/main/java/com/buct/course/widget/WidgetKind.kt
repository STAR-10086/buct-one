package com.buct.course.widget

/**
 * 桌面小组件类型。
 *
 * 三种小组件共用一套实例级配置（标题 / 隐藏标题 / 显示账号），
 * 但兜底标题与配置页文案不一样，所以类型要能传下去，不能再用一个布尔 isDay 表示。
 */
enum class WidgetKind(
    /** 用户没有自定义标题、App 里也没设全局标题时的兜底名称。 */
    val defaultTitle: String,
    /** 配置页 / 管理页里给人看的类型名。 */
    val displayName: String
) {
    WEEK(defaultTitle = "我的课表", displayName = "课表"),
    DAY(defaultTitle = "今日课表", displayName = "今日课表"),
    TODO(defaultTitle = "待办事项", displayName = "待办事项")
}

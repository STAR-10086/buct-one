package com.buct.course.widget

import android.content.Context
import android.content.res.Configuration
import com.buct.course.R

/**
 * 小组件外观（深浅色 / 卡片配色）。
 *
 * 桌面小组件跑在桌面进程里，读不到 App 的主题状态，只能自己按
 * 「外观设置里的主题偏好 + 系统深色模式」算一遍，所以课表与待办共用这一份判定。
 */
internal object WidgetAppearance {

    private const val THEME_PREFS = "appearance_settings"
    private const val KEY_THEME_MODE = "theme_mode"

    fun isDarkMode(context: Context): Boolean =
        when (context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME_MODE, "system")) {
            "light" -> false
            "dark" -> true
            else -> isSystemDark(context)
        }

    private fun isSystemDark(context: Context): Boolean {
        val flags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return flags == Configuration.UI_MODE_NIGHT_YES
    }

    fun backgroundRes(context: Context): Int =
        if (isDarkMode(context)) R.drawable.widget_bg_dark else R.drawable.widget_bg_light

    /** 主文字色：深色模式纯白，浅色模式近黑。 */
    fun textColor(context: Context): Int =
        if (isDarkMode(context)) 0xFFFFFFFF.toInt() else 0xFF1C1C1E.toInt()

    /** 次要文字色（时间 / 教室 / 空状态）。 */
    fun secondaryTextColor(context: Context): Int =
        if (isDarkMode(context)) 0xCCFFFFFF.toInt() else 0xFF6E6E73.toInt()

    /** 课程卡片背景：按课程名稳定取色，同一天同一门课永远同色，不依赖刷新顺序。 */
    fun courseCardRes(courseName: String): Int {
        val index = (courseName.hashCode().rem(CARD_RES.size) + CARD_RES.size).rem(CARD_RES.size)
        return CARD_RES[index]
    }

    private val CARD_RES = intArrayOf(
        R.drawable.widget_course_card_indigo,
        R.drawable.widget_course_card_blue,
        R.drawable.widget_course_card_green,
        R.drawable.widget_course_card_orange,
        R.drawable.widget_course_card_purple,
        R.drawable.widget_course_card_red,
        R.drawable.widget_course_card_cyan,
        R.drawable.widget_course_card_brown
    )
}

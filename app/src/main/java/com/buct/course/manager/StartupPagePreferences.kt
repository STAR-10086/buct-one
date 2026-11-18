package com.buct.course.manager

import android.content.Context
import android.content.SharedPreferences

enum class StartupPage(val route: String, val label: String) {
    Schedule("schedule", "课表"),
    Grades("grades", "成绩"),
    Dashboard("dashboard", "工作台"),
    Homework("homework", "作业"),
    Settings("settings", "我的"),
    /** 兼容历史入口：课程/抢课与好友不再占据默认底栏 */
    Courses("courses", "课程"),
    Grab("grab", "抢课"),
    Friends("friends", "好友");

    companion object {
        fun decode(value: String?): StartupPage {
            if (value == Grab.route) return Courses
            return entries.firstOrNull { it.route == value } ?: Dashboard
        }
    }
}

/** App preference shared across accounts; activity restoration still retains its current page. */
class StartupPagePreferences(private val preferences: SharedPreferences) {
    fun read(): StartupPage = StartupPage.decode(runCatching { preferences.getString(KEY, null) }.getOrNull())

    fun write(page: StartupPage) {
        preferences.edit().putString(KEY, page.route).apply()
    }

    companion object {
        private const val KEY = "startup_page"
        fun from(context: Context) = StartupPagePreferences(context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE))
    }
}

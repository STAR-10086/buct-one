package com.buct.one.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import com.buct.one.ui.dashboard.DashboardScreen
import com.buct.one.ui.placeholder.PlaceholderScreen
import com.buct.one.ui.schedule.ScheduleScreen
import com.buct.one.ui.system.BuctNavBar
import com.buct.one.ui.system.NavTab
import com.buct.one.ui.theme.BuctColors

/** 五栏：课表 · 成绩 · [工作台] · 作业 · 我的。 */
object Tabs {
    const val SCHEDULE = 0
    const val GRADES = 1
    const val WORKBENCH = 2
    const val ASSIGNMENTS = 3
    const val PROFILE = 4

    val items = listOf(
        NavTab("课表", Icons.Filled.DateRange),
        NavTab("成绩", Icons.Filled.List),
        NavTab("工作台", icon = null, center = true),
        NavTab("作业", Icons.Filled.CheckCircle),
        NavTab("我的", Icons.Filled.Person),
    )
}

/**
 * 应用外壳：画布 + 当前页 + 悬浮坞。
 * 页面自己不再画 Dock，避免每页各写一份。
 */
@Composable
fun AppRoot() {
    var selected by remember { mutableIntStateOf(Tabs.WORKBENCH) }

    Column(Modifier.fillMaxSize().background(WallpaperBrush)) {
        Box(Modifier.weight(1f)) {
            when (selected) {
                Tabs.SCHEDULE -> ScheduleScreen()
                Tabs.WORKBENCH -> DashboardScreen()
                Tabs.GRADES -> PlaceholderScreen("成绩", "按学期查看成绩与绩点，数据接入后在 M2 完成")
                Tabs.ASSIGNMENTS -> PlaceholderScreen("作业", "在线教学的作业与测试，M3 接入 THEOL 后可见")
                else -> PlaceholderScreen("我的", "账号、同步状态、学业工具与设置")
            }
        }
        BuctNavBar(
            tabs = Tabs.items,
            selected = selected,
            onSelect = { selected = it },
        )
    }
}

private val WallpaperBrush: Brush
    get() = Brush.linearGradient(
        0f to BuctColors.CanvasTop,
        0.34f to BuctColors.CanvasMid,
        0.62f to BuctColors.CanvasWarm,
        1f to BuctColors.CanvasBottom,
    )

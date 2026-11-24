package com.buct.course

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.buct.course.manager.AppThemeCoordinator
import com.buct.course.manager.UserManager
import com.buct.course.ui.screen.TheolCourseScreen
import com.buct.course.ui.system.GlassWindowHost
import com.buct.course.ui.theme.CourseSelectorTheme
import com.buct.one.data.sync.TheolSyncService

/**
 * 北化在线（THEOL / MEOL）独立 Activity：
 * 在读课程搜索 → 单元学习与课程资源只读浏览 → 在线预览与原生下载打开。
 * 支持跨 Activity 系统级预测性返回手势。
 */
class TheolCourseActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UserManager.getInstance().init(this)

        setContent {
            CourseSelectorTheme {
                GlassWindowHost {
                    val service = remember { TheolSyncService.getInstance(this@TheolCourseActivity) }
                    TheolCourseScreen(
                        service = service,
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    companion object {
        fun launch(context: Context) {
            context.startActivity(Intent(context, TheolCourseActivity::class.java))
        }
    }
}

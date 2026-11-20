package com.buct.course

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.buct.course.analytics.UmamiTracker
import com.buct.course.manager.AppThemeCoordinator
import com.buct.course.manager.UserManager
import com.buct.course.ui.screen.EmptyClassroomContent
import com.buct.course.ui.system.GlassWindowHost
import com.buct.course.ui.theme.CourseSelectorTheme

/**
 * 空闲教室查询独立 Activity：
 * 与校园卡、第二课堂、实践教学、体测、邮箱、i北化、教务消息保持一致的独立 Activity 形态，
 * 获得完整的 Android 系统级跨 Activity 预测性返回手势（缩回下层工作台 / 课表预览）。
 */
class EmptyClassroomActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UserManager.getInstance().init(this)
        UmamiTracker.trackPageVisit(this, "/module/empty_classroom", "空闲教室")

        val semester = intent.getStringExtra(EXTRA_SEMESTER).orEmpty()
        val week = intent.getIntExtra(EXTRA_WEEK, 1).coerceIn(1, 20)
        val day = intent.getIntExtra(EXTRA_DAY, 1).coerceIn(1, 7)
        val period = intent.getIntExtra(EXTRA_PERIOD, 1).coerceIn(1, 12)
        val allowTimeSwitch = intent.getBooleanExtra(EXTRA_ALLOW_TIME_SWITCH, false)
        val initialCampusId = intent.getIntExtra(EXTRA_INITIAL_CAMPUS_ID, 2)

        setContent {
            CourseSelectorTheme {
                GlassWindowHost {
                    EmptyClassroomContent(
                        semester = semester,
                        week = week,
                        day = day,
                        period = period,
                        allowTimeSwitch = allowTimeSwitch,
                        initialCampusId = initialCampusId,
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_SEMESTER = "extra_semester"
        private const val EXTRA_WEEK = "extra_week"
        private const val EXTRA_DAY = "extra_day"
        private const val EXTRA_PERIOD = "extra_period"
        private const val EXTRA_ALLOW_TIME_SWITCH = "extra_allow_time_switch"
        private const val EXTRA_INITIAL_CAMPUS_ID = "extra_initial_campus_id"

        fun launch(
            context: Context,
            semester: String,
            week: Int,
            day: Int,
            period: Int,
            allowTimeSwitch: Boolean = false,
            initialCampusId: Int = 2
        ) {
            context.startActivity(
                Intent(context, EmptyClassroomActivity::class.java).apply {
                    putExtra(EXTRA_SEMESTER, semester)
                    putExtra(EXTRA_WEEK, week)
                    putExtra(EXTRA_DAY, day)
                    putExtra(EXTRA_PERIOD, period)
                    putExtra(EXTRA_ALLOW_TIME_SWITCH, allowTimeSwitch)
                    putExtra(EXTRA_INITIAL_CAMPUS_ID, initialCampusId)
                }
            )
        }
    }
}

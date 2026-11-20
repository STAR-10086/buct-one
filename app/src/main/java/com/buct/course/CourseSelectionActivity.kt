package com.buct.course

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.buct.course.analytics.UmamiTracker
import com.buct.course.manager.AppThemeCoordinator
import com.buct.course.manager.SmartSelector
import com.buct.course.manager.UserManager
import com.buct.course.network.CourseApiClient
import com.buct.course.ui.route.CourseSectionRoute
import com.buct.course.ui.system.GlassWindowHost
import com.buct.course.ui.system.LocalAppOverlayBottomInset
import com.buct.course.ui.system.LocalFloatingNotice
import com.buct.course.ui.system.LocalNoticeAnchor
import com.buct.course.ui.system.LocalPageDataState
import com.buct.course.ui.system.NoticeAnchorState
import com.buct.course.ui.system.PageDataViewModel
import com.buct.course.ui.theme.CourseSelectorTheme

/**
 * 选课与队列抢课独立 Activity：
 * 与校园卡、第二课堂、实践教学、体测、邮箱、i北化、教务消息保持一致的独立 Activity 形态，
 * 获得完整的 Android 系统级跨 Activity 预测性返回手势（缩回下层工作台预览）。
 */
class CourseSelectionActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UserManager.getInstance().init(this)
        SmartSelector.getInstance().init(this)
        CourseApiClient.getInstance().init(this)
        UmamiTracker.trackPageVisit(this, "/module/course_select", "选课抢课")

        val initialTab = intent.getIntExtra(EXTRA_INITIAL_TAB, 0).coerceIn(0, 1)

        setContent {
            CourseSelectorTheme {
                val session by UserManager.getInstance().sessionState.state.collectAsState()
                val accountKey = session.token.accountStorageKey
                val pageDataViewModel: PageDataViewModel = viewModel()
                val pageData = remember(accountKey) { pageDataViewModel.forAccount(accountKey) }
                val noticeAnchorState = remember { NoticeAnchorState() }

                GlassWindowHost {
                    CompositionLocalProvider(
                        LocalPageDataState provides pageData,
                        LocalNoticeAnchor provides noticeAnchorState,
                        LocalFloatingNotice provides null,
                        LocalAppOverlayBottomInset provides 0.dp
                    ) {
                        CourseSectionRoute(
                            initialTab = initialTab,
                            onBack = { finish() }
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_INITIAL_TAB = "extra_initial_tab"

        fun launch(context: Context, initialTab: Int = 0) {
            context.startActivity(
                Intent(context, CourseSelectionActivity::class.java).apply {
                    putExtra(EXTRA_INITIAL_TAB, initialTab.coerceIn(0, 1))
                }
            )
        }
    }
}

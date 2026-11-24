package com.buct.course

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.buct.course.manager.AppThemeCoordinator
import com.buct.course.manager.UserManager
import com.buct.course.ui.screen.CourseLearningCenterScreen
import com.buct.course.ui.screen.TheolFolderResourcesScreen
import com.buct.course.ui.screen.TheolUnitResourcesScreen
import com.buct.course.ui.system.GlassWindowHost
import com.buct.course.ui.theme.CourseSelectorTheme
import com.buct.one.data.model.TheolCourseInfo
import com.buct.one.data.model.TheolCourseUnit
import com.buct.one.data.sync.TheolSyncService

/**
 * 北化在线课程详情、单元内容与资源子目录独立 Activity。
 * 每级页面均由独立 Activity 承载，确保系统级跨 Activity 预测性返回手势生效。
 */
class TheolCourseDetailActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UserManager.getInstance().init(this)

        val lid = intent.getStringExtra(EXTRA_LID).orEmpty().trim()
        val courseName = intent.getStringExtra(EXTRA_COURSE_NAME).orEmpty()
        val unitColumnId = intent.getStringExtra(EXTRA_UNIT_COLUMN_ID).orEmpty()
        val unitTitle = intent.getStringExtra(EXTRA_UNIT_TITLE).orEmpty()
        val unitUrl = intent.getStringExtra(EXTRA_UNIT_URL).orEmpty()
        val folderUrl = intent.getStringExtra(EXTRA_FOLDER_URL).orEmpty()
        val folderTitle = intent.getStringExtra(EXTRA_FOLDER_TITLE).orEmpty()

        if (lid.isEmpty()) {
            finish()
            return
        }

        setContent {
            CourseSelectorTheme {
                GlassWindowHost {
                    val service = remember { TheolSyncService.getInstance(this@TheolCourseDetailActivity) }
                    when {
                        unitUrl.isNotBlank() -> {
                            TheolUnitResourcesScreen(
                                lid = lid,
                                courseName = courseName,
                                unit = TheolCourseUnit(
                                    columnId = unitColumnId,
                                    title = unitTitle,
                                    url = unitUrl
                                ),
                                service = service,
                                onBack = { finish() }
                            )
                        }
                        folderUrl.isNotBlank() -> {
                            TheolFolderResourcesScreen(
                                lid = lid,
                                courseName = courseName,
                                folderUrl = folderUrl,
                                folderTitle = folderTitle,
                                service = service,
                                onBack = { finish() }
                            )
                        }
                        else -> {
                            CourseLearningCenterScreen(
                                course = TheolCourseInfo(
                                    lid = lid,
                                    courseName = courseName,
                                    type = "course",
                                    url = ""
                                ),
                                service = service,
                                onBack = { finish() }
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_LID = "theol_course_lid"
        private const val EXTRA_COURSE_NAME = "theol_course_name"
        private const val EXTRA_UNIT_COLUMN_ID = "theol_unit_column_id"
        private const val EXTRA_UNIT_TITLE = "theol_unit_title"
        private const val EXTRA_UNIT_URL = "theol_unit_url"
        private const val EXTRA_FOLDER_URL = "theol_folder_url"
        private const val EXTRA_FOLDER_TITLE = "theol_folder_title"

        fun launchCourse(context: Context, lid: String, courseName: String) {
            context.startActivity(
                Intent(context, TheolCourseDetailActivity::class.java).apply {
                    putExtra(EXTRA_LID, lid)
                    putExtra(EXTRA_COURSE_NAME, courseName)
                }
            )
        }

        fun launchUnit(context: Context, lid: String, courseName: String, unit: TheolCourseUnit) {
            context.startActivity(
                Intent(context, TheolCourseDetailActivity::class.java).apply {
                    putExtra(EXTRA_LID, lid)
                    putExtra(EXTRA_COURSE_NAME, courseName)
                    putExtra(EXTRA_UNIT_COLUMN_ID, unit.columnId)
                    putExtra(EXTRA_UNIT_TITLE, unit.title)
                    putExtra(EXTRA_UNIT_URL, unit.url)
                }
            )
        }

        fun launchFolder(
            context: Context,
            lid: String,
            courseName: String,
            folderUrl: String,
            folderTitle: String
        ) {
            context.startActivity(
                Intent(context, TheolCourseDetailActivity::class.java).apply {
                    putExtra(EXTRA_LID, lid)
                    putExtra(EXTRA_COURSE_NAME, courseName)
                    putExtra(EXTRA_FOLDER_URL, folderUrl)
                    putExtra(EXTRA_FOLDER_TITLE, folderTitle)
                }
            )
        }
    }
}

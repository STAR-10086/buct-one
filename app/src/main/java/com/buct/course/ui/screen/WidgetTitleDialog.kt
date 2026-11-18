package com.buct.course.ui.screen

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.buct.course.manager.ScheduleSettingsManager
import com.buct.course.ui.system.GlassTextField
import com.buct.course.ui.system.SystemDialog
import com.buct.course.ui.system.SystemPrimaryButton
import com.buct.course.ui.system.SystemSecondaryButton
import com.buct.course.widget.WidgetProviders

private const val MAX_WIDGET_TITLE_LENGTH = 12

/**
 * 小组件全局默认标题。仅影响没有单独设置过标题的小组件实例；
 * 实例级标题在桌面点小组件标题进入的配置页里改。
 */
@Composable
fun WidgetTitleDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val manager = ScheduleSettingsManager.getInstance().apply { init(context) }
    var text by remember { mutableStateOf(manager.widgetTitle) }

    fun commit() {
        manager.widgetTitle = text.trim()
        refreshWidgetTitles(context)
    }

    SystemDialog(
        onDismissRequest = {
            commit()
            onDismiss()
        },
        title = {
            Text(
                text = "小组件默认标题",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "完成",
                onClick = {
                    commit()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = {
            SystemSecondaryButton(
                text = "恢复默认",
                onClick = {
                    text = ""
                    manager.widgetTitle = ""
                    refreshWidgetTitles(context)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "只影响还没单独起名的小组件，留空则用「我的课表 / 今日课表」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            GlassTextField(
                value = text,
                onValueChange = {
                    if (it.length <= MAX_WIDGET_TITLE_LENGTH) text = it
                },
                placeholder = "我的课表",
                singleLine = true
            )
        }
    }
}

/** 两种小组件都重建一次（同时覆盖新旧兼容壳组件）。 */
private fun refreshWidgetTitles(context: Context) {
    WidgetProviders.refreshAll(context)
}

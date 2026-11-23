package com.buct.course.ui.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.buct.course.R
import com.buct.course.manager.AppThemeCoordinator
import com.buct.course.ui.system.InsetGroupedSection
import com.buct.course.ui.system.SystemIconButton
import com.buct.course.ui.system.SystemTopBar
import com.buct.course.ui.system.GlassTextField
import com.buct.course.ui.system.SystemPrimaryButton
import com.buct.course.ui.system.SystemSecondaryButton
import com.buct.course.ui.theme.CourseSelectorTheme
import com.buct.course.widget.ScheduleDayWidget
import com.buct.course.widget.ScheduleWidget
import com.buct.course.widget.TodayWidget
import com.buct.course.widget.TodoWidget
import com.buct.course.widget.WidgetConfigStore
import com.buct.course.widget.WidgetKind
import com.buct.course.widget.WidgetProviders

/** 标题输入上限（与 ScheduleSettingsManager.widgetTitle 的 take(12) 保持一致）。 */
private const val MAX_TITLE_LENGTH = 12

/**
 * 单个桌面小组件的配置页（Android 12+ 的 appwidget configure 入口）。
 *
 * 从桌面「添加小组件」或点击小组件标题进入：
 * - 只设置标题（实例级，覆盖全局默认；留空回落到全局默认/兜底文案）
 * - 课表类小组件还能选显示哪个账号的课表
 * - 保存后立即刷新该实例
 * - 首次添加时由系统把 EXTRA_APPWIDGET_ID 带进来，返回 RESULT_OK 才真正落桌面
 */
class WidgetConfigActivity : ComponentActivity() {

    companion object {
        /** 组件类型（[WidgetKind] 的名字）；老版本只有 EXTRA_IS_DAY，仍然兼容。 */
        const val EXTRA_WIDGET_KIND = "extra_widget_kind"
        const val EXTRA_IS_DAY = "extra_is_day"

        fun start(context: Context, appWidgetId: Int, kind: WidgetKind) {
            context.startActivity(
                Intent(context, WidgetConfigActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    .putExtra(EXTRA_WIDGET_KIND, kind.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var kind = WidgetKind.WEEK

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        kind = resolveKind()
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // 首次添加：默认按「取消添加」返回，用户点保存才改成 RESULT_OK
        setResult(
            Activity.RESULT_CANCELED,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        )

        setContent {
            CourseSelectorTheme {
                WidgetConfigPage(
                    appWidgetId = appWidgetId,
                    kind = kind,
                    onCancel = { finish() },
                    onSave = { rawTitle, hideTitle, accountKey ->
                        save(rawTitle.trim(), hideTitle, accountKey)
                        finish()
                    }
                )
            }
        }
    }

    /**
     * 桌面「添加小组件」进来时系统只带 EXTRA_APPWIDGET_ID，不带我们的类型参数；
     * 先看 extra，再按 provider 类名 / 实例列表反查，确保今日课表、待办事项都能认出来。
     */
    private fun resolveKind(): WidgetKind {
        val raw = intent?.getStringExtra(EXTRA_WIDGET_KIND)
        WidgetKind.values().firstOrNull { it.name == raw }?.let { return it }
        if (intent?.getBooleanExtra(EXTRA_IS_DAY, false) == true) return WidgetKind.DAY
        return runCatching {
            val manager = AppWidgetManager.getInstance(this)
            val provider = manager.getAppWidgetInfo(appWidgetId)?.provider?.className.orEmpty()
            when {
                provider.contains("TodoWidget") ||
                    WidgetProviders.todoIds(this, manager).contains(appWidgetId) -> WidgetKind.TODO
                provider.contains("TodayWidget") ||
                    WidgetProviders.todayIds(this, manager).contains(appWidgetId) -> WidgetKind.TODAY
                provider.contains("ScheduleDayWidget") ||
                    WidgetProviders.dayIds(this, manager).contains(appWidgetId) -> WidgetKind.DAY
                else -> WidgetKind.WEEK
            }
        }.getOrDefault(WidgetKind.WEEK)
    }

    private fun save(title: String, hideTitle: Boolean, accountKey: String?) {
        val ctx = applicationContext
        val manager = AppWidgetManager.getInstance(ctx)
        // exported=true 是为了让桌面拉起本页，代价是任何 App 都能传 appWidgetId 进来；
        // 只允许改本 App 真实存在的实例（部分桌面会在 bind 之前就拉起配置页，所以再兜一层 provider 归属判断）。
        val belongsToUs = WidgetProviders.allIds(ctx, manager).contains(appWidgetId) ||
            runCatching {
                manager.getAppWidgetInfo(appWidgetId)?.provider?.packageName == ctx.packageName
            }.getOrDefault(false)
        if (!belongsToUs) {
            finish()
            return
        }
        WidgetConfigStore.setTitle(ctx, appWidgetId, title)
        WidgetConfigStore.setHideTitle(ctx, appWidgetId, hideTitle)
        WidgetConfigStore.setAccount(ctx, appWidgetId, accountKey)
        WidgetConfigStore.setSource(ctx, appWidgetId, null)

        runCatching {
            when (kind) {
                WidgetKind.DAY -> {
                    ScheduleDayWidget.updateAppWidget(ctx, manager, appWidgetId)
                    manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_day_list)
                }
                WidgetKind.TODAY -> {
                    TodayWidget.updateAppWidget(ctx, manager, appWidgetId)
                    manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.today_list)
                }
                WidgetKind.TODO -> {
                    TodoWidget.updateAppWidget(ctx, manager, appWidgetId)
                    manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_todo_list)
                }
                WidgetKind.WEEK -> {
                    ScheduleWidget.updateAppWidget(ctx, manager, appWidgetId)
                    manager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list)
                }
            }
        }

        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        )
    }
}

@Composable
private fun WidgetConfigPage(
    appWidgetId: Int,
    kind: WidgetKind,
    onCancel: () -> Unit,
    onSave: (String, Boolean, String?) -> Unit
) {
    val context = LocalContext.current
    val effectiveDefault = remember(appWidgetId, kind) {
        WidgetConfigStore.resolveTitle(context, appWidgetId, kind)
    }
    var title by remember(appWidgetId) {
        mutableStateOf(WidgetConfigStore.title(context, appWidgetId) ?: "")
    }
    var hideTitle by remember(appWidgetId) {
        mutableStateOf(WidgetConfigStore.hideTitle(context, appWidgetId))
    }
    val accounts = remember(appWidgetId) {
        runCatching {
            com.buct.course.manager.UserManager.getInstance().apply { init(context) }.savedAccounts
        }.getOrNull().orEmpty()
    }
    var accountKey by remember(appWidgetId) {
        mutableStateOf(WidgetConfigStore.accountKey(context, appWidgetId))
    }
    val previewTitle = title.ifBlank { effectiveDefault }
    val isScheduleWidget = kind != WidgetKind.TODO

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding()
    ) {
        SystemTopBar(
            title = kind.displayName + "小组件",
            subtitle = "只对当前这一个小组件生效",
            navigationIcon = {
                SystemIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onCancel)
            }
        )

        Box(Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // 标题输入
                InsetGroupedSection(
                    header = "小组件标题",
                    footer = "在桌面点小组件标题可随时回来修改；最多 $MAX_TITLE_LENGTH 个字，留空则显示「$effectiveDefault」。"
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        GlassTextField(
                            value = title,
                            onValueChange = {
                                if (it.length <= MAX_TITLE_LENGTH) title = it
                            },
                            placeholder = effectiveDefault,
                            singleLine = true
                        )
                    }
                    com.buct.course.ui.system.InsetGroupedRow(
                        title = "隐藏顶部标题（${kind.displayName}）",
                        subtitle = when (kind) {
                            WidgetKind.TODO -> "开启后不再显示标题与统计，整块高度都留给课程名"
                            WidgetKind.TODAY -> "开启后释放顶部空间；2×1 形态本来就没有标题栏"
                            else -> "开启后释放顶部空间，点日期/周次栏仍可回到本配置页"
                        },
                        showDivider = false,
                        trailing = {
                            com.buct.course.ui.system.LiquidSwitch(
                                checked = hideTitle,
                                onCheckedChange = { hideTitle = it }
                            )
                        }
                    )
                }

                // 账号选择：待办事项读的是北化在线待办，不跟课表账号走
                if (isScheduleWidget && accounts.size > 1) {
                    InsetGroupedSection(
                        header = "显示哪个账号的课表",
                        footer = "选定后，切换 App 内账号不会影响这个小组件。"
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = accountKey == null,
                                onClick = { accountKey = null },
                                label = { Text("跟随当前账号") }
                            )
                            // 同一个人的多个账号会显示成两个一模一样的名字，加学号后 4 位区分
                            val duplicateNames = accounts.groupBy { it.getDisplayName() }
                                .filterValues { it.size > 1 }.keys
                            accounts.forEach { record ->
                                val name = record.getDisplayName()
                                val label = if (name in duplicateNames) {
                                    val tail = record.getAccountIdText().takeLast(4)
                                    if (tail.isBlank()) name else "$name·$tail"
                                } else name
                                FilterChip(
                                    selected = accountKey == record.key,
                                    onClick = { accountKey = record.key },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }

                // 预览：按小组件真实排版画一个缩小版，比两行文字直观
                InsetGroupedSection(
                    header = "预览",
                    footer = when (kind) {
                        WidgetKind.TODAY -> "2×1 只显示一堂课（默认下一节课），拉到 4×2 显示当天完整列表"
                        WidgetKind.DAY -> "可前后翻天：今天之外的日子也能翻着看"
                        WidgetKind.TODO -> "一行一门课，汇总北化在线的待交作业与未完成测验"
                        WidgetKind.WEEK -> "内容：本机账号课表"
                    }
                ) {
                    WidgetMiniPreview(
                        title = previewTitle,
                        kind = kind,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.weight(1f)) {
                SystemSecondaryButton(
                    text = "取消",
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Box(Modifier.weight(1f)) {
                SystemPrimaryButton(
                    text = "保存",
                    onClick = { onSave(title, hideTitle, accountKey) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

/**
 * 小组件缩小版预览。
 *
 * 之前这里只显示两行文字，用户以为是「预览坏了」；实际桌面小组件是网格 + 课程卡片，
 * 这里按同样的构图画一个静态示意，让用户在保存前就能看出大致长相。
 */
@Composable
private fun WidgetMiniPreview(
    title: String,
    kind: WidgetKind,
    modifier: Modifier = Modifier
) {
    val cardColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val blockColors = listOf(
        Color(0xFF5C6BC0), Color(0xFF42A5F5), Color(0xFF66BB6A),
        Color(0xFFFFA726), Color(0xFFAB47BC), Color(0xFF26C6DA)
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(cardColor)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title.ifBlank { kind.defaultTitle },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )

        when (kind) {
            WidgetKind.DAY, WidgetKind.TODAY -> {
                // 2×1：单节卡片；下面再示意 4×2 的当天列表
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(blockColors[0].copy(alpha = 0.75f))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "高等数学 · 第1-2节 · 教301",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1
                    )
                }
                repeat(2) { index ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${index * 2 + 1}-${index * 2 + 2}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(28.dp)
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(22.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(blockColors[(index + 1) % blockColors.size].copy(alpha = 0.6f))
                        )
                    }
                }
            }
            WidgetKind.TODO -> {
                repeat(3) { index ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(blockColors[index % blockColors.size])
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = listOf("高等数学", "大学物理", "程序设计")[index],
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "作业 ${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            WidgetKind.WEEK -> {
                // 周课表：5 列 × 4 行迷你网格
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(4) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            repeat(5) { col ->
                                val filled = (row * 5 + col) % 3 == 0
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(26.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (filled) blockColors[(row + col) % blockColors.size].copy(alpha = 0.75f)
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

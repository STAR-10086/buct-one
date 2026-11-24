package com.buct.course

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import com.buct.course.analytics.UmamiTracker
import com.buct.course.importer.TaskTableParser
import com.buct.course.importer.XlsxSheetReader
import com.buct.course.manager.AppThemeCoordinator
import com.buct.course.manager.CustomExamStore
import com.buct.course.manager.UserManager
import com.buct.course.model.CustomExam
import com.buct.course.ui.screen.GlassFilterChip
import com.buct.course.ui.system.GlassLoadingIndicator
import com.buct.course.ui.system.GlassTextField
import com.buct.course.ui.system.GlassToaster
import com.buct.course.ui.system.GlassWindowHost
import com.buct.course.ui.system.SystemDialog
import com.buct.course.ui.system.SystemEmptyState
import com.buct.course.ui.system.SystemIconButton
import com.buct.course.ui.system.SystemPrimaryButton
import com.buct.course.ui.system.SystemSecondaryButton
import com.buct.course.ui.system.SystemTopBar
import com.buct.course.ui.system.glassBorderColor
import com.buct.course.ui.system.glassSurfaceColor
import com.buct.course.ui.theme.CourseSelectorTheme
import com.buct.one.data.model.ImportDiffStatus
import com.buct.one.data.model.ImportedExamDraft
import com.buct.one.data.model.ImportedTaskDraft
import com.buct.one.data.model.TaskImportReport
import com.buct.one.data.sync.TheolSyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * 班级任务汇总表（.xlsx / .csv）导入审核页：
 * 独立 ComponentActivity + GlassWindowHost，支持选择文件、标准模板导出分享与逐条审核入库。
 */
class TaskImportActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UserManager.getInstance().init(this)
        UmamiTracker.trackPageVisit(this, "/module/task_import", "表格导入作业")

        val initialFilePath = intent?.getStringExtra(EXTRA_FILE_PATH)?.takeIf { it.isNotBlank() }
        val initialFileName = intent?.getStringExtra(EXTRA_FILE_NAME)?.takeIf { it.isNotBlank() }
        val initialUri = resolveIncomingUri(intent)

        setContent {
            CourseSelectorTheme {
                GlassWindowHost {
                    TaskImportScreen(
                        initialUri = initialUri,
                        initialFilePath = initialFilePath,
                        initialFileName = initialFileName,
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    private fun resolveIncomingUri(incoming: Intent?): Uri? {
        if (incoming == null) return null
        return when (incoming.action) {
            Intent.ACTION_VIEW -> incoming.data
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(incoming, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: incoming.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            }
            else -> incoming.data
        }
    }

    companion object {
        private const val EXTRA_FILE_PATH = "extra_file_path"
        private const val EXTRA_FILE_NAME = "extra_file_name"

        fun launch(context: Context) {
            context.startActivity(Intent(context, TaskImportActivity::class.java))
        }

        fun launchWithFile(context: Context, filePath: String, fileName: String) {
            context.startActivity(
                Intent(context, TaskImportActivity::class.java).apply {
                    putExtra(EXTRA_FILE_PATH, filePath)
                    putExtra(EXTRA_FILE_NAME, fileName)
                }
            )
        }
    }
}

private enum class ImportFilterMode(val label: String) {
    ALL("全部"),
    PENDING_ONLY("仅待导入"),
    HIDE_THEOL("隐藏北化在线"),
    HIDE_EXPIRED("隐藏已过期")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskImportScreen(
    initialUri: Uri? = null,
    initialFilePath: String? = null,
    initialFileName: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val theolService = remember { TheolSyncService.getInstance(context) }

    var loading by remember { mutableStateOf(false) }
    var parseError by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<TaskImportReport?>(null) }
    var filterMode by remember { mutableStateOf(ImportFilterMode.ALL) }
    var editingTask by remember { mutableStateOf<ImportedTaskDraft?>(null) }

    val selectedTasks = remember { mutableStateMapOf<String, Boolean>() }
    val selectedExams = remember { mutableStateMapOf<String, Boolean>() }

    fun applyParsedReport(parsed: TaskImportReport) {
        report = parsed
        selectedTasks.clear()
        selectedExams.clear()
        parsed.tasks.forEach { selectedTasks[it.tempId] = it.selectedByDefault }
        parsed.exams.forEach { selectedExams[it.tempId] = it.selectedByDefault }
        if (parsed.tasks.isEmpty() && parsed.exams.isEmpty()) {
            parseError = "未识别到可导入的作业或考试行，请确认表头包含「课程名称 / 任务内容 / 截止时间」"
        }
    }

    fun loadFromUri(uri: Uri) {
        scope.launch {
            loading = true
            parseError = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val fileName = resolveDisplayName(context, uri)
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("无法读取所选文件")
                    val sheets = XlsxSheetReader.readBytes(bytes, fileName)
                    val theolSnap = theolService.theolState.value.data
                    TaskTableParser.parse(
                        sheets = sheets,
                        sourceName = fileName,
                        existingTodos = theolService.todosState.value,
                        existingTheolHomeworks = theolSnap?.homeworks.orEmpty(),
                        existingTheolTests = theolSnap?.tests.orEmpty(),
                        existingExams = CustomExamStore.list(context)
                    )
                }
            }
            loading = false
            result.onSuccess(::applyParsedReport).onFailure { err ->
                parseError = err.message ?: "表格解析失败"
            }
        }
    }

    LaunchedEffect(initialUri, initialFilePath) {
        when {
            !initialFilePath.isNullOrBlank() -> {
                loading = true
                parseError = null
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val file = File(initialFilePath)
                        if (!file.exists() || !file.isFile) throw IllegalStateException("附件文件不存在")
                        val name = initialFileName?.takeIf { it.isNotBlank() } ?: file.name
                        val bytes = file.readBytes()
                        val sheets = XlsxSheetReader.readBytes(bytes, name)
                        val theolSnap = theolService.theolState.value.data
                        TaskTableParser.parse(
                            sheets = sheets,
                            sourceName = name,
                            existingTodos = theolService.todosState.value,
                            existingTheolHomeworks = theolSnap?.homeworks.orEmpty(),
                            existingTheolTests = theolSnap?.tests.orEmpty(),
                            existingExams = CustomExamStore.list(context)
                        )
                    }
                }
                loading = false
                result.onSuccess(::applyParsedReport).onFailure { err ->
                    parseError = err.message ?: "表格解析失败"
                }
            }
            initialUri != null -> loadFromUri(initialUri)
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) loadFromUri(uri)
    }

    fun launchPicker() {
        picker.launch(
            arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "text/csv",
                "text/comma-separated-values",
                "text/plain",
                "application/octet-stream",
                "*/*"
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        SystemTopBar(
            title = "表格导入待办",
            subtitle = report?.let { "${it.sourceName} · ${it.tasks.size} 项任务 / ${it.exams.size} 场考试" }
                ?: "支持 .xlsx 多表 / 标准 .csv 模板",
            navigationIcon = {
                SystemIconButton(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack
                )
            },
            actions = {
                SystemIconButton(
                    icon = Icons.Outlined.Share,
                    contentDescription = "分享标准模板",
                    onClick = { shareStandardTemplate(context) }
                )
                SystemIconButton(
                    icon = Icons.Outlined.FileUpload,
                    contentDescription = "选择表格文件",
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = { launchPicker() }
                )
            }
        )

        val currentReport = report
        when {
            loading -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    GlassLoadingIndicator(size = 44.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("正在解析表格内容…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            currentReport == null || (currentReport.tasks.isEmpty() && currentReport.exams.isEmpty()) -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    SystemEmptyState(
                        title = if (parseError != null) "未能识别有效任务" else "选择班级任务表格",
                        message = parseError
                            ?: "直接兼容学习委员发的 .xlsx 多表（线下学习 / U校园 / 慕课等），也支持导出标准 .csv 模板给学委填写。"
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SystemPrimaryButton(
                            text = "选择表格文件",
                            onClick = { launchPicker() }
                        )
                        SystemSecondaryButton(
                            text = "分享标准模板",
                            onClick = { shareStandardTemplate(context) }
                        )
                    }
                }
            }

            else -> {
                val filteredTasks = remember(currentReport, filterMode) {
                    currentReport.tasks.filter { task ->
                        when (filterMode) {
                            ImportFilterMode.ALL -> true
                            ImportFilterMode.PENDING_ONLY ->
                                task.diffStatus == ImportDiffStatus.NEW || task.diffStatus == ImportDiffStatus.UPDATE_AVAILABLE
                            ImportFilterMode.HIDE_THEOL -> task.diffStatus != ImportDiffStatus.THEOL_DUPLICATE
                            ImportFilterMode.HIDE_EXPIRED -> task.diffStatus != ImportDiffStatus.EXPIRED
                        }
                    }
                }
                val filteredExams = remember(currentReport, filterMode) {
                    currentReport.exams.filter { exam ->
                        when (filterMode) {
                            ImportFilterMode.ALL -> true
                            ImportFilterMode.PENDING_ONLY -> exam.diffStatus == ImportDiffStatus.NEW
                            ImportFilterMode.HIDE_THEOL -> true
                            ImportFilterMode.HIDE_EXPIRED -> exam.diffStatus != ImportDiffStatus.EXPIRED
                        }
                    }
                }
                val checkedTaskCount = currentReport.tasks.count { selectedTasks[it.tempId] == true }
                val checkedExamCount = currentReport.exams.count { selectedExams[it.tempId] == true }

                Column(modifier = Modifier.weight(1f)) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ImportFilterMode.entries.forEach { mode ->
                            GlassFilterChip(
                                label = mode.label,
                                selected = filterMode == mode,
                                onClick = { filterMode = mode },
                                compact = true
                            )
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (filteredExams.isNotEmpty()) {
                            item {
                                Text(
                                    text = "考试安排（${filteredExams.size}）",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            items(filteredExams, key = { it.tempId }) { exam ->
                                ExamDraftCard(
                                    draft = exam,
                                    checked = selectedExams[exam.tempId] == true,
                                    onToggle = {
                                        selectedExams[exam.tempId] = !(selectedExams[exam.tempId] == true)
                                    }
                                )
                            }
                        }

                        if (filteredTasks.isNotEmpty()) {
                            item {
                                Text(
                                    text = "作业与线下任务（${filteredTasks.size}）",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            items(filteredTasks, key = { it.tempId }) { task ->
                                TaskDraftCard(
                                    draft = task,
                                    checked = selectedTasks[task.tempId] == true,
                                    onToggle = {
                                        selectedTasks[task.tempId] = !(selectedTasks[task.tempId] == true)
                                    },
                                    onEdit = { editingTask = task }
                                )
                            }
                        }
                    }
                }

                // 底部确认栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(glassSurfaceColor())
                        .border(0.5.dp, glassBorderColor())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "已选 $checkedTaskCount 项待办 · $checkedExamCount 场考试",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "北化在线已有与已过期默认不勾选",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    SystemPrimaryButton(
                        text = "确认导入",
                        enabled = checkedTaskCount + checkedExamCount > 0,
                        onClick = {
                            val chosenTasks = currentReport.tasks.filter { selectedTasks[it.tempId] == true }
                            val chosenExams = currentReport.exams.filter { selectedExams[it.tempId] == true }
                            val (addedTodo, updatedTodo) = theolService.upsertImportedTodos(chosenTasks)
                            val addedExam = saveImportedExams(context, chosenExams)
                            GlassToaster.show("已导入：新增 $addedTodo 待办、更新 $updatedTodo 待办、新增 $addedExam 场考试")
                            onBack()
                        }
                    )
                }
            }
        }
    }

    editingTask?.let { target ->
        EditTaskDraftDialog(
            draft = target,
            onDismiss = { editingTask = null },
            onSave = { updated ->
                report = report?.let { r ->
                    r.copy(tasks = r.tasks.map { if (it.tempId == updated.tempId) updated else it })
                }
                editingTask = null
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskDraftCard(
    draft: ImportedTaskDraft,
    checked: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = if (checked) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(22.dp)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = draft.cleanCourseName.ifBlank { "未命名课程" },
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                StatusBadge(draft.diffStatus)
            }
            Text(
                text = draft.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (draft.deadlineText.isNotBlank()) {
                    MetaTag("截止 ${draft.deadlineText}")
                }
                if (draft.submitWay.isNotBlank()) {
                    MetaTag(draft.submitWay)
                }
                if (draft.location.isNotBlank()) {
                    MetaTag(draft.location)
                }
                draft.warnings.forEach { warn ->
                    MetaTag(warn, highlight = Color(0xFFFF9500))
                }
            }
            if (draft.note.isNotBlank()) {
                Text(
                    text = draft.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        SystemIconButton(
            icon = Icons.Outlined.Edit,
            contentDescription = "编辑",
            onClick = onEdit
        )
    }
}

@Composable
private fun ExamDraftCard(
    draft: ImportedExamDraft,
    checked: Boolean,
    onToggle: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), shape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = if (checked) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Icon(
            imageVector = Icons.Outlined.Event,
            contentDescription = null,
            tint = Color(0xFFFF9500),
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = draft.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false)
                )
                StatusBadge(draft.diffStatus)
            }
            Text(
                text = listOf(draft.timeText, draft.location).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusBadge(status: ImportDiffStatus) {
    val color = when (status) {
        ImportDiffStatus.NEW -> Color(0xFF34C759)
        ImportDiffStatus.UPDATE_AVAILABLE -> MaterialTheme.colorScheme.primary
        ImportDiffStatus.ALREADY_IMPORTED -> MaterialTheme.colorScheme.onSurfaceVariant
        ImportDiffStatus.THEOL_DUPLICATE -> Color(0xFFFF9500)
        ImportDiffStatus.EXPIRED -> Color(0xFF8E8E93)
    }
    Text(
        text = status.label,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun MetaTag(text: String, highlight: Color? = null) {
    val color = highlight ?: MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun EditTaskDraftDialog(
    draft: ImportedTaskDraft,
    onDismiss: () -> Unit,
    onSave: (ImportedTaskDraft) -> Unit
) {
    var title by remember { mutableStateOf(draft.title) }
    var submitWay by remember { mutableStateOf(draft.submitWay) }
    var note by remember { mutableStateOf(draft.note) }

    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("调整待办内容")
            }
        },
        confirmButton = {
            SystemPrimaryButton(
                text = "保存",
                enabled = title.isNotBlank(),
                onClick = {
                    onSave(draft.copy(title = title.trim(), submitWay = submitWay.trim(), note = note.trim()))
                }
            )
        },
        dismissButton = {
            SystemSecondaryButton(text = "取消", onClick = onDismiss)
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = "任务内容",
                singleLine = false
            )
            GlassTextField(
                value = submitWay,
                onValueChange = { submitWay = it },
                placeholder = "提交方式 / 平台",
                singleLine = true
            )
            GlassTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = "备注",
                singleLine = false
            )
        }
    }
}

private fun saveImportedExams(context: Context, drafts: List<ImportedExamDraft>): Int {
    if (drafts.isEmpty()) return 0
    val existing = CustomExamStore.list(context)
    var added = 0
    for (draft in drafts) {
        val dup = existing.any { !it.hidden && abs(it.startAt - draft.startAt) <= 5 * 60_000L && it.course == draft.cleanCourseName }
        if (dup) continue
        CustomExamStore.save(
            context,
            CustomExam(
                title = draft.title,
                course = draft.cleanCourseName,
                startAt = draft.startAt,
                endAt = draft.endAt,
                location = draft.location,
                note = draft.note,
                tag = "表格导入"
            )
        )
        added++
    }
    if (added > 0) {
        runCatching { com.buct.course.schedule.ExamReminderScheduler.reschedule(context) }
    }
    return added
}

private fun resolveDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) {
            cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "tasks.xlsx"
}

private fun shareStandardTemplate(context: Context) {
    runCatching {
        val exportDir = File(context.cacheDir, "exports").apply { if (!exists()) mkdirs() }
        val file = File(exportDir, "BUCT-One-作业任务模板.csv")
        file.writeText(TaskTableParser.STANDARD_TEMPLATE_CSV, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "BUCT One 作业任务标准模板.csv")
            clipData = ClipData.newRawUri("BUCT-One-作业任务模板.csv", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(sendIntent, "分享标准作业模板"))
    }.onFailure {
        GlassToaster.show("导出模板失败：${it.message}")
    }
}

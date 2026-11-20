package com.buct.course.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buct.course.manager.UserManager
import com.buct.course.network.EmptyClassroom
import com.buct.course.network.EmptyClassroomApi
import com.buct.course.network.SessionExpiredException
import com.buct.course.ui.system.GlassLoadingState
import com.buct.course.ui.system.GlassPageScaffold
import com.buct.course.ui.system.GlassSubpage
import com.buct.course.ui.system.SecondaryVerificationOverlay
import com.buct.course.ui.system.SessionExpiredHint
import com.buct.course.ui.system.SystemActionButton
import com.buct.course.ui.system.SystemDivider
import com.buct.course.ui.system.SystemEmptyState
import com.buct.course.ui.system.SystemIconButton
import com.buct.course.ui.system.glassBorderColor
import com.buct.course.ui.system.glassSurfaceColor
import com.buct.course.utils.SessionRenewer
import com.buct.course.utils.SessionRepair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 空闲教室查询全屏液态玻璃页：
 * - 统一采用 [GlassSubpage] + [GlassPageScaffold] 与上游折射顶栏；
 * - 筛选栏（时间、校区、教学楼、楼层）默认收起为单行摘要胶囊，为教室列表留出充足可视空间。
 */
@Composable
fun EmptyClassroomDialog(
    semester: String,
    week: Int,
    day: Int,
    period: Int,
    allowTimeSwitch: Boolean = false,
    initialCampusId: Int = 2,
    onDismiss: () -> Unit
) {
    val school = remember { UserManager.getInstance().currentSchool }
    val context = LocalContext.current
    var selectedWeek by remember(week) { mutableIntStateOf(week.coerceIn(1, 20)) }
    var selectedDay by remember(day) { mutableIntStateOf(day.coerceIn(1, 7)) }
    var selectedPeriod by remember(period) { mutableIntStateOf(period.coerceIn(1, 12)) }
    var campusId by remember(initialCampusId) { mutableIntStateOf(initialCampusId) }
    var selectedBuilding by remember { mutableStateOf("全部") }
    var selectedFloor by remember { mutableStateOf("全部") }
    var filtersExpanded by remember { mutableStateOf(false) }

    var classrooms by remember { mutableStateOf<List<EmptyClassroom>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var expiredCode by remember { mutableIntStateOf(0) }
    var repairing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val dayText = listOf("一", "二", "三", "四", "五", "六", "日").getOrElse(selectedDay - 1) { selectedDay.toString() }

    LaunchedEffect(school, semester, selectedWeek, selectedDay, selectedPeriod, campusId, reload) {
        if (school == null) {
            error = "请先登录教务系统"
            return@LaunchedEffect
        }
        loading = true
        error = ""
        expiredCode = 0
        try {
            classrooms = EmptyClassroomApi.load(school, semester, selectedWeek, selectedDay, selectedPeriod, campusId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionExpiredException) {
            val canAuto = UserManager.getInstance().canAutoRelogin() &&
                com.buct.course.manager.SyncSettings.enabled(context)
            if (canAuto) {
                repairing = true
                val outcome = suspendCancellableCoroutine<SessionRepair.Outcome> { cont ->
                    SessionRepair.repair(context) { res ->
                        if (cont.isActive) cont.resume(res)
                    }
                }
                repairing = false
                if (outcome.ok) {
                    try {
                        classrooms = EmptyClassroomApi.load(
                            school, semester, selectedWeek, selectedDay, selectedPeriod, campusId
                        )
                    } catch (retryCancel: CancellationException) {
                        throw retryCancel
                    } catch (retryExpired: SessionExpiredException) {
                        classrooms = emptyList()
                        expiredCode = retryExpired.statusCode
                    } catch (retryErr: Exception) {
                        classrooms = emptyList()
                        error = retryErr.message ?: "查询失败，请稍后重试"
                    }
                } else {
                    classrooms = emptyList()
                    expiredCode = e.statusCode
                    error = outcome.message.orEmpty()
                }
            } else {
                classrooms = emptyList()
                expiredCode = e.statusCode
            }
        } catch (e: Exception) {
            classrooms = emptyList()
            error = e.message ?: "查询失败，请稍后重试"
        } finally {
            repairing = false
            loading = false
        }
    }

    val allLabel = "全部"
    val buildings = remember(classrooms) {
        classrooms.map { it.building.trim() }.filter { it.isNotBlank() }.distinct()
            .sortedWith(compareBy<String> { buildingFilterRank(it) }.thenBy { it })
    }
    val floors = remember(classrooms, selectedBuilding) {
        classrooms.filter { selectedBuilding == allLabel || it.building.trim() == selectedBuilding }
            .map { it.floor.trim() }.filter { it.isNotBlank() }.distinct()
            .sortedWith(compareBy<String> { it.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it })
    }
    val filteredClassrooms = remember(classrooms, selectedBuilding, selectedFloor) {
        classrooms.filter {
            (selectedBuilding == allLabel || it.building.trim() == selectedBuilding) &&
                (selectedFloor == allLabel || it.floor.trim() == selectedFloor)
        }
    }
    LaunchedEffect(buildings) {
        if (selectedBuilding != allLabel && selectedBuilding !in buildings) selectedBuilding = allLabel
    }
    LaunchedEffect(floors) {
        if (selectedFloor != allLabel && selectedFloor !in floors) selectedFloor = allLabel
    }

    val campusLabel = EmptyClassroomApi.campuses.firstOrNull { it.id == campusId }?.label ?: "全部校区"
    val hasActiveSubFilter = selectedBuilding != allLabel || selectedFloor != allLabel
    val filterSummary = buildList {
        add(campusLabel)
        add(if (selectedBuilding == allLabel) "全部楼宇" else selectedBuilding)
        if (selectedFloor != allLabel) {
            add(if (selectedFloor.endsWith("层")) selectedFloor else "${selectedFloor}层")
        }
    }.joinToString(" · ")

    GlassSubpage(onDismiss = onDismiss) { close ->
        Box(modifier = Modifier.fillMaxSize()) {
            GlassPageScaffold(
                title = "空闲教室",
                subtitle = "第 ${selectedWeek} 周 · 星期${dayText} · 第 ${selectedPeriod} 节",
                onBack = close,
                actions = {
                    SystemIconButton(
                        icon = Icons.Outlined.Refresh,
                        contentDescription = "刷新",
                        enabled = !loading,
                        onClick = { reload++ }
                    )
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            top = padding.calculateTopPadding() + 4.dp,
                            start = 16.dp,
                            end = 16.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 单行可收起筛选摘要条（默认收起，给下方列表留足查看空间）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(glassSurfaceColor())
                            .border(0.5.dp, glassBorderColor(), RoundedCornerShape(20.dp))
                            .clickable { filtersExpanded = !filtersExpanded }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Text(
                                text = filterSummary,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val countHint = when {
                                loading -> "正在查询可用教室…"
                                error.isNotBlank() || expiredCode != 0 -> "点击右侧展开筛选或重试"
                                else -> "共 ${filteredClassrooms.size} 间空闲教室" +
                                    if (filtersExpanded) " · 点击收起筛选" else " · 点击切换筛选"
                            }
                            Text(
                                text = countHint,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (hasActiveSubFilter) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                    .clickable {
                                        selectedBuilding = allLabel
                                        selectedFloor = allLabel
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "重置",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        val toggleHighlight = filtersExpanded || hasActiveSubFilter
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (toggleHighlight) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                )
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = null,
                                tint = if (toggleHighlight) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (filtersExpanded) "收起" else "筛选",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = if (toggleHighlight) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = if (filtersExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                tint = if (toggleHighlight) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    // 展开后的统一液态玻璃筛选面板
                    AnimatedVisibility(
                        visible = filtersExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(22.dp))
                                .background(glassSurfaceColor())
                                .border(0.5.dp, glassBorderColor(), RoundedCornerShape(22.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (allowTimeSwitch) {
                                EmptyClassroomTimePicker(
                                    selectedWeek = selectedWeek,
                                    selectedDay = selectedDay,
                                    selectedPeriod = selectedPeriod,
                                    onWeekChange = {
                                        if (selectedWeek != it) {
                                            selectedWeek = it
                                            selectedBuilding = allLabel
                                            selectedFloor = allLabel
                                        }
                                    },
                                    onDayChange = {
                                        if (selectedDay != it) {
                                            selectedDay = it
                                            selectedBuilding = allLabel
                                            selectedFloor = allLabel
                                        }
                                    },
                                    onPeriodChange = {
                                        if (selectedPeriod != it) {
                                            selectedPeriod = it
                                            selectedBuilding = allLabel
                                            selectedFloor = allLabel
                                        }
                                    }
                                )
                                SystemDivider(alpha = 0.35f)
                            }

                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "校区",
                                    modifier = Modifier.width(50.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    EmptyClassroomApi.campuses.forEach { option ->
                                        GlassFilterPill(
                                            label = option.label,
                                            selected = campusId == option.id,
                                            onClick = {
                                                if (campusId != option.id) {
                                                    campusId = option.id
                                                    selectedBuilding = allLabel
                                                    selectedFloor = allLabel
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            FilterSection(
                                label = "教学楼",
                                options = buildings,
                                selected = selectedBuilding,
                                onSelect = { selectedBuilding = it; selectedFloor = allLabel }
                            )

                            FilterSection(
                                label = "楼层",
                                options = floors,
                                selected = selectedFloor,
                                onSelect = { selectedFloor = it },
                                format = { floor -> if (floor.endsWith("层")) floor else "${floor}层" }
                            )
                        }
                    }

                    // 教室结果列表区（独占剩余视口）
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        when {
                            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                GlassLoadingState(text = if (repairing) "正在自动恢复登录状态…" else "正在查询空闲教室…")
                            }

                            expiredCode != 0 -> Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(glassSurfaceColor())
                                    .border(0.5.dp, glassBorderColor(), RoundedCornerShape(24.dp))
                                    .padding(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                SessionExpiredHint(
                                    title = "登录状态已失效（$expiredCode）",
                                    subtitle = error.ifBlank { "教务登录过期了，更新一下就能继续查" },
                                    repairing = repairing,
                                    onRepair = {
                                        repairing = true
                                        error = ""
                                        SessionRepair.repair(context) { outcome ->
                                            repairing = false
                                            if (outcome.ok) {
                                                reload++
                                            } else {
                                                error = outcome.message ?: "更新失败，请稍后重试"
                                            }
                                        }
                                    }
                                )
                            }

                            error.isNotBlank() -> SystemEmptyState(
                                title = "空闲教室查询失败",
                                message = error,
                                icon = Icons.Outlined.MeetingRoom,
                                action = {
                                    SystemActionButton(
                                        text = "重新查询",
                                        primary = true,
                                        onClick = { reload++ }
                                    )
                                }
                            )

                            classrooms.isEmpty() -> SystemEmptyState(
                                title = "当前时段暂无空闲教室",
                                message = "可展开上方筛选条切换校区、周次或节次重新查询。",
                                icon = Icons.Outlined.MeetingRoom,
                                action = if (!filtersExpanded) {
                                    {
                                        SystemActionButton(
                                            text = "展开筛选",
                                            onClick = { filtersExpanded = true }
                                        )
                                    }
                                } else null
                            )

                            filteredClassrooms.isEmpty() -> SystemEmptyState(
                                title = "当前筛选下无教室",
                                message = "请清除楼宇或楼层筛选条件后再试。",
                                icon = Icons.Outlined.MeetingRoom,
                                action = {
                                    SystemActionButton(
                                        text = "清除筛选",
                                        primary = true,
                                        onClick = {
                                            selectedBuilding = allLabel
                                            selectedFloor = allLabel
                                        }
                                    )
                                }
                            )

                            else -> LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 20.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(filteredClassrooms, key = { it.id }) { room ->
                                    EmptyClassroomRow(room)
                                }
                            }
                        }
                    }
                }
            }

            val verification by SessionRenewer.verification.collectAsState()
            SecondaryVerificationOverlay(
                state = verification,
                onRememberChange = { SessionRenewer.setRememberDevice(it) },
                onSendCode = { captcha -> SessionRenewer.sendSecondarySms(captcha) },
                onSubmit = { code -> SessionRenewer.submitSecondarySms(code) },
                onDismiss = { SessionRenewer.cancelVerification() }
            )
        }
    }
}

private fun buildingFilterRank(name: String): Int {
    val value = name.trim()
    return when {
        value == "教学楼" -> 0
        value == "实验楼" -> 1
        value == "体育馆" -> 2
        value.contains("第一教学楼") -> 3
        value.contains("第二教学楼") -> 4
        else -> 5
    }
}

@Composable
private fun GlassFilterPill(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.primary
                    else -> glassSurfaceColor()
                }
            )
            .border(
                width = 0.5.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else glassBorderColor(),
                shape = shape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                selected -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun EmptyClassroomRow(room: EmptyClassroom) {
    val location = listOf(room.building, room.campus, room.floor.takeIf { it.isNotBlank() }?.let { "${it}层" } ?: "")
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    val cardShape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), cardShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.MeetingRoom,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = room.name,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (location.isNotBlank()) {
                Text(
                    text = location,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (room.code.isNotBlank() && room.code != room.name) {
                Text(
                    text = "编号 ${room.code}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "${room.seats} 座",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = room.type.ifBlank { "教室" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FilterSection(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    format: (String) -> String = { it }
) {
    if (options.isEmpty()) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.width(50.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GlassFilterPill(
                label = "全部",
                selected = selected == "全部",
                onClick = { onSelect("全部") }
            )
            options.forEach { option ->
                GlassFilterPill(
                    label = format(option),
                    selected = selected == option,
                    onClick = { onSelect(option) }
                )
            }
        }
    }
}

@Composable
private fun EmptyClassroomTimePicker(
    selectedWeek: Int,
    selectedDay: Int,
    selectedPeriod: Int,
    onWeekChange: (Int) -> Unit,
    onDayChange: (Int) -> Unit,
    onPeriodChange: (Int) -> Unit
) {
    val weekDays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "周次",
                modifier = Modifier.width(50.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassFilterPill(
                    label = "上一周",
                    selected = false,
                    enabled = selectedWeek > 1,
                    onClick = { onWeekChange((selectedWeek - 1).coerceAtLeast(1)) }
                )
                GlassFilterPill(
                    label = "第 $selectedWeek 周",
                    selected = true,
                    onClick = {}
                )
                GlassFilterPill(
                    label = "下一周",
                    selected = false,
                    enabled = selectedWeek < 20,
                    onClick = { onWeekChange((selectedWeek + 1).coerceAtMost(20)) }
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "星期",
                modifier = Modifier.width(50.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                weekDays.forEachIndexed { index, label ->
                    val d = index + 1
                    GlassFilterPill(
                        label = label,
                        selected = selectedDay == d,
                        onClick = { onDayChange(d) }
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "节次",
                modifier = Modifier.width(50.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (p in 1..12) {
                    GlassFilterPill(
                        label = "第${p}节",
                        selected = selectedPeriod == p,
                        onClick = { onPeriodChange(p) }
                    )
                }
            }
        }
    }
}

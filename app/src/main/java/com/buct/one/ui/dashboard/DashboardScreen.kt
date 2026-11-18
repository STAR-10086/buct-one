package com.buct.one.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.buct.one.ui.system.BuctNavBar
import com.buct.one.ui.system.Capsules
import com.buct.one.ui.system.GlassCapsule
import com.buct.one.ui.system.GlassCapsuleRow
import com.buct.one.ui.system.GlassPanel
import com.buct.one.ui.system.NavTab
import com.buct.one.ui.system.StatNumber
import com.buct.one.ui.theme.BuctColors
import com.buct.one.ui.theme.LocalGlass

private data class TodoItem(val title: String, val due: String, val urgent: Boolean)

/**
 * 工作台首屏。
 * M1 阶段全部是演示数据——数据层（CampusSource / CampusStore）在 M2 接入，
 * 届时这里只换数据来源，不改布局。
 */
@Composable
fun DashboardScreen() {
    var selected by remember { mutableIntStateOf(2) }  // 默认停在工作台
    // 五栏：课表 · 成绩 · [工作台] · 作业 · 我的
    val tabs = listOf(
        NavTab("课表", Icons.Filled.DateRange),
        NavTab("成绩", Icons.Filled.List),
        NavTab("工作台", icon = null, center = true),
        NavTab("作业", Icons.Filled.CheckCircle),
        NavTab("我的", Icons.Filled.Person),
    )

    Box(Modifier.fillMaxSize().background(WallpaperBrush)) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = 96.dp),  // 悬浮坞会盖住底部约 98dp
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Header()
                TopCapsuleRow()
                NextClassCard()
                TodoCard(
                    listOf(
                        TodoItem("数据结构作业", "今天 23:59", true),
                        TodoItem("选课确认", "周五", false),
                    )
                )
                ExamCard()
                UnreadCard(unreadTotal = 8, noticeCount = 3, mailCount = 5)
                WeekCard(current = 12, total = 20, note = "期末还有 5 周 · 寒假 1月12日")
                EntryGrid()
            }
            BuctNavBar(
                tabs = tabs,
                selected = selected,
                onSelect = { selected = it },
                modifier = Modifier.navigationBarsPadding(),
            )
        }
    }
}

/**
 * 壁纸：截图里是蓝→紫→青的高亮渐变，四角压低、中间提亮。
 * 用四段线性渐变近似，避免引入图片资源。
 */
private val WallpaperBrush: Brush
    get() = Brush.linearGradient(
        0f to BuctColors.CanvasTop,
        0.34f to BuctColors.CanvasMid,
        0.62f to BuctColors.CanvasWarm,
        1f to BuctColors.CanvasBottom,
    )

@Composable
private fun Header() {
    Column {
        Text(
            text = "周三 · 第 12 教学周 · 11月19日",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        )
        Text(
            text = "今天",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun NextClassCard() {
    GlassPanel(radius = 24.dp, strong = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(56.dp)
                    .clip(CircleShape)
                    .background(BuctColors.BrandPrimary)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "下一节课",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                )
                Text(
                    "高等数学 A(2)",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "10:10 – 11:45",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
                )
                Text(
                    "主教 402",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                )
            }
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(BuctColors.BrandPrimary)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    "12 分后",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun TodoCard(items: List<TodoItem>) {
    GlassPanel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "今日待办",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                modifier = Modifier.weight(1f),
            )
            Text(
                items.size.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            )
        }
        Spacer(Modifier.height(8.dp))
        items.forEachIndexed { index, item ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (item.urgent) BuctColors.Danger else Color.Gray)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    item.due,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.urgent) BuctColors.Danger
                    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun ExamCard() {
    GlassPanel(radius = 22.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "最近考试",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                )
                Text(
                    "高等数学 A(2)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    "12月20日 09:00 · 主教 402",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                StatNumber(
                    value = "31",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "天后",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                )
            }
        }
    }
}

@Composable
private fun UnreadCard(unreadTotal: Int, noticeCount: Int, mailCount: Int) {
    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "未读",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                )
                Text(
                    "$noticeCount 通知 · $mailCount 邮件",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                )
            }
            if (unreadTotal > 0) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(BuctColors.Danger)
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text(
                        unreadTotal.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekCard(current: Int, total: Int, note: String) {
    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "第 $current 周",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                "共 $total 周",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(current.toFloat() / total)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(BuctColors.BrandPrimary)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        )
    }
}

/** 顶部悬浮胶囊组：搜索胶囊 + 两个圆形图标钮。 */
@Composable
private fun TopCapsuleRow() {
    val glass = LocalGlass.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassCapsuleRow(spec = Capsules.Field, modifier = Modifier.weight(1f)) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = glass.inkTertiary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "搜索课程、成绩、通知",
                style = MaterialTheme.typography.bodyMedium,
                color = glass.inkTertiary,
            )
        }
        RoundIconButton(Icons.Filled.Notifications, badge = 8)
        RoundIconButton(Icons.Filled.Settings)
    }
}

/** 44dp 圆形玻璃图标钮，可带角标。 */
@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    badge: Int = 0,
) {
    val glass = LocalGlass.current
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(glass.fill)
                .border(0.5.dp, glass.border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = glass.inkSecondary, modifier = Modifier.size(20.dp))
        }
        if (badge > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(BuctColors.Danger)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    badge.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }
}

private data class EntryItem(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private val WorkbenchEntries = listOf(
    EntryItem("空闲教室", Icons.Filled.DateRange),
    EntryItem("场馆", Icons.Filled.Place),
    EntryItem("体测", Icons.Filled.Star),
    EntryItem("创新学分", Icons.Filled.Star),
    EntryItem("第二课堂", Icons.Filled.List),
    EntryItem("校历", Icons.Filled.DateRange),
    EntryItem("邮箱", Icons.Filled.Email),
    EntryItem("更多", Icons.Filled.Menu),
)

/** 入口网格：两行四列的小胶囊。低频功能全部收在这里，不占底部坞。 */
@Composable
private fun EntryGrid() {
    val glass = LocalGlass.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WorkbenchEntries.chunked(4).forEach { rowItems ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowItems.forEach { item ->
                    GlassCapsule(
                        modifier = Modifier.weight(1f),
                        spec = Capsules.Card.copy(radius = 24.dp, padH = 8.dp, padV = 12.dp),
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            tint = BuctColors.BrandPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            item.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = glass.inkSecondary,
                            maxLines = 1,
                        )
                    }
                }
                repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}


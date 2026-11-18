# BUCT One · 北化一站 — 液态玻璃设计系统规范

> 适用范围：`com.buct.one`（单 module，compileSdk 37.0 / minSdk 24 / targetSdk 34，Kotlin + Jetpack Compose + Material 3）。
> UI 底座是 Material 3（不离开 M3），在其上叠加三层：自定义色板、液态玻璃材质、成套动效。
> 视觉基线：上游 zhengfang-apk（GPL-3.0）`ui/theme` 与 `ui/system/glass/`（30 文件：GlassLensShader / ChromaticFringe / GlassLensRenderer / GlassChipSurface / GlassSheetSurface / PhysicalLensSurface / LiquidSpringPhysics 等）。
> 依赖：`io.github.kyant0:backdrop:2.0.0`、`io.github.kyant0:shapes:1.2.0`、androidx.compose BOM `2026.01.00`。
> 本文档为 M1 唯一视觉权威；组件实现以本文参数为准。

---

## 1. 色板

### 1.1 Kotlin 定义（`ui/theme/Color.kt`）

```kotlin
package com.buct.one.ui.theme

import androidx.compose.ui.graphics.Color

// ── 品牌主色（iOS systemBlue 一族）──
val BrandPrimary          = Color(0xFF0A84FF)  // 浅色主题表面用主色
val BrandPrimaryStrong    = Color(0xFF0069D9)  // 深色主题可读性加强档
val BrandPrimaryContainer = Color(0xFFD6E9FF)
val BrandSecondary        = Color(0xFF5C6772)
val BrandSecondaryContainer= Color(0xFFE6EBF0)

// ── 语义色（浅色档）──
val SemanticSuccess         = Color(0xFF1F845A)
val SemanticSuccessContainer= Color(0xFFD8F4E5)
val SemanticWarning         = Color(0xFFE58A00)
val SemanticWarningContainer= Color(0xFFFFE9C2)
val SemanticDanger          = Color(0xFFC73A2F)
val SemanticDangerContainer = Color(0xFFFFDAD6)
val SemanticInfo            = BrandPrimary
val SemanticInfoContainer   = BrandPrimaryContainer

// ── 语义色（深色档：提亮一档保对比）──
val SemanticSuccessDark          = Color(0xFF4CC38A)
val SemanticWarningDark          = Color(0xFFFFB340)
val SemanticDangerDark           = Color(0xFFFF6B5E)
val SemanticInfoDark             = Color(0xFF4DA3FF)

// ── 中性色阶 ──
val Neutral0   = Color(0xFFFFFFFF)
val Neutral10  = Color(0xFFF8F9FC)  // 页面底（浅色）
val Neutral20  = Color(0xFFF3F5F9)
val Neutral30  = Color(0xFFECEFF5)
val Neutral40  = Color(0xFFE0E5EE)
val Neutral100 = Color(0xFFE8ECF3)
val Neutral200 = Color(0xFFD5DCE7)
val Neutral300 = Color(0xFFB3BDCC)  // 深色主题次要文字
val Neutral500 = Color(0xFF667085)  // outline
val Neutral700 = Color(0xFF344054)
val Neutral900 = Color(0xFF101828)  // 浅色主题正文

// ── 深色表面 ──
val SurfaceDark   = Color(0xFF171B22)
val BackgroundDark= Color(0xFF0F1218)

// ── 8 种课程块色 ──
val BlockBlue   = Color(0xFF4F6BED)
val BlockMint   = Color(0xFF0F9D84)
val BlockCyan   = Color(0xFF1F88C8)
val BlockIndigo = Color(0xFF5965D8)
val BlockViolet = Color(0xFF855AE0)
val BlockOrange = Color(0xFFE57A1F)
val BlockRose   = Color(0xFFD9487E)
val BlockTeal   = Color(0xFF1C8F8F)
val BlockColors = listOf(BlockBlue, BlockMint, BlockCyan, BlockIndigo,
    BlockViolet, BlockOrange, BlockRose, BlockTeal)

// ── 玻璃层半透明色（浅色：半透白；深色：半透黑）──
object GlassAlpha {
    // 浅色（白）
    const val CARD_SURFACE_LIGHT  = 0.10f  // 控件玻璃底：Color.White.copy(alpha = CARD_SURFACE_LIGHT)
    const val SHEET_SURFACE_LIGHT = 0.62f  // 模态玻璃底
    const val BAR_SURFACE_LIGHT   = 0.16f  // 导航/顶栏玻璃底
    const val BORDER_LIGHT         = 0.12f  // 玻璃描边 alpha
    const val BORDER_BRIGHT        = 0.80f  // 顶部/左侧亮边（0xCCFFFFFF）
    const val BORDER_DIM           = 0x33   // 底部/右侧暗边 0x33A6B4C9
    const val DIVIDER              = 0.45f  // 分隔线（outlineVariant.copy(alpha = 0.45f)，0.5dp 高）
    const val HIGHLIGHT            = 0xAA   // 0xAAFFFFFF 表面高光叠层
    const val HIGHLIGHT_SHEAR      = 0x80   // 0x80FFFFFF 月牙反光渐变起始
    const val OVERLAY              = 0x18   // 0x18FFFFFF 内部磨砂覆盖
    const val INNER_SHADOW         = 0x0D   // 0x0DBEC8D6 内阴影暗角
    // 深色（黑）
    const val CARD_SURFACE_DARK   = 0.08f  // Color.Black.copy(...)
    const val SHEET_SURFACE_DARK   = 0.78f
    const val BAR_SURFACE_DARK    = 0.20f
    const val BORDER_DARK          = 0.16f
    const val HIGHLIGHT_DARK       = 0x1A   // 0x1AFFFFFF 深色玻璃亮边
}
```

### 1.2 规则

- 课程块色仅用于课表方块、stat 卡左侧色条、chip 选中态；不进入 `colorScheme`。
- 语义色深浅档通过 `isSystemInDarkTheme()` 切换，禁止在浅色主题使用深色档。
- 玻璃层颜色一律 `Color.White / Color.Black .copy(alpha = GlassAlpha.*)`，禁止新造十六进制。

---

## 2. 材质规范（`ui/system/GlassMaterial.kt`）

### 2.1 角色（角色决定参数，组件不私自调数字）

| 角色 | 用于 | 模糊半径 | 底 alpha（浅/深） | 边框宽 | 边框 alpha | 折射高度/幅度 |
|---|---|---|---|---|---|---|
| Navigation | 底栏 NavBar、顶栏 TopBar | 10dp | 0.16 / 0.20 | 0.5dp | 0.14 | 8dp / 12dp |
| Control | GlassPanel 卡片、Chip、分段控件 | 5dp | 0.10 / 0.08 | 0.5dp | 0.12 | 6dp / 10dp |
| Modal | 对话框、BottomSheet、下拉菜单 | 6dp | 0.62 / 0.78 | 1dp | 0.30 | 10dp / 14dp |
| Interactive | 滑块 thumb、选中透镜 | 0dp（靠折射） | 0.08 / 0.08 | 0.5dp | 0.16 | 12dp / 18dp |

```kotlin
enum class GlassRole { Navigation, Control, Modal, Interactive }

@Immutable
data class GlassSpec(
    val blurDp: Float, val surfaceAlphaLight: Float, val surfaceAlphaDark: Float,
    val borderWidthDp: Float, val borderAlpha: Float,
    val refractionHeightDp: Float, val refractionAmountDp: Float
)

object GlassSpecs {
    val Navigation = GlassSpec(10f, 0.16f, 0.20f, 0.5f, 0.14f,  8f, 12f)
    val Control    = GlassSpec( 5f, 0.10f, 0.08f, 0.5f, 0.12f,  6f, 10f)
    val Modal      = GlassSpec( 6f, 0.62f, 0.78f, 1.0f, 0.30f, 10f, 14f)
    val Interactive= GlassSpec( 0f, 0.08f, 0.08f, 0.5f, 0.16f, 12f, 18f)
}
```

### 2.2 圆角档位

| 圆角 | 组件 |
|---|---|
| 6dp | Chip 选中胶囊内衬、tag、小徽标 |
| 12dp | GlassChip、列表行（GlassRow）、输入框 |
| 16dp | GlassPanel 常规卡片、下拉菜单项 |
| 20dp | WorkbenchCard 大卡（下一节课）、BottomSheet 顶部抓手区 |
| 22dp | stat 卡系列（待办/考试/未读/教学周） |
| 24dp | GlassPanel 全宽主卡、对话框（Navigation 顶/底栏容器） |

### 2.3 阴影与高光策略

- 内容卡片：**不用** M3 `shadow()`；层次由「顶部 0xCCFFFFFF 亮边 + 底部 0x33A6B4C9 暗边」双渐变描边承担。
- 导航/模态层：可用 backdrop 库 `Shadow(alpha = 0.12f)`；对话框阴影 elevation 8dp、alpha 0.24。
- 高光：静止态 `0x18FFFFFF` overlay；按压态边缘亮边提升（+0.45 alpha，像玻璃被压出的应力边）。

### 2.4 禁用玻璃的场景

1. 列表滚动中的 item（滚动时退化为 `Color.White.copy(alpha = 0.10f)` 纯色，见 §8）。
2. 长文阅读页（成绩单、通知正文）：纯色 `MaterialTheme.colorScheme.surface`，保证可读性。
3. API 24–26 且无 RenderEffect：全部退化为纯色 + 半透描边（见 §8.1）。
4. 高对比度模式（`high_text_contrast_enabled = 1`）：surface alpha 提到 0.96（近实色）。
5. 系统动画时长缩放为 0：折射幅度减半、禁色散（动效可达性）。

---

## 3. 排版（`ui/theme/Type.kt`，中文优先）

字体栈：默认 Roboto + 系统中文（NotoSansCJK）；**所有中文可用档 letterSpacing ≥ 0**，禁止负字距（CJK 无 kerning 收益，负值会挤糊笔画）；仅 Display/Headline 的纯数字/拉丁场景允许 -0.5sp。

```kotlin
val AppTypography = Typography(
    displayLarge = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold,   lineHeight = 52.sp, letterSpacing = 0.sp),
    displayMedium= TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold,   lineHeight = 44.sp, letterSpacing = 0.sp),
    headlineLarge= TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, lineHeight = 36.sp, letterSpacing = 0.sp),
    headlineMedium=TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp, letterSpacing = 0.sp),
    titleLarge   = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp, letterSpacing = 0.sp),
    titleMedium  = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium,    lineHeight = 24.sp, letterSpacing = 0.1.sp),
    titleSmall   = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium,    lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge    = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal,   lineHeight = 26.sp, letterSpacing = 0.2.sp),
    bodyMedium   = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal,   lineHeight = 22.sp, letterSpacing = 0.2.sp),
    bodySmall    = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal,   lineHeight = 18.sp, letterSpacing = 0.3.sp),
    labelLarge   = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium,  lineHeight = 20.sp, letterSpacing = 0.3.sp),
    labelMedium  = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium,  lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall   = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium,  lineHeight = 14.sp, letterSpacing = 0.5.sp)
)
```

数字专用（StatNumber 滚动）：`tabular figures` —— `fontFeatureSettings = "tnum"`，size 32sp / weight Bold / lineHeight 38sp；仅此档允许 `letterSpacing = (-0.5).sp`（纯数字无汉字）。

---

## 4. 间距系统（4 的倍数）

| token | dp | 用法 |
|---|---|---|
| XS | 4 | 图标与文字、chip 内横向 |
| S  | 8 | 列表行内元素、chip 内边距（水平 8/垂直 4） |
| M  | 12 | 卡片内图标区与正文、stat 数字与单位 |
| L  | 16 | **卡片内边距（全卡 16dp）**、列表行内容起始 |
| XL | 20 | SectionHeader 与上方内容 |
| XXL| 24 | **卡片间间距（垂直 24dp 节流为 16dp 视觉等价可）**、BottomSheet 顶内边距 |
| XXXL|32 | **页面水平边距 20dp（首页）/16dp（次级页）**、模块区块间 32dp |

补充：页面顶部首个 SectionHeader 距顶栏 24dp；WorkbenchCard 网格水平间距 12dp、垂直 16dp；NavBar 高 64dp + 导航手势区 inset。

---

## 5. 动效映射表（`ui/theme/Motion.kt`）

### 5.1 Token

```kotlin
object MotionDuration {
    const val Fast = 120;  const val Medium = 220;  const val Number = 180
    const val Slow = 400;  const val TabTransition = 220
    const val DialogEnter = 400; const val ExpandCollapse = 250
    const val NavIndicator = 260; const val NavLabel = 180
    const val EmphasisPulse = 1400
}
object MotionEasing {
    val Standard   = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)
    val Emphasized = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val FastOutSlowIn = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
    val Accelerate = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)
}
fun <T> spec(duration: Int, easing: Easing) = tween<T>(duration, easing = easing)
```

### 5.2 交互 → duration + easing

| 交互 | duration | easing | 实现要点 |
|---|---|---|---|
| 按下（ripple/挤压） | Fast 120ms | FastOutSlowIn | scale 0.98f，玻璃表面 alpha ×0.55 |
| 页面切换（NavHost） | Medium 220ms | Standard（进）/Accelerate（出） | fade + 8dp slide-in |
| tab 切换 | TabTransition 220ms | Standard | 内容 crossfade |
| 数字变化（StatNumber） | Number 180ms | FastOutSlowIn | 逐位滚动，容器几何不变 |
| 卡片展开/收起 | ExpandCollapse 250ms | FastOutSlowIn | `animateContentSize` |
| 下拉刷新 | Medium 220ms（收起 400） | Emphasized | 指示器 spring(damping 0.8, stiffness 420) |
| 对话框进入 | DialogEnter 400ms | FastOutSlowIn | scale 0.92→1 + fade，scrim alpha 0→0.32 |
| 对话框退出 | Medium 220ms | Accelerate | 反向 |
| 骨架屏 shimmer | EmphasisPulse 1400ms 循环 | Linear | `infiniteRepeatable(tween(1400, LinearEasing))` |
| 底栏指示器滑移 | NavIndicator 260ms | Emphasized | spring(damping 0.6, stiffness 310) jelly 过冲 |
| 底栏选中标签浮现 | NavLabel 180ms | FastOutSlowIn | scaleY 0.6→1 + fade |
| 壁纸高亮脉冲 | EmphasisPulse 1400ms 循环 | Linear | alpha 0.4↔0.8 |

```kotlin
val pressSpec      = spec<Float>(MotionDuration.Fast, MotionEasing.FastOutSlowIn)
val navSpec        = spec<Float>(MotionDuration.NavIndicator, MotionEasing.Emphasized)
val dialogSpec     = spec<Float>(MotionDuration.DialogEnter, MotionEasing.FastOutSlowIn)
val expandSpec     = spec<Float>(MotionDuration.ExpandCollapse, MotionEasing.FastOutSlowIn)
val numberSpec     = spec<Float>(MotionDuration.Number, MotionEasing.FastOutSlowIn)
val skeletonSpec   = infiniteRepeatable(tween<Float>(MotionDuration.EmphasisPulse, easing = LinearEasing))
```

系统「移除动画」开启时全部替换为 `snap()`。

---

## 6. 组件清单（M1）

| 组件 | 文件路径（java/com/buct/one/ 下） | 职责 | 依赖 | 预估行数 |
|---|---|---|---|---|
| BuctTheme | `ui/theme/Theme.kt` | 深浅双 scheme + edge-to-edge + glass 挂载 | M3、GlassPressIndication | 180 |
| Color/Type/Motion token | `ui/theme/Color.kt` `Type.kt` `Motion.kt` | 本文 §1/§3/§5 全量 token | Compose ui | 320 |
| GlassPanel | `ui/system/GlassPanel.kt` | 角色化玻璃容器（blur+surface+border+高光） | backdrop 2.0.0 | 160 |
| GlassChip | `ui/system/GlassChip.kt` | 玻璃胶囊（筛选/入口），选中态实心胶囊 | backdrop | 120 |
| SectionHeader | `ui/system/SectionHeader.kt` | 标题 + 「更多」行 | Compose | 50 |
| StatNumber | `ui/system/StatNumber.kt` | 数字滚动（tnum，逐位），0 值支持 | Motion | 130 |
| NavBar | `ui/navigation/NavBar.kt` | 底部玻璃导航 + 指示器 + 标签动画 | backdrop、shapes | 220 |
| WorkbenchCard 基类 | `ui/workbench/WorkbenchCard.kt` | 统一玻璃卡壳（22dp 圆角、色条槽） | GlassPanel | 90 |
| NextClassCard | `ui/workbench/NextClassCard.kt` | 下一节课（课程名/时间/教室，Block 色条） | WorkbenchCard | 140 |
| TodoCard | `ui/workbench/TodoCard.kt` | 待办（数量 StatNumber + 前两条预览） | 同上 | 110 |
| ExamCard | `ui/workbench/ExamCard.kt` | 考试倒计时（最近 1 场 + 数量） | 同上 | 100 |
| UnreadCard | `ui/workbench/UnreadCard.kt` | 未读通知数（EmphasisPulse 高亮） | 同上 | 90 |
| WeekCard | `ui/workbench/WeekCard.kt` | 教学周（第 N 周 + 周进度 GlassProgressBar） | 同上 | 100 |
| DashboardScreen | `ui/workbench/DashboardScreen.kt` | 首屏编排（§7） | 全部 | 200 |
| GlassPressIndication | `ui/system/GlassPressIndication.kt` | 全局按压反馈（120ms 挤压） | Motion | 90 |

M1 合计约 2100 行 Kotlin。

---

## 7. 首屏组件树（DashboardScreen）

```
DashboardScreen(state: DashboardUiState, onNavigate: (Route) -> Unit)
├─ Box(modifier = Modifier.fillMaxSize())                       // 壁纸层
│   └─ WallpaperLayer(dim = 0.15f)                              // 背景壁纸 + 15% 压暗 scrim
├─ Scaffold(containerColor = Color.Transparent,
│   bottomBar = { NavBar(selected = state.tab, items = WorkbenchTabs,
│                        spec = GlassSpecs.Navigation, height = 64.dp,
│                        indicatorSpec = spring(dampingRatio = 0.6f, stiffness = 310f)) })
│   └─ LazyColumn(
│        contentPadding = PaddingValues(horizontal = 20.dp, top = 24.dp, bottom = 96.dp),
│        verticalArrangement = Arrangement.spacedBy(24.dp))
│       ├─ item: GreetingHeader(name = state.userName, dateText = state.todayText)
│       │        // headlineMedium 22sp + labelMedium 12sp 日期
│       ├─ item: SectionHeader(title = "今天", trailing = "全部")   // 距上 0dp
│       ├─ item: NextClassCard(course = state.nextClass,
│       │        accent = state.nextClass?.color ?: BlockBlue)     // GlassPanel, 20dp, Control
│       ├─ item: Row(horizontalArrangement = Arrangement.spacedBy(12.dp))  // 2×2 stat
│       │   ├─ TodoCard(count = state.todoCount, preview = state.todoPreview)   // weight(1f), 22dp
│       │   └─ ExamCard(nearest = state.nearestExam)                            // weight(1f), 22dp
│       ├─ item: Row(spacedBy(12.dp))
│       │   ├─ UnreadCard(count = state.unreadCount)   // weight(1f), 22dp, EmphasisPulse
│       │   └─ WeekCard(week = state.weekNo, progress = state.weekProgress)      // weight(1f), 22dp
│       ├─ item: SectionHeader(title = "快捷入口")
│       └─ item: GlassChipRow(entries = state.quickEntries, onSelect = onNavigate)
│            // GlassChip(12dp 圆角, Control 材质, 选中胶囊 6dp)
└─ AnimatedVisibility(state.loading) → SkeletonShimmer(spec = skeletonSpec)   // 全屏骨架
```

---

## 8. 性能约束

### 8.1 API 24–26 降级（无 AGSL / RenderEffect）

- 全部玻璃面板退化为 **纯色 + 半透描边**：`Color.White.copy(alpha = 0.86f)`（浅）/ `Color.Black.copy(alpha = 0.70f)`（深），保留边框渐变与高光（纯 Canvas 绘制，无模糊）。
- 判定：`Build.VERSION.SDK_INT < 31` 走 fallback 路径；API 31/32 用 blur 但折射减 30%。
- 每帧最多 1 个 backdrop 采样层（仅 NavBar）；其余零 backdrop（纯 alpha 混合）。

### 8.2 滚动禁用模糊

```kotlin
val listState = rememberLazyListState()
val isScrolling = listState.isScrollInProgress
// GlassPanel modifier 内：
.blur(if (isScrolling) 0.dp else spec.blurDp.dp)
```

滚动中卡片退化为纯 alpha 底；停止 120ms 后恢复模糊。

### 8.3 面板数量上限

- 单屏（一帧可见区）真 backdrop 玻璃面板 ≤ **2 个**（NavBar + 一个悬浮层）；LazyColumn 内卡片用「纯 alpha 混合 + 描边」的廉价玻璃（上游同策略：零 backdrop 采样开销）。
- stat 卡网格一次组合 ≤ 8 张卡；骨架屏期间禁一切玻璃。
- `GraphicsLayer` 层级深度 ≤ 4；嵌套玻璃禁止（玻璃上不再叠玻璃，中间必须有不透明层）。

---

## 9. 可访问性

- **对比度**：玻璃面板上文字对「玻璃底 + 最坏壁纸」的组合对比度 ≥ 4.5:1。规则化实现：**当玻璃底面 alpha（浅色）< 0.10 时必须加 scrim** —— 即卡片玻璃 alpha 0.10 是下限，且壁纸 dim scrim ≥ 0.15；模态层因 alpha 0.62 免 scrim。深色主题同理 alpha ≥ 0.08 + 壁纸 dim ≥ 0.25。若壁纸局部出现大面积高对比内容（`usesDarkForeground` 检测），面板自动切换到 `Color.White.copy(alpha = 0.86f)` 实底。
- **字号缩放**：所有正文使用 sp；用户字体缩放至 200% 时 stat 网格允许从 2 列折为 1 列（`LocalDensity.current.fontScale > 1.5` 时 `FlowRow`）；StatNumber 滚动容器宽度用 `widthIn(min = 3 * 20.dp)` 防截断。
- **TalkBack**：
  - GlassPanel 点击整体可及的卡片合并语义：`semantics(mergeDescendants = true)`，`contentDescription` 按语义读（如「下一节课，高等数学，14:00 到 15:40，主教楼 301」）。
  - NavBar 项：`Role.Tab` + 选中态 `selected` 属性；StatNumber 读最终数值不读滚动过程，`liveRegion = LiveRegionMode.Polite`。
  - 骨架屏标记 `invisibleToUser`；EmphasisPulse 动画不携带语义。
  - 按压反馈用 `indication`（GlassPressIndication 自带），可聚焦元素最小触达 48dp。
- **动效减弱**：`Settings.Global.ANIMATOR_DURATION_SCALE <= 0` 时全部 `snap()`、折射减 45%、色散关闭。

---

## 附：M3 Shapes 对齐

```kotlin
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
```

玻璃组件圆角优先用 §2.2 显式档位，不依赖 Shapes 默认。

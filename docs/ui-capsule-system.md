# BUCT One · 全悬浮胶囊 + 液态玻璃 UI 规范

> 版本：v1.0 · 适用范围：com.buct.one 单 module · Material 3 + Jetpack Compose
> 基线：compileSdk 37 / minSdk 24 / targetSdk 34
> 上游形态：顶部悬浮胶囊组 → 圆形玻璃钮行 → 彩色半透明课程块 → 悬浮胶囊坞（5 项，中心圆形凸起）

---

## 0. 设计立场

**一切容器皆胶囊**：导航坞、卡片、筛选器、输入框、按钮、提示条、分段控件，没有例外。
所有胶囊悬浮于画布之上，四周至少 16dp 呼吸留白，靠玻璃描边与投影分层，不用满宽贴边块。

**画布（Canvas）**：三段垂直淡彩渐变，不带任何内容承载职责。
```kotlin
val CanvasBrush = Brush.verticalGradient(
    listOf(Color(0xFFEEF2FA), Color(0xFFECE2EF), Color(0xFFE4EDF5))
)
```

**玻璃基准**：alpha 0.46（浅色模式容器底色 0x46FFFFFF），描边 0xF0FFFFFF（1dp 顶部高光），
主色 #0A84FF。

---

## 1. 胶囊规格库（8 种）

| # | 名称 | 高度 | 圆角 | 内边距 | 用途 |
|---|------|------|------|--------|------|
| 1 | Dock | 68dp | 34dp(全圆) | 6dp×8dp | 底部悬浮导航坞 |
| 2 | Card | 92-120dp | 28dp | 20dp | 首页信息卡、课表卡 |
| 3 | Chip | 36dp | 18dp(全圆) | 0×12dp | 筛选器、周次切换 |
| 4 | Field | 52dp | 26dp(全圆) | 0×16dp | 搜索/登录输入框 |
| 5 | Pill | 44dp | 22dp(全圆) | 0×18dp | 主/次按钮 |
| 6 | Segment | 40dp | 20dp(全圆) | 0×8dp | 分段控件（含滑块） |
| 7 | Toast | 48dp | 24dp(全圆) | 0×16dp | 提示条、Snackbar |
| 8 | IconDot | 44dp 圆 | 22dp | 居中 | 课表块圆点、状态点 |

规则：高度一半即圆角，「全圆」胶囊永不出现直角；Card 是大号胶囊（非全圆，软圆角 28dp）。

### Compose 签名

```kotlin
@Composable
fun GlassCapsule(
    modifier: Modifier = Modifier,
    spec: CapsuleSpec,                        // 上表 8 种之一
    tint: Color = GlassToken.containerLight, // 0x46FFFFFF
    border: Border = GlassToken.highlight,    // 1dp 0xF0FFFFFF
    shadow: Boolean = true,
    content: @Composable RowScope.() -> Unit
)

data class CapsuleSpec(
    val height: Dp, val radius: Dp,
    val paddingHorizontal: Dp, val paddingVertical: Dp
)
```

---

## 2. FloatingDock 完整规格

### 2.1 尺寸与留白

- 坞体：高 68dp，水平内容 5 项等分，左右外边距 16dp，底部留白 = 导航栏安全区 + 12dp。
- 中心第 3 项为**圆形凸起钮**：直径 56dp，圆心相对坞体顶边上浮 8dp（伸出坞外），
  用 Modifier.graphicsLayer { translationY = -8.dp.toPx() } 实现。
- 其余 4 项：44dp IconDot，间距 = (坞宽 - 4*44 - 56) / 5。
- 图标下 4dp 处 10sp 标签，仅选中态显示。

### 2.2 玻璃参数

- 容器：alpha 0.46 白，blur 按第 5 节分档（33+ Cloudy / 24-32 Haze / 纯 alpha）。
- 描边：1dp 0xF0FFFFFF，仅顶部半弧（Brush 上下渐变描边）。
- 投影：elevation 6dp，ambient 0x14031739，spot 0x1E031739。
- 中心凸起钮：不透明主色 #0A84FF（浮起物不做玻璃），按下缩至 0.92。

### 2.3 选中指示器

- 指示器：激活项 IconDot 背景 36dp 胶囊 0x330A84FF（主色 20% alpha），弹性进出。
- 换页时指示器在坞内做 320ms spring(dampingRatio=0.8) 横移，
  图标色在 0x0A84FF 与 0xFF7A8699 之间切换。

### 2.4 安全区

```kotlin
val navBars = WindowInsets.navigationBars.asPaddingValues()
Box(Modifier.padding(bottom = navBars.calculateBottomPadding() + 12.dp))
```
坞体永远悬浮在内容之上（内容底部 padding = 坞高 68 + 12 + 安全区）。

### 2.5 骨架

```kotlin
@Composable
fun FloatingDock(
    tabs: List<DockTab>, current: Int,
    onSelect: (Int) -> Unit, modifier: Modifier = Modifier
) {
    GlassCapsule(spec = DockSpec, modifier = modifier
        .padding(horizontal = 16.dp)
        .padding(bottom = 12.dp)
        .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        tabs.forEachIndexed { i, tab ->
            key(i) {
                if (i == 2) CenterRiseButton(tab, onSelect)  // 56dp 凸起
                else DockItem(tab, selected = i == current,
                    onClick = { onSelect(i) })
            }
        }
    }
}
```

---

## 3. 五页组件树（ASCII）

### 3.1 工作台 Workbench
```
Screen(canvas: verticalGradient)
+-- TopCapsuleRow ---- 顶部悬浮胶囊组（问候 + 日期）
|   +-- GlassCapsule(Field)  搜索框
|   +-- IconDot x2           通知 / 设置
+-- LazyColumn(padding bottom = dock+80dp)
|   +-- GlassCapsule(Card) NextClassCard   下一节课
|   +-- GlassCapsule(Card) TodoCard        今日待办
|   +-- GlassCapsule(Card) ExamCard        考试倒计时
|   +-- GlassCapsule(Card) UnreadCard      未读消息
|   +-- GlassCapsule(Card) WeekCard        学周概览
+-- FloatingDock(5)  中心凸起 = 扫一扫/快速请假
```

### 3.2 课表 Timetable
```
Screen(canvas)
+-- TopCapsuleRow
|   +-- GlassCapsule(Chip) x N  周次切换（横向滚动）
|   +-- GlassCapsule(IconDot)   今天
+-- Grid 时间轴 + 课程格
|   +-- CourseBlock(彩色半透明 8 色, 圆角 16dp, alpha 0.18)
|   +-- CourseBlock(进行中 -> 描边 0x0A84FF)
+-- FloatingDock(5)  中心凸起 = 新建自定义课
```

### 3.3 成绩 Score
```
Screen(canvas)
+-- TopCapsuleRow
|   +-- GlassCapsule(Segment) 学期 / 学年 / 总绩点
|   +-- GlassCapsule(Chip)    排序
+-- LazyColumn
|   +-- GlassCapsule(Card) GPACard（StatNumber 数字滚动）
|   +-- GlassCapsule(Card) ScoreRow x N  成绩行卡
|   +-- GlassCapsule(Card) RankCard      排名
+-- FloatingDock(5)  中心凸起 = 成绩分析
```

### 3.4 作业 Homework
```
Screen(canvas)
+-- TopCapsuleRow
|   +-- GlassCapsule(Segment) 全部 / 未交 / 已交
+-- LazyColumn
|   +-- GlassCapsule(Card) DeadlineCard（临期红色状态点）
|   +-- GlassCapsule(Card) HomeworkRow x N
+-- FloatingDock(5)  中心凸起 = 新建作业
```

### 3.5 我的 Profile
```
Screen(canvas)
+-- TopCapsuleRow
|   +-- GlassCapsule(Field)  搜索设置项
|   +-- IconDot              消息
+-- LazyColumn
|   +-- GlassCapsule(Card) ProfileCard（头像 + 学号 + 绩点）
|   +-- GlassCapsule(Card) MenuGroup x 3（功能 / 数据 / 关于）
|   +-- GlassCapsule(Pill)  退出登录
+-- FloatingDock(5)  中心凸起 = 校园卡
```

---

## 4. 滚动遮挡与收缩行为

1. **顶部胶囊组**：内容上滚，顶栏收缩为 56dp 单行（仅保留搜索框），200ms FastOutSlowIn；
   下拉回顶展开。用 LazyListState.firstVisibleItemScrollOffset 驱动 animateFloatAsState。
2. **内容穿坞遮挡**：列表最后 item 底部 padding = 68dp 坞高 + 12dp 留白 + 安全区，
   内容永不被坞遮死；滚到尽头允许最后一卡半透明掠过坞后（玻璃透出内容）。
3. **坞收缩**：键盘弹出（imePadding）或分屏窄幅，坞收缩为 3 项宽度
   （当前页 + 相邻页），260ms spring；键盘收起 320ms 回满宽。
4. **滚动时坞微降**：快速上滑坞 translationY +6dp 并减弱投影，静止 1.5s 回位（默认关闭）。
5. **课表块溢出**：CourseBlock 不截断换行，网格高度超出时改为「+N」IconDot 胶囊角标。

---

## 5. 玻璃实现分档（API 33 / 24-32 / 降级）

| 档 | API | 效果 | 成本 |
|----|-----|------|------|
| A | 33+ | RenderEffect.createBlurEffect + Cloudy liquidGlass（真折射：微位移 + 色散边缘） | 每帧离屏，仅坞/顶栏/模态 |
| B | 24-32 | Haze（dev.chrisbanes.haze）模糊 blurRadius 24dp | 中等，同 A 限少数容器 |
| C | 全部兜底 | 纯 alpha 0.46 + 1dp 高光描边 + 投影 | 零成本 |

### 骨架代码

```kotlin
@Composable
fun Modifier.liquidGlass(shape: Shape, corners: Dp = 28.dp): Modifier =
    if (Build.VERSION.SDK_INT >= 33) {
        val glass = rememberLiquidGlassLayer()   // M2: GraphicsLayer + blurEffect(24.dp)
            .refraction(displacement = 1.5f)     // Cloudy: 微折射
            .tint(Color.White.copy(alpha = 0.46f))
            .borderHighlight(Color(0xF0FFFFFF))
        this.glassBackground(glass, shape, corners)
    } else if (Build.VERSION.SDK_INT >= 24) {
        this
            .hazeSource(LocalHazeState.current)
            .clip(shape)
            .background(Color(0x46FFFFFF))
            .border(1.dp, Brush.verticalGradient(
                listOf(Color(0xF0FFFFFF), Color(0x14FFFFFF))
            ), shape)
    } else {
        this
            .clip(shape)
            .background(Color(0x46FFFFFF))
            .border(1.dp, Color(0xF0FFFFFF), shape)
            .shadow(6.dp, shape, clip = false)
    }
```

> M1 先交付 C 档（纯 alpha，约 70% 玻璃感），接口签名按上面固定，
> A/B 档在 M2 作为同一 API 的内部实现切换。

---

## 6. 动效 10 条

| # | 交互 | 时长 | Easing | API |
|---|------|------|--------|-----|
| 1 | 页面切换 | 320ms | CubicBezier(0.2,0,0,1) | NavHost + animateFloatAsState |
| 2 | 坞选中指示器横移 | 320ms | spring(stiffness=380, damping=0.8) | Animatable offset |
| 3 | 中心凸起钮按压 | 120ms 按下 / 180ms 回弹 | spring | graphicsLayer scaleX/scaleY |
| 4 | 胶囊入场（首屏级联） | 240ms + 40ms/item 阶梯 | FastOutSlowIn | AnimatedVisibility + 阶梯 delay |
| 5 | 卡片点开到详情 | 340ms | PathInterpolator(0.4,0,0.2,1) | SharedTransitionScope |
| 6 | 顶栏收缩/展开 | 200ms / 260ms | FastOutSlowIn | derivedStateOf + animateDpAsState |
| 7 | Chip 选中填充 | 180ms | LinearOutSlowIn | animateColorAsState |
| 8 | Segment 滑块移动 | 260ms | spring(damping=0.85) | animateDpAsState |
| 9 | Toast 上浮进出 | 240ms 进 / 200ms 出 | FastOutLinearIn | AnimatedVisibility(slide+fade) |
| 10 | StatNumber 数字滚动 | 700ms | FastOutSlowIn | animateIntAsState + 格式化 |

统一 token：Motion.DurationS=180 / M=260 / L=320；全局只用表中 easing，禁止新增。

---

## 7. 上游元素映射表

| 上游形态 | 落位 | 实现 |
|----------|------|------|
| 顶部悬浮胶囊组 | TopCapsuleRow（五页通用） | GlassCapsule(Field)+IconDot，随滚动收缩 |
| 圆形玻璃钮行 | Dock 非中心 4 项 + 卡片右上角操作钮 | IconDot 44dp 玻璃圆钮 |
| 彩色半透明课程块 | 课表 CourseBlock | 8 色板 alpha 0.18，圆角 16dp，进行中主色描边 |
| 悬浮胶囊坞（5 项中心凸起） | FloatingDock | 68dp 坞 + 56dp 中心主色凸起钮 |
| 胶囊筛选器 | 周 Chip / Segment | 规格库 #3 / #6 |
| 悬浮输入框 | 搜索 / 登录 Field | 规格库 #4 |
| 悬浮提示条 | Snackbar / Toast | 规格库 #7，坞上方 16dp |

---

## 8. 优先级与人日

| P | 事项 | 人日 |
|---|------|------|
| P0 | CapsuleSpec + GlassCapsule（C 档玻璃）+ 画布渐变 | 2 |
| P0 | FloatingDock（凸起钮 + 指示器 + 安全区） | 2 |
| P0 | 工作台页 5 卡 + 动效 1/4/6/10 | 3 |
| P1 | Chip / Segment / Field / Pill / Toast 规格 | 2 |
| P1 | 课表页（课程块 + 周次 Chip + 溢出角标） | 3 |
| P1 | 成绩 / 作业 / 我的 页骨架 + TopCapsuleRow | 3 |
| P2 | 玻璃 A/B 档（Cloudy / Haze）切换 + 折射调优 | 3 |
| P2 | SharedElement 转场 + 坞收缩（键盘） | 2 |
| — | 合计 | 20 人日（P0 先行 7 人日出可演示版） |

---

## 附：token 汇总

```kotlin
object GlassToken {
    val canvasTop = Color(0xFFEEF2FA)
    val canvasMid = Color(0xFFECE2EF)
    val canvasBot = Color(0xFFE4EDF5)
    val containerLight = Color(0x46FFFFFF)
    val highlight = Color(0xF0FFFFFF)
    val brand = Color(0xFF0A84FF)
}
```

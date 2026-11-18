# plan-2 · 功能路线图 + 企业微信接入

> 上游文档：docs/plan-1（架构）｜本文件与《北化校园工作台-规划.md》v1 草稿一致，冲突处以本文件为准（文中标注 3 处修订）。
> 基线：zhengfang-apk 7.1MB，497 个 .kt；新代码全部落在 com.buct.one.data.* 包（草稿第 3 节包结构）。

---

## 1. THEIA 模块盘点（实测 docs/features 目录 15 个文档，归并为 22 个功能模块）

数据来源：https://raw.githubusercontent.com/bakahuiii/THEIA/main/docs/features/overview.md（已完整读取）+ docs/features 目录实测清单（README / advisor / assignments / campus-map / communications / course-selection / courses / dashboard / exams / grades / overview / progress / schedule / settings / tools）。以下无「推断」标注的均为 overview.md 与 README.md 明文确认。

| # | THEIA 模块 | 数据来源 | Android 端承接方式 |
|---|---|---|---|
| 1 | 校园概览 Dashboard | 本地快照聚合 | campus/ui/DashboardScreen.kt（新写） |
| 2 | 课表 | JWGLXT API | 已有 ZfAcademicAdapter + ScheduleRepository，M1 收口 |
| 3 | 考试 | JWGLXT API | jwglxt/JwglxtSource.kt + AcademicStudyParser 复用 |
| 4 | 成绩 | JWGLXT API | 同上，GradeSemesters.kt 已有分学期模型 |
| 5 | 学业进度 | JWGLXT API（培养方案 + 成绩归类，标记「部分/推断」） | jwglxt 域，complete=false 容忍 |
| 6 | 我的课程（THEOL） | THEOL 页面解析 + 登录后静默补充 | theol/TheolSource.kt（新写） |
| 7 | 抢课 | JWGLXT API + 本地时间窗 | 已有 AcademicGrabQueue/Scheduler/ProtocolGrabRunner，M4 只做查询不自动抢（见「不做」清单） |
| 8 | 作业与在线测试 | THEOL 页面 | theol 域，作业列表 M3 |
| 9 | 本地工作包（题目/附件/答案文件） | 本地 | 不搬（桌面文件工作流，Android 无对应场景） |
| 10 | 模型辅助（AI 答题/笔记/PDF） | 外部模型 API | 不搬（与只读边界冲突） |
| 11 | 提交准备（打开学校提交页） | THEOL 写操作 | 不做，只读边界 |
| 12 | 校园通知（教务+THEOL 聚合） | JWGLXT API + THEOL 页面 | notices 聚合页 M3 |
| 13 | 校园邮箱（IMAP） | 邮箱 IMAP 独立授权码 | mail/ImapSource.kt（新写） |
| 14 | 后台收信 | IMAP 定时 | 前台刷新为主，后台受 Android 限制（见 §6-4） |
| 15 | 校历与教学进程 | 教务处离线资料（文本/PDF） | calendar/CalendarSource.kt 文本解析，不做 OCR |
| 16 | 培养计划 PDF | 教务处公开文件 | P2：只存链接 + 系统浏览器打开 |
| 17 | 空闲教室 | JWGLXT 公开接口（北化版 514 行纯客户端） | M1 搬移 |
| 18 | 体测评分 | tygl 系统（SSO）+ 本地估算 | M4：先本地估算，tygl 抓包后再接 |
| 19 | 场馆状态 | MOTION 公开页（免登录） | venue/VenueSource.kt，M4 |
| 20 | 学业预警 / 创新学分 / 第二课堂计算器 | 纯本地计算 | M4 本地计算页，合并为「学业工具」1 个模块 |
| 21 | 学业顾问 Agent | 外部模型 API + 本地快照 | 不搬（见「不做」清单） |
| 22 | Iris QQ 助手 / 本机 API / 外观 3D | — | 不搬 |

**结论**：22 个模块中，Android 端承接 16 个（1-8、12-19），合并后为 14 个交付模块；砍掉 6 个（9、10、11、14 的后台收信、21、22）。THEIA 的两条核心洞察必须保留：**空结果 ≠ 没有数据**（RefreshState 五态，草稿第 2 节）、**单域失败不清空其它域**。

---

## 2. P0/P1/P2 分级表

分级标准：
- **P0** = 没有它产品不成立（登录/课表/快照基础设施，砍掉任何一个 App 失去存在理由）
- **P1** = 高频刚需（每周至少打开一次的确定性需求，或 semester 级不可错过事件：考试/成绩/作业截止）
- **P2** = 长尾（低频、可被学校官方渠道替代、或数据源不稳定）

人日粒度：**1 人日 = 单人 4 小时净编码 + 1 小时云端 CI 验证（体积门禁跑通）**。估算含解析器编写与解析失败兜底，不含抓包调研（单列）。

| 模块 | 优先级 | 数据来源 | 新增依赖 | 人日 | 主要风险 | ≤15MB 预算内 |
|---|---|---|---|---|---|---|
| SSO 原生登录接线（BuctSsoLoginManager → LoginScreen） | P0 | sidportal SSO | 无（9 文件已移植） | 3 | SM2 公钥轮换；BuctSm2Crypto 兜底 + WebView 降级 | 是 |
| 课表收口（多账号/小组件/空教室/提醒） | P0 | JWGLXT | 无 | 8 | 小组件 30 文件搬移回归 | 是 |
| CampusState + CampusStore + Provenance | P0 | 本地 | 无 | 6 | 事务模型设计错误返工 | 是 |
| SyncEngine + SyncPolicy | P0 | 本地 | 无 | 4 | Android 后台限制 | 是 |
| 成绩 + 考试 | P1 | JWGLXT API | 无 | 5 | 接口字段变动（parserVersion 应对） | 是 |
| 学业进度（培养方案归类） | P1 | JWGLXT API | 无 | 5 | 层级不一致需归一化，THEIA 也标「推断」 | 是 |
| 校历（文本解析） | P1 | 公开页/离线资料 | 无 | 3 | 格式年年变 | 是 |
| THEOL 作业/测试列表 | P1 | THEOL 页面 | 无（Jsoup 已有） | 8 | 无公开 API，页面改版即崩；complete=false | 是 |
| THEOL 课程资料浏览 | P2 | THEOL 页面 | 无 | 4 | 同上 | 是 |
| 通知聚合（教务+THEOL） | P1 | JWGLXT + THEOL | 无 | 3 | 双源去重 | 是 |
| 校园邮箱 IMAP | P1 | 邮箱 IMAP | angus-mail ~700KB | 6 | 授权码用户教育；超 12MB 门禁则降 P2 | 是 |
| Dashboard 首屏 | P1 | 本地快照 | 无 | 4 | 无 | 是 |
| 空闲教室查询 | P1 | JWGLXT 公开 | 无 | 1（北化版 514 行现成） | 无 | 是 |
| 场馆状态 | P2 | MOTION 公开页 | 无 | 2 | 公开页随时关 | 是 |
| 体测（本地估算） | P2 | 本地计算 + 手工录入 | 无 | 2 | 仅估算 | 是 |
| 学业工具（创新学分/第二课堂/学业预警） | P2 | 本地计算 + 手工录入 | 无 | 4 | 规则口径需对照手册 | 是 |
| 抢课查询（只查不抢） | P2 | JWGLXT | 无 | 3 | 风控敏感 | 是 |
| 导出（ICS/CSV/JSON） | P2 | 本地 | 无 | 2 | 无 | 是 |
| 企业微信 A 链路（学校自建） | P1（若存在）/P2 | qy.buct.edu.cn（待抓包） | 无 | 6 + 3 抓包 | 域名/接口未知 | 是 |
| 企业微信 B 链路（标准版） | P2 | open.work.weixin.qq.com | 无 | 4 | 需 corpId/secret 或 OAuth；学生侧通常无 | 是 |

P0 合计 21 人日；P0+P1 约 61 人日；全量约 80 人日（单人、含 CI 验证）。企业微信 A/B 的优先级由 §4 判断流程的抓包结果决定，表内按「最坏情况都要做」估。

---

## 3. 四阶段里程碑（M1→M4，每阶段可安装可演示）

> 修订 1：草稿是 M1→M5（企微单列 M5）。本文件将企微并入 M4 可选切片 + M3 架构预留，因为「等抓包」不应阻塞主线；抓包提前完成可按 §4 判断流程提前插入。M1-M4 内容与草稿一致，补齐演示脚本与验收数字。

### M1 · 收口现有（3 周 / 15 人日）

模块：登录接线、小组件换上游（30 文件）、空教室（514 行）、自定义提醒（494 行）、多账号 AccountSwitcher（285 行）、默认作息三档。

交付物：versionCode 140，release-v1.1.0-m1 tag，APK ≤ 9MB。

演示脚本（手机 5 步）：
1. 首次启动 → 输入学号密码 → 原生登录成功（全程无 WebView）
2. 课表按周显示，今天课程高亮
3. 桌面添加小组件 → 显示今日课程，左右切日期
4. 设置里切换第二个账号 → 课表与小组件同步切换
5. 空教室页按节次查询 → 返回列表

验收标准：
- APK ≤ 9MB（CI 体积门禁 > 12MB fail）
- 冷启动到课表首屏 ≤ 2.5s（中端机档，本地 5 次中位数）
- 登录连续 20 次成功（断网失败有明确错误提示）
- 课表与教务系统网页逐格对照 0 差异（1 周 20 节课抽查）

退出条件（不满足不许进 M2）：登录或课表有 P0 级未修复 bug（崩溃/数据错格）；APK > 9MB；小组件与账号切换联动有已知缺陷。

### M2 · 教务全量（4 周 / 20 人日）

模块：campus/source/jwglxt + CampusState/CampusStore/SyncEngine + 成绩 + 考试 + 学业进度 + 校历。THEOL/邮箱/企微等域只做接口预留（CampusSource 可插拔验证）。

交付物：versionCode 150，APK ≤ 10MB。

演示脚本：
1. 登录后下拉刷新 → 同步页逐域显示 jwglxt 域绿色
2. 成绩页：分学期 GPA + 趋势
3. 关闭 Wi-Fi 和数据 → 杀进程重启 → 成绩/考试仍显示，标注「数据来自 X 时刻」（FAILED_FALLBACK）
4. 考试页筛选未来考试 → 显示倒计时
5. 校历页显示教学周与假期

验收标准：
- 断网重启后 100% 显示上次快照且时间戳正确
- 故意输错密码刷新 → 旧数据不被清空（逐域事务提交验证）
- 成绩与教务网页逐科对照 0 差异（2 学期约 30 科）
- 同步 6 域总耗时 ≤ 15s（校园网内）

退出条件：任一域「失败却清空旧快照」；快照体积无上限增长（> 5MB 需清理策略）；体积 > 10MB。

### M3 · 在线与通知（5 周 / 25 人日）

模块：TheolSource（课程列表/作业/测试/通知）+ ImapSource（angus-mail）+ 通知聚合页 + CampusSource 插拔改造（为企微预留 wecom/WecomSource.kt 空位）。

交付物：versionCode 160，APK ≤ 12MB（angus-mail 进来是体积最危险的一步）。

演示脚本：
1. 同步后 THEOL 作业列表显示「待完成/已提交」
2. 通知聚合页：教务 + THEOL 通知混排，按来源筛选
3. 邮箱：输入独立授权码 → IMAP 列表加载 → 点开一封读正文
4. 「今天」聚合卡：今日作业截止 + 未读通知 + 未读邮件计数
5. 演示单域失败：THEOL 失败但教务域数据与 UI 状态不受影响

验收标准：
- 单域失败隔离：人为断 THEOL 域，教务域不受影响
- 邮箱 50 封列表加载 ≤ 5s，正文按需加载
- APK ≤ 12MB（超则邮箱降 P2、挪 M4 后）
- THEOL 解析失败率 < 10%（对照 20 门课页面，失败页 complete=false 标注）

退出条件：THEOL 解析 3 门课以上崩溃；体积 > 12MB；邮箱授权流程 3 人用户测试 2 人失败。

### M4 · 工作台 + 企微（5 周 / 24 人日，企微按 §4 结果 6-10 人日）

模块：DashboardScreen（首屏）+ 场馆 + 体测本地估算 + 学业工具三件套 + 导出 + 企微链路（按 §4 判断流程选定 A 或 B）。

交付物：versionCode 170，APK ≤ 15MB（最终门禁）。

演示脚本：
1. 冷启动 → 3 秒内首屏出「今日常课 + 下一节 + 待办 + 考试倒计时」（纯本地快照）
2. 企微卡片：待办/通知列表（只读）
3. 场馆状态查询（公开页免登录）
4. 学业工具：录入 2 条创新学分项目 → 估算值更新
5. 导出 ICS 到系统日历

验收标准：
- 首屏 3s 内出内容（读本地快照，无网环境同样达标）
- 企微数据与企业微信 App 内列表逐条对照一致（10 条）
- 全程无网络首屏可渲染 5 张卡
- APK ≤ 15MB；崩溃率（30 天窗口）< 0.5%

退出条件（= 项目收口条件）：企微只读链路存在任何静默写操作；体积 > 15MB；首屏 > 3s。

---

## 4. 企业微信两条链路

### A. 学校自建版（假设 qy.buct.edu.cn / 学校自有域）

前提：北化若像多数部署金智一站式的高校，企业微信入口是 SSO 域内 H5，登录走 sidportal 换票。抓包结论成立才走此链路（未抓包前全部视为假设）。

能拿到什么（按可行性排序）：通知列表、待办列表、审批列表（只读，自己的）、通讯录只读（本部门）、校历、班车时刻。全部只读。

抓包方法（分步骤，单人一台手机可完成）：
1. 手机装 HttpCanary（或 PC Fiddler + 手机代理，需安装学校 CA 证书），开启 TLS 解密
2. 手机企业微信里打开一个学校应用（如「移动校园」），HttpCanary 过滤域名含 buct.edu.cn
3. 看应用首页加载的 URL：**判断依据 ①** 出现 /sso/ 路径或 ?ticket= 参数 → SSO 换票，可复用 CAS 会话；**判断依据 ②** URL 域名是 qy.buct.edu.cn 还是重定向到 open.work.weixin.qq.com → 决定走 A 还是 B
4. 记录后续 XHR 三要素：Referer、Cookie（是否有 jwglxt 同源 Cookie）、X-Csrf-Token（有则需先 GET 首页提取）
5. curl 复放请求：带自己 Cookie 直连成功 → 可原生接入；返回 401/跳扫码 → 不能复用，走 B
6. 响应是 JSON（多数金智移动门户是 JSON API）→ 直接照接口建模；是 HTML → 走 AcademicParsing 同款宽松解析

复用 CAS 会话流程（复用现有基础设施，不新写登录）：
1. BuctSsoSession 先完成 sidportal 登录（已有 BuctSsoLoginManager）
2. WebView 打开企微 H5 入口 URL，把 CookieManager 中 sidportal Cookie 注入（指定 domain=.buct.edu.cn）
3. 服务端 302 → 带 ticket 落到企微 H5 首页 → WebView 的 CookieJar 里出现企微域会话 Cookie
4. 通过 CookieManager.getCookie(url) 把企微域 Cookie 导出到原生 OkHttp CookieJar（与 AcademicSessionStore 同模式）——这正是 THEIA-Android 死掉的那一步，但方向反过来：**原生持有 SSO，WebView 只在首次换取企微会话时出现一次，之后全原生**
5. 后续请求全走 OkHttp + BuctTlsTrustManager

存储与会话并存策略：企微 Cookie 单独 WecomSessionStore（与 AcademicSessionStore 平级），EncryptedSharedPreferences 落盘，失效判定 = 企微 API 返回 401 → 重新走步骤 2-4 一次静默换取（SessionRenewer 扩展 wecom scope）。

### B. 腾讯企业微信标准版（open.work.weixin.qq.com）

能接：应用消息、待办、日程（需要企业自建应用 + corpId/secret 的服务端配合，学生个人 App 无凭据可用）；个人侧唯一可行的是 OAuth 网页授权（需学校管理员配置可信域名）。**现实结论：B 链路对学生侧几乎不可用**，除非用户提供自己组织的 corpId/secret（如社团/实验室企业微信）。

需要凭据：corpId + corpsecret（服务端换 access_token）或 OAuth code（网页授权，redirect 需可信域名）。

无法复用 SSO 时：接受双会话并存——企微数据独立域 wecom，独立登录态独立过期，UI 上企微卡片显示独立「未连接」态（NEVER），不与教务会话生命周期耦合。

### 对比表 + 判断流程（3 步定链路）

| | A 学校自建 | B 腾讯标准版 |
|---|---|---|
| 域名 | *.buct.edu.cn | open.work.weixin.qq.com |
| 凭据 | 复用 SSO Cookie | corpId/secret 或 OAuth code |
| 登录 | SSO 换票一次 | 独立登录/扫码 |
| 能拿 | 通知/待办/审批/通讯录只读/校历/班车 | 应用消息/待办/日程（需服务端） |
| 学生可用性 | 高（有学号即可） | 低（需管理员凭据） |
| 实现人日 | 6（含抓包结论落地） | 4 |
| 存储 | EncryptedSharedPreferences Cookie | access_token（内存 + 加密落盘） |

用户拿到手机 3 步判断：
1. 企业微信 App → 工作台 → 看学校应用入口 URL 域名：buct.edu.cn 系 → A；qq.com 系 → B
2. A：入口跳转是否经过 sidportal 登录页（是 → 可复用会话）
3. B：是否拥有 corpId/secret（否 → 企微功能降级为「只读公告卡片」或直接砍掉）

### 合规边界（产品红线，写进 UI）

不代打卡、不代审批、不自动回复、不静默上报。UI 体现：
- 企微所有卡片右上角固定「只读」角标
- 任何指向写操作的链接（审批详情的「去处理」）只提供「在系统浏览器打开」按钮，App 内不渲染提交表单
- 企微域同步前首次弹一次确认对话框（「以下操作只读取列表，不会替你提交任何内容」），结果记入 SharedPreferences 不再骚扰
- SyncPolicy 中企微域默认手动刷新；App 内根本不存在企微写路径（架构上保证，不是 UI 约定）

---

## 5. 工作台首屏信息架构

首屏卡片按「打开 App 最想看的 3 件事」排序：① 现在下一节上什么课（含地点）② 今天要交什么 ③ 什么时候考试。

| 顺位 | 卡片 | 数据域 | 空态 | 加载态 | 失败态 | 过期态 |
|---|---|---|---|---|---|---|
| 1 | 下一节课（倒计时 + 教室） | schedule | 「今天没有课」EMPTY_CONFIRMED | 骨架 + 上次快照灰显 | 「课表同步失败」+ 重试按钮 | 顶部黄条「课表来自 X 时刻」STALE |
| 2 | 今日待办（作业截止 + 企微待办合并） | theol + wecom | 「今天没有待办」 | 计数骨架 | 「待办读取失败，显示 X 时刻数据」 | 黄条同上 |
| 3 | 考试倒计时（最近一场） | exams | NEVER≠EMPTY 时显示「未同步」而非「没有」 | 骨架 | 同上 | 同上 |
| 4 | 未读通知 + 未读邮件（合计数） | notices + mail | 0 徽标时隐藏卡片 | 骨架 | 域级失败只影响本卡 | 同上 |
| 5 | 校历条（本周第 X 周 / 假期提示） | calendar | 永不空（内置默认学期） | 立即显示（本地） | 不失败（纯本地） | 换季时提示 |

四态对应 RefreshState：空态 = EMPTY_CONFIRMED 或 data==null&&NEVER（显示「未同步」而非「没有」）；失败态 = FAILED_FALLBACK 显示旧数据 + 时间；过期态 = STALE 黄条；加载态 = 骨架 + 灰显旧数据。

底部导航 4 tab（砍掉草稿未定的第 5 个）：
1. **工作台**（Dashboard 首屏）
2. **课表**（含考试/成绩入口，学业域聚合）
3. **消息**（通知聚合 + 邮箱 + 待办）
4. **我的**（账号/同步状态/设置/学业工具/企微连接）

取舍理由：不单设「工具」tab——场馆/体测/创新学分等低频功能放「我的」入口，避免 5tab 稀释高频路径；成绩/考试挂课表 tab，因为用户心智里它们同源（教务）。

---

## 6. 「不做」清单（12 条）

1. **WebView 登录**：硬约束 3；THEIA-Android 已验证死路（CAS Cookie 无法交给原生 HTTP）。
2. **Capacitor/Cordova/React Native 壳**：同上，且体积失控。
3. **代抢课自动执行**：Android 后台不可靠 + 风控风险（账号封禁代价 > 抢课收益）。只做查询 + 开抢前本地闹钟提醒。
4. **THEOL 作业代提交 / 答案写入**：写操作破坏只读定位，一次误操作代价高于整个功能收益。THEIA 自己也要求用户最终在学校页面提交。
5. **AI 学业顾问 / 模型辅助答题**：外接模型 API 的 key 管理、体积、隐私三重成本，受众极窄；导出 JSON 已覆盖「喂给 AI」需求。
6. **后台自动收信（常驻 IMAP 轮询）**：Android 后台限制下可靠性差 + 耗电口碑风险；前台刷新 + 手动间隔已够。
7. **校园地图**：基线已删高德+MapLibre（16MB），体积预算不容；学校有官方地图渠道。
8. **OCR 校历/培养方案识别**：已删 ONNX（16MB）；文本解析 + PDF 链接已够。
9. **本机 loopback API（THEIA 的 127.0.0.1 服务）**：桌面集成场景，Android 无消费方。
10. **Iris QQ 机器人**：QQ Bot 凭据 + 常驻服务，单人维护无余力。
11. **主题 3D 背景 / glb 模型**：体积 + 与工具属性不符。
12. **课表 PDF 导出（教务原生流程）**：手机端无人用，流程脆弱（THEIA README 自称「估计没人用」）。

---

## 7. 与规划草稿的差异汇总（需合并回主规划）

1. **M5 企微并入 M4**（M4 = 工作台+企微），主线 4 阶段，总周期约 17 周。
2. **邮箱依赖选 angus-mail（~700KB）而非 jakarta.mail（1.6MB）**，这是 M3 体积门禁设在 12MB 的依据。
3. **企微 A 链路给出具体接线方案**：WebView 仅首次换取企微 Cookie（一次性），之后全原生 OkHttp——补上草稿只写「可复用」没写怎么复用的缺口；§4 步骤 2-4 是关键，与 THEIA-Android 的死法方向相反。
4. 其余（架构、数据模型、包结构、风险表）与草稿一致，无冲突。

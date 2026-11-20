<div align="center">

# BUCT One · 北化一站

**北京化工大学（BUCT）原生液态玻璃校园工作台**

面向北京化工大学（BUCT）师生的原生 Android 校园客户端：一次原生登录学校统一身份认证，串起课表（含官方校历自动校准）、桌面小组件、成绩与培养方案、选课抢课、教务调停课消息、北化在线、i北化（校内资讯与图书馆在借）、第二课堂五育综测、实践教学、体测与体育、校园邮箱与校园一卡通——本地优先、零商业埋点 SDK。

[![Release](https://img.shields.io/github/v/release/STAR-10086/buct-one?style=flat-square&label=Release&color=0A84FF)](https://github.com/STAR-10086/buct-one/releases)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue?style=flat-square)](LICENSE)
[![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-34a853?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin + Compose](https://img.shields.io/badge/UI-Kotlin%20%2B%20Compose-7c3aed?style=flat-square)](https://developer.android.com/compose)
[![Code Check](https://img.shields.io/github/actions/workflow/status/STAR-10086/buct-one/check.yml?style=flat-square&label=code-check)](https://github.com/STAR-10086/buct-one/actions/workflows/check.yml)
[![Build APK](https://img.shields.io/github/actions/workflow/status/STAR-10086/buct-one/build-apk.yml?style=flat-square&label=build-apk)](https://github.com/STAR-10086/buct-one/actions/workflows/build-apk.yml)

[官网下载 (buct.one)](https://buct.one) · [GitHub Releases](https://github.com/STAR-10086/buct-one/releases) · [更新日志 (CHANGELOG)](CHANGELOG.md) · [上游声明 (NOTICE)](NOTICE) · [路线规划](docs/规划.md) · [签名接入](docs/签名接入.md)

</div>

---

## 设计理念

- **一次认证，多站联动**：全原生 Kotlin 实现 `sidportal.buct.edu.cn` 统一身份认证（SM2/AES + 短信二次验证 + Passkey/WebAuthn，无 WebView 登录），单次登录后免密直连教务（课表/成绩/选课/教务消息）、北化在线、i北化（官方校历/校内资讯/图书馆在借）、第二课堂、实践教学、体测与体育及校园邮箱，并由跨模块 `SsoSilentRelogin` 协调器在会话失效时单飞静默续期。
- **校内校外混合 DNS 解析（`BuctDns`）**：仅限校内/VPN 的第二课堂优先直连校内 DNS `202.4.130.100`，其余校内业务优先走系统 DNS 并支持故障回退，兼顾校内外访问体验。
- **本地优先，离线可用**：全部快照按域原子落盘；遵循数据层四条铁律，同步失败绝不清空已有缓存，课表、成绩、作业、邮件与桌面小组件时刻可读。
- **隐私与安全底线**：零第三方商业统计 SDK（仅接入自托管 Umami 匿名统计，绝不采集学号、姓名或硬件指纹，支持在设置中一键关闭）；本地按开关记住的凭据均经 `Android Keystore`（AES-256-GCM）独立别名加密存储，显式排除出云备份与设备迁移；内存 `char[]` 用后清零，全网强制校方证书链校验（`BuctTlsTrustManager`）。

---

## 核心功能一览

### 1. 2D 自由栅格工作台
- **4 列 × N 行自由坐标骨架**：聚焦 `北化在线`、`第二课堂`、`校园卡`、`实践教学`、`体测与体育`、`校园邮箱`、`i北化`、`教务消息`、`空闲教室` 与 `选课抢课` 十大无重复核心模块，每张卡片均可自由设为 **`2×1` 大卡片 / `1×1` 中卡片 / `0.5×0.5` 迷你小卡片**；支持单列竖排并保留空白格位，不再强行填满行。
- **全屏悬浮编辑与动画补位**：进入自定义后，长按卡片直接**悬浮拖放到任意坐标槽位**，周围卡片以弹簧物理动效平滑让位；点击或拖动卡片**外置右上角双向斜箭头**实时调节尺寸，外置左上角 `×` 隐藏并用底部停靠栏加回，标题文字零遮挡。
- **顶部固定操作栏**：编辑态顶栏常驻屏幕最上方（纯图标 **撤回 / 重置** + 完整 **取消 / 保存**），支持最多 30 步撤回。

### 2. 课表与桌面小组件
- **多视图、多学期与官方校历自动校准**：周视图 / 日视图完整展示课程、教师与教室；支持学年及第一 / 第二 / 小学期切换与往年课表回看；首次同步 i北化后自动对齐学校官方校历开学日期与当前教学周（同时保留手动覆盖与一键重校准能力）。
- **日程管理与扩展**：自定义课程、课前提醒、空闲教室实时查询、全校选课 / 队列抢课、ICS 日历导出与多账号切换。
- **桌面小组件（OriginOS / HyperOS 适配）**：提供周课表与可滚动的今日课表小组件（最小支持 `2×2`），采用独立无标题布局 XML、按实例隔离的偏移存储与局部增量刷新（`partiallyUpdateAppWidget`）、圆角矢量绘制与 `clipToOutline` 裁剪，修复标题无法隐藏与翻页回跳问题。

### 3. 在线教学（北化在线 THEOL / MEOL）
- **两阶段后台静默同步（`parserVersion = 5`）**：第一阶段优先刷新个人中心待办作业与测验；第二阶段通过单通道互斥队列在后台静默补齐全部在修课程（包含已完成全部作业、退出个人中心提醒列表的课程），且不阻塞前台点开作业详情与附件下载。
- **原生体验交作业**：先原生预览作业要求与附件，支持附件原生下载与系统应用打开，再拉起共享会话的 WebView 一键提交。
- **降噪工具**：支持忽略/隐藏单条作业、屏蔽已退课程、在线测验查看与本地自定义待办清单。

### 4. 校内专属域直连

| 模块 | 接入域名 | 核心能力 |
| :--- | :--- | :--- |
| **i北化（资讯 / 图书 / 校历）** | `i.buct.edu.cn` / `mnews.buct.edu.cn` | CAS 单点登录拆解三大高频接口：官方校历与当前教学周自动校准、校内综合资讯四栏聚合（通知公告 / 活动公告 / 招聘信息 / 北化新闻）及正文阅读、图书馆在借清单与应还天数提示 |
| **教务消息** | `jwglxt.buct.edu.cn` | 独立采集教务系统个人消息（调停补课、考试安排与选课提醒）及教务通知公告，支持分类筛选、关键词搜索与详情查看 |
| **校园邮箱** | `mail.buct.edu.cn` | CAS 单点登录免密换票直连网易企业邮箱 `/js6/s`；收件箱 / 未读 / 多文件夹 / 搜索 / 分页，点击未读即实时同步官方已读状态，支持移至已删除、彻底删除、从已删除还原与未读页一键全部标已读，安全 HTML 阅读正文并原生下载打开附件 |
| **体测与体育** | `tygl.buct.edu.cn` | 历年体质测试总分与单项成绩明细、学期阳光跑 / 课外锻炼进度与打卡记录，内置 2014《国家学生体质健康标准》离线算分模拟器 |
| **第二课堂** | `scs.buct.edu.cn` / `eapi.buct.edu.cn` | 校内网/VPN 下直连；原生 CAS 换取 JWT，德智体美劳五育综测总分、分项基础/拓展得分明细、二课活动清单与自评窗口状态 |
| **实践教学** | `sjcx.buct.edu.cn` | 实验排课课表、开放讲座一键抢报（支持满员捡漏与前台蹲守）、已报讲座退选与心得、创新学分明细查询 |
| **校园一卡通** | `ecard.buct.edu.cn` | 独立认证直连查询实时余额、挂失状态与消费流水；支持可选开启本机 `Android Keystore` 加密记住密码并在会话过期时自动静默重登，认证失败或退出登录自动抹除凭据；支持直达企业微信 / 微信 |
| **本科 / 研究生教务** | `jwglxt.buct.edu.cn` / `gmis.buct.edu.cn` | 在「我的 → 教务系统（本科 / 研究生）」切换，覆盖课表、GPA 统计、成绩分布、培养方案与考试倒计时 |

---

## 「我的」、更新与界面系统

- **五栏液态玻璃底栏与全局预测性返回手势**：居中工作台「**课表 · 成绩 · 工作台 · 作业 · 我的**」，采用统一 16f 笔画的 Phosphor 双色线条动画图标，顶栏标题按可用宽度自适应字号缩放，支持暗色模式、自定义壁纸与关闭毛玻璃降级；全局适配 Android 14+ 预测性返回手势（跨 Activity 侧滑预览 + 应用内二级页 / 弹窗跟手缩放圆角动效，支持在「我的 → 外观」实时开关）。
- **个人名片、权限管理与隐私开关**：顶部卡片支持从系统相册选取自定义头像、填写展示昵称与个性签名，并与「更新 Cookie」（数据与同步分组）、通知 / 精确闹钟权限自检、自托管匿名使用统计开关（数据与安全分组）同页收纳。
- **App 内自动更新与手动检查**：接入 `https://update.buct.one/update.json` 与 `dl.buct.one`（Cloudflare R2 + GitHub Release 双源分发），按设备 ABI 自动匹配安装包，执行 SHA-256 与正式签名双重校验；支持「我的 → 应用与支持 → 检查更新」手动立即检查。

---

## 系统架构与安全约定

### 跨域数据流

```text
sidportal 统一身份认证 ──┬─→ jwglxt / gmis        教务系统（课表 / 成绩 / 培养方案 / 考试 / 选课 / 教务消息）
  （全原生 Kotlin）     ├─→ i.buct / mnews       i北化（官方校历教学周校准 / 校内四栏资讯 / 图书馆在借）
                        ├─→ course.buct.edu.cn   北化在线（两阶段同步作业 / 测验 / 附件）
                        ├─→ sjcx.buct.edu.cn     实践教学（实验课表 / 抢讲座 / 创新学分）
                        ├─→ scs.buct.edu.cn      第二课堂（五育综测 / 二课活动）
                        ├─→ tygl.buct.edu.cn     体测与体育（体测成绩 / 阳光跑 / 国标算分）
                        └─→ mail.buct.edu.cn     校园邮箱（收件箱 / 未读同步 / 删除还原 / 附件）
ecard.buct.edu.cn      ───→ 校园卡独立会话       余额 / 流水 / Keystore 加密静默续期 / 企微跳转
                                   │
                   分域采集 → 版本化解析 → 原子落盘（失败不覆盖旧快照）
                                   │
                         界面与桌面小组件按需读取
```

### 包依赖与数据铁律（CI 硬门禁）

`com.buct.one.data` 严格遵循单向分层，`.github/workflows/check.yml` 会在每次提交时执行静态扫描：

```text
ui/*      → store、session、model（禁止直接 import source/*）
sync/*    → source、store、session（禁止依赖 ui）
source/*  → session、model（禁止跨子包依赖其他域 source/*）
store/*   → model（禁止依赖 source、sync、ui）
model/    → 零任何外部 import
```

同时所有数据域遵守四条铁律：
1. **失败永不产生新 `data`**：网络异常仅更新 `refreshState` 与 `lastError`，完整保留旧缓存；
2. **`NEVER` ≠ `EMPTY_CONFIRMED`**：未曾同步与确认无数据严格区分；
3. **`parserVersion` 只增不减**：调整解析规则必递增版本号并触发平滑迁移；
4. **`capturedAt` 单调不减**：抵御系统时钟回拨。

### 安全机制清单

- **凭据安全**：内存使用 `char[]` 用后清零，学号与密码绝不写明文；多个 session/凭据 vault 分别采用独立 `Android Keystore`（AES-256-GCM）别名加密，校园卡/统一身份认证密码校验失败或退出登录时立即擦除对应密文。
- **备份隔离**：`backup_rules.xml` 与 `data_extraction_rules.xml` 显式排除全部 vault 文件，防止系统云备份或设备迁移带出密文。
- **网络安全**：强制走 `BuctTlsTrustManager` 校方证书链，CI 静态禁止任何 `trustAllCerts` / 放行任意 `HostnameVerifier`。
- **日志脱敏**：保留诊断所需域名与状态码，学号、姓名与成绩数值自动掩码。

---

## 技术栈

| 维度 | 实现方案 |
| :--- | :--- |
| 语言与 UI | Kotlin + Java + Jetpack Compose（Kyant Backdrop 液态玻璃） |
| 系统兼容 | 最低 Android 7.0（API 24），`compileSdk android-37.0` / `build-tools 37.0.0` |
| 包名标识 | `com.buct.one`（源码包名 `com.buct.course` / `com.buct.one.data`） |
| 网络与安全 | OkHttp + BouncyCastle（SM2）+ `BuctTlsTrustManager` + `Android Keystore` |
| 持久化 | 分域 JSON 原子落盘 + 加密会话 Vault + `SharedPreferences` |
| 构建与交付 | 单 module，全云端 GitHub Actions CI（JDK 21，100MB APK 体积硬门禁，R2 + Release 双源发布） |

---

## 从源码构建

项目已配置全云端 CI（推送 `main` 或发起 PR 自动运行 `code-check` 与 `build-apk`；推送 `v*` 标签自动运行 `release-apk`）。如需本地调试，请准备 **JDK 21**、`compileSdk android-37.0` 与 `build-tools 37.0.0`：

```bash
./gradlew assembleRelease
```

正式发布签名配置与 Keystore 规范请参阅 [签名接入指南](docs/签名接入.md)。

---

## 项目路线图

- [x] 工作台 2D 自由坐标栅格（`2×1` / `1×1` / `0.5×0.5`）及固定顶栏、悬浮拖拽、箭头缩放与撤回保存
- [x] 选课与队列抢课原生工作台（可选列表 / 已选退课 / 定时并发抢课）
- [x] 北化在线全量在修课程两阶段后台静默同步与作业交取闭环
- [x] 第二课堂 `scs.buct.edu.cn` 五育综测成绩、明细与活动原生接入（含校内 DNS 优先解析）
- [x] 实践教学（实验课表 / 抢讲座 / 创新学分）与校园一卡通直连（含本机 Keystore 加密自动重登）
- [x] 体测与体育管理平台 `tygl.buct.edu.cn` 原生接入与 2014 国标离线算分工具
- [x] 北化校园邮箱 `mail.buct.edu.cn` 原生 CAS SSO 收件箱（点击标已读同步官方 / 删除与还原 / 一键全部已读 / 附件下载）
- [x] i北化 `i.buct.edu.cn` / `mnews.buct.edu.cn` 官方校历教学周自动校准、校内四栏资讯流与图书馆在借清单原生接入
- [x] 教务系统 `jwglxt.buct.edu.cn` 个人调停课消息与教务通知公告原生模块
- [x] 全局 Android 14+ 预测性返回手势（含设置开关）与自托管 Umami（`stats.buct.one`）匿名活跃统计
- [x] 统一身份认证跨模块自动静默重登（`SsoSilentRelogin`）与 App 内自动更新（`dl.buct.one` / `update.buct.one`）
- [x] 桌面小组件 HyperOS / OriginOS 无标题布局、增量翻页与圆角适配
- [ ] 北化在线课程资料 / 课件目录只读浏览与原生下载
- [ ] 本地轻量提醒（作业 DDL / 考试倒计时 / 调停课 / 图书到期 / 校园卡低余额）与 DDL / 余额桌面小组件扩展
- [ ] 新校园网络与生活域调研与原生接入（`tree.buct.edu.cn` 校园网认证与流量状态 / 东区↔昌平通勤班车时刻表）
- [ ] 成绩 GPA 试算、校园卡消费统计与各数据域 Parser 离线单测回归

> 完整分阶段规划与接入边界详见 [路线规划 (docs/规划.md)](docs/规划.md)。

---

## 目录结构

```text
app/src/main/java/
  com/buct/course/    主应用 UI、独立页（一卡通/实践教学/第二课堂/体测/校园邮箱/i北化/教务消息）、教务适配、自动更新、匿名统计、桌面小组件
  com/buct/one/data/  纯分层数据内核（model / session / source / store / sync）
docs/                 路线规划（规划.md）与正式发布签名配置（签名接入.md）
.github/workflows/    GitHub Actions 静态门禁（check.yml）、APK 构建（build-apk.yml）与标签发版（release-apk.yml）
NOTICE                上游开源工程溯源与许可声明
LICENSE               GNU General Public License v3.0
```

---

## 开源致谢与许可

当前维护者：[STAR-10086](https://github.com/STAR-10086)

本项目整合改造自以下优秀开源工程与图标/渲染库，完整版权声明见 [NOTICE](NOTICE)：

- **[zhengfang-apk](https://github.com/znjhahaha/zhengfang-apk)**（GPL-3.0）— 液态玻璃界面体系、胶囊导航、教务解析与桌面小组件架构
- **[beihua-kebiao](https://github.com/lioanchijian/beihua-kebiao)**（GPL-3.0）— 北化原生 `sidportal` 认证、`BuctTlsTrustManager` 信任链与教务/研究生适配
- **[go-buct-course](https://github.com/Ling0727-ai/go-buct-course) / [BUCT-course-remind](https://github.com/Ling0727-ai/BUCT-course-remind)**（MIT）— 北化在线（THEOL/MEOL）协议与待办建模参考
- **[THEIA](https://github.com/bakahuiii/THEIA)**（MIT）— 校园工作台形态、Provenance 数据溯源与状态隔离设计思想
- **[Phosphor Icons](https://github.com/phosphor-icons/core)**（MIT）— 双色矢量导航图标语言
- **[Backdrop (Kyant0)](https://github.com/Kyant0/AndroidLiquidGlass)**（Apache-2.0）— Jetpack Compose 液态玻璃材质基础

本项目整体遵循 [GNU General Public License v3.0 (GPL-3.0)](LICENSE) 开源协议发布。

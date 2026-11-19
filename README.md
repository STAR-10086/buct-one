<div align="center">

# BUCT One · 北化一站

**BUCT One · 北化一站 — 北京化工大学校园工作台（Android / Kotlin + Compose）**

集课表查询、桌面小组件、成绩与培养方案、北化在线（THEOL/MEOL）、第二课堂五育综测、实践教学、校园卡及工作台自定义于一体的本地优先校园客户端。

[![Release](https://img.shields.io/github/v/release/STAR-10086/buct-one?style=flat-square&label=Release&color=0A84FF)](https://github.com/STAR-10086/buct-one/releases)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue?style=flat-square)](LICENSE)
[![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-34a853?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin + Compose](https://img.shields.io/badge/UI-Kotlin%20%2B%20Compose-7c3aed?style=flat-square)](https://developer.android.com/compose)
[![Code Check](https://img.shields.io/github/actions/workflow/status/STAR-10086/buct-one/check.yml?style=flat-square&label=code-check)](https://github.com/STAR-10086/buct-one/actions/workflows/check.yml)
[![Build APK](https://img.shields.io/github/actions/workflow/status/STAR-10086/buct-one/build-apk.yml?style=flat-square&label=build-apk)](https://github.com/STAR-10086/buct-one/actions/workflows/build-apk.yml)

[下载正式版 (Releases)](https://github.com/STAR-10086/buct-one/releases) · [上游声明 (NOTICE)](NOTICE) · [规划](docs/规划.md) · [数据层设计](docs/plan-1-data-layer.md) · [签名接入](docs/签名接入.md)

</div>

---

## 设计理念

- **一次认证，多站联动**：全原生 Kotlin 实现 `sidportal.buct.edu.cn` 统一身份认证（SM2/AES + 短信二次验证 + Passkey/WebAuthn，无 WebView 登录），单次登录后免密直连教务、北化在线、第二课堂与实践教学。
- **本地优先，离线可用**：全部快照按域原子落盘；遵循数据层四条铁律，同步失败绝不清空已有缓存，课表、成绩、作业与桌面小组件时刻可读。
- **隐私与安全底线**：关闭全部第三方统计与遥测；学号密码绝不落盘；凭据使用 `Android Keystore` AES-GCM 独立加密，全网强制校方证书链校验（`BuctTlsTrustManager`）。

---

## 核心功能一览

### 1. 模块化液态玻璃工作台
- **自由尺寸混排（4 格弹性布局）**：支持 `2×1` 大卡片、`1×1` 中卡片与 **`0.5×0.5` 迷你快捷瓷砖** 任意组合，轻松摆满实验课表、抢讲座、创新学分、二课活动、选课抢课、好友课表、成绩考试、待办清单等高频入口。
- **悬浮卡片直接编辑**：进入自定义后，直接**长按卡片悬浮拖拽动画换位**，点击或拖动卡片**右上角双向斜箭头**实时缩放卡片大小，点击左上角 `×` 隐藏并用底部胶囊栏随时加回。
- **可逆操作保障**：屏幕右上角常驻 **撤回（最多 30 步）/ 重置 / 取消 / 保存** 操作栏，调整满意再落盘。

### 2. 课表与桌面小组件
- **多视图与多学期**：周视图 / 日视图完整展示课程、教师与教室；支持学年及第一 / 第二 / 小学期切换与往年课表回看。
- **日程管理与扩展**：自定义课程、课前提醒、空闲教室实时查询、好友课表对比、全校选课 / 队列抢课、ICS 日历导出与多账号切换。
- **桌面小组件**：提供周课表与可滚动的今日课表小组件（最小支持 `2×2`），采用圆角矢量绘制与 `clipToOutline` 裁剪适配 OriginOS / HyperOS 等桌面，支持动态同步学期第一周、自定义字号与自动隐藏已结束课程。

### 3. 在线教学（北化在线 THEOL / MEOL）
- **两阶段后台静默同步（`parserVersion = 5`）**：第一阶段优先刷新个人中心待办作业与测验；第二阶段通过单通道互斥队列在后台静默补齐全部在修课程（包含已完成全部作业、退出个人中心提醒列表的课程），且不阻塞前台点开作业详情与附件下载。
- **原生体验交作业**：先原生预览作业要求与附件，支持附件原生下载与系统应用打开，再拉起共享会话的 WebView 一键提交。
- **降噪工具**：支持忽略/隐藏单条作业、屏蔽已退课程、在线测验查看与本地自定义待办清单。

### 4. 校内专属域直连

| 模块 | 接入域名 | 核心能力 |
| :--- | :--- | :--- |
| **第二课堂** | `scs.buct.edu.cn` / `eapi.buct.edu.cn` | 原生 CAS 换取 JWT，德智体美劳五育综测总分、分项基础/拓展得分明细、二课活动清单与自评窗口状态 |
| **实践教学** | `sjcx.buct.edu.cn` | 实验排课课表、开放讲座一键抢报、已报讲座心得与创新学分明细查询 |
| **校园一卡通** | `ecard.buct.edu.cn` | 独立口令登录后实时查询卡余额、挂失状态与消费流水；通过 `<queries>` 与 `wxwork://` / `weixin://` 直达企业微信/微信充值扫码 |
| **本科 / 研究生教务** | `jwglxt.buct.edu.cn` / `gmis.buct.edu.cn` | 在「我的 → 教务系统（本科 / 研究生）」切换，覆盖课表、GPA 统计、成绩分布、培养方案与考试倒计时 |

---

## 个人中心与界面系统

- **五栏液态玻璃底栏**：居中工作台「**课表 · 成绩 · 工作台 · 作业 · 我的**」，采用统一 16f 笔画的 Phosphor 双色线条动画图标（含「我的」人像轮廓动画），支持暗色模式、自定义壁纸与关闭毛玻璃降级。
- **权限自检管理**：内置 Android 13+ 通知权限引导与精确闹钟课前提醒开关入口，可在「我的 → 通知与系统权限」一站检查。

---

## 系统架构与安全约定

### 跨域数据流

```text
sidportal 统一身份认证 ──┬─→ jwglxt / gmis       教务系统（课表 / 成绩 / 培养方案 / 考试 / 选课）
  （全原生 Kotlin）     ├─→ course.buct.edu.cn   北化在线（两阶段同步作业 / 测验 / 附件）
                        ├─→ sjcx.buct.edu.cn     实践教学（实验课表 / 抢讲座 / 创新学分）
                        └─→ scs.buct.edu.cn      第二课堂（五育综测 / 二课活动）
ecard.buct.edu.cn      ───→ 校园卡独立会话       余额 / 消费流水 / 企微充值跳转
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

- **凭据安全**：内存使用 `char[]` 用后清零，学号与密码绝不写盘；三条 session vault 分别采用独立 `Android Keystore`（AES-GCM）别名加密。
- **备份隔离**：`backup_rules.xml` 与 `data_extraction_rules.xml` 显式排除全部 vault 文件。
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
| 构建与交付 | 单 module，全云端 GitHub Actions CI（JDK 21，100MB APK 体积硬门禁） |

---

## 从源码构建

项目已配置全云端 CI（推送 `main` 或发起 PR 自动运行 `code-check` 与 `build-apk`）。如需本地调试，请准备 **JDK 21**、`compileSdk android-37.0` 与 `build-tools 37.0.0`：

```bash
./gradlew assembleRelease
```

正式发布签名配置与 Keystore 规范请参阅 [签名接入指南](docs/签名接入.md)。

---

## 项目路线图

- [x] 工作台模块化 4 格弹性栅格（`2×1` / `1×1` / `0.5×0.5`）及动画悬浮拖拽、尺寸缩放、撤回保存
- [x] 北化在线全量在修课程两阶段后台静默同步与作业交取闭环
- [x] 第二课堂 `scs.buct.edu.cn` 五育综测成绩、明细与活动原生接入
- [x] 实践教学（实验课表 / 抢讲座 / 创新学分）与校园一卡通直连
- [x] 桌面小组件 HyperOS / OriginOS 圆角与矢量翻页图标适配
- [ ] 全量课程资料目录浏览与课程通知聚合
- [ ] 校园邮箱只读收件（IMAP 授权码接入）与统一校内消息聚合
- [ ] 场馆余量状态与体测成绩估算工具

---

## 目录结构

```text
app/src/main/java/
  com/buct/course/    主应用 UI、活动页（一卡通/实践教学/第二课堂）、教务适配、桌面小组件
  com/buct/one/data/  纯分层数据内核（model / session / source / store / sync）
docs/                 项目规划、数据层设计与签名配置文档
.github/workflows/    GitHub Actions 静态门禁（check.yml）与 APK 构建发版（build-apk.yml）
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

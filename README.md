<div align="center">

# BUCT One · 北化一站

**北京化工大学校园工作台**

在手机上看课表、查成绩、看考试安排、盯作业截止、直达北化在线交作业。
一次原生登录学校统一身份认证，教务系统与北化在线（THEOL/MEOL）共享会话凭证，纯本地存储、零第三方上报。

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue?style=flat-square)](LICENSE)
[![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-34a853?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-7c3aed?style=flat-square)](https://developer.android.com/compose)
[![CI](https://img.shields.io/github/actions/workflow/status/STAR-10086/buct-one/build-apk.yml?style=flat-square)](https://github.com/STAR-10086/buct-one/actions/workflows/build-apk.yml)

[下载正式版 (Releases)](https://github.com/STAR-10086/buct-one/releases) · [上游声明 (NOTICE)](NOTICE) · [规划](docs/规划.md) · [数据层设计](docs/plan-1-data-layer.md)

</div>

## 功能进度

已实现并集成的功能已打勾，其余按规划持续推进，验收标准见 [规划](docs/规划.md)。

### 界面与导航

- [x] 五栏液态玻璃底栏（**课表 · 成绩 · 工作台(居中) · 作业 · 我的**）与深色/自定义壁纸适配

### 课表与小组件

- [x] 周视图与日视图，课程名称、教师、地点完整显示
- [x] 学年与三学期切换（第一学期 / 第二学期 / 小学期，修复春季标签错位并支持切换往年课表）
- [x] 课程详情查看与自定义课程 / 课前提醒
- [x] 桌面小组件（最小支持 2×2，周课表与可上下滑动、按当前时间自动隐藏已上完课程、按行数限流的今日课表）
- [x] 小组件支持隐藏顶部标题、自定义字号，并动态同步用户设置的开学第一周与学期
- [x] 日历导出（ICS）与多账号切换

### 成绩与考试

- [x] 按学期查询成绩、绩点统计与单科明细
- [x] 总体成绩 / 培养方案完成情况查询
- [x] 考试安排与倒计时展示

### 在线教学（北化在线 THEOL / MEOL）

- [x] 复用教务统一身份认证（`sidportal.buct.edu.cn`）会话凭证免二次登录
- [x] 待交作业与全部作业列表，自动过滤已超时/已完成状态
- [x] 点击作业卡片先查看原生抓取的作业要求与附件，支持作业附件原生下载与系统应用打开，再通过内嵌 WebView 复用会话与课程上下文交作业
- [x] 手动删除/隐藏单条作业，或将已退课程加入黑名单不再显示
- [x] 在线测验详情查看、课程主页直达与本地自定义待办
- [ ] 全量课程资料目录浏览与课程通知聚合

### 工作台与校园工具

- [x] 工作台首屏：教务真实姓名展示、下一节课、今日课程、待办作业与测验概览、教学周提示
- [x] 空闲教室查询（工作台独立入口 + 课表空白格点击，含选课/已选课程浏览）
- [ ] 场馆状态、体测成绩估算、创新与第二课堂学分计算

### 邮箱、通知与消息

- [ ] 校园邮箱只读收件（IMAP，独立授权码）
- [ ] 教务通知与在线教学通知聚合、「消息」页统一入口
- [ ] 企业微信通知与待办只读接入

### 基础设施与安全

- [x] 单 module 工程架构（`applicationId = com.buct.one`）
- [x] 全原生 Kotlin 登录流程（SM2/AES + 短信二次验证 + Passkey/WebAuthn，无 WebView 登录）
- [x] 严格隐私策略：关闭全部上游统计与远程上报，移除好友与反馈上报入口
- [x] 凭据由 `Android Keystore`（AES-GCM）加密存储，且从系统自动备份中排除 vault 文件
- [x] 全链校方 TLS 校验（`BuctTlsTrustManager`），绝不跳过证书或主机名验证
- [x] 全云端 GitHub Actions CI + 包依赖静态规则门禁 + 100MB APK 体积门禁
- [x] 正式发布签名接入（已启用正式发布签名构建与 Release 发布，详情见 [签名接入](docs/签名接入.md)）

## 数据怎么走

```
sidportal 统一身份认证 ──┬─→ jwglxt / gmis  教务（课表 / 成绩 / 考试 / 选课）
      （原生登录一次）     └─→ course.buct    北化在线（THEOL 作业详情 / 测验 / 附件）
                                     │
                     按域抓取 → 解析 (parserVersion) → 原子落盘
                                     │
                  旧数据失败保留 + Keystore AES-GCM 会话复用
                                     │
                            界面与桌面小组件直接读取
```

`com.buct.one.data` 分层遵循单向依赖门禁（CI 自动检查）：

```
ui/*      → store、session、model（禁止依赖 source）
sync/*    → source、store、session（禁止依赖 ui）
source/*  → session、model（禁止依赖 store、sync、ui）
store/*   → model（禁止依赖 source、sync、ui）
model/    → 零依赖
```

## 技术栈

| 项 | 选择 |
| --- | --- |
| 语言与界面 | Kotlin + Java + Jetpack Compose（Kyant Backdrop 液态玻璃） |
| 最低版本 | Android 7.0（API 24），`compileSdk android-37.0` |
| 包名 | `com.buct.one`（源码命名空间 `com.buct.course` / `com.buct.one.data`） |
| 构建 | 单 module，全云端 CI（GitHub Actions，JDK 21） |
| 数据存储 | 分片 JSON 原子落盘 + `SharedPreferences` + 私有文件离线缓存 |
| 网络与安全 | OkHttp + BouncyCastle SM2 + `BuctTlsTrustManager` 校方证书链 |
| 凭据保护 | `Android Keystore`（AES-GCM），学号密码绝不落盘 |
| 体积门禁 | 上限 100MB（80MB 预警），当前正式版约 13MB |

## 从源码构建

所有常规构建在 GitHub Actions 完成。推送 `main` 或提 PR 会自动触发 `build-apk` 工作流并运行包依赖检查与体积门禁。

如需本地排查，需要 JDK 21、`compileSdk android-37.0`、`build-tools 37.0.0`：

```bash
./gradlew assembleRelease
```

正式签名所需的 secrets 配置见 [签名接入](docs/签名接入.md)。

## 目录结构

```
app/
  src/main/java/
    com/buct/course/    主应用界面、教务协议适配、桌面小组件、原生活动
    com/buct/one/data/  纯分层数据模块（model / session / source / store / sync）
docs/                   规划与设计文档
.github/workflows/      GitHub Actions 云端构建与门禁
NOTICE                  上游开源工程与版权说明
LICENSE                 GNU General Public License v3.0
```

## 开源致谢与许可

当前维护者：[STAR-10086](https://github.com/STAR-10086)

本项目整合改造自以下优秀开源工程，详细出处与许可声明见 [NOTICE](NOTICE)：

- **[zhengfang-apk](https://github.com/znjhahaha/zhengfang-apk)**（GPL-3.0）— 液态玻璃 UI、胶囊导航、教务解析与桌面小组件基础架构
- **[beihua-kebiao](https://github.com/lioanchijian/beihua-kebiao)**（GPL-3.0）— 北化原生 `sidportal` 认证、TLS 信任链与教务/研究生系统适配
- **[go-buct-course](https://github.com/Ling0727-ai/go-buct-course) / [BUCT-course-remind](https://github.com/Ling0727-ai/BUCT-course-remind)**（MIT）— 北化在线（THEOL/MEOL）协议与待办建模参考
- **[THEIA](https://github.com/bakahuiii/THEIA)**（MIT）— 校园工作台形态、Provenance 溯源与刷新状态隔离思想

本项目整体以 [GNU General Public License v3.0 (GPL-3.0)](LICENSE) 开源发布。

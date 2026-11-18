<div align="center">

# BUCT One · 北化一站

**北京化工大学校园工作台**

在手机上看课表、查成绩、看考试安排、盯作业截止、收校园通知和邮件。
一次登录打通学校统一身份认证，各子系统的数据在本地整理成快照，打开就有。

[![License](https://img.shields.io/badge/License-MIT-64748b?style=flat-square)](LICENSE)
[![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-34a853?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-7c3aed?style=flat-square)](https://developer.android.com/compose)

[规划](docs/规划.md) · [数据层设计](docs/plan-1-data-layer.md) · [功能路线图](docs/plan-2-roadmap.md) · [工程与风险](docs/plan-3-engineering.md)

</div>

## 功能进度

已完成打勾，其余按四阶段计划推进，验收标准见 [规划](docs/规划.md)。

### 课表

- [ ] 周视图与日视图，课程名称、教师、地点完整显示
- [ ] 课程详情与长按复制
- [ ] 桌面小组件（单课 / 双课 / 时间轴 / 今日课程）
- [ ] 课前提醒与自定义提前量
- [ ] 自定义课程与调课
- [ ] 日历导出（ICS）
- [ ] 多账号切换

### 成绩与考试

- [ ] 按学期查询成绩
- [ ] 绩点统计与趋势
- [ ] 考试安排与倒计时

### 学业进度

- [ ] 培养方案对照
- [ ] 已修学分与课程类别统计
- [ ] 学业预警提示

### 在线教学

- [ ] 课程与课程资料列表
- [ ] 作业截止时间与提交状态
- [ ] 在线测试安排
- [ ] 课程通知

### 邮箱与通知

- [ ] 校园邮箱只读收件（IMAP，独立授权码）
- [ ] 教务通知与在线教学通知聚合
- [ ] 「消息」页统一入口

### 校园工具

- [ ] 空闲教室查询
- [ ] 场馆状态
- [ ] 体测成绩估算
- [ ] 创新学分计算
- [ ] 第二课堂学分计算

### 工作台

- [ ] 首屏：下一节课、今日待办、考试倒计时
- [ ] 未读通知与未读邮件计数
- [ ] 教学周与假期提示

### 企业微信

- [ ] 学校自建链路（只读通知与待办）
- [ ] 腾讯标准版链路

### 基础设施

- [ ] Gradle 工程骨架（单 module，`com.buct.one`）
- [ ] 原生 SSO 登录（金智 linkid，无 WebView）
- [ ] 数据层：会话复用与按域 Cookie
- [ ] 数据层：逐域抓取与解析
- [ ] 数据层：分片快照与来源标注
- [ ] 数据层：同步引擎与失败隔离
- [ ] CI 体积门禁与 PR 体积 delta
- [ ] 正式签名接入
- [ ] 官网

数据来自学校各系统，内容与可用操作以学校提供为准。应用只做读取与整理，不代为提交任何操作。

## 数据怎么走

```
sidportal 统一身份认证 ──┬─→ jwglxt  教务（课表 / 成绩 / 考试 / 学业进度）
      （登录一次）        ├─→ theol   在线教学（课程 / 作业 / 通知）
                        ├─→ mail    校园邮箱（IMAP 独立授权码）
                        └─→ venue   场馆（公开页）
                                 ↓
                  按域抓取 → 解析 → 完整性判定
                                 ↓
             分片快照 + 来源／时间／解析器版本／刷新状态
                                 ↓
                      界面只读快照，网络由同步承担
```

### 五种刷新状态

「没同步过」和「学校确实没有」是两件事，界面用五个状态区分：

| 状态 | 含义 | 界面表现 |
| --- | --- | --- |
| `NEVER` | 从未成功同步 | 显示「未同步」 |
| `EMPTY_CONFIRMED` | 同步成功，学校确实没有 | 显示「今天没有课」 |
| `FAILED_FALLBACK` | 本次失败，展示的是旧数据 | 显示旧数据并标注时间 |
| `STALE` | 数据偏旧 | 顶部提示条 |
| `FRESH` | 新鲜 | 无提示 |

抓取失败时旧快照原样保留，不覆盖、不清空；单域失败不影响其它域。

## 技术

| 项 | 选择 |
| --- | --- |
| 语言与界面 | Kotlin + Jetpack Compose |
| 最低版本 | Android 7.0（API 24） |
| 包名 | `com.buct.one` |
| 构建 | 单 module，全云端 CI（GitHub Actions） |
| 数据存储 | 分片 JSON 快照，逐域原子提交 |
| 网络 | OkHttp + 校方证书链校验 |
| 凭据 | Android Keystore（AES-GCM），学号密码不落盘 |
| 体积 | 上限 100MB（CI 硬门禁，80MB 预警） |

## 开发计划

| 阶段 | 内容 | 目标体积 |
| --- | --- | --- |
| M1 | 工程骨架、原生登录、课表与桌面小组件 | ≤ 30MB |
| M2 | 数据层（会话复用／抓取／快照／同步）、成绩、考试、学业进度、校历 | ≤ 50MB |
| M3 | 在线教学、校园邮箱、通知聚合 | ≤ 70MB |
| M4 | 工作台首屏、校园工具、导出、企业微信接入 | ≤ 100MB |

## 从源码构建

构建在 GitHub Actions 完成。推送 `main` 或提 PR 会触发构建，产出 APK 并执行体积门禁；推送 `v*` 标签为正式发版。

本地构建需要 JDK 21、`compileSdk android-37.0`、`build-tools 37.0.0`：

```bash
./gradlew assembleRelease
```

正式签名所需的 secrets 配置见 [签名接入](docs/签名接入.md)。

## 目录

```
app/                    Android 应用
  src/main/java/com/buct/one/
    data/               数据层（session / source / store / sync）
    ui/                 界面
docs/                   规划与设计文档
.github/workflows/      云端 CI
```

## 致谢与许可

产品形态与数据模型参考 [THEIA](https://github.com/bakahuiii/THEIA)（北京化工大学同校校园工作台，MIT，已停更）：来源与时间标注、五种刷新状态、单域失败隔离，都源自它踩过的坑。

金智统一身份认证协议与正方教务接口的调用方式，参考 `zhengfang-apk` 一系的开源实现（GPL-3.0）。本项目重新实现，不包含其源码。

以 [MIT](LICENSE) 许可发布。
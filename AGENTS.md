# 开发约定

## 构建

- **本机不跑 gradle 构建**。所有构建走 GitHub Actions，本地只改代码。
- 需要看构建结果：`gh run list --limit 5` / `gh run view <id> --log-failed`。
- `compileSdk android-37.0`、`build-tools 37.0.0`、JDK 21。

## 硬约束（改动前先自查）

1. 全云端 CI：不要引入需要本地工具链的构建插件（如 protobuf、NDK、自定义 Gradle 插件源码）
2. APK ≤15MB，每阶段 12MB 门禁：新增依赖必须在 PR 描述写体积归因
3. 登录必须原生 Kotlin：不允许 WebView 登录流程
4. 单人维护：能不加的抽象就不加；一个功能一个提交

## 包结构规则

```
ui/*      → store、session、model；禁止 import source/* 里的 Http/Parse 类
sync/*    → source、store、session；禁止 ui
source/*  → session、model；禁止 store、sync、ui；各域子包互不依赖
store/*   → model；禁止 source、sync、ui
session/* → login、model；禁止一切上层
model/    → 零依赖
```

CI 里 `Package dependency rules` 这一步会 grep 检查，违反即失败。

## 数据层四条铁律

1. **失败永不产生新 data**：失败路径只改 `refreshState` / `lastError`，旧 data 原样保留
2. `NEVER` ≠ `EMPTY_CONFIRMED`：没同步过不能说「学校没有」
3. `parserVersion` 只增不减；改解析逻辑必须 +1
4. `capturedAt` 单调不减（时钟回拨保护）

## 安全

- 学号/密码绝不落盘，内存 `char[]` 用完清零
- 凭据走 `KeystoreCipher`（AES-GCM + Android Keystore），三个 vault 各自独立 alias
- 所有网络请求必须走 `BuctTlsTrustManager`；**禁止** `trustAllCerts`、`hostnameVerifier { true }`
- 日志脱敏：域名可留，学号/姓名/成绩数字必须替换
- `res/xml/backup_rules.xml` 与 `data_extraction_rules.xml` 必须 exclude 三个 vault 文件

## 提交

- 中文 commit message，一行说清做了什么
- 改动涉及解析器的，commit 里写明 `parserVersion: n → n+1`
- 不提交 `local.properties`、`*.jks`、`release-notes/` 之外的发版材料
# 北化校园工作台 · 工程与风险（plan-3）

> 配套：北化校园工作台-规划.md（v1）。本文覆盖第 6 节的工程面：模块化、CI 体积门禁、凭据存储、TLS 固定、解析灰度与可观测性、风险表、发布与回滚、每周例程。
> 四条硬约束（不可违反）：全云端 CI / APK ≤15MB（阶段门禁 12MB）/ 登录原生 Kotlin / 单人维护。

---

## 1. 模块化决策

### 1.1 量化对比（AGP 9.4.1 + Gradle 9.6.0，ubuntu-latest 4 核 16GB）

| 维度 | 单 module + 包级隔离（推荐） | 拆 :core/:data/:feature |
|---|---|---|
| 冷构建（无缓存） | ~5-8 min（现状 497 .kt 已验证范围） | +2-4 min（配置任务数 ×2.5，每个 module 一套 assemble/compile 任务） |
| 增量构建 | CI 缓存命中 ~2-3 min | 多 module 增量命中率高，但**全云端 CI 下本地增量不存在**——每次 push 都接近冷构建，增量收益归零 |
| 配置缓存兼容 | 简单 | 多 module + version catalog 初期踩坑多 |
| 单人认知负担 | 低：一个 settings.gradle、一个 build.gradle.kts，IDE 索引快 | 每加一个 module：+1 份构建脚本、+1 份 ProGuard 边界、+1 份 test variant 矩阵；单人下纯负债 |
| R8/体积 | 单 module R8 全局可达性分析最彻底 | 跨 module 默认 public，R8 保留更多死代码，APK 反而更大 |
| 故障隔离 | 无 | 无（进程级隔离才叫隔离，module 不是） |

**结论：保持单 module（:app）+ 包级隔离，与草稿第 6 节一致。** 决定性因素是全云端 CI：多 module 最主要的收益（本机增量编译快）完全兑现不了，而成本（配置复杂度、R8 劣化）全部照付。

### 1.2 触发拆分的量化条件（满足任意两条才启动评估）

1. .kt 代码 > 30,000 行（以 `wc -l` 实测为准）；
2. 缓存命中后全量 CI 构建 > 15 min；
3. feature 包 ≥ 6 且出现「改 A 域编译连带 B 域」的包依赖违规 ≥ 3 次/月；
4. 出现第二个构建变体需求（如无 Google 服务的 foss 变体）。

真要拆，只拆两步：先 :core-model（纯数据类，零依赖），再 :campus-sync。**不拆 :feature-\***（Compose 页面拆出去对构建时长几乎无收益）。

### 1.3 目标包依赖规则（文字写死，可做 lint 规则）

```
ui/*        → 只能依赖 store、campus/session（只读状态）、model；禁止 import source/* 里的 Http/Parse 类
sync/*      → 依赖 source、store、session；禁止依赖 ui
source/*    → 依赖 session（拿 Cookie）、model；禁止依赖 store、sync、ui；各域子包互不依赖（jwglxt 不 import theol）
store/*     → 依赖 model；禁止依赖 source、sync、ui（数据只经 CampusStore 进出）
session/*   → 依赖 login（复用已移植的 Buct* 文件）、model；禁止依赖一切上层
model/      → 零依赖（纯 data class + enum）
widget/*    → 依赖 store、model（小组件只读快照）
```

落地三件事：CI 检查脚本（见 2.4，grep 版零成本先跑）；后续可升级为 Detekt ForbiddenImport 自定义规则；包依赖图贴在本节，PR 自查第一项。

---

## 2. CI 体积门禁

### 2.1 现有 workflow 评估（.github/workflows/build-apk.yml，75 行）

现有流程：checkout → JDK 21（temurin）→ sdkmanager 装 platforms;android-37.0 + build-tools;37.0.0 → actions/cache 手动缓存 ~/.gradle/caches + wrapper → keytool 生成临时签名 → assembleRelease → upload-artifact → softprops 发 Release（tag ci-N）。

**要改的行（在现有文件上打补丁，不重写）：**

| 位置 | 改动 |
|---|---|
| L57 assembleRelease 之后 | 插入两个新 step：`APK size gate (12MB)` 与 `Size report`（内容见 2.2） |
| L60 List APKs | 可删（被门禁明细取代） |
| 顶部 on: | 增加 `pull_request: branches: [main]`，PR 也跑门禁（PR 不发 Release，L69 的 if 已保证） |
| L36-42 cache | 保持现状；M2 时建议换成 gradle/actions/setup-gradle（缓存 key 更精准） |

### 2.2 门禁脚本（完整可粘贴）

```yaml
      - name: APK size gate (12MB)
        run: |
          set -euo pipefail
          APK=$(find app/build/outputs/apk/release -name "*.apk" | head -1)
          BYTES=$(stat -c%s "$APK")
          MB=$(awk "BEGIN{printf \"%.2f\", $BYTES/1048576}")
          echo "APK: $APK  size: ${BYTES} bytes (${MB} MB)"
          LIMIT=$((12*1024*1024))
          if [ "$BYTES" -gt "$LIMIT" ]; then
            echo "::error::APK ${MB}MB 超过 12MB 门禁"
            echo "==== 体积明细（unzip 条目，Top 30，按未压缩大小）===="
            unzip -l -v "$APK" | sort -k1 -n -r | head -30
            exit 1
          fi
          echo "体积门禁通过 (${MB}MB <= 12MB)"

      - name: Per-entry size report
        if: always()
        run: |
          APK=$(find app/build/outputs/apk/release -name "*.apk" | head -1)
          {
            echo "### APK 体积报告"
            echo '```'
            unzip -l "$APK" | sort -k1 -n -r | head -25
            echo '```'
          } >> "$GITHUB_STEP_SUMMARY"
```

说明：

- `unzip -l -v` 输出 Length（未压缩）/ Size（压缩后）/ Name，按 Length 排序是找资源大头的最好信号；unzip/stat/awk 都是 runner 自带的，不装任何 SDK 组件。
- 可选增强：`dexdump -f classes.dex | grep method_refs` 看方法数；`apkanalyzer dex packages` 按 dex 归因，但要多装 SDK 组件拖慢 CI，不建议默认。

### 2.3 PR 体积 delta 报告

```yaml
      - name: Size delta vs main
        if: github.event_name == 'pull_request'
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          set -euo pipefail
          gh run download --repo "$GITHUB_REPOSITORY" --branch main \
            --name apks --dir /tmp/baseline || { echo "无基准，跳过 delta"; exit 0; }
          BASE=$(find /tmp/baseline -name "*.apk" | head -1)
          APK=$(find app/build/outputs/apk/release -name "*.apk" | head -1)
          B=$(stat -c%s "$BASE"); N=$(stat -c%s "$APK")
          {
            echo "### 体积 delta"
            echo "- main 基准: $(awk "BEGIN{printf \"%.2f\", $B/1048576}") MB"
            echo "- 本 PR: $(awk "BEGIN{printf \"%.2f\", $N/1048576}") MB"
          } >> "$GITHUB_STEP_SUMMARY"
          if [ $(( N - B )) -gt 512000 ]; then
            echo "::warning::本 PR 增重 >500KB，请在 PR 描述中写归因（新增依赖/资源各占多少）"
          fi
```

体积归因做法：main 最近一次成功 run 的 apks artifact 作基准（保留 90 天）；delta > 500KB 触发 warning。**禁止无归因的 >500KB 合并**——流程规则，不需要代码。

### 2.4 Runner 规格、缓存策略、时长预期与包依赖检查

- ubuntu-latest = 4 vCPU / 16GB / 14GB SSD；公共仓库免费 2000 min/月，单次 ~8-10 min，每月 push 50 次约 500 min，充裕。
- 缓存：现有 actions.cache（gradle caches + wrapper，key 按 gradle 文件 hash + restore-keys 前缀）已可用；命中后配置+依赖 ~1 min，编译 ~4-6 min，整 run **8-10 min**。保持 `--no-daemon`（一次性 runner 上 daemon 无意义）。
- 包依赖 lint（1.3 节的 grep 版，挂在门禁前，秒级）：

```yaml
      - name: Package dependency rules
        run: |
          set -euo pipefail
          SRC=app/src/main/java/com/buct/one/campus
          v=0
          check() {
            hits=$(grep -rl "$2" "$SRC/$1" 2>/dev/null || true)
            if [ -n "$hits" ]; then echo "::error::$1 违规依赖 $2: $hits"; v=1; fi
          }
          check store "import com.buct.one.data.source"
          check store "import com.buct.one.data.sync"
          check ui   "import com.buct.one.data.source"
          check sync "import com.buct.one.ui"
          [ "$v" = 0 ] && echo "包依赖检查通过" || exit 1
```

---

## 3. 凭据存储

### 3.1 逐项决策表

| 凭据 | 存储位置 | API | 接触点 |
|---|---|---|---|
| 学号/密码 | **绝不落盘**：只在 BuctSsoLoginManager 登录瞬间存在于内存（char[]，用完清零） | 无 | 登录表单 → 直接进加密请求体 |
| SSO Cookie（sidportal/jwglxt/theol） | EncryptedSharedPreferences | Keystore AES-256-GCM，alias `buct_sso_master_key` | CookieWatchdog / SessionRenewer |
| WebAuthn/软件通行密钥 | Keystore 强密钥 + 公钥映射存 EncryptedSharedPreferences | BuctWebAuthnManager 已有，补 `setUserAuthenticationRequired(true)` | 仅生物识别解锁后可用 |
| IMAP 口令 | EncryptedSharedPreferences **独立文件**（生命周期独立：用户可单独撤销邮箱授权） | alias `buct_mail_master_key` | ImapSource 只读 |
| 企业微信 secret | EncryptedSharedPreferences，corpid 可明文、secret 必须加密 | alias `buct_wecom_master_key`（M5） | WecomSource |

统一封装（新文件 campus/session/CredentialVault.kt）：

```kotlin
package com.buct.one.data.session

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object CredentialVault {
    private const val SSO_FILE = "campus_sso_vault"
    private const val MAIL_FILE = "campus_mail_vault"
    private const val WECOM_FILE = "campus_wecom_vault"

    @Volatile private var sso: SharedPreferences? = null
    @Volatile private var mail: SharedPreferences? = null
    @Volatile private var wecom: SharedPreferences? = null

    fun sso(context: Context): SharedPreferences =
        sso ?: create(context, SSO_FILE, "buct_sso_master_key").also { sso = it }

    fun mail(context: Context): SharedPreferences =
        mail ?: create(context, MAIL_FILE, "buct_mail_master_key").also { mail = it }

    fun wecom(context: Context): SharedPreferences =
        wecom ?: create(context, WECOM_FILE, "buct_wecom_master_key").also { wecom = it }

    private fun create(context: Context, file: String, alias: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setRequestStrongBoxBacked(true)   // 有 StrongBox 的设备用硬件隔离，否则自动回退 TEE
            .build()
        return EncryptedSharedPreferences.create(
            context, file, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** 登出时全清：三个文件 + Keystore alias 删除（alias 删除不可逆，凭据随之作废） */
    fun wipeAll(context: Context) {
        context.deleteSharedPreferences(SSO_FILE)
        context.deleteSharedPreferences(MAIL_FILE)
        context.deleteSharedPreferences(WECOM_FILE)
        runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.deleteEntry("buct_sso_master_key")
            ks.deleteEntry("buct_mail_master_key")
            ks.deleteEntry("buct_wecom_master_key")
        }
        sso = null; mail = null; wecom = null
    }
}
```

> androidx.security:security-crypto 用 1.1.0-alpha07+（支持 setRequestStrongBoxBacked，minSdk 24 兼容）。

### 3.2 backup rules（完整）

app/src/main/res/xml/backup_rules.xml：

```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <exclude domain="sharedpref" path="campus_sso_vault.xml" />
    <exclude domain="sharedpref" path="campus_mail_vault.xml" />
    <exclude domain="sharedpref" path="campus_wecom_vault.xml" />
    <exclude domain="sharedpref" path="campus_webauthn.xml" />
    <!-- 快照数据无凭据，可备份，不排除 -->
</full-backup-content>
```

app/src/main/res/xml/data_extraction_rules.xml（Android 12+）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="sharedpref" path="campus_sso_vault.xml" />
        <exclude domain="sharedpref" path="campus_mail_vault.xml" />
        <exclude domain="sharedpref" path="campus_wecom_vault.xml" />
        <exclude domain="sharedpref" path="campus_webauthn.xml" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="campus_sso_vault.xml" />
        <exclude domain="sharedpref" path="campus_mail_vault.xml" />
        <exclude domain="sharedpref" path="campus_wecom_vault.xml" />
        <exclude domain="sharedpref" path="campus_webauthn.xml" />
    </device-transfer>
</data-extraction-rules>
```

AndroidManifest application 节点确认（缺则补）：

```xml
android:allowBackup="true"
android:fullBackupContent="@xml/backup_rules"
android:dataExtractionRules="@xml/data_extraction_rules"
```

> 原因：vault 的密钥在 Keystore，云备份搬走的只是密文，换新设备解不开，只会制造「数据在但读不出」的疑难杂症，所以显式排除。

### 3.3 凭据生命周期

```
登录（LoginActivity 原生表单）
  → 密码仅存内存 char[]，SM2/AES 加密后 POST sidportal
  → 成功：CookieJar 写 CredentialVault.sso → char[] Arrays.fill('\0')，表单 state 清空
使用
  → SessionRenewer 读 vault → OkHttp CookieJar 注入
  → 401/302 跳登录页 = 失效信号（CookieWatchdog 已有）
失效（三类）
  a) 学校会话过期：清 Cookie，保留 WebAuthn，提示重新认证（生物识别一键续）
  b) 用户改密码：wipe sso 文件（Keystore alias 保留）
  c) 登出/卸载：wipeAll() —— 三个文件 + 三个 Keystore alias 全删
升级迁移
  → vault schema 加 version int；不兼容升级直接 wipe 对应 vault 重新登录（代价：一次登录）
```

---

## 4. TLS 固定现状与改进

### 4.1 现状评估（读了 BuctTlsTrustManager.kt 105 行 + BuctCertificates.kt 29 行）

现状是「**系统信任库优先 + 内置 CA 链兜底**」，不是证书固定（pinning）：

1. checkServerTrusted 先走系统默认 TrustManager，失败才用内置链；
2. 内置两条链：GlobalSign RSA OV 2018 ← GlobalSign Root R3（校园网入口），ZeroSSL ECC DV 2 ← Sectigo E46（公网入口）；verifyChain 只做「leaf 由 intermediate 签发、intermediate 由 root 签发、有效期」三步验证；
3. **不校验 leaf 的域名/SAN**——任何由这两家公共 CA 签发的证书都会通过兜底路径。所以这不是 pinning，只是「老设备系统库缺根时的根可用性兜底」，抗 MITM 强度 ≈ 系统默认信任库。

风险评估：

- **过期**：GlobalSign 中间 2028-11-21 到期、R3 根 2028-01-28、Sectigo E46 到 2038-01-18、ZeroSSL 中间 2035-09-23。5 年内不炸。**叶子证书 ZeroSSL 免费 90 天一换**——好在方案不固定叶子，叶子换签不影响；
- **换 CA**：学校哪天换到 Let's Encrypt，内置兜底链全废；好在系统库优先，多数设备不受影响，只有系统库缺新根的老设备翻车（与今天问题同构）；
- **MITM 面**：信任的是公共 CA，理论上对 *.buct.edu.cn 有签发能力的任何人都能通过校验。校园账号场景勉强够用，但不是最优。

### 4.2 改进方案（M2 做前两条，M4 做第三条）

**a) 补域名校验（必做，~15 行）**：verifyChain 返回前检查 leaf 的 SAN：

```kotlin
private fun isBuctDomain(leaf: X509Certificate): Boolean {
    val sans = leaf.subjectAlternativeNames ?: return false
    for (san in sans) {
        if (san[0] == 0) {  // type 0 = DNS
            val dns = san[1] as String
            if (dns == "buct.edu.cn" || dns.endsWith(".buct.edu.cn")) return true
        }
    }
    return false
}
```

verifyChain 内三步验证通过后加 `if (!isBuctDomain(leaf)) return false`。防止公共 CA 签发的其他域名证书蹭兜底链。

**b) 证书链 digest pin 集合 + 远端可更新**：GitHub Raw 放 `config/tls-pins.json`：

```json
{
  "version": 3,
  "domains": {
    "sidportal.buct.edu.cn": {
      "pins": ["sha256/AAAA-example-current=", "sha256/BBBB-example-second="],
      "backup_pins": ["sha256/CCCC-example-next="],
      "not_after": "2027-06-01T00:00:00Z"
    },
    "jwglxt.buct.edu.cn": { "pins": ["sha256/DDDD="], "backup_pins": [], "not_after": "2027-06-01T00:00:00Z" }
  },
  "notice": "pins 固定 CA 与备用公钥 digest；CA 轮换时先加 backup_pins 再删旧 pin"
}
```

（示例值，真实 digest 生成命令：`openssl s_client -connect sidportal.buct.edu.cn:443 </dev/null 2>/dev/null | openssl x509 -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | base64`，每个域跑一遍，叶子+中间证书各记一条。）

App 内 PinStore：启动后台拉一次（7 天本地缓存），校验「叶子或链上任一证书公钥 digest 命中 pins ∪ backup_pins」；拉不到远端用内置默认集。

**c) 失败降级路径**：pin 不匹配 ≠ 静默放行——SSO/教务请求直接拒绝并提示「网络异常，建议校园网」；若远端 pins 拉取失败且内置 `not_after` 已过，降级为系统信任库（即现状路径）+ UI 黄条。

**不做的后果**：学校换 CA + 部分老设备缺新根时同时翻车，而你没有远程止血手段，唯一修复是发新版 APK，全量用户至少坏一天。做了 b/c，最坏情况是一次配置文件 commit。


---

## 5. 解析失败的灰度与可观测性

### 5.1 parserVersion 机制

- 每个 CampusSource 实现 `parserVersion: Int`（写入 Provenance，草稿第 2 节已有字段）；
- 页面结构变化调解析时 +1；parse() 内结构指纹不匹配抛 `ParserMismatchException(version, fingerprint)`；
- 快照保留旧 parserVersion 数据，UI 显示「数据来自 X 时刻」，不显示假数据。

### 5.2 失败上报（零后端、不传隐私）

只传三样：**domain + 失败类型 + 页面结构指纹 hash**。指纹 = 规范化后 HTML 的 SHA-256 前 16 hex（数字→N、中文块→C、压空白）：

```kotlin
fun structureFingerprint(html: String): String {
    val normalized = html
        .replace(Regex("[0-9]{4,}"), "N")
        .replace(Regex("[\\u4e00-\\u9fa5]+"), "C")
        .replace(Regex("\\s+"), " ")
    return java.security.MessageDigest.getInstance("SHA-256")
        .digest(normalized.toByteArray(Charsets.UTF_8)).take(8)
        .joinToString("") { "%02x".format(it) }
}
```

上报通道：用户端「一键反馈」生成预填 issue 文本（domain + type + fingerprint + parserVersion + App 版本），你贴到 repo。半自动但零后端、零 SDK。**不引入第三方 crash SDK**（基线刚删掉 5.9MB 的 crash 库，别加回来）。

### 5.3 远端 kill switch（GitHub Raw JSON，零后端）

`config/remote-flags.json`（commit 进 main 即全量生效）：

```json
{
  "version": 42,
  "kill": {
    "jwglxt_schedule": false,
    "jwglxt_grades": false,
    "theol_assignments": false,
    "imap": false
  },
  "notice": "置 true = 客户端停止抓取该域，只读旧快照。解析全崩时 30 分钟内止血。"
}
```

客户端 RemoteFlagStore：App 启动 + 每次同步前拉一次（12h 本地缓存，失败用缓存）；kill=true 直接跳过 fetch，provenance 标 FAILED_FALLBACK。

### 5.4 失败率阈值本地降级

```kotlin
class DomainHealth(private val window: ArrayDeque<Pair<Long, Boolean>> = ArrayDeque()) {
    fun record(success: Boolean, now: Long = System.currentTimeMillis()) {
        window.addLast(now to success)
        while (window.isNotEmpty() && now - window.first().first > 24 * 3600_000L) window.removeFirst()
    }
    /** 最近 20 次失败 >= 15 次（75%）→ 本机降级：保留快照 + 标注，不发网络请求 */
    val shouldDegrade: Boolean
        get() = window.size >= 20 && window.count { !it.second } >= 15
}
```

本地阈值只触发本机降级（保留快照 + 标注）；全局 kill 只由远端 JSON 下发，两级不混。

### 5.5 本地诊断包

「设置 → 诊断」一键导出 zip 到 Downloads/buct-diag-<ts>.zip：App 版本、各域 parserVersion、RefreshState、失败类型统计、指纹列表、**脱敏关键 HTML 片段**（保留标签结构、文本替换为 …，每域前 2KB；学号/姓名/成绩数字全替换）。用户手动发给你。

---

## 6. Top 8 风险表

| # | 风险 | 概率 | 影响面 | 早期信号 | 缓解 | 兜底 | 每周检查 |
|---|---|---|---|---|---|---|---|
| 1 | 教务页面改版→解析崩 | 中（学期 1-2 次） | 教务数据不可刷新 | 指纹 hash 变、issue 骤增 | parserVersion + 宽松解析 + 保留旧快照 | kill switch 停该域，热修 parser+1 | 刷新一次课表 |
| 2 | SSO 接口变更（SM2 公钥轮换） | 中 | 无法登录 | 本机测试登录失败；「登录卡住」issue | 公钥进可远端更新 config | 降级 WebView 兜底（仅此场景，不违反约束 3） | 手动登录一次 |
| 3 | 体积失控 | 低 | CI fail 发不了版 | 门禁 delta>500KB | 依赖准入：新依赖必须给体积归因 | 门禁 fail 当刹车 | 自动，无需检查 |
| 4 | 学校风控/封禁 | 中低 | 429/403，功能受限 | 本机现验证码/限流页 | 限速+随机抖动+非爬虫 UA+失败退避 | kill switch 降全量撞墙概率 | 全量刷一次看耗时 |
| 5 | 单人超载/弃坑 | 中 | 项目停更 | 例程连续 2 周没做 | 阶段严格串行；里程碑做减法 | MIT 许可，可接手 | 例程勾选（第 8 节） |
| 6 | 厂商 ROM Keystore 异常 | 低 | 频繁重登 | issue「每次都要重新登录」 | EncryptedSharedPreferences 内置回退；catch 后降级+提示 | 内存 Cookie 保进程存活期 | 看 issue 列表 |
| 7 | TLS 链过期/换 CA | 低（2-3 年一遇） | 老设备无法联网 | pins not_after 临近 | pins.json 远端更新+降级系统库 | 黄条提示+校园网说明 | 每月查 not_after |
| 8 | 许可合规争议 | 低 | 法律/社区声誉 | 上游作者 issue | MIT 许可 + NOTICE 致谢参考实现 | 代码独立实现，不复制受版权保护的源码 | 发版前 grep license 头 |

> 与草稿 Top 8 差异：THEOL 无 API、企业微信扫码两条属产品决策而非工程风险，移出工程表；补入 6/7 两条「一觉醒来全炸」型。

---

## 7. 发布与回滚

### 7.1 版本号策略

- tag 即权威（vX.Y.Z），CI 从 tag 反写 app/version.properties（沿用现机制）；
- versionCode = major*10000+minor*100+patch（跨 minor 时 patch 归零不撞；现有 release.yml 注释也建议此公式）；
- parser 调整升 patch；新域接入升 minor；架构层落地（如 M2 数据层）升 minor；
- release notes 唯一来源：release-notes/<tag>.md 的 ## notes 区块（现有机制保留）。

### 7.2 分发渠道（单一渠道原则）

1. **GitHub Release**：ci-N tag 仅自测；**只有 vTag 是用户渠道**——避免用户装到 CI 临时签名的包后无法覆盖升级（签名不同，升级直接失败）。
2. 应用内更新检查：拉 `https://raw.githubusercontent.com/STAR-10086/buct-kebiao/main/config/version.json`（结构沿用 release.yml 的 version.json：versionCode/versionName/releaseNotes/downloadUrl/forceUpdate）。
3. 强制更新开关：forceUpdate:true + minVersionCode，低于 min 弹不可关闭更新页。触发条件预案：仅用于「解析全崩无法热修」或「安全漏洞」。

### 7.3 回滚协议（解析全崩后 30 分钟动作表）

```
T+0     发现（issue / 自测 / 早期信号）
T+0~10  改 config/remote-flags.json：崩的域 kill=true → commit+push main
        （全量用户 12h 内停止抓取，界面回退旧快照+标注）
T+10~20 本地验证旧快照路径：飞行模式开 App，确认 UI 正常显示旧数据
T+20~30 收尾二选一：
        a) 页面小改 → 修 parser +1 走正常发版
        b) 接口大改 → 保留 kill，issue 置顶说明，排期修
```

**回滚单位是「数据域」而不是 APK 版本**：签名一致时用户降级安装实际不会发生，靠 kill switch + 逐域快照在数据面回滚。这就是第 5 节全部机制的意义。

### 7.4 发布前检查单（每次 vTag 前，5 分钟）

1. lintRelease 无新增 error（CI 有）；
2. 体积门禁通过（自动）；
3. 登录冒烟：取每周例程第 1 项的最新结果（CI 装不了真机）；
4. `grep -rn "com.tyust" app/src` 为 0（旧包名残留检查）；
5. release-notes/<tag>.md 存在且 notes 区块非空（CI 会挡，提前看）。

---

## 8. 每周维护例程（单人，总计 ≤60 min/周）

| # | 频率 | 事项 | 耗时 | 怎么做 |
|---|---|---|---|---|
| 1 | 每周 | 真机冒烟：登录一次 + 刷新课表/成绩 | 5 min | 装最新构建 |
| 2 | 每周 | 分诊 issue（bug_report 模板已含日志） | 10 min | 30 分钟能修的当场修，否则打标签排期 |
| 3 | 每周 | 推一个提交保 CI 绿（哪怕文档/小修） | 10 min | CI 红超一周 = 项目死亡早期信号 |
| 4 | 每两周 | 体积巡检：看门禁 summary 最近 delta | 5 min | >500KB 未归因的补归因 |
| 5 | 每月 | pins/证书检查：tls-pins.json 的 not_after、浏览器看 sidportal 证书 | 10 min | 距到期 <90 天即更新 pins |
| 6 | 每月 | 看 Actions 分钟数余量与 artifact 配额 | 2 min | 2000 min/月是否够 |
| 7 | 每月 | keystore 离线双备份确认 + secrets 有效性 | 5 min | keystore 丢 = 全量用户无法升级 |
| 8 | 每学期 | 全量回归（开学第一周是校系统变动高峰） | 30 min | 所有域各刷一次，对照风险表逐项过 |

超时规则：单周例程超 60 分钟即触发做减法——砍功能优先于加投入，单人约束下健康度 > 功能数。

---

## 9. 与主规划 v1 草稿的差异清单（合并时改）

1. 草稿「等 M3 过 3 万行再拆」→ 替换为 1.2 节的四条量化触发条件；
2. 草稿「CI 新增体积门禁」→ 2.2/2.3 给了可直接粘贴的 step 与 build-apk.yml 的 4 处改动点；
3. 草稿安全表补：backup_rules + dataExtractionRules 的 exclude 配置（3.2 节），草稿未提；
4. 草稿 Top 8 中「THEOL 无 API / 企业微信扫码」两条移出工程风险表，归业务决策；
5. 草稿没有的三块新增：TLS pins 远端更新（4.2）、kill switch + 30 分钟回滚协议（5.3/7.3）、每周例程（第 8 节）。


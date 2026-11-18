# 数据层架构设计 · plan-1-data-layer

> 上游文档：《北化校园工作台-规划.md》（v1）。本文件是其 M2「教务全量（数据层落地）」的详细设计，
> 覆盖 SSO 会话复用、CampusSource、provenance、CampusStore、SyncEngine、IMAP、场馆、包结构与迁移路径。
> 代码基线：C:/Users/STAR/Desktop/zhengfang-apk（497 .kt，源码包 com.buct.one，7.1MB）。
> 四条硬约束全程生效：云端 CI 构建、APK ≤100MB（硬门禁 100MB）、登录原生 Kotlin、单人可维护。

---

## 1. SSO 会话复用链路

### 1.1 一次登录打穿四个域的 HTTP 流程

基线 BuctSsoLoginManager 已实测（2026-10，注释内记录）：金智 linkid 协议，
GET /login?service=<target> 拿一次性 execution 与 AES key（login-croypto），
POST 密码（AES-ECB-PKCS7 → base64），成功 302 携带 ticket。完整打穿链路：

~~~text
[A] sidportal.buct.edu.cn（统一身份认证，金智 linkid）
    GET  /login?service=https://jwglxt.buct.edu.cn/sso/jziotlogin
    POST /login?service=...  (username + AES(password, croypto) + execution)
    → 302 Location: https://jwglxt.buct.edu.cn/sso/jziotlogin?ticket=ST-xxxx
    → sidportal 侧落 CASTGC / TGC（金智 TGT cookie，HttpOnly，域=sidportal.buct.edu.cn）

[B] jwglxt.buct.edu.cn（教务 JWGLXT）
    GET /sso/jziotlogin?ticket=ST-xxxx     → 302 → /xtgl/index_initMenu.html
    → jwglxt 侧落 JSESSIONID（域=jwglxt.buct.edu.cn，Path=/）
    此后所有 zf 协议接口（kbcx/xskbcx_cxXsKb 等既有 AcademicHttpTransport 通道）带此 cookie

[C] theol.buct.edu.cn（北化在线 THEOL）
    首次访问任意受保护页（如 /meol/common/index.jsp）→ 302 到 sidportal
    GET sidportal /login?service=https://theol.buct.edu.cn/...（带 A 步的 CASTGC/TGC）
    → sidportal 校验 TGT 有效 → 302 带 theol ticket → theol 落自己的 JSESSIONID
    → 后续 theol 请求不再经过 sidportal（同域 SSO 免二次登录）

[D] 场馆 / 公开页（如体育馆预约系统）
    无需登录，直接 GET 公开页（见第 7 节）
~~~

**Cookie 明细表**（名字以抓包为准，基线 MatchingMemoryCookieJar 已实现 RFC6265 匹配，此处为设计基准）：

| Cookie | 域 | Path | 作用 | 生命周期 |
|---|---|---|---|---|
| CASTGC / TGC（金智 TGT） | sidportal.buct.edu.cn | / | 换 ticket 的根凭证 | 数小时~数天，学校策略 |
| JSESSIONID | sidportal.buct.edu.cn | / | 认证页自身会话 | 会话级 |
| JSESSIONID | jwglxt.buct.edu.cn | / | 教务会话 | 约 30 分钟无操作过期 |
| JSESSIONID | theol.buct.edu.cn | / | THEOL 会话 | 约 30~60 分钟 |

### 1.2 OkHttp CookieJar 按域池化

现有 login/MatchingMemoryCookieJar.kt 已经是内存 CookieJar。数据层在其上加一层**持久化 + 按域分池**：

~~~kotlin
package com.buct.one.data.session

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 按域池化的持久化 CookieJar。
 * - 内存 map 为唯一运行时真相，EncryptedSharedPreferences 只做冷启动恢复
 * - 每次写操作后异步落盘（去抖 500ms），杜绝每个请求都写盘
 */
class PooledCookieJar(
    private val persistence: CookiePersistence,   // EncryptedSharedPreferences 封装
) : CookieJar {

    private val lock = Any()
    private val pools = mutableMapOf<String, MutableList<Cookie>>()  // key = host|path

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        pools.values.flatten()
            .filter { it.matches(url) && (it.expiresAt == Cookie.MAXAGE || it.expiresAt > System.currentTimeMillis()) }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(lock) {
        for (c in cookies) {
            val key = url.host + "|" + c.path
            val list = pools.getOrPut(key) { mutableListOf() }
            list.removeAll { it.name == c.name }
            list += c
        }
        persistence.schedulePersist { snapshot() }  // 去抖异步
    }

    /** 提取某域的会话 cookie 串（供诊断），仅供内部 */
    internal fun cookieHeader(domain: CampusDomain): String = synchronized(lock) {
        pools.entries.filter { it.key.startsWith(domain.host) }
            .flatMap { it.value }.joinToString("; ") { it.name + "=" + it.value }
    }

    fun clear() = synchronized(lock) { pools.clear(); persistence.clear() }
    private fun snapshot(): Map<String, List<Cookie>> = synchronized(lock) { pools.toMap() }
}
~~~

### 1.3 jziotlogin 换票：请求与响应

~~~kotlin
/** 静默换票：拿 sidportal TGT 换目标域会话。幂等，可重复调用。 */
class TicketExchanger(private val client: OkHttpClient) {

    data class ExchangeResult(val ok: Boolean, val finalUrl: HttpUrl, val failure: ExchangeFailure?)

    enum class ExchangeFailure { NO_TGT, TGT_EXPIRED, TARGET_DOWN, RISK_CONTROL }

    /**
     * 目标域首次访问的标准三跳。OkHttp 跟随重定向即可完成，无需手工拆 302；
     * 关键是 [entryUrl] 必须是「会触发 SSO 跳转」的受保护页，而非首页。
     */
    suspend fun ensureSession(entryUrl: HttpUrl): ExchangeResult = withContext(Dispatchers.IO) {
        val resp = client.newCall(
            Request.Builder().url(entryUrl)
                .header("User-Agent", UaPolicy.browserLike)   // 非爬虫 UA
                .build()
        ).await()
        when {
            resp.isSuccessful && !resp.request.url.host.contains("sidportal") ->
                ExchangeResult(ok = true, finalUrl = resp.request.url, failure = null)
            // 被弹回登录页 = TGT 失效
            resp.request.url.host.contains("sidportal") && resp.request.url.encodedPath.startsWith("/login") ->
                ExchangeResult(ok = false, finalUrl = resp.request.url, failure = ExchangeFailure.TGT_EXPIRED)
            else -> ExchangeResult(ok = false, finalUrl = resp.request.url, failure = ExchangeFailure.TARGET_DOWN)
        }
    }
}
~~~

### 1.4 各域会话有效期判定与失效探测

探测原则：**只打最便宜的、无副作用的、返回小体积的页面**，且只在「即将用」之前探测，不做定时轮询。

| 域 | 探测 URL | 有效 | 失效 |
|---|---|---|---|
| sidportal (TGT) | 不直接探测；由换票结果间接判定 | 换票 302 带目标域 cookie | 换票被弹回 /login |
| jwglxt | GET /xtgl/index_initMenu.html | 200（HTML 菜单页） | 302 → sidportal/login |
| theol | GET /meol/common/index.jsp（或任意受保护轻页） | 200 | 302 → sidportal/login |
| mail (IMAP) | IMAP NOOP / SELECT INBOX | OK 响应 | BYE / AUTHENTICATE 失败 |

~~~kotlin
package com.buct.one.data.session

/** 单域会话状态机：只由 SsoSessionManager 驱动，其他类只读 */
enum class SessionPhase { ACTIVE, PROBING, RENEWING, EXPIRED, UNAUTHORIZED }

class DomainSessionState(
    val domain: CampusDomain,
    @Volatile var phase: SessionPhase,
    @Volatile var lastConfirmedAt: Long,   // 上次探测/使用确认为 ACTIVE 的时刻
    @Volatile var lastError: String?,
)
~~~

状态机（复用现有 SessionRenewer / SessionRecoveryCoordinator 的单飞恢复语义）：

~~~text
ACTIVE --(source 请求收到 302→login)--> EXPIRED --(canRenew: 有已存凭据)--> RENEWING
RENEWING --(BuctSsoLoginManager 重登成功)--> ACTIVE
RENEWING --(InvalidCredentials)--> UNAUTHORIZED --(用户重新输入密码)--> ACTIVE
RENEWING --(验证码/风控)--> EXPIRED（降级：通知 UI「需要重新登录」，保留旧快照）
ACTIVE --(静默续期窗口)--> PROBING --(探测 200)--> ACTIVE
                              --(探测 302)--> EXPIRED
~~~

**静默续期的接线方式**：现有 SessionRenewer 是回调式（PasswordLoginCallback），面向旧的单 cookie 模型。数据层不动它的恢复主干，只在前面加一个适配器 SsoSessionManager：

~~~kotlin
package com.buct.one.data.session

/**
 * 数据层会话门面。SyncEngine 的每个 source 调用前都经过 [ensure]。
 * - 命中「ACTIVE 且距 lastConfirmedAt < staleMs」→ 直接放行（零开销）
 * - 否则探测 → 失效则走 SessionRenewer 单飞重登 → 成功后重放原请求
 * 重登期间并发请求在 coordinator 内排队（既有行为，不重复造轮子）。
 */
class SsoSessionManager(
    private val cookieJar: PooledCookieJar,
    private val exchanger: TicketExchanger,
    private val renewer: SessionRenewerAdapter,   // 包装现有 utils.SessionRenewer
    private val staleMs: Long = 10 * 60_000,      // 10 分钟内用过的会话不再探测
) {
    /** ACTIVE 且新鲜 → true；探测/重登后恢复 → true；UNAUTHORIZED / 风控 → false */
    suspend fun ensure(domain: CampusDomain): Boolean

    /** UI 观察用 */
    fun states(): List<DomainSessionState>
}
~~~

SessionRenewerAdapter 职责：把回调式 PasswordLoginGateway 包成 suspend fun relogin(): LoginRecoveryOutcome（suspendCancellableCoroutine 实现，此处略），并把成功后的新 cookie 灌回 PooledCookieJar。这样旧代码零改动，新数据层拿到协程 API。

---

## 2. CampusSource 接口

~~~kotlin
package com.buct.one.data.source

import com.buct.one.data.session.CampusDomain
import com.buct.one.data.session.SsoSessionManager

/** 域枚举：cookie 池、探测、同步策略都挂在它上面 */
enum class CampusDomain(
    val host: String,
    val requiresLogin: Boolean,
) {
    SIDPORTAL("sidportal.buct.edu.cn", requiresLogin = true),
    JWGLXT("jwglxt.buct.edu.cn", requiresLogin = true),
    THEOL("theol.buct.edu.cn", requiresLogin = true),
    MAIL("mail.buct.edu.cn", requiresLogin = true),      // IMAP，独立凭据
    VENUE("tyb.buct.edu.cn", requiresLogin = false),     // 占位：以实际公开页域为准
}

/** 错误类型：失败隔离的基本单位，SyncEngine 按此决定退避与降级 */
sealed class CampusError(open val message: String) {
    class Network(override val message: String, val cause: Throwable?) : CampusError(message)
    class Auth(override val message: String) : CampusError(message)          // 会话失效且续期失败
    class Parse(override val message: String, val parserVersion: Int) : CampusError(message)
    class RateLimited(override val message: String) : CampusError(message)   // 风控/限流
    class Incomplete(override val message: String) : CampusError(message)    // 抓到了但不完整
}

/** 抓取的原始载荷：字节 + 解析所需上下文 */
class RawSource(
    val url: String,
    val bytes: ByteArray,
    val contentType: String?,
    val capturedAt: Long,
    val fetchNotes: Map<String, String> = emptyMap(),   // 如 page=3, totalPages=8
)

/** 完整性判定：complete=false 时 UI 必须标注「部分数据」 */
data class Completeness(
    val complete: Boolean,
    val reason: String?,       // 不完整的原因：分页中断/权限受限/解析降级
    val expectedCount: Int?,   // 服务端宣称的总数（zf JqGrid 的 records 字段）
    val actualCount: Int,
)

/**
 * 每域一个 Adapter。实现类只负责 fetch+parse+validate，不碰持久化、不碰调度。
 * T 必须是 kotlinx.serialization 可序列化的规范化模型。
 */
interface CampusSource<T : Any> {
    val domain: CampusDomain
    val sourceId: String                 // 如 jwglxt.schedule，进 provenance
    val parserVersion: Int               // 当前解析器版本，页面改版时 +1

    /** 拉全量。内部分页。抛 CampusError 即失败，不抛即成功（哪怕 complete=false）。 */
    suspend fun fetch(session: SsoSessionManager): RawSource

    /** 纯函数：同一个 RawSource 必须解析出同一个 T（无时钟、无随机、无 IO）。 */
    fun parse(raw: RawSource): T

    /** 校验解析结果的完整性。 */
    fun validate(value: T): Completeness

    /** 生成 provenance 的便捷方法（避免各 Adapter 手拼）。 */
    fun provenance(capturedAt: Long, completeness: Completeness, state: RefreshState) = Provenance(
        source = sourceId,
        capturedAt = capturedAt,
        parserVersion = parserVersion,
        refreshState = state,
        complete = completeness.complete,
    )
}
~~~

### 2.1 JwglxtSource 实现要点

- **抓什么**：全部走既有 zf 协议通道（ZfAcademicAdapter / AcademicHttpTransport），不新写 HTTP 层。JwglxtSource 是**门面**：把 AcademicStudyReader / AcademicStudyParser 的结果转成规范化模型 + provenance。
- **URL**：课表 POST /kbcx/xskbcx_cxXsgrkb（JSON，既有实现）；成绩 POST /cxcx/xskccj_list；考试 POST /kscx/xskscx_list。均为 JqGrid 风格 JSON，自带 records/total/page 字段。
- **分页**：zf JqGrid 用 page + rows=100 参数循环，直到 page == total。**每页之间 sleep 300~600ms 随机抖动**（风控缓解，规划风险 #6）。
- **complete=false 判定**：records != 实际累计条数、或 total > 1 但中途某页非 200、或学期列表为空但 profile 存在。
- **解析入口**：AcademicParsing.kt 既有 JSONObject 解析；JwglxtSource.parse 内先过一层「结构探针」（见第 3.3 节）再委托。

~~~kotlin
class JwglxtScheduleSource(
    private val transport: AcademicHttpTransport,   // 既有类，复用其 cookie 与错误处理
) : CampusSource<CourseTable> {

    override val domain = CampusDomain.JWGLXT
    override val sourceId = "jwglxt.schedule"
    override val parserVersion = 1

    override suspend fun fetch(session: SsoSessionManager): RawSource {
        check(session.ensure(domain)) { throw CampusError.Auth("jwglxt 会话不可用") }
        // 逐页抓取，fetchNotes 记录 page/total，全部页拼接成一个 RawSource（分页细节私有于实现）
        return transport.fetchSchedulePages()
    }

    override fun parse(raw: RawSource): CourseTable {
        // 委托既有 AcademicStudyParser + 结构探针，此处为编排
        return when (val verdict = scheduleProbe.check(raw)) {
            is StructureProbe.Verdict.Fatal -> throw CampusError.Parse("课表页面结构变更", parserVersion)
            else -> AcademicStudyParser.parseCourseTable(raw.bytes.decodeToString())
        }
    }

    override fun validate(value: CourseTable) = Completeness(
        complete = value.courses.isNotEmpty(),
        reason = if (value.courses.isEmpty()) "课表为空（可能是学期未排课）" else null,
        expectedCount = value.declaredCourseCount,
        actualCount = value.courses.size,
    )
}
~~~

### 2.2 TheolSource 实现要点

- **抓什么**：GET /meol/jkc_list.do（我的课程）→ 每门课的课程页与作业/通知列表页（THEOL 是 JSP 页面，无公开 API——规划风险 #3）。
- **分页**：THEOL 列表多为 offset/每页 20，解析 <a> 与表格行；分页上限 5 页，超出记 complete=false。
- **解析入口**：自写轻量 HTML 解析（Regex + 手写游标，不引入 Jsoup——**省体积**，Jsoup 约 500KB；THEOL 页面结构简单，正则够用。若后期复杂度失控再评估）。
- **complete=false**：列表页出现「系统繁忙」、课程数 > 上次快照 ×3（改版嫌疑）、或分页中断。

---

## 3. provenance 模型 Kotlin 落地

### 3.1 完整定义

~~~kotlin
package com.buct.one.data.store

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RefreshState { FRESH, STALE, EMPTY_CONFIRMED, FAILED_FALLBACK, NEVER }

@Serializable
data class Provenance(
    @SerialName("source") val source: String,          // sourceId，如 jwglxt.schedule
    @SerialName("capturedAt") val capturedAt: Long,    // epoch ms，抓取时刻
    @SerialName("parserVersion") val parserVersion: Int,
    @SerialName("refreshState") val refreshState: RefreshState,
    @SerialName("complete") val complete: Boolean,
)

@Serializable
data class DomainSlice<T>(
    @SerialName("data") val data: T?,
    @SerialName("provenance") val provenance: Provenance,
    @SerialName("lastError") val lastError: String? = null,
)
// 泛型序列化：CampusStore 落盘时按域分别用具体类型序列化，顶层不聚合序列化泛型
~~~

### 3.2 不变式（写入前由 CampusStore 强制校验，违反即拒绝落盘）

| # | 不变式 | 强制点 |
|---|---|---|
| I1 | refreshState == NEVER ⟺ 该域从未成功抓取过（data 必为 null） | commit 前 |
| I2 | data == null 且 refreshState != NEVER → 允许（上次也失败），但 lastError 必非空 | commit 前 |
| I3 | complete == false → reason 必须体现在 source 的 Completeness，且 UI 层可见 | parse 后 |
| I4 | capturedAt 单调不减：新快照的 capturedAt < 旧快照的 → 拒绝（时钟回拨保护） | commit 前 |
| I5 | 失败的抓取**永不**产生新 data：失败路径只能更新 refreshState=FAILED_FALLBACK + lastError，data 原样保留旧值 | commit 前 |
| I6 | parserVersion 只增不减；落盘时旧快照 parserVersion > 新值 → 拒绝（版本回退 = bug） | commit 前 |

### 3.3 parserVersion 版本管理与页面改版检测

**版本管理**：每个 CampusSource 用伴生对象持有版本常量，改解析逻辑必须 +1。落盘 JSON 里带版本，升级 App 后 UI 可提示「数据由旧版解析器生成，建议刷新」。

**改版检测（自动降级）——「结构探针」模式**：

~~~kotlin
package com.buct.one.data.source

/**
 * 解析前的廉价结构校验。不匹配不直接失败，而是触发降级路径。
 * 每个 source 声明自己依赖的 2~5 个关键特征（JSON 键 / HTML 标记 / 正则）。
 */
class StructureProbe(private val required: List<Probe>) {
    data class Probe(val name: String, val matches: (RawSource) -> Boolean, val severity: Severity)
    enum class Severity { FATAL, DEGRADE }

    sealed class Verdict {
        object Ok : Verdict()
        data class Degrade(val failedProbes: List<String>) : Verdict()   // 照常解析但 complete=false
        data class Fatal(val failedProbes: List<String>) : Verdict()     // 抛 CampusError.Parse
    }

    fun check(raw: RawSource): Verdict { /* 逐项检查，按 severity 汇总 */ }
}
~~~

**降级链**：Fatal（任一 FATAL 探针挂）→ 抛 CampusError.Parse → SyncEngine 标记该域 FAILED_FALLBACK，**旧快照原样保留**（THEIA 血泪：改版后清空数据是最大事故）。Degrade（DEGRADE 探针挂）→ 解析照走、complete=false、UI 标注。连续 3 次同步 Parse 失败 → 本地记录 parserStaleSince，UI 首页提示「学校页面疑似改版」。

---

## 4. CampusStore 持久化选型

### 4.1 决策：分片 JSON 文件（THEIA sharded-store 模式的 Android 版）

| 方案 | 体积 | 复杂度 | 快照语义 | 结论 |
|---|---|---|---|---|
| DataStore(Proto) | 0（用已有依赖） | **高**：每改 schema 要 proto 编译，全云端 CI 下无本地工具链缓冲，单人维护成本大 | 弱：单文件整体覆盖，「失败不覆盖旧快照」要自己造 | 否 |
| Room | +约300KB（含 ktx） | 高：表/迁移/DAO 三件套；数据是**只读快照**不是关系查询场景 | 中：事务可用但要建 12+ 张表映射 DomainSlice | 否 |
| **分片 JSON（kotlinx.serialization）** | 0（serialization 已在依赖树） | **低**：每域一个文件、一个 data class、无迁移（schema 演进 = 新文件名） | **天然**：每域独立提交 = 天然失败隔离；temp+rename = 天然原子 | **是** |

理由展开：数据访问模式是「整域读、整域写、从不跨域 JOIN」，关系型与 proto 的优势用不上；云端 CI 约束下 protobuf 插件链是额外故障点；单人维护下「读文件→JSON.decode→内存对象」的排障成本最低。

### 4.2 API 与原子写入

~~~kotlin
package com.buct.one.data.store

/**
 * 分片快照存储。目录布局（filesDir/campus/<account>/）：
 *   manifest.json            分片索引（每域: 文件名、sha256、capturedAt、provenance 摘要）
 *   manifest.json.bak        上一个版本的 manifest
 *   jwglxt.schedule.v3.json  分片文件（vN = schemaVersion，演进换名不迁移）
 *   .trash/                  被替换下来的旧分片，保留 N=3 份后 FIFO 清理
 */
class CampusStore(
    private val rootDir: java.io.File,       // filesDir/campus/<account>
    private val json: kotlinx.serialization.json.Json,
) {
    /** 读一个域。文件缺失/损坏/校验和不符 → 返回 null（调用方按 NEVER/FAILED 处理），不抛异常。 */
    suspend fun <T : Any> read(slice: SliceRef<T>): DomainSlice<T>?

    /** 事务提交一个域：写入成功才覆盖。失败（IO/校验/不变式违反）→ 旧快照原封不动。 */
    suspend fun <T : Any> commit(slice: SliceRef<T>, value: DomainSlice<T>): Boolean

    /** 当前全部域的 provenance 摘要（Dashboard 状态条用）。 */
    suspend fun manifestSummary(): Map<String, Provenance>

    class SliceRef<T : Any>(
        val fileName: String,         // 如 jwglxt.schedule.v3
        val serializer: kotlinx.serialization.KSerializer<T>,
    )
}
~~~

**commit 的原子写入序列**（全部在 Dispatchers.IO + 单 Mutex 串行内执行）：

1. 校验不变式 I1~I6，违反 → return false（记日志）；
2. 序列化 → 写 <file>.<uuid>.tmp；
3. 计算 sha256，写新 manifest 到 manifest.json.<uuid>.tmp；
4. tmp.renameTo(target) —— Android 上同目录 rename 是原子的（同一文件系统）；
5. 成功后把旧分片移入 .trash/，超过 3 份删最旧；
6. 冷启动自检：manifest 记录的分片 sha256 与实际不符 → 丢弃该分片、从 .trash/ 恢复上一个好版本 → 仍不行 → 该域按 FAILED_FALLBACK 初始化（内存态）。

**单飞与并发**：CampusStore 用一个 Mutex 串行化所有 commit（单人 App 无高并发，正确性优先于吞吐）；读路径无锁（文件不可变 + rename 原子性保证读到的永远是完整文件）。

---

## 5. SyncEngine

### 5.1 职责与形状

~~~kotlin
package com.buct.one.data.sync

/** 逐域调度：一次 run 处理一批 source，单域失败不阻断其他域（失败隔离）。 */
class SyncEngine(
    private val sources: List<CampusSourceEntry<*>>,
    private val session: SsoSessionManager,
    private val store: CampusStore,
    private val policy: SyncPolicy,
    private val backoff: BackoffPolicy = ExponentialBackoff(baseMs = 60_000, factor = 2.0, maxMs = 6 * 3600_000),
) {
    class CampusSourceEntry<T : Any>(
        val source: CampusSource<T>,
        val ref: CampusStore.SliceRef<T>,
        val priority: Int,          // 小的先跑：schedule=0, grades=1, theol=2, ...
    )

    sealed class RunResult {
        data class Success(val sourceId: String, val provenance: Provenance) : RunResult()
        data class Failure(val sourceId: String, val error: CampusError, val nextAttemptAt: Long) : RunResult()
    }

    /** 前台/后台统一入口。返回每个域的结果；内部并发度见下文。 */
    suspend fun syncOnce(trigger: Trigger, filter: (CampusSourceEntry<*>) -> Boolean = { true }): List<RunResult>

    enum class Trigger { APP_START, PULL_REFRESH, WIDGET_TICK, BACKGROUND }
}
~~~

### 5.2 调度细节

- **逐域失败隔离**：每个 source 的 fetch/parse/commit 包在独立 runCatching，异常转 RunResult.Failure，写退避表（sourceId → nextAttemptAt），不抛出循环外。
- **并发度 = 1（登录域串行）**。理由：学校风控（风险 #6）是主要矛盾，串行 + 请求间 300~600ms 抖动最安全；单人 App 无吞吐诉求；串行让退避与限速逻辑一目了然。唯一例外：VENUE（免登录公开页）可与登录域并行。
- **退避**：Failure → nextAttemptAt = now + backoff.next(failCount)；连续失败 5 次后该域进入「冷却」，只响应手动 PULL_REFRESH。Auth 错误不指数退避（重登失败一般是凭据问题，直接停）；RateLimited 直接跳到 maxMs。
- **前台触发条件**：
  - APP_START：距上次任一域成功同步 > 30 分钟才跑；
  - PULL_REFRESH：用户下拉，全量，重置退避；
  - WIDGET_TICK：只跑 schedule 域（小组件刷新）；
  - 冷启动若快照 capturedAt 超过 7 天 → Dashboard 顶部黄条。
- **网络条件**：默认「任意网络」；设置项「仅 WiFi 同步大流量域（theol/mail/venue）」，schedule/grades 恒允许（体积极小）。用 ConnectivityManager 的 NET_CAPABILITY_NOT_METERED 判定。

### 5.3 与 WorkManager 的关系

- **WorkManager 只做两件事**：(a) 每 6 小时一次的 PeriodicWorkRequest（SyncWorker，约束 NetworkType.CONNECTED），(b) 小组件刷新的 expedited work。**SyncEngine 是纯 Kotlin 类**（无 Android 依赖，Context 由调用方注入），Worker 只是薄壳调用 syncOnce(Trigger.BACKGROUND)。这保证 SyncEngine 可在 JVM 单测里直接跑（云端 CI 跑单测，配套约束 #1）。
- WorkManager 依赖已在基线（widget 侧使用），无新增体积。

---

## 6. 邮箱 IMAP 方案

### 6.1 库选择

| 方案 | 体积 | 评估 |
|---|---|---|
| org.eclipse.angus:angus-mail（Jakarta Mail 2.x）+ angus-activation | 约600KB（R8 后估 <300KB） | **推荐**。纯 Java、无传递依赖爆炸、IMAP 协议完整（IDLE/UTF-8/部分抓取），Android 验证充分 |
| 自写最小 IMAP 客户端 | 0 | 协议坑多（字面量、续行、charset、IDLE），单人维护不值 |
| com.sun.mail:android-mail | 同上 | 停更，是 angus 的前身，不选 |

**决策：angus-mail，M3 引入并在 CI 体积门禁观察；若 R8 后增量 >400KB 则降级为「只保留 mail.buct.edu.cn 的 webmail 页面跳转」并在文档记录。**

### 6.2 实现要点

- **只读收件**：folder.open(Folder.READ_ONLY)，拉 INBOX 最近 30 天 ENVELOPE（不拉正文），UID 缓存增量（UIDFolder.getUIDNext() 起点记忆）。正文按需：用户点开才 getMessageByUID(uid).getContent()，缓存最近 50 封到 store 分片。
- **授权**：IMAP 独立口令（学校邮箱设置里开的授权码），首次使用弹独立设置页：服务器 mail.buct.edu.cn:993 SSL（走现有 BuctTlsTrustManager 的证书链校验）。**不与 SSO 凭据混存**。
- **凭据存储**：EncryptedSharedPreferences，key = imap.<account>；退出账号即删。IMAP 凭据失效 → 该域 FAILED_FALLBACK + UI 提示重授权，不影响其他域。
- **provenance**：source = imap.inbox，complete=false 当只拉了部分 UID 区间。

---

## 7. 场馆（免登录公开页）

- **抓取**：VenueSource 直接 GET 公开预约页（占位 https://tyb.buct.edu.cn/...，以实际抓包为准）。不经过 SsoSessionManager（domain.requiresLogin = false）。
- **解析**：与 TheolSource 同款轻量 HTML 解析；探针特征：日期表格列头 + 场地行。
- **provenance 语义**：source = venue.public，refreshState 正常流转；特别地：公开页无会话，无 Auth 错误路径；页面改版检测同样走结构探针。缓存策略激进（1 小时即 STALE），因为数据是预约余量，时效敏感。
- 可与 THEOL 并行抓取（无登录域限速顾虑）。

---

## 8. 包结构

现状：本仓库初始化时已把全部源码从 `com.tyust.course` 重命名为 **`com.buct.one`**（497 .kt / 9 .java / 3 .aidl / 9 个 res XML / proguard 规则 / build.gradle / CI 一并改动，`namespace` 与 `applicationId` 同为 `com.buct.one`，版本线重置为 0.1.0 / versionCode 1）。新数据层统一放在 **`com.buct.one.data.*`** 下，与既有业务包平级隔离。

新包树（新增部分）：

~~~text
com.buct.one.data/
├── session/
│   ├── CampusDomain.kt            域枚举（host/requiresLogin）
│   ├── PooledCookieJar.kt         按域池化 + 加密持久化
│   ├── CookiePersistence.kt       EncryptedSharedPreferences 封装（去抖落盘）
│   ├── TicketExchanger.kt         静默换票三跳
│   ├── SsoSessionManager.kt       会话门面（ensure/states）
│   ├── SessionRenewerAdapter.kt   旧回调式 SessionRenewer → suspend 适配
│   └── UaPolicy.kt                非爬虫 UA 常量
├── source/
│   ├── CampusSource.kt            接口 + CampusError + RawSource + Completeness
│   ├── StructureProbe.kt          结构探针（改版检测）
│   ├── jwglxt/JwglxtScheduleSource.kt
│   ├── jwglxt/JwglxtGradeSource.kt
│   ├── jwglxt/JwglxtExamSource.kt
│   ├── theol/TheolSource.kt       课程+作业+通知（M3，先一个类，不拆细）
│   ├── mail/ImapSource.kt
│   ├── calendar/CalendarSource.kt 校历文本解析（M2 后期）
│   └── venue/VenueSource.kt
├── store/
│   ├── Provenance.kt              Provenance/RefreshState/DomainSlice/不变式
│   ├── CampusStore.kt             分片 JSON + 原子提交 + .trash
│   ├── CampusSlices.kt            每域 SliceRef 常量表
│   └── model/                     各域规范化 data class（CourseTable/GradeRow/…）
└── sync/
    ├── SyncEngine.kt
    ├── SyncPolicy.kt              频率/网络条件/退避参数
    ├── BackoffPolicy.kt
    └── SyncWorker.kt              WorkManager 薄壳
~~~

**R 类边界**：全部代码同属 `com.buct.one`，R 类唯一（`com.buct.one.R`）。但 `data/` 层**不直接引用 R**（纯数据层，无 UI 资源），需要字符串/颜色时由调用方注入参数，保持 JVM 单测可跑。

---

## 9. 必须新写的类清单

| 文件路径（com.buct.one.data. 下） | 职责 | 预估行数 | 依赖的现有类 | 优先级 |
|---|---|---|---|---|
| campus/session/CampusDomain.kt | 域枚举 | 40 | — | P0 |
| campus/session/PooledCookieJar.kt | 按域 cookie 池 | 120 | MatchingMemoryCookieJar（参考） | P0 |
| campus/session/CookiePersistence.kt | 加密落盘 | 90 | — | P0 |
| campus/session/TicketExchanger.kt | 静默换票 | 110 | OkHttpClient | P0 |
| campus/session/SsoSessionManager.kt | 会话门面+状态机 | 180 | BuctSsoLoginManager | P0 |
| campus/session/SessionRenewerAdapter.kt | 回调→协程适配 | 90 | utils.SessionRenewer | P0 |
| campus/session/UaPolicy.kt | UA 常量 | 15 | — | P0 |
| campus/source/CampusSource.kt | 接口+错误类型 | 150 | — | P0 |
| campus/source/StructureProbe.kt | 改版检测 | 100 | — | P0 |
| campus/store/Provenance.kt | 溯源模型+不变式 | 140 | kotlinx.serialization | P0 |
| campus/store/CampusStore.kt | 分片存储+原子提交 | 280 | — | P0 |
| campus/store/CampusSlices.kt | SliceRef 常量表 | 60 | 各 model | P0 |
| campus/store/model/AcademicModels.kt | 教务规范化模型 | 220 | — | P0 |
| campus/sync/SyncEngine.kt | 逐域调度 | 200 | 全部上者 | P0 |
| campus/sync/SyncPolicy.kt + BackoffPolicy.kt | 策略 | 80 | — | P0 |
| campus/sync/SyncWorker.kt | WorkManager 壳 | 60 | SyncEngine | P1 |
| campus/source/jwglxt/JwglxtScheduleSource.kt | 课表源 | 160 | AcademicHttpTransport, AcademicStudyParser | P0 |
| campus/source/jwglxt/JwglxtGradeSource.kt | 成绩源 | 140 | 同上 | P1 |
| campus/source/jwglxt/JwglxtExamSource.kt | 考试源 | 120 | 同上 | P1 |
| campus/source/theol/TheolSource.kt | THEOL 源 | 520 | SsoSessionManager | P1 (M3) |
| campus/source/mail/ImapSource.kt | IMAP 源 | 380 | angus-mail | P1 (M3) |
| campus/source/calendar/CalendarSource.kt | 校历 | 200 | — | P2 |
| campus/source/venue/VenueSource.kt | 场馆 | 140 | — | P2 |

P0 约 2100 行（M2 内完成），新增依赖 0；P1 约 1300 行 + angus-mail；P2 约 340 行。

---

## 10. 迁移路径

现状三件旧设施：schedule/ScheduleRepository.kt（SharedPreferences 缓存，供 App+小组件）、study/AcademicSnapshotStore.kt、CourseApiClient（manager/ 下，旧 HTTP 客户端）。共存原则：**新读旧写→双读择新→旧退役**，每步一个可回滚点（git tag + 功能开关）。

| 步 | 动作 | 回滚点 |
|---|---|---|
| S0 | 引入 campus/ 全部 P0 类，**零接线**：不删不改任何旧类；加 JVM 单测（结构探针、CampusStore 原子性、不变式）跑在云端 CI | tag data-layer-scaffold：纯新增，revert 即回滚 |
| S1 | 写路径并轨：登录成功后 SyncEngine.syncOnce(APP_START) 照跑，JwglxtScheduleSource 抓到的课表**同时**写 CampusStore 与旧 ScheduleCacheStore（双写）。读路径不变（仍读旧） | 开关 dual_write_schedule（SharedPreferences），关掉即回到旧行为 |
| S2 | 读路径切换：ScheduleRepository.snapshot() 内部改为「CampusStore 有 → 用新；无 → 旧缓存 → 兜底 EMPTY」。小组件零改动（它只认 ScheduleSnapshot） | 开关 read_from_campus_store；任何崩溃/空课表反馈 → 关开关回到旧读 |
| S3 | 旧写路径退役：ScheduleCacheStore 只作导入源（首次升级迁移：旧缓存一次性搬进 CampusStore），此后不再写入 | tag legacy-cache-readonly |
| S4 | CourseApiClient / AcademicSnapshotStore 逐功能替换：成绩/考试页面改读 CampusStore 分片；CourseApiClient 保留到 M4（小组件刷新链路还有引用）后再删 | 每替换一个页面一个 commit，独立 revert |
| S5 | M3/M4：TheolSource/ImapSource 直接只写 CampusStore（无旧设施，天生干净） | — |

关键校验点（每步的验收）：S1 后「断网启动仍显示课表（读旧缓存）」；S2 后「密码错误→重登失败→课表仍在且 provenance=FAILED_FALLBACK 标注时间」（M2 验收条款）；S3 后「卸载重装→登录→快照空但 NEVER 不误报 EMPTY_CONFIRMED」。

---

## 附：与规划稿 v1 的差异说明

本设计与规划稿一致，仅三处细化/修正：
1. 规划稿 §3 的包结构把 session/source 等画在根包下——本设计统一收进 **`com.buct.one.data.*`**（§8），与既有业务包平级隔离；包名重生（`tyust.course` → `buct.one`）已随仓库初始化完成，不再是风险项；
2. 规划稿未定持久化选型——本设计拍板**分片 JSON**（§4.1）；
3. 规划稿 SyncEngine 未定并发度——本设计拍板**登录域串行=1，公开域可并行**（§5.2），并明确 WorkManager 只是薄壳、SyncEngine 保持 JVM 可测。

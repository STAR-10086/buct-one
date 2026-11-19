package com.buct.one.data.source.theol

import android.util.Base64
import com.buct.course.login.BuctAesCrypto
import com.buct.course.login.BuctTlsTrustManager
import com.buct.course.login.MatchingMemoryCookieJar
import com.buct.one.data.model.TheolHomeworkDetail
import com.buct.one.data.model.TheolHomeworkItem
import com.buct.one.data.model.TheolSnapshot
import com.buct.one.data.model.TheolTestDetail
import com.buct.one.data.model.TheolTestItem
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 北化在线（THEOL / MEOL）原生 HTTP 客户端。
 * 协议移植自 Ling0727-ai/go-buct-course 并融合 BUCT-Course-Companion 会话与路径修复，零 WebView，强制走 BuctTlsTrustManager。
 */
class TheolClient {
    private val cookieJar: MatchingMemoryCookieJar = MatchingMemoryCookieJar()

    @Volatile
    private var lastSessionVerifiedAt: Long = 0L

    @Volatile
    private var activeCourseLid: String = ""

    @Volatile
    private var activeCourseActivatedAt: Long = 0L

    companion object {
        const val SID_PORTAL_BASE = "https://sidportal.buct.edu.cn"
        const val MEOL_BASE = "https://course.buct.edu.cn"
        const val MEOL_SSO_SERVICE = "https://course.buct.edu.cn/meol/homepage/common/sso_login.jsp"
        const val MEOL_WELCOME_HOME = "https://course.buct.edu.cn/meol/welcomepage/student/index.jsp"
        const val MEOL_LEGACY_HOME_PATH = "/meol/homepage/student/index.jsp"
        const val MEOL_REMINDER_URL = "https://course.buct.edu.cn/meol/welcomepage/student/interaction_reminder_v8.jsp"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        private const val UA = USER_AGENT
        private const val MAX_COURSE_PAGES = 40
        private const val MAX_COURSE_DEPTH = 6
        private const val SESSION_TTL_MILLIS = 5 * 60_000L
        private const val COURSE_CONTEXT_TTL_MILLIS = 2 * 60_000L

        private val ALLOWED_HW_PATHS = setOf(
            "/meol/jpk/course/layout/newpage/index.jsp",
            "/meol/jpk/course/course_column_preview_transfer.jsp",
            "/meol/buildless/colUrlStuView.do",
            "/meol/common/hw/student/hwtask.jsp",
            "/meol/common/hw/student/hwtask_blended.jsp"
        )
        private val LOCATION_REGEX = Regex(
            """(?:window\.)?location(?:\.href)?\s*=\s*['"]([^'"]+)['"]|(?:window\.)?location\.replace\s*\(\s*['"]([^'"]+)['"]"""
        )
        private val QUOTED_URL_REGEX = Regex("""['"]([^'"]+)['"]""")
        private val REFRESH_REGEX = Regex("""(?i)url\s*=\s*(.+)""")
        private val CHARSET_REGEX = Regex("""(?i)charset\s*=\s*['"]?([a-zA-Z0-9_-]+)""")
    }

    private val trustManager = BuctTlsTrustManager()

    private val noRedirectClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .sslSocketFactory(trustManager.sslSocketFactory(), trustManager)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val meolClient: OkHttpClient = noRedirectClient.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** 从持久化存储注入历史 Cookie（MEOL 的 JSESSIONID 默认作用域为 /meol，避免与根路径冲突） */
    fun importCookies(host: String, cookieHeader: String) {
        val pathForHost = if (host.equals("course.buct.edu.cn", ignoreCase = true)) "/meol" else "/"
        val url = "https://$host$pathForHost".toHttpUrl()
        val parsed = cookieHeader.split(';').mapNotNull { part ->
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val name = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1).trim()
            if (value.isEmpty()) return@mapNotNull null
            val domain = if (name == "_zte_cid_") "buct.edu.cn" else host
            val path = if (name == "_zte_cid_") "/" else pathForHost
            Cookie.Builder()
                .name(name)
                .value(value)
                .domain(domain)
                .path(path)
                .expiresAt(Long.MAX_VALUE)
                .build()
        }
        if (parsed.isNotEmpty()) {
            cookieJar.saveFromResponse(url, parsed)
        }
    }

    /** 导出指定域名的 Cookie 字符串以便加密保存（course.buct.edu.cn 按 /meol/ 路径检索） */
    fun exportCookieHeader(host: String): String {
        val probeUrl = if (host.equals("course.buct.edu.cn", ignoreCase = true)) {
            MEOL_WELCOME_HOME.toHttpUrl()
        } else {
            "https://$host/login".toHttpUrl()
        }
        val merged = LinkedHashMap<String, String>()
        cookieJar.loadForRequest(probeUrl).forEach { cookie ->
            merged[cookie.name] = cookie.value
        }
        cookieJar.cookiesSetBy(host, 443).forEach { cookie ->
            if (!merged.containsKey(cookie.name) && cookie.value.isNotBlank()) {
                merged[cookie.name] = cookie.value
            }
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** 重置内存中的会话校验与课程上下文缓存（切换账号或外部重新登录后调用） */
    fun invalidateSessionCache() {
        lastSessionVerifiedAt = 0L
        activeCourseLid = ""
        activeCourseActivatedAt = 0L
    }

    fun isSessionRecentlyVerified(ttlMillis: Long = SESSION_TTL_MILLIS): Boolean =
        lastSessionVerifiedAt > 0L &&
            (System.currentTimeMillis() - lastSessionVerifiedAt) in 0L..ttlMillis &&
            hasMeolSessionCookie()

    fun isCourseContextFresh(courseLid: String, ttlMillis: Long = COURSE_CONTEXT_TTL_MILLIS): Boolean {
        val lid = courseLid.trim()
        return lid.isNotEmpty() &&
            activeCourseLid == lid &&
            activeCourseActivatedAt > 0L &&
            (System.currentTimeMillis() - activeCourseActivatedAt) in 0L..ttlMillis
    }

    private fun hasMeolSessionCookie(): Boolean =
        cookieJar.loadForRequest(MEOL_REMINDER_URL.toHttpUrl()).any {
            it.name.equals("JSESSIONID", ignoreCase = true) && it.value.isNotBlank()
        }

    /**
     * 快速探针：仅请求一次待办索引页验证已有 JSESSIONID 是否存活。
     * 成功时返回响应 HTML 并刷新会话校验时间戳（可直接复用于列表解析，避免多余重定向）；失效则返回 null。
     */
    fun probeReminderHtml(): String? {
        val req = Request.Builder()
            .url(MEOL_REMINDER_URL)
            .header("User-Agent", UA)
            .header("Referer", MEOL_WELCOME_HOME)
            .get()
            .build()
        noRedirectClient.newCall(req).execute().use { resp ->
            if (resp.code != 200) return null
            val finalUrl = resp.request.url
            if (!finalUrl.host.equals("course.buct.edu.cn", ignoreCase = true)) return null
            val html = decodeResponseBody(resp)
            if (TheolParser.isPermissionDeniedOrExpired(html)) return null
            lastSessionVerifiedAt = System.currentTimeMillis()
            return html
        }
    }

    /** 检查当前会话是否可直接访问北化在线（优先快速探针，失效时再通过 TGT 自动换票） */
    fun ensureSessionActive(forceSso: Boolean = false): Boolean {
        if (!forceSso && hasMeolSessionCookie()) {
            if (probeReminderHtml() != null) return true
        }
        val probe = followAuthRedirects(
            Request.Builder()
                .url(MEOL_SSO_SERVICE)
                .header("User-Agent", UA)
                .get()
                .build(),
            allowedHosts = setOf("course.buct.edu.cn", "sidportal.buct.edu.cn", "portal.buct.edu.cn")
        )
        val landed = probe.use { resp ->
            val finalUrl = resp.request.url
            val body = if (resp.isSuccessful) decodeResponseBody(resp) else ""
            resp.isSuccessful &&
                finalUrl.host.equals("course.buct.edu.cn", ignoreCase = true) &&
                finalUrl.encodedPath.startsWith("/meol/") &&
                !finalUrl.encodedPath.contains("login", ignoreCase = true) &&
                !TheolParser.isPermissionDeniedOrExpired(body)
        }
        if (!landed) return false
        return probeReminderHtml() != null
    }

    /**
     * 使用学号与密码（CharArray，用完立即清零）原生登录 sidportal 并建立 MEOL 会话。
     */
    fun loginWithCredentials(username: String, passwordChars: CharArray): Result<Unit> {
        return try {
            val encodedService = URLEncoder.encode(MEOL_SSO_SERVICE, "UTF-8")
            val loginEntry = "$SID_PORTAL_BASE/login?service=$encodedService"

            val entryResp = followAuthRedirects(
                Request.Builder()
                    .url(loginEntry)
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .get()
                    .build(),
                allowedHosts = setOf("course.buct.edu.cn", "sidportal.buct.edu.cn", "portal.buct.edu.cn")
            )

            val (entryUrl, entryHtml) = entryResp.use { resp ->
                if (!resp.isSuccessful) {
                    return Result.failure(IOException("统一认证页面响应异常（HTTP ${resp.code}）"))
                }
                val finalUrl = resp.request.url
                if (finalUrl.host.equals("course.buct.edu.cn", ignoreCase = true) &&
                    finalUrl.encodedPath.startsWith("/meol/") &&
                    !finalUrl.encodedPath.contains("login", ignoreCase = true)
                ) {
                    lastSessionVerifiedAt = System.currentTimeMillis()
                    activeCourseLid = ""
                    activeCourseActivatedAt = 0L
                    return Result.success(Unit)
                }
                finalUrl to decodeResponseBody(resp)
            }

            if (!entryUrl.host.equals("sidportal.buct.edu.cn", ignoreCase = true)) {
                return Result.failure(IOException("统一认证入口发生非预期跳转"))
            }

            val page = TheolParser.parseSidPortalPage(entryHtml)
            if (page.errorCode.isNotEmpty()) {
                return Result.failure(IOException(mapSidError(page.errorCode)))
            }
            if (page.execution.isEmpty() || page.croypto.isEmpty()) {
                return Result.failure(IOException("认证页面缺少 execution 或 croypto 参数"))
            }

            val pwdString = String(passwordChars)
            val encryptedPassword = BuctAesCrypto.encrypt(page.croypto, pwdString)
            val encryptedCaptchaPayload = BuctAesCrypto.encrypt(page.croypto, "{}")

            val form = FormBody.Builder()
                .add("type", "UsernamePassword")
                .add("_eventId", "submit")
                .add("username", username)
                .add("password", encryptedPassword)
                .add("croypto", page.croypto)
                .add("execution", page.execution)
                .add("captcha_code", "")
                .add("captcha_payload", encryptedCaptchaPayload)
                .add("geolocation", "")
                .build()

            val postReq = Request.Builder()
                .url(loginEntry)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Origin", SID_PORTAL_BASE)
                .header("Referer", loginEntry)
                .post(form)
                .build()

            val loginResp = followAuthRedirects(
                postReq,
                allowedHosts = setOf("course.buct.edu.cn", "sidportal.buct.edu.cn", "portal.buct.edu.cn")
            )

            loginResp.use { resp ->
                if (!resp.isSuccessful) {
                    return Result.failure(IOException("统一认证登录失败（HTTP ${resp.code}）"))
                }
                val finalUrl = resp.request.url
                val body = decodeResponseBody(resp)
                val parsed = TheolParser.parseSidPortalPage(body)
                if (parsed.second.equals("true", ignoreCase = true)) {
                    return Result.failure(IOException("账号触发了短信二次验证，请先在「我的 → 重新登录」完成教务登录，北化在线会自动复用凭证"))
                }
                if (parsed.errorCode.isNotEmpty()) {
                    return Result.failure(IOException(mapSidError(parsed.errorCode)))
                }
                if (parsed.captchaUrl.isNotEmpty()) {
                    return Result.failure(IOException("需要图形验证码，请先在教务登录页完成登录，北化在线会自动复用凭证"))
                }
                val landedInMeol = finalUrl.host.equals("course.buct.edu.cn", ignoreCase = true) &&
                    finalUrl.encodedPath.startsWith("/meol/") &&
                    resp.request.method.equals("GET", ignoreCase = true) &&
                    parsed.execution.isEmpty() &&
                    parsed.croypto.isEmpty() &&
                    (!finalUrl.encodedPath.contains("login", ignoreCase = true) || finalUrl.encodedPath == "/meol/homepage/common/sso_login.jsp")

                if (!landedInMeol) {
                    return Result.failure(IOException("认证未完成，请检查学号密码或在教务登录页重新登录后点击刷新"))
                }
            }
            lastSessionVerifiedAt = System.currentTimeMillis()
            activeCourseLid = ""
            activeCourseActivatedAt = 0L
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            Arrays.fill(passwordChars, '\u0000')
        }
    }

    private class SessionExpiredSignal(message: String) : IOException(message)

    /** 在服务端 Tomcat HttpSession 中激活当前课程上下文，防止直达作业详情/提交页报 Permission Denied */
    fun enterCourseContext(courseLid: String, force: Boolean = false): Boolean {
        val lid = courseLid.trim()
        if (lid.isEmpty()) return true
        if (!force && isCourseContextFresh(lid)) return true

        val courseHome = "$MEOL_BASE/meol/jpk/course/layout/newpage/index.jsp?courseId=${URLEncoder.encode(lid, "UTF-8")}"
        val courseHtml = fetchHtml(courseHome, MEOL_WELCOME_HOME)
        if (TheolParser.isPermissionDeniedOrExpired(courseHtml)) {
            activeCourseLid = ""
            activeCourseActivatedAt = 0L
            return false
        }
        // 部分课程使用自定义栏目，默认 hwtask.jsp 失败时仍可继续读取单条详情，因此忽略此步错误
        val hwEntry = "$MEOL_BASE/meol/common/hw/student/hwtask.jsp?tagbug=client&strStyle=new03"
        runCatching {
            val req = Request.Builder()
                .url(hwEntry)
                .header("User-Agent", UA)
                .header("Referer", courseHome)
                .get()
                .build()
            meolClient.newCall(req).execute().use { /* 同步激活作业栏目上下文 */ }
        }
        activeCourseLid = lid
        activeCourseActivatedAt = System.currentTimeMillis()
        return true
    }

    private fun normalizeSecureCourseUrl(rawUrl: String): String {
        val normalized = normalizeUrl(MEOL_BASE, rawUrl) ?: rawUrl
        return if (normalized.startsWith("http://course.buct.edu.cn", ignoreCase = true)) {
            "https://" + normalized.removePrefix("http://")
        } else {
            normalized
        }
    }

    private fun fetchCourseSubpageHtml(courseLid: String, secureUrl: String, referer: String): String {
        enterCourseContext(courseLid)
        var html = fetchHtml(secureUrl, referer)
        if (TheolParser.isPermissionDeniedOrExpired(html)) {
            if (!ensureSessionActive(forceSso = true) || !enterCourseContext(courseLid, force = true)) {
                throw IOException("北化在线会话已过期，请刷新或重新登录")
            }
            html = fetchHtml(secureUrl, referer)
            if (TheolParser.isPermissionDeniedOrExpired(html)) {
                throw IOException("北化在线提示权限不足或登录超时，请重新登录后重试")
            }
        }
        return html
    }

    /** 拉取单条作业详情（要求、附件等），自动建立课程上下文并在失效时尝试换票重试一次 */
    fun fetchHomeworkDetail(
        item: TheolHomeworkItem,
        nowMillis: Long = System.currentTimeMillis()
    ): TheolHomeworkDetail {
        val rawDetailUrl = item.detailHref.ifBlank { "$MEOL_BASE/meol/common/hw/student/hwtask.view.jsp?hwtid=${item.hwtid}" }
        val secureUrl = normalizeSecureCourseUrl(rawDetailUrl)
        val referer = "$MEOL_BASE/meol/common/hw/student/hwtask.jsp?tagbug=client&strStyle=new03"
        val html = fetchCourseSubpageHtml(item.lid, secureUrl, referer)
        return TheolParser.parseHomeworkDetail(html, secureUrl, item.hwtid, nowMillis)
    }

    /** 拉取单条在线测验详情（view.jsp?testId=...），无法直接定位数字编号时平滑回退列表摘要 */
    fun fetchTestDetail(
        item: TheolTestItem,
        nowMillis: Long = System.currentTimeMillis()
    ): TheolTestDetail {
        val cleanId = item.testId.trim()
        if (cleanId.isEmpty() || !cleanId.all(Char::isDigit) || cleanId.all { it == '0' }) {
            return TheolTestDetail(
                testId = item.testId,
                title = item.title,
                startTime = item.startTime,
                endTime = item.endTime,
                duration = item.duration,
                fetchedAt = nowMillis
            )
        }
        val detailUrl = "$MEOL_BASE/meol/common/question/test/student/view.jsp?testId=${URLEncoder.encode(cleanId, "UTF-8")}"
        val referer = TheolParser.buildTestListUrl(item.lid, MEOL_BASE)
        val html = fetchCourseSubpageHtml(item.lid, detailUrl, referer)
        return TheolParser.parseTestDetail(html, cleanId, nowMillis)
    }

    /** 抓取北化在线所有待办作业与测验（支持复用探针已拿到的 reminderHtml 与测验页并发） */
    fun fetchPendingSnapshot(
        nowMillis: Long = System.currentTimeMillis(),
        prefetchedReminderHtml: String? = null
    ): TheolSnapshot {
        val reminderHtml = prefetchedReminderHtml
            ?.takeIf { !TheolParser.isPermissionDeniedOrExpired(it) }
            ?: fetchHtml(MEOL_REMINDER_URL, MEOL_WELCOME_HOME).also { html ->
                if (TheolParser.isPermissionDeniedOrExpired(html)) {
                    throw IOException("北化在线会话已过期，请重新登录")
                }
                lastSessionVerifiedAt = System.currentTimeMillis()
            }

        val (hwCourses, testCourses) = TheolParser.parsePendingCourses(reminderHtml, MEOL_BASE)
        val allHomeworks = mutableListOf<TheolHomeworkItem>()
        val seenHwIds = mutableSetOf<String>()
        val allTests = mutableListOf<TheolTestItem>()
        val seenTestIds = mutableSetOf<String>()
        var failedCourses = 0

        // 抓取多门课程作业会切换 Tomcat session 中的当前课程，因此顺序执行并在结束后重置课程上下文缓存
        activeCourseLid = ""
        activeCourseActivatedAt = 0L
        for (course in hwCourses) {
            try {
                val items = walkHomeworkPages(course.url, course.lid, course.courseName, nowMillis)
                for (hw in items) {
                    if (seenHwIds.add(hw.hwtid)) {
                        allHomeworks.add(hw)
                    }
                }
            } catch (e: SessionExpiredSignal) {
                throw e
            } catch (_: Exception) {
                failedCourses++
            }
        }
        activeCourseLid = ""
        activeCourseActivatedAt = 0L

        if (testCourses.size <= 1) {
            for (course in testCourses) {
                try {
                    for (t in fetchCourseTests(course.lid, course.courseName, course.url)) {
                        if (seenTestIds.add("${t.lid}:${t.testId}")) {
                            allTests.add(t)
                        }
                    }
                } catch (e: SessionExpiredSignal) {
                    throw e
                } catch (_: Exception) {
                    failedCourses++
                }
            }
        } else {
            val pool = Executors.newFixedThreadPool(minOf(3, testCourses.size))
            try {
                val futures = testCourses.map { course ->
                    pool.submit(Callable {
                        fetchCourseTests(course.lid, course.courseName, course.url)
                    })
                }
                for (future in futures) {
                    try {
                        for (t in future.get()) {
                            if (seenTestIds.add("${t.lid}:${t.testId}")) {
                                allTests.add(t)
                            }
                        }
                    } catch (e: java.util.concurrent.ExecutionException) {
                        val cause = e.cause
                        if (cause is SessionExpiredSignal) throw cause
                        failedCourses++
                    }
                }
            } finally {
                pool.shutdown()
            }
        }

        allHomeworks.sortWith(compareBy<TheolHomeworkItem>({ !it.canSubmit }, { it.deadlineMillis ?: Long.MAX_VALUE }))
        allTests.sortWith(compareBy<TheolTestItem>({ !it.canStart }, { it.endTimeMillis ?: Long.MAX_VALUE }))

        return TheolSnapshot(
            homeworks = allHomeworks,
            tests = allTests,
            failedCourseCount = failedCourses
        )
    }

    private fun fetchCourseTests(lid: String, courseName: String, url: String): List<TheolTestItem> {
        val referer = "$MEOL_BASE/meol/jpk/course/layout/newpage/index.jsp?courseId=$lid"
        val testHtml = fetchHtml(url, referer)
        if (TheolParser.isPermissionDeniedOrExpired(testHtml)) {
            throw SessionExpiredSignal("课程会话已过期")
        }
        return TheolParser.parseTestList(testHtml, lid, courseName, MEOL_BASE)
    }

    private fun walkHomeworkPages(
        startUrl: String,
        lid: String,
        courseName: String,
        nowMillis: Long
    ): List<TheolHomeworkItem> {
        data class QueueEntry(val url: String, val referer: String, val depth: Int)

        val queue = ArrayDeque<QueueEntry>()
        val visited = mutableSetOf<String>()
        val seenTableSignatures = mutableSetOf<String>()
        val found = mutableListOf<TheolHomeworkItem>()
        val seenIds = mutableSetOf<String>()

        val normalizedStart = normalizeUrl(MEOL_BASE, startUrl) ?: return emptyList()
        if (!isAllowedHomeworkUrl(normalizedStart)) return emptyList()
        queue.addLast(QueueEntry(normalizedStart, "$MEOL_BASE/", 0))
        visited.add(normalizedStart)

        var fetched = 0
        while (queue.isNotEmpty() && fetched < MAX_COURSE_PAGES) {
            val current = queue.removeFirst()
            if (current.depth > MAX_COURSE_DEPTH) continue
            fetched++

            val req = Request.Builder()
                .url(current.url)
                .header("User-Agent", UA)
                .header("Referer", current.referer)
                .get()
                .build()

            noRedirectClient.newCall(req).execute().use { resp ->
                if (resp.code in 300..399) {
                    val location = resp.header("Location").orEmpty()
                    val next = normalizeUrl(current.url, location) ?: return@use
                    if (next.contains("login", ignoreCase = true)) {
                        throw SessionExpiredSignal("课程会话已过期")
                    }
                    if (isAllowedHomeworkUrl(next) && visited.add(next)) {
                        queue.addFirst(QueueEntry(next, current.url, current.depth + 1))
                    }
                    return@use
                }
                if (!resp.isSuccessful) return@use
                val html = decodeResponseBody(resp)
                if (TheolParser.isPermissionDeniedOrExpired(html)) {
                    throw SessionExpiredSignal("课程会话已过期")
                }

                // 单次 Jsoup 解析同时供作业抽取与子栏目发现使用，避免重复 parse
                val doc = Jsoup.parse(html, current.url)
                for (hw in TheolParser.parseHomeworkPage(doc, current.url, lid, courseName, nowMillis)) {
                    if (seenIds.add(hw.hwtid)) found.add(hw)
                }

                val parsedUrl = runCatching { current.url.toHttpUrl() }.getOrNull()
                val currentPath = parsedUrl?.encodedPath.orEmpty()
                val pagingPageStr = parsedUrl?.queryParameter("pagingPage").orEmpty()
                val table = doc.selectFirst("table.valuelist")
                var repeated = false
                if (table != null && pagingPageStr.isNotEmpty()) {
                    val seriesUrl = parsedUrl!!.newBuilder().removeAllQueryParameters("pagingPage").build().toString()
                    val signature = sha256Hex("$seriesUrl\u0000${table.html()}")
                    repeated = !seenTableSignatures.add(signature)
                }

                fun enqueue(raw: String, sameDepth: Boolean = false) {
                    val next = normalizeUrl(current.url, raw) ?: return
                    if (isAllowedHomeworkUrl(next) && visited.add(next)) {
                        val nextDepth = if (sameDepth) current.depth else current.depth + 1
                        queue.addLast(QueueEntry(next, current.url, nextDepth))
                    }
                }

                doc.select("iframe[src], frame[src]").forEach { enqueue(it.attr("src")) }
                doc.select("meta[http-equiv]").forEach {
                    if (it.attr("http-equiv").equals("refresh", ignoreCase = true)) {
                        val match = REFRESH_REGEX.find(it.attr("content"))
                        if (match != null) enqueue(match.groupValues[1].trim(' ', '"', '\''))
                    }
                }
                doc.select("script:not([src])").forEach { script ->
                    LOCATION_REGEX.findAll(script.data()).forEach { m ->
                        val target = m.groupValues[1].ifEmpty { m.groupValues[2] }
                        if (target.isNotEmpty()) enqueue(target)
                    }
                }
                doc.select("a[href], a[onclick]").forEach { a ->
                    val href = a.attr("href")
                    if (href.isNotEmpty()) {
                        val targetPath = normalizeUrl(current.url, href)
                            ?.let { u -> runCatching { u.toHttpUrl().encodedPath }.getOrNull() }
                        if (targetPath == currentPath) {
                            if (!repeated) enqueue(href, sameDepth = true)
                        } else {
                            enqueue(href)
                        }
                    }
                    val onclick = a.attr("onclick")
                    if (onclick.isNotEmpty()) {
                        LOCATION_REGEX.findAll(onclick).forEach { m ->
                            val target = m.groupValues[1].ifEmpty { m.groupValues[2] }
                            if (target.isNotEmpty()) enqueue(target)
                        }
                        QUOTED_URL_REGEX.findAll(onclick).forEach { m ->
                            val quoted = m.groupValues[1]
                            if (quoted.contains("columnId=") || quoted.contains("pagingPage=")) {
                                if (!repeated) enqueue(quoted, sameDepth = true)
                            } else {
                                enqueue(quoted)
                            }
                        }
                    }
                }
                val currentPage = pagingPageStr.toIntOrNull() ?: 0
                val perPage = parsedUrl?.queryParameter("pagingNumberPer")?.toIntOrNull() ?: 0
                if (!repeated && currentPage > 0 && perPage > 0 && table != null) {
                    val dataRows = table.select("tr").count { it.select("> td").isNotEmpty() }
                    if (dataRows >= perPage) {
                        val nextUrl = parsedUrl.newBuilder()
                            .setQueryParameter("pagingPage", (currentPage + 1).toString())
                            .build()
                            .toString()
                        enqueue(nextUrl, sameDepth = true)
                    }
                }
            }
        }
        return found
    }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun checkCaptchaPreflight(username: String, referer: String) {
        val randomBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val csrfKey = randomBytes.joinToString("") { "%02x".format(it) }
        val encoded = Base64.encodeToString(csrfKey.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val half = encoded.length / 2
        val combined = encoded.substring(0, half) + encoded + encoded.substring(half)
        val md5 = MessageDigest.getInstance("MD5")
            .digest(combined.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val encodedUser = URLEncoder.encode(username, "UTF-8")
        val req = Request.Builder()
            .url("$SID_PORTAL_BASE/api/protected/user/findCaptchaCount/$encodedUser")
            .header("User-Agent", UA)
            .header("Csrf-Key", csrfKey)
            .header("Csrf-Value", md5)
            .header("Referer", referer)
            .get()
            .build()

        noRedirectClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("验证码预检失败（HTTP ${resp.code}）")
            }
            val json = JSONObject(resp.body?.string().orEmpty())
            val code = json.optInt("code", -1)
            val data = json.optJSONObject("data")
            if (code != 200 || data == null || !data.has("captchaInvisible")) {
                throw IOException("无法确认验证码要求")
            }
            if (data.optBoolean("captchaInvisible", false)) {
                throw IOException(mapSidError("1320007"))
            }
        }
    }

    /** 仅跟随可信域名的 GET 重定向，拒绝 307/308 重放带凭据的 POST */
    private fun followAuthRedirects(initial: Request, allowedHosts: Set<String>): Response {
        var currentReq = initial
        var hops = 0
        while (hops < 10) {
            val resp = noRedirectClient.newCall(currentReq).execute()
            if (resp.code !in 300..399) {
                return resp
            }
            val location = resp.header("Location")
            val code = resp.code
            resp.close()
            if (location.isNullOrBlank()) {
                throw IOException("认证重定向缺少 Location")
            }
            if (code == 307 || code == 308) {
                throw IOException("拒绝在 307/308 重定向中重放凭据")
            }
            val resolved = currentReq.url.resolve(location)
                ?: throw IOException("非法的重定向地址")
            val hostLower = resolved.host.lowercase()
            if (hostLower !in allowedHosts) {
                throw IOException("拒绝跳转到非信任域名: ${resolved.host}")
            }
            val upgradedUrl = if (!resolved.isHttps && hostLower == "course.buct.edu.cn") {
                resolved.newBuilder().scheme("https").build()
            } else if (!resolved.isHttps) {
                throw IOException("拒绝非 HTTPS 重定向: ${resolved.host}")
            } else {
                resolved
            }
            val safeUrl = if (
                upgradedUrl.host.equals("course.buct.edu.cn", ignoreCase = true) &&
                upgradedUrl.encodedPath.equals(MEOL_LEGACY_HOME_PATH, ignoreCase = true)
            ) {
                MEOL_WELCOME_HOME.toHttpUrl()
            } else {
                upgradedUrl
            }
            currentReq = Request.Builder()
                .url(safeUrl)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .get()
                .build()
            hops++
        }
        throw IOException("认证重定向次数过多")
    }

    private fun fetchHtml(url: String, referer: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .get()
            .build()
        meolClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("北化在线请求失败（HTTP ${resp.code}）")
            }
            return decodeResponseBody(resp)
        }
    }

    private fun decodeResponseBody(resp: Response): String {
        val bytes = resp.body?.bytes() ?: return ""
        val contentType = resp.header("Content-Type").orEmpty()
        val headerCharset = CHARSET_REGEX.find(contentType)?.groupValues?.getOrNull(1)
        val probeHead = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val metaCharset = CHARSET_REGEX.find(probeHead)?.groupValues?.getOrNull(1)
        val declared = headerCharset ?: metaCharset
        if (declared != null) {
            val charsetName = declared.uppercase()
            val charset = runCatching {
                when {
                    charsetName.contains("GB") -> Charset.forName("GBK")
                    else -> Charset.forName(charsetName)
                }
            }.getOrDefault(Charsets.UTF_8)
            return String(bytes, charset)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            Charset.forName("GBK").decode(ByteBuffer.wrap(bytes)).toString()
        }
    }

    private fun normalizeUrl(base: String, raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("javascript:", true)) return null
        return runCatching {
            val resolved = URL(URL(base), trimmed)
            val uri = URI(resolved.protocol, resolved.authority, resolved.path, resolved.query, null).normalize()
            uri.toString()
        }.getOrNull()
    }

    private fun isAllowedHomeworkUrl(urlStr: String): Boolean {
        val parsed = runCatching { urlStr.toHttpUrl() }.getOrNull() ?: return false
        return parsed.isHttps &&
            parsed.host.equals("course.buct.edu.cn", ignoreCase = true) &&
            parsed.encodedPath in ALLOWED_HW_PATHS
    }

    private fun mapSidError(code: String): String = when (code) {
        "1030027", "1030031" -> "学号或密码错误"
        "1030028" -> "账号已锁定，请稍后再试"
        "1410040", "1410041" -> "当前账号不可用"
        "1320007" -> "需要图形验证码，请先在教务登录完成验证"
        "1030007" -> "验证码不正确"
        "1330001" -> "触发风险验证"
        else -> "北化在线统一认证未完成（$code）"
    }
}

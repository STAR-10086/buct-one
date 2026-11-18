package com.buct.one.data.source.theol

import android.util.Base64
import com.buct.course.login.BuctAesCrypto
import com.buct.course.login.BuctTlsTrustManager
import com.buct.course.login.MatchingMemoryCookieJar
import com.buct.one.data.model.TheolHomeworkItem
import com.buct.one.data.model.TheolSnapshot
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
import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.concurrent.TimeUnit

/**
 * 北化在线（THEOL / MEOL）原生 HTTP 客户端。
 * 协议移植自 Ling0727-ai/go-buct-course (MIT)，零 WebView，强制走 BuctTlsTrustManager。
 */
class TheolClient {
    private val cookieJar: MatchingMemoryCookieJar = MatchingMemoryCookieJar()
    companion object {
        const val SID_PORTAL_BASE = "https://sidportal.buct.edu.cn"
        const val MEOL_BASE = "https://course.buct.edu.cn"
        const val MEOL_SSO_SERVICE = "https://course.buct.edu.cn/meol/homepage/common/sso_login.jsp"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        private const val MAX_COURSE_PAGES = 40
        private const val MAX_COURSE_DEPTH = 6

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

    /** 从持久化存储注入历史 Cookie（例如已登录的 sidportal 或 course.buct.edu.cn 会话） */
    fun importCookies(host: String, cookieHeader: String) {
        val url = "https://$host/".toHttpUrl()
        val parsed = cookieHeader.split(';').mapNotNull { part ->
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val name = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1).trim()
            Cookie.Builder()
                .name(name)
                .value(value)
                .domain(host)
                .path("/")
                .build()
        }
        if (parsed.isNotEmpty()) {
            cookieJar.saveFromResponse(url, parsed)
        }
    }

    /** 导出指定域名的 Cookie 字符串以便加密保存 */
    fun exportCookieHeader(host: String): String {
        val cookies = cookieJar.loadForRequest("https://$host/".toHttpUrl())
        return cookies.joinToString("; ") { "${it.name}=${it.value}" }
    }

    /** 检查当前会话是否可直接访问北化在线（或通过已有 TGT 自动换票） */
    fun ensureSessionActive(): Boolean {
        val probe = followAuthRedirects(
            Request.Builder()
                .url(MEOL_SSO_SERVICE)
                .header("User-Agent", UA)
                .get()
                .build(),
            allowedHosts = setOf("course.buct.edu.cn", "sidportal.buct.edu.cn", "portal.buct.edu.cn")
        )
        return probe.use { resp ->
            val finalUrl = resp.request.url
            resp.isSuccessful &&
                finalUrl.host.equals("course.buct.edu.cn", ignoreCase = true) &&
                finalUrl.encodedPath.startsWith("/meol/") &&
                !finalUrl.encodedPath.contains("login", ignoreCase = true)
        }
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
            if ((page.vendor.isNotEmpty() && page.vendor != "system") ||
                (page.risk.isNotEmpty() && page.risk != "default") ||
                (page.rule.isNotEmpty() && page.rule != "normal") ||
                page.second.isNotEmpty() ||
                page.captchaUrl.isNotEmpty()
            ) {
                return Result.failure(IOException("当前认证触发验证码或二次验证，请先在教务登录页完成认证"))
            }

            checkCaptchaPreflight(username, loginEntry)

            val pwdString = String(passwordChars)
            val encryptedPassword = BuctAesCrypto.encrypt(page.croypto, pwdString)
            val encryptedCaptchaPayload = BuctAesCrypto.encrypt(page.croypto, "{}")

            val form = FormBody.Builder()
                .add("username", username)
                .add("type", "UsernamePassword")
                .add("_eventId", "submit")
                .add("geolocation", "")
                .add("execution", page.execution)
                .add("captcha_code", "")
                .add("croypto", page.croypto)
                .add("password", encryptedPassword)
                .add("captcha_payload", encryptedCaptchaPayload)
                .build()

            val postReq = Request.Builder()
                .url("$SID_PORTAL_BASE/login")
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
                if (parsed.errorCode.isNotEmpty()) {
                    return Result.failure(IOException(mapSidError(parsed.errorCode)))
                }
                val landedInMeol = finalUrl.host.equals("course.buct.edu.cn", ignoreCase = true) &&
                    finalUrl.encodedPath.startsWith("/meol/") &&
                    resp.request.method.equals("GET", ignoreCase = true) &&
                    parsed.execution.isEmpty() &&
                    parsed.croypto.isEmpty() &&
                    (!finalUrl.encodedPath.contains("login", ignoreCase = true) || finalUrl.encodedPath == "/meol/homepage/common/sso_login.jsp")

                if (!landedInMeol) {
                    return Result.failure(IOException("认证未完成，请检查学号密码或是否触发二次验证"))
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            Arrays.fill(passwordChars, '\u0000')
        }
    }

    /** 抓取北化在线所有待办作业与测验 */
    fun fetchPendingSnapshot(nowMillis: Long = System.currentTimeMillis()): TheolSnapshot {
        val reminderUrl = "$MEOL_BASE/meol/welcomepage/student/interaction_reminder_v8.jsp"
        val reminderHtml = fetchHtml(reminderUrl, "$MEOL_BASE/meol/homepage/student/index.jsp")
        if (reminderHtml.contains("type=\"password\"", ignoreCase = true) ||
            reminderHtml.contains("login-page-flowkey")
        ) {
            throw IOException("北化在线会话已过期，请重新登录")
        }

        val (hwCourses, testCourses) = TheolParser.parsePendingCourses(reminderHtml, MEOL_BASE)
        val allHomeworks = mutableListOf<TheolHomeworkItem>()
        val seenHwIds = mutableSetOf<String>()
        val allTests = mutableListOf<TheolTestItem>()
        val seenTestIds = mutableSetOf<String>()
        var failedCourses = 0

        for (course in hwCourses) {
            try {
                val items = walkHomeworkPages(course.url, course.lid, course.courseName, nowMillis)
                for (hw in items) {
                    if (seenHwIds.add(hw.hwtid)) {
                        allHomeworks.add(hw)
                    }
                }
            } catch (_: Exception) {
                failedCourses++
            }
        }

        for (course in testCourses) {
            try {
                val referer = "$MEOL_BASE/meol/jpk/course/layout/newpage/index.jsp?courseId=${course.lid}"
                val testHtml = fetchHtml(course.url, referer)
                val tests = TheolParser.parseTestList(testHtml, course.lid, course.courseName, MEOL_BASE)
                for (t in tests) {
                    if (seenTestIds.add("${t.lid}:${t.testId}")) {
                        allTests.add(t)
                    }
                }
            } catch (_: Exception) {
                failedCourses++
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

    private fun walkHomeworkPages(
        startUrl: String,
        lid: String,
        courseName: String,
        nowMillis: Long
    ): List<TheolHomeworkItem> {
        data class QueueEntry(val url: String, val referer: String, val depth: Int)

        val queue = ArrayDeque<QueueEntry>()
        val visited = mutableSetOf<String>()
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
                        throw IOException("课程会话已过期")
                    }
                    if (isAllowedHomeworkUrl(next) && visited.add(next)) {
                        queue.addFirst(QueueEntry(next, current.url, current.depth + 1))
                    }
                    return@use
                }
                if (!resp.isSuccessful) return@use
                val html = decodeResponseBody(resp)
                if (html.contains("type=\"password\"", ignoreCase = true)) {
                    throw IOException("课程会话已过期")
                }

                for (hw in TheolParser.parseHomeworkPage(html, current.url, lid, courseName, nowMillis)) {
                    if (seenIds.add(hw.hwtid)) found.add(hw)
                }

                fun enqueue(raw: String) {
                    val next = normalizeUrl(current.url, raw) ?: return
                    if (isAllowedHomeworkUrl(next) && visited.add(next)) {
                        queue.addLast(QueueEntry(next, current.url, current.depth + 1))
                    }
                }

                val doc = Jsoup.parse(html, current.url)
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
                    if (href.isNotEmpty()) enqueue(href)
                    val onclick = a.attr("onclick")
                    if (onclick.isNotEmpty()) {
                        QUOTED_URL_REGEX.findAll(onclick).forEach { m -> enqueue(m.groupValues[1]) }
                    }
                }
            }
        }
        return found
    }

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
            if (!resolved.isHttps || resolved.host.lowercase() !in allowedHosts) {
                throw IOException("拒绝跳转到非信任域名: ${resolved.host}")
            }
            currentReq = Request.Builder()
                .url(resolved)
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
        val charsetName = (headerCharset ?: metaCharset ?: "UTF-8").uppercase()
        val charset = runCatching {
            when {
                charsetName.contains("GB") -> Charset.forName("GBK")
                else -> Charset.forName(charsetName)
            }
        }.getOrDefault(Charsets.UTF_8)
        return String(bytes, charset)
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

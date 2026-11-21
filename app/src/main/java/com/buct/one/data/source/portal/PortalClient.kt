package com.buct.one.data.source.portal

import com.buct.course.login.BuctAesCrypto
import com.buct.course.login.BuctDns
import com.buct.course.login.BuctTlsTrustManager
import com.buct.course.login.BuctWebVpn
import com.buct.course.login.BuctWebVpnAuthRequiredException
import com.buct.course.login.BuctWebVpnInterceptor
import com.buct.course.login.BuctWebVpnNetworkInterceptor
import com.buct.course.login.MatchingMemoryCookieJar
import com.buct.one.data.model.PortalCalendarSnapshot
import com.buct.one.data.model.PortalLibrarySnapshot
import com.buct.one.data.model.PortalNewsColumn
import com.buct.one.data.model.PortalNewsColumnPage
import com.buct.one.data.model.PortalNewsSnapshot
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * i北化信息门户（i.buct.edu.cn）原生 HTTP 客户端。
 *
 * 完整实测协议链路：
 * 1. 请求 https://i.buct.edu.cn/（`followRedirects(false)`）
 *    -> `302` https://portal.buct.edu.cn/cas/login?service=...
 *    -> `302` https://sidportal.buct.edu.cn/login?service=...
 * 2. 若 sidportal TGT Cookie 有效，自动签发 `?ticket=ST-...` 建立门户会话。
 * 3. `getJxz.jsp` 实测必须携带 `_p` 上下文参数（缺省返回 HTTP 403）；
 *    `calendar/mgr/api/ids/list.rst` 的 `fromDate`/`endDate` 必须为 ISO-8601 UTC（`T00:00:00.000Z`）。
 */
class PortalClient {
    private val cookieJar = MatchingMemoryCookieJar()

    @Volatile
    private var authenticated: Boolean = false

    private val httpClient: OkHttpClient by lazy {
        val trustManager = BuctTlsTrustManager()
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .followRedirects(false)
            .followSslRedirects(false)
            .sslSocketFactory(trustManager.sslSocketFactory(), trustManager)
            .dns(BuctDns)
            .addInterceptor(BuctWebVpnInterceptor())
            .addNetworkInterceptor(BuctWebVpnNetworkInterceptor())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        const val SID_PORTAL_BASE = "https://sidportal.buct.edu.cn"
        const val PORTAL_BASE = "https://i.buct.edu.cn"
        const val MNEWS_BASE = "https://mnews.buct.edu.cn"
        const val MNEWS_SITE_ID = "100000251"
        private const val DEFAULT_PORTAL_CTX = "YXM9MiZ0PTYmZD0xMTYmcD0xJmY9MzAmbT1OJg__"
        private const val REFERER_MAIN = "$PORTAL_BASE/_s2/students_sy/main.psp"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    }

    fun importSidPortalCookies(rawCookieHeader: String) {
        if (rawCookieHeader.isBlank()) return
        val url = "$SID_PORTAL_BASE/login".toHttpUrl()
        val parsed = rawCookieHeader.split(';').mapNotNull { segment ->
            val trimmed = segment.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val name = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1).trim()
            if (name.isEmpty() || value.isEmpty()) return@mapNotNull null
            val domain = if (name == "_zte_cid_") "buct.edu.cn" else "sidportal.buct.edu.cn"
            Cookie.Builder()
                .name(name)
                .value(value)
                .domain(domain)
                .path("/")
                .secure()
                .expiresAt(Long.MAX_VALUE)
                .build()
        }
        if (parsed.isNotEmpty()) {
            cookieJar.saveFromResponse(url, parsed)
        }
    }

    fun exportSidPortalCookieHeader(): String {
        val probeUrl = "$SID_PORTAL_BASE/login".toHttpUrl()
        val merged = LinkedHashMap<String, String>()
        cookieJar.loadForRequest(probeUrl).forEach { c ->
            merged[c.name] = c.value
        }
        cookieJar.cookiesSetBy("sidportal.buct.edu.cn", 443).forEach { c ->
            if (!merged.containsKey(c.name) && c.value.isNotBlank()) {
                merged[c.name] = c.value
            }
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun invalidateSession() {
        authenticated = false
    }

    fun hasActiveSession(): Boolean = authenticated

    /**
     * 尝试通过已有 sidportal TGT 无感换票建立 i.buct.edu.cn 会话。
     */
    fun authenticateWithSso(): Boolean {
        return followCasToMain("$PORTAL_BASE/")
    }

    /**
     * 使用学号与密码（CharArray 用完立即清零）原生登录 sidportal 并建立 i.buct.edu.cn 会话。
     */
    fun loginWithSidCredentials(username: String, passwordChars: CharArray): Result<Unit> {
        return try {
            var loginUrl: HttpUrl? = null
            var entryHtml = ""
            var parsed: PortalParser.SidPortalPageInfo? = null
            var entrySecond = ""

            for (attempt in 0..1) {
                if (attempt == 1) cookieJar.clear()
                var currentUrl: HttpUrl = "$PORTAL_BASE/".toHttpUrl()
                loginUrl = null
                entryHtml = ""

                var hops = 0
                while (hops < 14) {
                    hops++
                    val safeUrl = upgradePortalHttpToHttps(currentUrl)
                    val req = Request.Builder()
                        .url(safeUrl)
                        .header("User-Agent", BuctWebVpn.userAgentFor(safeUrl, UA))
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .get()
                        .build()

                    var redirectNext: HttpUrl? = null
                    var statusCode = 0
                    var body = ""
                    httpClient.newCall(req).execute().use { resp ->
                        statusCode = resp.code
                        if (statusCode in 300..399) {
                            val loc = resp.header("Location").orEmpty()
                            if (loc.isNotBlank()) {
                                redirectNext = safeUrl.resolve(loc)
                            }
                        } else {
                            body = resp.body?.string().orEmpty()
                        }
                    }

                    if (redirectNext != null) {
                        val host = redirectNext!!.host.lowercase()
                        if (!isAllowedAuthHost(host)) break
                        currentUrl = redirectNext!!
                        continue
                    }

                    if (statusCode in 200..299) {
                        val host = safeUrl.host.lowercase()
                        if (host == "i.buct.edu.cn" && PortalParser.isPortalAuthenticatedHtml(body)) {
                            authenticated = true
                            return Result.success(Unit)
                        }
                        if (host == "sidportal.buct.edu.cn") {
                            loginUrl = safeUrl
                            entryHtml = body
                            break
                        }
                    }
                    break
                }

                if (loginUrl == null || entryHtml.isBlank()) {
                    return Result.failure(IOException("无法获取统一身份认证登录页面"))
                }

                val currentParsed = PortalParser.parseSidPortalPage(entryHtml)
                parsed = currentParsed
                entrySecond = Jsoup.parse(entryHtml).selectFirst("#sso-second")?.text()?.trim().orEmpty()
                if (currentParsed.errorCode.isNotEmpty()) {
                    return Result.failure(IOException("统一身份认证页面异常（${currentParsed.errorCode}）"))
                }
                if (attempt == 0 && (currentParsed.execution.isEmpty() || currentParsed.croypto.isEmpty())) {
                    continue
                }
                break
            }

            val resolvedParams = parsed ?: return Result.failure(IOException("无法获取统一身份认证登录页面"))
            if (entrySecond.equals("true", ignoreCase = true) ||
                (entryHtml.contains("sso-second") && entrySecond.isNotEmpty())
            ) {
                return Result.failure(IOException("统一身份认证需要短信二次验证，请重新登录统一身份认证"))
            }
            if (resolvedParams.execution.isEmpty() || resolvedParams.croypto.isEmpty()) {
                return Result.failure(IOException("统一认证页面缺少 execution 或 croypto 参数"))
            }

            val pwdStr = String(passwordChars)
            val encryptedPassword = BuctAesCrypto.encrypt(resolvedParams.croypto, pwdStr)
            val encryptedCaptchaPayload = BuctAesCrypto.encrypt(resolvedParams.croypto, "{}")

            val form = FormBody.Builder()
                .add("type", "UsernamePassword")
                .add("_eventId", "submit")
                .add("username", username.trim())
                .add("password", encryptedPassword)
                .add("croypto", resolvedParams.croypto)
                .add("execution", resolvedParams.execution)
                .add("captcha_code", "")
                .add("captcha_payload", encryptedCaptchaPayload)
                .add("geolocation", "")
                .build()

            val postReq = Request.Builder()
                .url(loginUrl!!)
                .header("User-Agent", BuctWebVpn.SID_PORTAL_UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Origin", SID_PORTAL_BASE)
                .header("Referer", loginUrl.toString())
                .post(form)
                .build()

            val nextUrl = httpClient.newCall(postReq).execute().use { resp ->
                if (resp.code !in 300..399) {
                    val postBody = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                    val postSecond = if (postBody.isNotBlank()) {
                        Jsoup.parse(postBody).selectFirst("#sso-second")?.text()?.trim().orEmpty()
                    } else ""
                    val need2fa = postSecond.equals("true", ignoreCase = true) ||
                        (postBody.contains("sso-second") && postSecond.isNotEmpty()) ||
                        postBody.contains("smsNoPhoneloginForm")
                    return Result.failure(
                        IOException(
                            if (need2fa) "统一身份认证需要短信二次验证，请重新登录统一身份认证"
                            else "统一身份认证登录失败，请检查学号或密码"
                        )
                    )
                }
                val loc = resp.header("Location").orEmpty()
                resp.request.url.resolve(loc)?.toString() ?: loc
            }

            if (nextUrl.isBlank() || !followCasToMain(nextUrl)) {
                return Result.failure(IOException("统一认证换取信息门户登录凭证失败"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    private fun followCasToMain(startUrl: String): Boolean {
        var currentUrl: HttpUrl = upgradePortalHttpToHttps(startUrl.toHttpUrl())
        var hops = 0

        while (hops < 14) {
            hops++
            val safeUrl = upgradePortalHttpToHttps(currentUrl)
            val req = Request.Builder()
                .url(safeUrl)
                .header("User-Agent", BuctWebVpn.userAgentFor(safeUrl, UA))
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()

            var redirectNext: HttpUrl? = null
            var statusCode = 0
            var body = ""
            httpClient.newCall(req).execute().use { resp ->
                statusCode = resp.code
                if (statusCode in 300..399) {
                    val loc = resp.header("Location").orEmpty()
                    if (loc.isNotBlank()) {
                        redirectNext = safeUrl.resolve(loc)
                    }
                } else {
                    body = resp.body?.string().orEmpty()
                }
            }

            if (redirectNext != null) {
                val next = redirectNext!!
                val host = next.host.lowercase()
                if (!isAllowedAuthHost(host)) {
                    authenticated = false
                    return false
                }
                currentUrl = upgradePortalHttpToHttps(next)
                continue
            }

            if (statusCode in 200..299 && safeUrl.host.equals("i.buct.edu.cn", ignoreCase = true)) {
                if (PortalParser.isPortalAuthenticatedHtml(body)) {
                    authenticated = true
                    return true
                }
            }
            authenticated = false
            return false
        }

        authenticated = false
        return false
    }

    private fun isAllowedAuthHost(host: String): Boolean =
        host == "i.buct.edu.cn" ||
            host == "mnews.buct.edu.cn" ||
            host == "portal.buct.edu.cn" ||
            host == "sidportal.buct.edu.cn"

    private fun upgradePortalHttpToHttps(url: HttpUrl): HttpUrl {
        val host = url.host.lowercase()
        if ((host == "i.buct.edu.cn" || host == "mnews.buct.edu.cn") && url.scheme == "http") {
            return url.newBuilder().scheme("https").build()
        }
        return url
    }

    private fun fetchAuthenticatedGet(fullUrl: String, allowReauth: Boolean = true): String {
        if (!authenticated && !authenticateWithSso()) {
            throw IOException("信息门户统一认证会话已失效，请重新登录")
        }

        var currentUrl = upgradePortalHttpToHttps(fullUrl.toHttpUrl())
        var hops = 0
        while (hops < 4) {
            hops++
            val safeUrl = upgradePortalHttpToHttps(currentUrl)
            val req = Request.Builder()
                .url(safeUrl)
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Referer", REFERER_MAIN)
                .header("X-Requested-With", "XMLHttpRequest")
                .get()
                .build()

            var redirectNext: HttpUrl? = null
            var statusCode = 0
            var body = ""
            httpClient.newCall(req).execute().use { resp ->
                statusCode = resp.code
                if (statusCode in 300..399) {
                    val loc = resp.header("Location").orEmpty()
                    if (loc.isNotBlank()) {
                        redirectNext = safeUrl.resolve(loc)
                    }
                } else {
                    body = resp.body?.string().orEmpty()
                }
            }

            if (redirectNext != null) {
                val next = redirectNext!!
                val host = next.host.lowercase()
                if (host == "i.buct.edu.cn" && !next.encodedPath.contains("login", ignoreCase = true)) {
                    currentUrl = upgradePortalHttpToHttps(next)
                    continue
                }
                authenticated = false
                if (allowReauth && authenticateWithSso()) {
                    return fetchAuthenticatedGet(fullUrl, allowReauth = false)
                }
                throw IOException("信息门户统一认证会话已失效，请重新登录")
            }

            if (statusCode == 401 || statusCode == 403) {
                authenticated = false
                if (allowReauth && authenticateWithSso()) {
                    return fetchAuthenticatedGet(fullUrl, allowReauth = false)
                }
                throw IOException("信息门户认证失效或拒绝访问（HTTP $statusCode）")
            }

            if (statusCode !in 200..299) {
                throw IOException("信息门户请求失败（HTTP $statusCode）")
            }

            val trimmed = body.trimStart()
            if (trimmed.startsWith("<") && (body.contains("login-page-flowkey") || body.contains("统一身份认证"))) {
                authenticated = false
                if (allowReauth && authenticateWithSso()) {
                    return fetchAuthenticatedGet(fullUrl, allowReauth = false)
                }
                throw IOException("信息门户统一认证会话已失效，请重新登录")
            }

            return body
        }

        throw IOException("信息门户接口重定向次数过多")
    }

    /**
     * 拉取 i北化官方学期校历、当前教学周与法定节假日。
     */
    fun fetchCalendarSnapshot(todayDate: String): PortalCalendarSnapshot {
        val jxzUrl =
            "$PORTAL_BASE/_web/fusionportal/getJxz.jsp?currentDate=$todayDate&_p=$DEFAULT_PORTAL_CTX"
        val jxzRaw = fetchAuthenticatedGet(jxzUrl)
        val base = PortalParser.parseJxzResponse(jxzRaw)

        val holidays = if (base.beginDate.length == 10 && base.endDate.length == 10) {
            runCatching {
                val idsUrl =
                    "$PORTAL_BASE/calendar/mgr/api/ids/list.rst" +
                        "?queryType=2&categoryIds=-2" +
                        "&fromDate=${base.beginDate}T00:00:00.000Z" +
                        "&endDate=${base.endDate}T00:00:00.000Z" +
                        "&type=0&_p=$DEFAULT_PORTAL_CTX"
                val rawDays = fetchAuthenticatedGet(idsUrl)
                PortalParser.parseCalendarDays(rawDays)
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        return base.copy(holidays = holidays)
    }

    /**
     * 拉取 i北化首页「借阅图书」总本数与在借明细（getYktOrBook.jsp?type=book）。
     */
    fun fetchBorrowedBooks(page: Int = 1, rows: Int = 50): PortalLibrarySnapshot {
        val url =
            "$PORTAL_BASE/_web/fusionportal/getYktOrBook.jsp" +
                "?page=$page&rows=$rows&type=book&_p=$DEFAULT_PORTAL_CTX"
        val raw = fetchAuthenticatedGet(url)
        return PortalParser.parseBorrowedBooks(raw)
    }

    private fun fetchMnewsGet(fullUrl: String): String {
        var currentUrl: HttpUrl = upgradePortalHttpToHttps(fullUrl.toHttpUrl())
        var hops = 0
        while (hops < 14) {
            hops++
            val safeUrl = upgradePortalHttpToHttps(currentUrl)
            val req = Request.Builder()
                .url(safeUrl)
                .header("User-Agent", BuctWebVpn.userAgentFor(safeUrl, UA))
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Referer", REFERER_MAIN)
                .get()
                .build()

            var redirectNext: HttpUrl? = null
            var statusCode = 0
            var body = ""
            httpClient.newCall(req).execute().use { resp ->
                statusCode = resp.code
                if (statusCode in 300..399) {
                    val loc = resp.header("Location").orEmpty()
                    if (loc.isNotBlank()) {
                        redirectNext = safeUrl.resolve(loc)
                    }
                } else {
                    body = resp.body?.string().orEmpty()
                }
            }

            if (redirectNext != null) {
                val next = redirectNext!!
                if (!isAllowedAuthHost(next.host.lowercase())) {
                    throw MnewsAuthExpiredException()
                }
                currentUrl = upgradePortalHttpToHttps(next)
                continue
            }

            if (statusCode == 401 || statusCode == 403) {
                throw MnewsAuthExpiredException()
            }
            if (statusCode !in 200..299) {
                throw IOException("校内资讯请求失败（HTTP $statusCode）")
            }

            val host = safeUrl.host.lowercase()
            val trimmed = body.trimStart()
            if (host == "sidportal.buct.edu.cn" ||
                (trimmed.startsWith("<") && (body.contains("login-page-flowkey") || body.contains("统一身份认证")))
            ) {
                throw MnewsAuthExpiredException()
            }
            return body
        }

        throw IOException("校内资讯重定向次数过多")
    }

    /**
     * 拉取 mnews.buct.edu.cn 单个栏目的校内资讯列表。
     */
    fun fetchNewsColumn(
        columnId: String,
        beginIndex: Int = 0,
        pageSize: Int = 15
    ): PortalNewsColumnPage {
        val url =
            "$MNEWS_BASE/mobile/getPortalArticleList16ByUser.rst" +
                "?siteId=$MNEWS_SITE_ID&columnId=$columnId&pageSize=$pageSize&beginIndex=$beginIndex&scope=0"
        val raw = fetchMnewsGet(url)
        return PortalParser.parseNewsArticlesPage(columnId, raw)
    }

    /**
     * 依次拉取全部 4 个校内综合资讯栏位（通知公告 / 活动公告 / 招聘信息 / 北化新闻）。
     */
    fun fetchAllNewsSnapshot(pageSize: Int = 15): PortalNewsSnapshot {
        val columns = LinkedHashMap<String, PortalNewsColumnPage>()
        var firstError: Throwable? = null
        for (col in PortalNewsColumn.entries) {
            val res = runCatching {
                fetchNewsColumn(columnId = col.columnId, beginIndex = 0, pageSize = pageSize)
            }
            val err = res.exceptionOrNull()
            if (err is MnewsAuthExpiredException || err is BuctWebVpnAuthRequiredException) throw err
            if (err != null && firstError == null) firstError = err
            res.getOrNull()?.let { page ->
                columns[col.columnId] = page
            }
        }
        if (columns.isEmpty() && firstError != null) {
            throw firstError
        }
        return PortalNewsSnapshot(columns = columns)
    }

    private class MnewsAuthExpiredException : IOException("校内资讯统一认证会话已过期，请重新登录")
}

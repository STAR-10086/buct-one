package com.buct.one.data.source.scs

import com.buct.course.login.BuctAesCrypto
import com.buct.course.login.BuctTlsTrustManager
import com.buct.course.login.MatchingMemoryCookieJar
import com.buct.one.data.model.ScsSnapshot
import com.buct.one.data.model.ScsTermScore
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 第二课堂（scs.buct.edu.cn / eapi.buct.edu.cn）原生 HTTP 客户端。
 *
 * 完整实测认证与协议链路：
 * 1. GET https://eapi.buct.edu.cn/cas/opLogin -> 获取 portal CAS 登录入口 URL
 * 2. 携带统一身份认证（sidportal.buct.edu.cn）TGT Cookie 访问 CAS 入口，经 302 跳转到
 *    https://scs.buct.edu.cn/commonLogin?ticket=ST-... 并提取 ticket
 * 3. GET https://eapi.buct.edu.cn/sys/casLogin?ticket=<ticket> -> 换取 JWT App-Token
 * 4. 后续业务请求统一携带 App-Token / App-source: yfdop / App-Version: 1.0.0 / Device-Id
 *    并强制走 BuctTlsTrustManager。
 */
class ScsClient {
    private val cookieJar = MatchingMemoryCookieJar()

    @Volatile
    private var appToken: String = ""

    private val httpClient: OkHttpClient by lazy {
        val trustManager = BuctTlsTrustManager()
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .followRedirects(false)
            .followSslRedirects(false)
            .sslSocketFactory(trustManager.sslSocketFactory(), trustManager)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        const val SID_PORTAL_BASE = "https://sidportal.buct.edu.cn"
        const val SCS_API_BASE = "https://eapi.buct.edu.cn"
        const val SCS_COMMON_LOGIN_SERVICE = "https://scs.buct.edu.cn/commonLogin"
        private const val APP_SOURCE = "yfdop"
        private const val APP_VERSION = "1.0.0"
        private const val DEVICE_ID = "buct-one-android"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
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
        appToken = ""
    }

    fun hasActiveToken(): Boolean = appToken.isNotBlank()

    fun authenticateWithSso(): Boolean {
        val casUrl = runCatching {
            val req = Request.Builder()
                .url("$SCS_API_BASE/cas/opLogin")
                .header("User-Agent", UA)
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) ""
                else ScsParser.parseStringDataResponse(resp.body?.string().orEmpty())
            }
        }.getOrDefault("")
            .ifBlank {
                "$SID_PORTAL_BASE/login?service=${URLEncoder.encode(SCS_COMMON_LOGIN_SERVICE, "UTF-8")}"
            }

        return followCasAndLogin(casUrl)
    }

    /**
     * 使用学号和密码（CharArray，用完立即清零）原生登录 sidportal 并换取第二课堂 App-Token。
     */
    fun loginWithSidCredentials(username: String, passwordChars: CharArray): Result<Unit> {
        return try {
            val encodedService = URLEncoder.encode(SCS_COMMON_LOGIN_SERVICE, "UTF-8")
            val loginEntry = "$SID_PORTAL_BASE/login?service=$encodedService"

            val entryReq = Request.Builder()
                .url(loginEntry)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .get()
                .build()

            val entryHtml = httpClient.newCall(entryReq).execute().use { resp ->
                if (resp.code in 300..399) {
                    val loc = resp.header("Location").orEmpty()
                    val resolved = if (loc.isNotBlank()) resp.request.url.resolve(loc)?.toString() ?: loc else ""
                    if (resolved.isNotBlank() && followCasAndLogin(resolved)) {
                        return Result.success(Unit)
                    }
                }
                if (!resp.isSuccessful) {
                    return Result.failure(IOException("统一身份认证响应异常（HTTP ${resp.code}）"))
                }
                resp.body?.string().orEmpty()
            }

            val parsed = ScsParser.parseSidPortalPage(entryHtml)
            if (parsed.errorCode.isNotEmpty()) {
                return Result.failure(IOException("统一身份认证页面异常（${parsed.errorCode}）"))
            }
            if (parsed.execution.isEmpty() || parsed.croypto.isEmpty()) {
                return Result.failure(IOException("认证页面缺少 execution 或 croypto 参数"))
            }

            val pwdStr = String(passwordChars)
            val encPwd = BuctAesCrypto.encrypt(parsed.croypto, pwdStr)
            val encCaptcha = BuctAesCrypto.encrypt(parsed.croypto, "{}")

            val form = FormBody.Builder()
                .add("type", "UsernamePassword")
                .add("_eventId", "submit")
                .add("username", username.trim())
                .add("password", encPwd)
                .add("croypto", parsed.croypto)
                .add("execution", parsed.execution)
                .add("captcha_code", "")
                .add("captcha_payload", encCaptcha)
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

            val nextUrl = httpClient.newCall(postReq).execute().use { resp ->
                if (resp.code !in 300..399) {
                    return Result.failure(IOException("统一身份认证登录失败，请检查学号或密码"))
                }
                val loc = resp.header("Location").orEmpty()
                resp.request.url.resolve(loc)?.toString() ?: loc
            }

            if (nextUrl.isBlank() || !followCasAndLogin(nextUrl)) {
                return Result.failure(IOException("统一认证换取第二课堂登录凭证失败"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    private fun followCasAndLogin(startUrl: String): Boolean {
        var currentUrl: HttpUrl = startUrl.toHttpUrl()
        var extractedTicket = extractTicketFromUrl(currentUrl)

        var hops = 0
        while (extractedTicket.isBlank() && hops < 10) {
            hops++
            val req = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()

            val nextLoc = httpClient.newCall(req).execute().use { resp ->
                if (resp.code in 300..399) resp.header("Location").orEmpty() else ""
            }
            if (nextLoc.isBlank()) break
            val resolved = currentUrl.resolve(nextLoc) ?: break
            val ticket = extractTicketFromUrl(resolved)
            if (ticket.isNotBlank()) {
                extractedTicket = ticket
                break
            }
            val host = resolved.host.lowercase()
            if (host != "sidportal.buct.edu.cn" && host != "portal.buct.edu.cn" && host != "scs.buct.edu.cn") {
                break
            }
            currentUrl = resolved
        }

        if (extractedTicket.isBlank()) return false

        val loginUrl = "$SCS_API_BASE/sys/casLogin?ticket=${URLEncoder.encode(extractedTicket, "UTF-8")}"
        val loginReq = Request.Builder()
            .url(loginUrl)
            .header("User-Agent", UA)
            .header("App-source", APP_SOURCE)
            .header("App-Version", APP_VERSION)
            .header("Device-Id", DEVICE_ID)
            .get()
            .build()

        val token = runCatching {
            httpClient.newCall(loginReq).execute().use { resp ->
                if (!resp.isSuccessful) ""
                else ScsParser.parseStringDataResponse(resp.body?.string().orEmpty())
            }
        }.getOrDefault("")

        if (token.isBlank()) return false
        appToken = token
        return true
    }

    private fun extractTicketFromUrl(url: HttpUrl): String {
        if (!url.host.equals("scs.buct.edu.cn", ignoreCase = true)) return ""
        return url.queryParameter("ticket")?.trim().orEmpty()
    }

    private fun ensureTokenReady() {
        if (!hasActiveToken() && !authenticateWithSso()) {
            throw IOException("第二课堂会话已过期，请重新登录")
        }
    }

    private class ScsTokenExpiredException(msg: String) : IOException(msg)

    private fun executeApiGet(path: String): String {
        ensureTokenReady()
        return try {
            doApiGet(path)
        } catch (_: ScsTokenExpiredException) {
            appToken = ""
            if (!authenticateWithSso()) throw IOException("第二课堂会话已过期，请重新登录")
            doApiGet(path)
        }
    }

    private fun doApiGet(path: String): String {
        val req = Request.Builder()
            .url("$SCS_API_BASE$path")
            .header("User-Agent", UA)
            .header("App-Token", appToken)
            .header("App-source", APP_SOURCE)
            .header("App-Version", APP_VERSION)
            .header("Device-Id", DEVICE_ID)
            .get()
            .build()
        return httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code == 401 || resp.code == 403) {
                throw ScsTokenExpiredException("HTTP ${resp.code}")
            }
            if (!resp.isSuccessful) {
                throw IOException("第二课堂接口请求失败（HTTP ${resp.code}）")
            }
            val code = runCatching { JSONObject(body).optInt("code", 200) }.getOrDefault(200)
            val msg = runCatching { JSONObject(body).optString("message", "") }.getOrDefault("")
            if (ScsParser.isUnauthorized(code, msg)) {
                throw ScsTokenExpiredException(msg.ifBlank { "认证失效" })
            }
            body
        }
    }

    private fun executeApiPost(path: String, jsonBody: JSONObject = JSONObject()): String {
        ensureTokenReady()
        return try {
            doApiPost(path, jsonBody)
        } catch (_: ScsTokenExpiredException) {
            appToken = ""
            if (!authenticateWithSso()) throw IOException("第二课堂会话已过期，请重新登录")
            doApiPost(path, jsonBody)
        }
    }

    private fun doApiPost(path: String, jsonBody: JSONObject): String {
        val body = jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE)
        val req = Request.Builder()
            .url("$SCS_API_BASE$path")
            .header("User-Agent", UA)
            .header("App-Token", appToken)
            .header("App-source", APP_SOURCE)
            .header("App-Version", APP_VERSION)
            .header("Device-Id", DEVICE_ID)
            .header("Content-Type", "application/json; charset=utf-8")
            .post(body)
            .build()
        return httpClient.newCall(req).execute().use { resp ->
            val respBody = resp.body?.string().orEmpty()
            if (resp.code == 401 || resp.code == 403) {
                throw ScsTokenExpiredException("HTTP ${resp.code}")
            }
            if (!resp.isSuccessful) {
                throw IOException("第二课堂接口请求失败（HTTP ${resp.code}）")
            }
            val code = runCatching { JSONObject(respBody).optInt("code", 200) }.getOrDefault(200)
            val msg = runCatching { JSONObject(respBody).optString("message", "") }.getOrDefault("")
            if (ScsParser.isUnauthorized(code, msg)) {
                throw ScsTokenExpiredException(msg.ifBlank { "认证失效" })
            }
            respBody
        }
    }

    /** 拉取第二课堂完整快照（个人信息、自评窗口、五育维度满分、历年各学期明细、历史记录、校园二课活动） */
    fun fetchFullSnapshot(): ScsSnapshot {
        val userJson = executeApiGet("/sysAccount/getLoginUserInfo")
        val userInfo = ScsParser.parseUserInfo(userJson)

        val windowJson = runCatching { executeApiGet("/scSelfEvalTerm/getDefaultYearAndTerm") }.getOrDefault("")
        val currentWindow = if (windowJson.isNotBlank()) ScsParser.parseCurrentEvalWindow(windowJson) else null

        val dimensionsJson = runCatching { executeApiGet("/scStudentEvaluationType/getEvaluationType") }.getOrDefault("")
        val dimensions = if (dimensionsJson.isNotBlank()) ScsParser.parseEvaluationDimensions(dimensionsJson) else emptyList()

        val allScoreJson = executeApiGet("/tStudent/getLoginStudentAllScore")
        var terms = ScsParser.parseAllTermsScores(allScoreJson)

        // 若当前自评开放中且当前学期暂无五育明细，拉取当前学期的自评评定细则以便展示自评表结构
        if (currentWindow != null) {
            val idx = terms.indexOfFirst { it.termId == currentWindow.termId && it.yearId == currentWindow.yearId }
            if (idx >= 0 && terms[idx].details.isEmpty()) {
                val evalItemsJson = runCatching {
                    executeApiPost(
                        "/scStudentEvaluationItem/getAppointTermEvaluationItems",
                        JSONObject().put("yearId", currentWindow.yearId).put("termId", currentWindow.termId)
                    )
                }.getOrDefault("")
                if (evalItemsJson.isNotBlank()) {
                    val parsedDetails = ScsParser.parseCurrentTermEvalDetails(evalItemsJson)
                    if (parsedDetails.isNotEmpty()) {
                        val mutable = terms.toMutableList()
                        val old = mutable[idx]
                        mutable[idx] = old.copy(
                            isOpenSelfEva = currentWindow.isOpen || old.isOpenSelfEva,
                            details = parsedDetails
                        )
                        terms = mutable
                    }
                }
            }
        }

        val classInfo = if (userInfo.studentId.isNotBlank()) {
            val histJson = runCatching {
                executeApiPost(
                    "/scStudentScore/listStudentHistoryScore?xh=${URLEncoder.encode(userInfo.studentId, "UTF-8")}",
                    JSONObject()
                )
            }.getOrDefault("")
            if (histJson.isNotBlank()) ScsParser.parseStudentClassInfo(histJson) else ScsParser.StudentClassInfo("", "", "")
        } else {
            ScsParser.StudentClassInfo("", "", "")
        }

        val actsJson = runCatching {
            executeApiPost("/scActivity/listByCondition?currentPage=1&pageSize=30", JSONObject())
        }.getOrDefault("")
        val activities = if (actsJson.isNotBlank()) ScsParser.parseActivities(actsJson) else emptyList()

        return ScsSnapshot(
            studentName = userInfo.name,
            studentId = userInfo.studentId,
            collegeName = classInfo.collegeName,
            className = classInfo.className,
            gradeName = classInfo.gradeName,
            currentWindow = currentWindow,
            dimensions = dimensions,
            terms = terms,
            activities = activities
        )
    }
}

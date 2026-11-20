package com.buct.one.data.source.mail

import com.buct.course.login.BuctAesCrypto
import com.buct.course.login.BuctDns
import com.buct.course.login.BuctTlsTrustManager
import com.buct.course.login.MatchingMemoryCookieJar
import com.buct.one.data.model.MailAccountInfo
import com.buct.one.data.model.MailAttachment
import com.buct.one.data.model.MailFolder
import com.buct.one.data.model.MailFolderIds
import com.buct.one.data.model.MailMessageDetail
import com.buct.one.data.model.MailDownloadedFile
import com.buct.one.data.model.MailSnapshot
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 北化校园邮箱（mail.buct.edu.cn → 网易企业邮箱 `/js6/s` JSON RPC）原生 HTTP 客户端。
 *
 * 完整实测认证与数据链路：
 * 1. GET https://sidportal.buct.edu.cn/login?service=https%3A%2F%2Fmailuser.buct.edu.cn%2Fbuct%2Fapi%2Flogin
 * 2. CAS 302 携 ?ticket=ST-... 跳转 https://mailuser.buct.edu.cn/buct/api/login?ticket=...
 * 3. 桥接服务换票并 302 跳转至 https://mailh.qiye.163.com/static/sirius-web/jump/index.html?sid=<SID>&...
 *    此时 cookieJar 已写入 mailh.qiye.163.com 会话 Cookie，无需 WebView/JS；
 * 4. 后续直接调用 POST /js6/s?func=mbox:...&sid=<SID>&_host=mailh.qiye.163.com 与
 *    GET /js6/s?func=mbox%3AgetMessageData&sid=<SID>&mode=download&part=...&mid=...。
 */
class MailClient {
    private val cookieJar = MatchingMemoryCookieJar()

    @Volatile
    private var sid: String = ""

    @Volatile
    private var mailBase: String = DEFAULT_MAIL_BASE

    private val httpClient: OkHttpClient by lazy {
        val trustManager = BuctTlsTrustManager()
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .followRedirects(false)
            .followSslRedirects(false)
            .sslSocketFactory(trustManager.sslSocketFactory(), trustManager)
            .dns(BuctDns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        const val SID_PORTAL_BASE = "https://sidportal.buct.edu.cn"
        const val MAIL_CAS_SERVICE = "https://mailuser.buct.edu.cn/buct/api/login"
        const val DEFAULT_MAIL_BASE = "https://mailh.qiye.163.com"
        private const val APP_NAME = "sirius-web"
        private const val APP_VERSION = "1.67.0"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val HTML_REDIRECT_REGEX = Regex(
            """(?:location(?:\.href)?\s*=|url=)['"]?(https://[^'"\s>]+)['"]?""",
            RegexOption.IGNORE_CASE
        )
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
        sid = ""
    }

    fun hasActiveSession(): Boolean = sid.isNotBlank()

    fun authenticateWithSso(): Boolean {
        val encodedService = URLEncoder.encode(MAIL_CAS_SERVICE, "UTF-8")
        return followCasToMailSid("$SID_PORTAL_BASE/login?service=$encodedService")
    }

    fun loginWithSidCredentials(username: String, passwordChars: CharArray): Result<Unit> {
        return try {
            val encodedService = URLEncoder.encode(MAIL_CAS_SERVICE, "UTF-8")
            val loginEntry = "$SID_PORTAL_BASE/login?service=$encodedService"
            var currentUrl = loginEntry.toHttpUrl()
            var loginUrl: HttpUrl? = null
            var entryHtml = ""

            var hops = 0
            while (hops < 14) {
                hops++
                extractSidIfReady(currentUrl)?.let { return Result.success(Unit) }

                val req = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", UA)
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
                            redirectNext = currentUrl.resolve(loc)
                        }
                    } else {
                        body = resp.body?.string().orEmpty()
                    }
                }

                if (redirectNext != null) {
                    val next = redirectNext!!
                    extractSidIfReady(next)?.let { return Result.success(Unit) }
                    if (!isAllowedAuthUrl(next)) break
                    currentUrl = next
                    continue
                }

                if (statusCode in 200..299 && currentUrl.host.equals("sidportal.buct.edu.cn", ignoreCase = true)) {
                    loginUrl = currentUrl
                    entryHtml = body
                    break
                }
                break
            }

            if (loginUrl == null || entryHtml.isBlank()) {
                return Result.failure(IOException("无法获取统一身份认证登录页面"))
            }

            val parsed = MailParser.parseSidPortalPage(entryHtml)
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
                .url(loginUrl)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Origin", SID_PORTAL_BASE)
                .header("Referer", loginUrl.toString())
                .post(form)
                .build()

            val nextUrl = httpClient.newCall(postReq).execute().use { resp ->
                if (resp.code !in 300..399) {
                    return Result.failure(IOException("统一身份认证登录失败，请检查学号或密码"))
                }
                val loc = resp.header("Location").orEmpty()
                resp.request.url.resolve(loc)?.toString() ?: loc
            }

            if (nextUrl.isBlank() || !followCasToMailSid(nextUrl)) {
                return Result.failure(IOException("统一认证换取校园邮箱凭证失败"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    private fun followCasToMailSid(startUrl: String): Boolean {
        var currentUrl = startUrl.toHttpUrl()
        if (!isAllowedAuthUrl(currentUrl)) return false
        extractSidIfReady(currentUrl)?.let { return true }

        var hops = 0
        while (hops < 16) {
            hops++
            val req = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", UA)
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
                        redirectNext = currentUrl.resolve(loc)
                    }
                } else {
                    body = resp.body?.string().orEmpty()
                }
            }

            if (redirectNext != null) {
                val next = redirectNext!!
                extractSidIfReady(next)?.let { return true }
                if (!isAllowedAuthUrl(next)) {
                    invalidateSession()
                    return false
                }
                currentUrl = next
                continue
            }

            if (statusCode in 200..299 && body.isNotBlank()) {
                val matched = HTML_REDIRECT_REGEX.find(body)?.groupValues?.getOrNull(1)
                    ?.trim()
                    ?.let { currentUrl.resolve(it) }
                if (matched != null && isAllowedAuthUrl(matched) && matched != currentUrl) {
                    extractSidIfReady(matched)?.let { return true }
                    currentUrl = matched
                    continue
                }
            }
            break
        }
        invalidateSession()
        return false
    }

    private fun extractSidIfReady(url: HttpUrl): String? {
        val host = url.host.lowercase(Locale.ROOT)
        if (host != "qiye.163.com" && !host.endsWith(".qiye.163.com")) return null
        val extracted = url.queryParameter("sid")?.trim().orEmpty()
        if (extracted.isBlank()) return null
        mailBase = "https://$host"
        sid = extracted
        return extracted
    }

    private fun isAllowedAuthUrl(url: HttpUrl): Boolean {
        if (!url.isHttps) return false
        val host = url.host.lowercase(Locale.ROOT)
        return host == "buct.edu.cn" ||
            host.endsWith(".buct.edu.cn") ||
            host == "qiye.163.com" ||
            host.endsWith(".qiye.163.com")
    }

    private fun currentMailHost(): String = mailBase.toHttpUrl().host

    private fun ensureSidReady() {
        if (sid.isBlank() && !authenticateWithSso()) {
            throw IOException("校园邮箱统一认证会话已失效，请重新登录")
        }
    }

    private fun postJs6(func: String, payload: JSONObject, extraQuery: String = ""): String {
        ensureSidReady()
        var attempt = 0
        while (attempt < 2) {
            attempt++
            val base = mailBase
            val host = currentMailHost()
            val encodedFunc = URLEncoder.encode(func, "UTF-8")
            val encodedSid = URLEncoder.encode(sid, "UTF-8")
            val url = "$base/js6/s?_host=$host&func=$encodedFunc&sid=$encodedSid&p=web&_appName=$APP_NAME&_version=$APP_VERSION$extraQuery"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .header("Lingxi-Language", "zh")
                .header("Origin", base)
                .header("Referer", "$base/static/sirius-web/")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            var expired = false
            var respText = ""
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403 || resp.code in 300..399) {
                    expired = true
                } else {
                    respText = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IOException("校园邮箱请求失败（HTTP ${resp.code}）")
                    }
                    val (ok, code) = MailParser.checkRpcCode(respText)
                    if (!ok && (!respText.contains('{') || MailParser.isSessionErrorCode(code))) {
                        expired = true
                    }
                }
            }

            if (expired) {
                invalidateSession()
                if (attempt == 1 && authenticateWithSso()) {
                    continue
                }
                throw IOException("校园邮箱统一认证会话已失效，请重新登录")
            }
            return respText
        }
        throw IOException("校园邮箱统一认证会话已失效，请重新登录")
    }

    fun fetchAccountInfo(): MailAccountInfo {
        ensureSidReady()
        val base = mailBase
        val host = currentMailHost()
        val encodedSid = URLEncoder.encode(sid, "UTF-8")
        val url = "$base/cowork/api/biz/enter/accountInfo?_host=$host&sid=$encodedSid&needUnitNamePath=false&_appName=$APP_NAME&_version=$APP_VERSION"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("Lingxi-Language", "zh")
            .header("Referer", "$base/static/sirius-web/")
            .get()
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) MailAccountInfo()
                else MailParser.parseAccountInfo(resp.body?.string().orEmpty())
            }
        }.getOrDefault(MailAccountInfo())
    }

    fun fetchFolders(): List<MailFolder> {
        val body = JSONObject().put("order", "custom_virtual")
        return MailParser.parseFolders(postJs6("mbox:getAllFolders", body))
    }

    fun fetchMessages(
        folderId: Int,
        start: Int = 0,
        limit: Int = 30,
        unreadOnly: Boolean = false
    ): MailParser.MailListPage {
        val filter = JSONObject().apply {
            if (unreadOnly) {
                put("flags", JSONObject().put("read", false))
            }
        }
        val body = JSONObject().apply {
            put("fid", folderId)
            put("start", start)
            put("limit", limit)
            put("summaryWindowSize", limit)
            put("order", "date")
            put("desc", true)
            put("returnTotal", true)
            put("returnTid", true)
            put("returnTag", true)
            put("returnAttachments", true)
            put("skipLockedFolders", false)
            put("filter", filter)
        }
        return MailParser.parseMessageList(
            json = postJs6("mbox:listMessages", body),
            fallbackFolderId = folderId
        )
    }

    fun fetchFullSnapshot(pageSize: Int = 30): MailSnapshot {
        val folders = fetchFolders()
        val inboxPage = fetchMessages(folderId = 1, start = 0, limit = pageSize, unreadOnly = false)
        val account = fetchAccountInfo()
        val inboxFolder = folders.firstOrNull { it.id == 1 }
        val unreadTotal = inboxFolder?.unreadCount ?: inboxPage.messages.count { !it.isRead }
        return MailSnapshot(
            account = account,
            folders = folders,
            inboxMessages = inboxPage.messages,
            inboxTotal = inboxPage.total,
            unreadTotal = unreadTotal
        )
    }

    fun readMessage(messageId: String): MailMessageDetail {
        val body = JSONObject().apply {
            put("id", messageId)
            put("level", 32)
            put("mode", "html")
            put("returnHeaders", JSONObject().put("Resent-From", "A").put("Sender", "A"))
            put("markRead", false)
        }
        val json = postJs6(
            func = "mbox:readMessage",
            payload = body,
            extraQuery = "&l=read&uid=&supportTNEF=true"
        )
        return MailParser.parseMessageDetail(messageId, json, System.currentTimeMillis())
    }

    fun setMessageRead(messageId: String, isRead: Boolean) {
        setMessagesRead(listOf(messageId), isRead)
    }

    fun setMessagesRead(messageIds: List<String>, isRead: Boolean) {
        if (messageIds.isEmpty()) return
        val body = JSONObject().apply {
            put("ids", JSONArray().apply { messageIds.forEach { put(it) } })
            put("attrs", JSONObject().put("flags", JSONObject().put("read", isRead)))
        }
        val resp = postJs6("mbox:updateMessageInfos", body)
        val (ok, code) = MailParser.checkRpcCode(resp)
        if (!ok) {
            throw IOException("更新邮件已读状态失败（$code）")
        }
    }

    fun moveMessages(messageIds: List<String>, targetFolderId: Int) {
        if (messageIds.isEmpty()) return
        val body = JSONObject().apply {
            put("ids", JSONArray().apply { messageIds.forEach { put(it) } })
            put("attrs", JSONObject().put("fid", targetFolderId))
        }
        val resp = postJs6("mbox:updateMessageInfos", body)
        val (ok, code) = MailParser.checkRpcCode(resp)
        if (!ok) {
            throw IOException("移动邮件失败（$code）")
        }
    }

    fun deleteMessagesPermanently(messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        val body = JSONObject().apply {
            put("ids", JSONArray().apply { messageIds.forEach { put(it) } })
        }
        val resp = postJs6("mbox:deleteMessages", body)
        val (ok, code) = MailParser.checkRpcCode(resp)
        if (!ok) {
            throw IOException("彻底删除邮件失败（$code）")
        }
    }

    fun deleteMessage(messageId: String, currentFolderId: Int, permanent: Boolean = false) {
        if (permanent || currentFolderId == MailFolderIds.TRASH) {
            deleteMessagesPermanently(listOf(messageId))
        } else {
            moveMessages(listOf(messageId), MailFolderIds.TRASH)
        }
    }

    fun markFolderAllRead(folderId: Int) {
        val body = JSONObject().apply {
            put("fid", folderId)
            put("read", true)
        }
        val resp = postJs6("mbox:markSeen", body)
        val (ok, code) = MailParser.checkRpcCode(resp)
        if (!ok) {
            throw IOException("全部标为已读失败（$code）")
        }
    }

    fun downloadAttachment(
        messageId: String,
        attachment: MailAttachment,
        cacheRootDir: File
    ): MailDownloadedFile {
        ensureSidReady()
        var attempt = 0
        while (attempt < 2) {
            attempt++
            val base = mailBase
            val host = currentMailHost()
            val encodedSid = URLEncoder.encode(sid, "UTF-8")
            val encodedPart = URLEncoder.encode(attachment.partId, "UTF-8")
            val encodedMid = URLEncoder.encode(messageId, "UTF-8")
            val url = "$base/js6/s?_host=$host&func=mbox%3AgetMessageData&sid=$encodedSid&mode=download&part=$encodedPart&mid=$encodedMid&email=&_appName=$APP_NAME&trigger_type=user_click"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", "$base/static/sirius-web/")
                .get()
                .build()

            var needReauth = false
            var result: MailDownloadedFile? = null
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403 || resp.code in 300..399) {
                    needReauth = true
                } else {
                    if (!resp.isSuccessful) {
                        throw IOException("附件下载失败（HTTP ${resp.code}）")
                    }
                    val respContentType = resp.header("Content-Type").orEmpty()
                    if (respContentType.contains("text/html", ignoreCase = true) &&
                        !attachment.filename.endsWith(".html", ignoreCase = true) &&
                        !attachment.filename.endsWith(".htm", ignoreCase = true)
                    ) {
                        val preview = resp.body?.string().orEmpty()
                        if (preview.contains("会话") || preview.contains("登录")) {
                            needReauth = true
                        } else {
                            throw IOException("附件下载返回异常网页响应")
                        }
                    } else {
                        val body = resp.body ?: throw IOException("附件响应体为空")
                        val dir = File(cacheRootDir, "mail_attachments").apply { if (!exists()) mkdirs() }
                        val safeName = attachment.filename
                            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                            .ifBlank { "attachment_${attachment.partId}" }
                        val targetFile = File(dir, safeName)
                        body.byteStream().use { input ->
                            FileOutputStream(targetFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (!targetFile.exists() || targetFile.length() == 0L) {
                            throw IOException("下载的附件内容为空")
                        }
                        val resolvedMime = resolveMimeType(
                            explicit = attachment.contentType,
                            headerType = respContentType,
                            filename = safeName
                        )
                        result = MailDownloadedFile(
                            filePath = targetFile.absolutePath,
                            fileName = safeName,
                            mimeType = resolvedMime,
                            sizeBytes = targetFile.length()
                        )
                    }
                }
            }

            if (needReauth) {
                invalidateSession()
                if (attempt == 1 && authenticateWithSso()) {
                    continue
                }
                throw IOException("校园邮箱统一认证会话已失效，请重新登录")
            }
            if (result != null) return result!!
        }
        throw IOException("附件下载失败，请稍后重试")
    }

    private fun resolveMimeType(explicit: String, headerType: String, filename: String): String {
        val candidate = explicit.substringBefore(';').trim().takeIf {
            it.isNotBlank() && !it.equals("application/octet-stream", ignoreCase = true)
        } ?: headerType.substringBefore(';').trim().takeIf {
            it.isNotBlank() && !it.equals("application/octet-stream", ignoreCase = true)
        }
        if (candidate != null) return candidate
        val lower = filename.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".doc") -> "application/msword"
            lower.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            lower.endsWith(".xls") -> "application/vnd.ms-excel"
            lower.endsWith(".xlsx") -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            lower.endsWith(".ppt") -> "application/vnd.ms-powerpoint"
            lower.endsWith(".pptx") -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            lower.endsWith(".zip") -> "application/zip"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".txt") -> "text/plain"
            else -> "*/*"
        }
    }
}

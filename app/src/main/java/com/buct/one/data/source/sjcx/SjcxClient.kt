package com.buct.one.data.source.sjcx

import android.util.Base64
import com.buct.course.login.BuctAesCrypto
import com.buct.course.login.BuctTlsTrustManager
import com.buct.course.login.MatchingMemoryCookieJar
import com.buct.one.data.model.SjcxDownloadedFile
import com.buct.one.data.model.SjcxExperimentReportItem
import com.buct.one.data.model.SjcxSnapshot
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.text.Collator
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 实践教学综合服务平台（sjcx.buct.edu.cn）原生 HTTP 客户端。
 *
 * 完整协议链路（实测验证）：
 * 1. 使用统一身份认证（sidportal.buct.edu.cn）会话请求：
 *    GET https://sidportal.buct.edu.cn/login?service=https%3A%2F%2Fsjcx.buct.edu.cn%2Fbuct%2F
 * 2. 跟随 302 跳转至 https://sjcx.buct.edu.cn/buct/?ticket=ST-...
 *    服务端下发 Path=/buct 的 JSESSIONID 并重定向至 /buct/
 * 3. GET https://sjcx.buct.edu.cn/buct/
 *    服务端下发各微前端子系统（/config, /fteach, /fcredit 等）的 product_info、X_TB_AUTH、powers_product Cookie，
 *    并在 Location: https://sjcx.buct.edu.cn/#/product/cas?token=<JWT> 中返回 JWT Authorization。
 * 4. POST /config/sys/login/permissions/cas/login（携带 Authorization, MenuId: null, Product: null, Random: <epochMs>）
 *    获取 2048-bit RSA 公钥（data.random，即前端 BAIDI_1）与当前学期（data.yearterm）。
 * 5. 后续业务请求必须携带：
 *    - Authorization: <JWT>
 *    - MenuId: null, Product: null, Random: <epochMs>
 *    - Cnonce: RSA/ECB/PKCS1Padding(BAIDI_1, 32字节随机nonce) Base64
 *    - Xtsign: HmacSHA256(key=nonce, message=sortedKeyEqualsValues).toUpperCaseHex()
 * 6. 模块上下文激活：
 *    - 调用 /fteach/ 下实验报告、实验课表、实验成绩接口前必须先 POST /config/sys/menu/listStuMenuByProduct?menuId=9900
 *    - 调用 /fcredit/ 下抢讲座、我的讲座、创新学分接口前必须先 POST /config/sys/menu/listStuMenuByProduct?menuId=11900
 */
class SjcxClient {
    private val cookieJar = MatchingMemoryCookieJar()
    private val secureRandom = SecureRandom()

    @Volatile
    private var jwtToken: String = ""

    @Volatile
    private var rsaPublicKeyBase64: String = ""

    @Volatile
    private var yearterm: String = ""

    @Volatile
    private var activeMenuId: String = ""

    private val httpClient: OkHttpClient by lazy {
        val trustManager = BuctTlsTrustManager()
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .followRedirects(false)
            .followSslRedirects(false)
            .sslSocketFactory(trustManager.sslSocketFactory(), trustManager)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        const val SID_PORTAL_BASE = "https://sidportal.buct.edu.cn"
        const val SJCX_BASE = "https://sjcx.buct.edu.cn"
        const val SJCX_CAS_SERVICE = "https://sjcx.buct.edu.cn/buct/"
        const val MENU_ID_TEACH = "9900"
        const val MENU_ID_CREDIT = "11900"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        private const val NONCE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
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
            Cookie.Builder()
                .name(name)
                .value(value)
                .domain("sidportal.buct.edu.cn")
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
        jwtToken = ""
        rsaPublicKeyBase64 = ""
        activeMenuId = ""
    }

    fun hasActiveToken(): Boolean = jwtToken.isNotBlank() && rsaPublicKeyBase64.isNotBlank()

    /**
     * 通过已有 sidportal TGT 自动换票建立 sjcx 会话；成功返回 true。
     */
    fun authenticateWithSso(): Boolean {
        val encodedService = URLEncoder.encode(SJCX_CAS_SERVICE, "UTF-8")
        val entryUrl = "$SID_PORTAL_BASE/login?service=$encodedService"
        return followCasToSjcxToken(entryUrl)
    }

    /**
     * 当 sidportal 会话过期时，使用学号与密码（CharArray 用完清零）原生登录 sidportal 并建立 sjcx 会话。
     */
    fun loginWithSidCredentials(username: String, passwordChars: CharArray): Result<Unit> {
        return try {
            val encodedService = URLEncoder.encode(SJCX_CAS_SERVICE, "UTF-8")
            val loginEntry = "$SID_PORTAL_BASE/login?service=$encodedService"

            val req = Request.Builder()
                .url(loginEntry)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .get()
                .build()

            val entryHtml = httpClient.newCall(req).execute().use { resp ->
                if (resp.code in 300..399) {
                    val loc = resp.header("Location").orEmpty()
                    if (loc.isNotBlank() && followCasToSjcxToken(
                            resp.request.url.resolve(loc)?.toString() ?: loc
                        )
                    ) {
                        return Result.success(Unit)
                    }
                }
                if (!resp.isSuccessful) {
                    return Result.failure(IOException("统一身份认证响应异常（HTTP ${resp.code}）"))
                }
                resp.body?.string().orEmpty()
            }

            val doc = Jsoup.parse(entryHtml)
            val execution = doc.selectFirst("#login-page-flowkey")?.text()?.trim().orEmpty()
            val croypto = doc.selectFirst("#login-croypto")?.text()?.trim().orEmpty()
            if (execution.isEmpty() || croypto.isEmpty()) {
                return Result.failure(IOException("统一认证页面缺少 execution 或 croypto 参数"))
            }

            val pwdString = String(passwordChars)
            val encryptedPassword = BuctAesCrypto.encrypt(croypto, pwdString)
            val encryptedCaptchaPayload = BuctAesCrypto.encrypt(croypto, "{}")

            val form = FormBody.Builder()
                .add("type", "UsernamePassword")
                .add("_eventId", "submit")
                .add("username", username.trim())
                .add("password", encryptedPassword)
                .add("croypto", croypto)
                .add("execution", execution)
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

            val firstRedirect = httpClient.newCall(postReq).execute().use { resp ->
                if (resp.code !in 300..399) {
                    return Result.failure(IOException("统一身份认证登录失败，请检查学号或密码"))
                }
                val loc = resp.header("Location").orEmpty()
                resp.request.url.resolve(loc)?.toString() ?: loc
            }

            if (firstRedirect.isBlank() || !followCasToSjcxToken(firstRedirect)) {
                return Result.failure(IOException("统一认证换取实践教学平台票据失败"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    private fun followCasToSjcxToken(startUrl: String): Boolean {
        var currentUrl: HttpUrl = startUrl.toHttpUrl()
        var extractedToken = ""

        for (hop in 0 until 10) {
            val req = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()

            val nextLocation = httpClient.newCall(req).execute().use { resp ->
                if (resp.code in 300..399) {
                    resp.header("Location").orEmpty()
                } else {
                    ""
                }
            }

            if (nextLocation.isBlank()) break
            val tokenIdx = nextLocation.indexOf("token=")
            if (tokenIdx >= 0) {
                val rawToken = nextLocation.substring(tokenIdx + 6).substringBefore('&').trim()
                if (rawToken.isNotBlank()) {
                    extractedToken = rawToken
                    break
                }
            }
            val resolved = currentUrl.resolve(nextLocation) ?: break
            val allowed = resolved.host.equals("sidportal.buct.edu.cn", ignoreCase = true) ||
                resolved.host.equals("sjcx.buct.edu.cn", ignoreCase = true)
            if (!allowed) break
            currentUrl = resolved
        }

        if (extractedToken.isBlank()) return false

        val casReq = Request.Builder()
            .url("$SJCX_BASE/config/sys/login/permissions/cas/login")
            .header("User-Agent", UA)
            .header("Authorization", extractedToken)
            .header("MenuId", "null")
            .header("Product", "null")
            .header("Random", System.currentTimeMillis().toString())
            .post(EMPTY_BODY)
            .build()

        return runCatching {
            httpClient.newCall(casReq).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body?.string().orEmpty()
                val info = SjcxParser.parseCasLoginInfo(body)
                jwtToken = extractedToken
                rsaPublicKeyBase64 = info.rsaPublicKeyBase64
                yearterm = info.yearterm
                activeMenuId = ""
                true
            }
        }.getOrDefault(false)
    }

    /**
     * 计算 sjcx 请求签名头（Xtsign 与 Cnonce）。
     * 完整复刻前端 index.nrKsr9TH.js 中的 Te / Ie / Le 逻辑。
     */
    private fun computeSignatureHeaders(paramsMap: Map<String, List<String>>): Pair<String, String> {
        val effectiveMap = TreeMap<String, MutableList<String>>(
            Collator.getInstance(Locale.CHINA)
        )
        for ((k, vList) in paramsMap) {
            if (k.isBlank()) continue
            val copy = vList.toMutableList()
            copy.sort()
            effectiveMap[k] = copy
        }
        if (effectiveMap.isEmpty()) {
            effectiveMap["Authorization"] = mutableListOf(jwtToken)
        }

        val sb = StringBuilder()
        for ((k, vList) in effectiveMap) {
            if (vList.isNotEmpty()) {
                for (v in vList) {
                    sb.append(k).append('=').append(v)
                }
            } else {
                sb.append(k).append('=')
            }
        }
        val cleanString = sb.toString().replace(Regex("(\\r\\n|\\n|\\r|\\u0085|\\u2028|\\u2029)"), "")

        val nonceChars = CharArray(32) { NONCE_CHARS[secureRandom.nextInt(NONCE_CHARS.length)] }
        val nonce = String(nonceChars)

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(nonce.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hmacBytes = mac.doFinal(cleanString.toByteArray(Charsets.UTF_8))
        val xtsign = hmacBytes.joinToString("") { "%02X".format(it) }

        val keyBytes = Base64.decode(rsaPublicKeyBase64, Base64.DEFAULT)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encryptedNonce = Base64.encodeToString(
            cipher.doFinal(nonce.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
        return xtsign to encryptedNonce
    }

    private fun executeSignedPost(
        path: String,
        queryParams: Map<String, String> = emptyMap(),
        jsonBody: JSONObject? = null
    ): String {
        if (!hasActiveToken() && !authenticateWithSso()) {
            throw IOException("实践教学平台会话未建立，请先登录统一身份认证")
        }
        return try {
            doSignedPostOnce(path, queryParams, jsonBody)
        } catch (e: SjcxAuthExpiredException) {
            invalidateSession()
            if (!authenticateWithSso()) {
                throw IOException("实践教学平台登录已过期，请刷新统一认证状态")
            }
            doSignedPostOnce(path, queryParams, jsonBody)
        }
    }

    private fun doSignedPostOnce(
        path: String,
        queryParams: Map<String, String>,
        jsonBody: JSONObject?
    ): String {
        val signParams = LinkedHashMap<String, MutableList<String>>()
        for ((k, v) in queryParams) {
            signParams.getOrPut(k) { mutableListOf() }.add(v)
        }
        if (jsonBody != null) {
            collectJsonValuesForSign(jsonBody, "", signParams)
        }
        val (xtsign, cnonce) = computeSignatureHeaders(signParams)

        val urlBuilder = "$SJCX_BASE$path".toHttpUrl().newBuilder()
        for ((k, v) in queryParams) {
            urlBuilder.addQueryParameter(k, v)
        }

        val body = jsonBody?.toString()?.toRequestBody(JSON_MEDIA_TYPE) ?: EMPTY_BODY
        val req = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", UA)
            .header("Authorization", jwtToken)
            .header("MenuId", "null")
            .header("Product", "null")
            .header("Xtsign", xtsign)
            .header("Cnonce", cnonce)
            .header("Random", System.currentTimeMillis().toString())
            .post(body)
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) {
                throw SjcxAuthExpiredException()
            }
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("实践教学平台响应异常（HTTP ${resp.code}）")
            }
            if (text.contains("登录状态已过期") || text.contains("\"code\":401")) {
                throw SjcxAuthExpiredException()
            }
            return text
        }
    }

    private fun collectJsonValuesForSign(
        obj: JSONObject,
        parentKey: String,
        target: MutableMap<String, MutableList<String>>
    ) {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (obj.isNull(k)) continue
            when (val v = obj.get(k)) {
                is JSONObject -> collectJsonValuesForSign(v, k, target)
                is org.json.JSONArray -> {
                    for (i in 0 until v.length()) {
                        val elem = v.get(i)
                        if (elem is JSONObject) {
                            collectJsonValuesForSign(elem, k, target)
                        } else if (elem != JSONObject.NULL) {
                            target.getOrPut(k) { mutableListOf() }.add(elem.toString())
                        }
                    }
                }
                else -> target.getOrPut(k) { mutableListOf() }.add(v.toString())
            }
        }
    }

    private fun ensureModuleContext(menuId: String) {
        if (activeMenuId == menuId && hasActiveToken()) return
        executeSignedPost(
            path = "/config/sys/menu/listStuMenuByProduct",
            queryParams = mapOf("menuId" to menuId)
        )
        activeMenuId = menuId
    }

    /**
     * 一次性拉取实践教学完整快照：
     * - 实验教学（9900）：实验报告、实验课表、实验成绩
     * - 创新学分（11900）：活动/讲座报名列表、我的讲座、已获创新学分、缺勤统计
     */
    fun fetchFullSnapshot(): SjcxSnapshot {
        if (!hasActiveToken() && !authenticateWithSso()) {
            throw IOException("实践教学平台会话未建立，请重新登录统一身份认证")
        }

        val productsJson = executeSignedPost("/config/sys/menu/listProductByStu")
        val currentWeek = SjcxParser.parseCurrentWeekFromProducts(productsJson)

        // 1. 激活实验教学模块 (9900)
        activeMenuId = ""
        ensureModuleContext(MENU_ID_TEACH)
        val pageJson = JSONObject().put("currentPage", 1).put("pageSize", 100)
        val reportsRaw = executeSignedPost("/fteach/teach/report/listStuReportPage", jsonBody = pageJson)
        val schedulesRaw = executeSignedPost("/fteach/teach/timeTable/listStuTimePage", jsonBody = pageJson)
        val scoresRaw = executeSignedPost("/fteach/teach/score/listStuScorePage", jsonBody = pageJson)

        val reports = SjcxParser.parseExperimentReports(reportsRaw)
        val schedules = SjcxParser.parseExperimentSchedules(schedulesRaw)
        val scores = SjcxParser.parseExperimentScores(scoresRaw)

        // 2. 激活创新学分模块 (11900)
        ensureModuleContext(MENU_ID_CREDIT)
        val openQuery = JSONObject()
            .put("currentPage", 1)
            .put("pageSize", 50)
            .put("keyword", "")
            .put("state", 1)
        val endedQuery = JSONObject()
            .put("currentPage", 1)
            .put("pageSize", 30)
            .put("keyword", "")
            .put("state", 2)
        val myQuery = JSONObject()
            .put("currentPage", 1)
            .put("pageSize", 50)
            .put("keyword", "")

        val openLectures = runCatching {
            val raw = executeSignedPost("/fcredit/credit/creProjectTime/listStuBatchPage", jsonBody = openQuery)
            SjcxParser.parseLectureBatches(raw)
        }.getOrDefault(emptyList())

        val endedLectures = runCatching {
            val raw = executeSignedPost("/fcredit/credit/creProjectTime/listStuBatchPage", jsonBody = endedQuery)
            SjcxParser.parseLectureBatches(raw)
        }.getOrDefault(emptyList())

        val myLectures = runCatching {
            val raw = executeSignedPost("/fcredit/credit/creProjectStu/stu/listMyBatchPage", jsonBody = myQuery)
            SjcxParser.parseMyLectures(raw)
        }.getOrDefault(emptyList())

        val (totalCredit, creditRecords) = runCatching {
            val raw = executeSignedPost("/fcredit/credit/creStu/stu/listTotalCreditPage", jsonBody = pageJson)
            SjcxParser.parseCreditRecords(raw)
        }.getOrDefault(0.0 to emptyList())

        val noSignCount = runCatching {
            val raw = executeSignedPost("/fcredit/credit/creProjectStu/stu/getNoSignNum")
            JSONObject(raw).optJSONObject("data")?.optInt("no_sign_num", 0) ?: 0
        }.getOrDefault(0)

        return SjcxSnapshot(
            yearterm = yearterm,
            currentWeek = currentWeek,
            reports = reports,
            schedules = schedules,
            scores = scores,
            openLectures = openLectures,
            endedLectures = endedLectures,
            myLectures = myLectures,
            creditRecords = creditRecords,
            totalCredit = totalCredit,
            noSignCount = noSignCount
        )
    }

    /**
     * 上传提交实验报告 PDF（POST /fteach/teach/report/addStuReport）。
     */
    fun uploadExperimentReport(
        item: SjcxExperimentReportItem,
        pdfFile: File,
        displayFileName: String
    ): String {
        if (!pdfFile.exists() || pdfFile.length() <= 0L) {
            throw IOException("选择的实验报告文件不存在或为空")
        }
        ensureModuleContext(MENU_ID_TEACH)

        val signParams = LinkedHashMap<String, MutableList<String>>()
        signParams["report_id"] = mutableListOf(item.reportId)
        signParams["group_id"] = mutableListOf(item.groupId)
        signParams["stu_id"] = mutableListOf(item.stuId)
        val (xtsign, cnonce) = computeSignatureHeaders(signParams)

        val safeName = if (displayFileName.lowercase(Locale.ROOT).endsWith(".pdf")) {
            displayFileName
        } else {
            "$displayFileName.pdf"
        }

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                safeName,
                pdfFile.asRequestBody("application/pdf".toMediaType())
            )
            .addFormDataPart("report_id", item.reportId)
            .addFormDataPart("group_id", item.groupId)
            .addFormDataPart("stu_id", item.stuId)
            .build()

        val req = Request.Builder()
            .url("$SJCX_BASE/fteach/teach/report/addStuReport")
            .header("User-Agent", UA)
            .header("Authorization", jwtToken)
            .header("MenuId", "null")
            .header("Product", "null")
            .header("Xtsign", xtsign)
            .header("Cnonce", cnonce)
            .header("Random", System.currentTimeMillis().toString())
            .post(multipart)
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("上传实验报告失败（HTTP ${resp.code}）")
            }
            val root = JSONObject(text)
            val code = root.optInt("code", -1)
            val msg = root.optString("msg", "").trim()
            if (code != 200) {
                throw IOException(msg.ifBlank { "上传实验报告失败" })
            }
            return msg.ifBlank { "实验报告上传成功" }
        }
    }

    /**
     * 下载实验报告模板文件（GET /config/sys/download/downExpReport?id=<expId>&name=<templateName>）。
     */
    fun downloadReportTemplate(
        item: SjcxExperimentReportItem,
        cacheDir: File
    ): SjcxDownloadedFile {
        if (item.templateName.isBlank()) {
            throw IOException("该实验未设置报告模板")
        }
        ensureModuleContext(MENU_ID_TEACH)
        val query = mapOf(
            "id" to item.expId,
            "name" to item.templateName
        )
        return downloadBlob(
            path = "/config/sys/download/downExpReport",
            queryParams = query,
            suggestedName = item.templateName,
            cacheDir = cacheDir
        )
    }

    /**
     * 下载学生已提交的实验报告文件（GET /fteach/teach/download/downloadReport）。
     */
    fun downloadSubmittedReport(
        item: SjcxExperimentReportItem,
        cacheDir: File
    ): SjcxDownloadedFile {
        if (item.uploadedDocName.isBlank()) {
            throw IOException("尚未上传实验报告")
        }
        ensureModuleContext(MENU_ID_TEACH)
        val query = mapOf(
            "yearterm" to item.yearterm,
            "report_id" to item.reportId,
            "group_id" to item.groupId,
            "name" to item.uploadedDocName
        )
        return downloadBlob(
            path = "/fteach/teach/download/downloadReport",
            queryParams = query,
            suggestedName = item.uploadedDocName,
            cacheDir = cacheDir
        )
    }

    private fun downloadBlob(
        path: String,
        queryParams: Map<String, String>,
        suggestedName: String,
        cacheDir: File
    ): SjcxDownloadedFile {
        val signParams = queryParams.mapValues { listOf(it.value) }
        val (xtsign, cnonce) = computeSignatureHeaders(signParams)
        val urlBuilder = "$SJCX_BASE$path".toHttpUrl().newBuilder()
        for ((k, v) in queryParams) {
            urlBuilder.addQueryParameter(k, v)
        }
        val req = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", UA)
            .header("Authorization", jwtToken)
            .header("MenuId", "null")
            .header("Product", "null")
            .header("Xtsign", xtsign)
            .header("Cnonce", cnonce)
            .header("Random", System.currentTimeMillis().toString())
            .get()
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("文件下载失败（HTTP ${resp.code}）")
            }
            val contentType = resp.header("Content-Type").orEmpty()
            val body = resp.body ?: throw IOException("响应体为空")
            if (contentType.contains("application/json", ignoreCase = true)) {
                val errJson = body.string()
                val msg = runCatching { JSONObject(errJson).optString("msg") }.getOrDefault("")
                throw IOException(msg.ifBlank { "文件不存在或无权下载" })
            }
            val dir = File(cacheDir, "sjcx_files").apply { if (!exists()) mkdirs() }
            val cleanName = suggestedName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "experiment_report.pdf" }
            val targetFile = File(dir, cleanName)
            body.byteStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            val mime = if (cleanName.lowercase(Locale.ROOT).endsWith(".pdf")) {
                "application/pdf"
            } else {
                contentType.substringBefore(';').trim().ifBlank { "application/octet-stream" }
            }
            return SjcxDownloadedFile(
                localPath = targetFile.absolutePath,
                displayName = cleanName,
                mimeType = mime,
                sizeBytes = targetFile.length()
            )
        }
    }

    /**
     * 报名讲座/活动（POST /fcredit/credit/creProjectStu/stu/StuRegistrationProject?projectTimeId=<id>）。
     */
    fun registerLecture(projectTimeId: String): String {
        ensureModuleContext(MENU_ID_CREDIT)
        val raw = executeSignedPost(
            path = "/fcredit/credit/creProjectStu/stu/StuRegistrationProject",
            queryParams = mapOf("projectTimeId" to projectTimeId)
        )
        val root = JSONObject(raw)
        val code = root.optInt("code", -1)
        val msg = root.optString("msg", "").trim()
        if (code != 200) {
            throw IOException(msg.ifBlank { "报名失败" })
        }
        return msg.ifBlank { "报名成功" }
    }

    /**
     * 取消讲座报名（POST /fcredit/credit/creProjectStu/stu/cancelRegistration?creProjectStuId=<id>）。
     */
    fun cancelLectureRegistration(creProjectStuId: String): String {
        ensureModuleContext(MENU_ID_CREDIT)
        val raw = executeSignedPost(
            path = "/fcredit/credit/creProjectStu/stu/cancelRegistration",
            queryParams = mapOf("creProjectStuId" to creProjectStuId)
        )
        val root = JSONObject(raw)
        val code = root.optInt("code", -1)
        val msg = root.optString("msg", "").trim()
        if (code != 200) {
            throw IOException(msg.ifBlank { "取消报名失败" })
        }
        return msg.ifBlank { "已取消报名" }
    }

    /**
     * 保存/修改讲座心得体会（POST /fcredit/credit/creProjectStu/stu/saveExperience）。
     */
    fun saveLectureExperience(creProjectStuId: String, experience: String): String {
        ensureModuleContext(MENU_ID_CREDIT)
        val body = JSONObject()
            .put("creProjectStuId", creProjectStuId)
            .put("experience", experience.trim())
        val raw = executeSignedPost(
            path = "/fcredit/credit/creProjectStu/stu/saveExperience",
            jsonBody = body
        )
        val root = JSONObject(raw)
        val code = root.optInt("code", -1)
        val msg = root.optString("msg", "").trim()
        if (code != 200) {
            throw IOException(msg.ifBlank { "保存心得体会失败" })
        }
        return msg.ifBlank { "心得体会已保存" }
    }

    private class SjcxAuthExpiredException : IOException("实践教学平台会话已过期")
}

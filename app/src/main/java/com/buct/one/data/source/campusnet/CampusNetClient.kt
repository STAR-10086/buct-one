package com.buct.one.data.source.campusnet

import android.os.Build
import com.buct.course.BuildConfig
import com.buct.course.login.BuctDns
import com.buct.course.login.BuctTlsTrustManager
import com.buct.course.login.BuctWebVpnAuthRequiredException
import com.buct.course.login.BuctWebVpnInterceptor
import com.buct.course.login.BuctWebVpnNetworkInterceptor
import com.buct.course.login.MatchingMemoryCookieJar
import com.buct.one.data.model.CampusNetCheckoutRecord
import com.buct.one.data.model.CampusNetPayRecord
import com.buct.one.data.model.CampusNetSnapshot
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 北化校园网网费与账单客户端：
 * 1. https://tree.buct.edu.cn/cgi-bin/rad_user_info 查询实时在线与计量状态；
 * 2. http://tree.buct.edu.cn:8800/site/sso 免密同步自服务平台 /home、/log/pay、/log/check-out。
 * 已接入 BuctWebVpn 直连优先探测 + 校外 WebVPN 自动降级与 BuctTlsTrustManager 证书校验。
 */
internal class CampusNetClient {

    companion object {
        private const val PORTAL_BASE_URL = "https://tree.buct.edu.cn"
        private const val SELF_SERVICE_BASE_URL = "http://tree.buct.edu.cn:8800"
        private const val MAX_REDIRECT_HOPS = 6
    }

    private val trustManager = BuctTlsTrustManager()
    private val cookieJar = MatchingMemoryCookieJar()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(false)
        .followSslRedirects(false)
        .sslSocketFactory(trustManager.sslSocketFactory(), trustManager)
        .dns(BuctDns)
        .addInterceptor(BuctWebVpnInterceptor())
        .addNetworkInterceptor(BuctWebVpnNetworkInterceptor())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun fetchBillingSnapshot(fallbackStudentId: String): CampusNetSnapshot {
        val radRes = runCatching { fetchRadUserInfo() }
        radRes.exceptionOrNull()?.let { err ->
            if (err is BuctWebVpnAuthRequiredException) throw err
        }
        val rad = radRes.getOrNull()
        val resolvedStudentId = rad?.username?.takeIf { it.isNotBlank() } ?: fallbackStudentId.trim()

        var home: SelfServiceHomeData? = null
        var payRecords: List<CampusNetPayRecord> = emptyList()
        var checkoutRecords: List<CampusNetCheckoutRecord> = emptyList()
        var selfServiceError: Throwable? = null

        if (resolvedStudentId.isNotBlank()) {
            cookieJar.clear()
            val ssoData = Base64.getEncoder().encodeToString("zh-CN:$resolvedStudentId".toByteArray(Charsets.UTF_8))
            val ssoUrl = "$SELF_SERVICE_BASE_URL/site/sso".toHttpUrl().newBuilder()
                .addQueryParameter("data", ssoData)
                .build()
                .toString()

            val homeRes = runCatching {
                val homeHtml = executeGet(ssoUrl, referer = "$PORTAL_BASE_URL/")
                CampusNetParser.parseSelfServiceHome(homeHtml)
                    ?: throw IllegalStateException("校园网自服务平台返回了未登录页面")
            }
            homeRes.exceptionOrNull()?.let { err ->
                if (err is BuctWebVpnAuthRequiredException) throw err
            }
            home = homeRes.getOrNull()
            selfServiceError = homeRes.exceptionOrNull()

            if (home != null) {
                payRecords = runCatching {
                    val payHtml = executeGet("$SELF_SERVICE_BASE_URL/log/pay", referer = "$SELF_SERVICE_BASE_URL/home")
                    CampusNetParser.parsePayRecords(payHtml)
                }.getOrDefault(emptyList())

                checkoutRecords = runCatching {
                    val checkoutHtml = executeGet("$SELF_SERVICE_BASE_URL/log/check-out", referer = "$SELF_SERVICE_BASE_URL/home")
                    CampusNetParser.parseCheckoutRecords(checkoutHtml)
                }.getOrDefault(emptyList())
            }
        }

        if (rad == null && home == null) {
            throw (selfServiceError
                ?: radRes.exceptionOrNull()
                ?: IllegalStateException("未能获取到校园网网费数据"))
        }

        return CampusNetParser.mergeSnapshot(
            rad = rad,
            home = home,
            payRecords = payRecords,
            checkoutRecords = checkoutRecords,
            fallbackUsername = resolvedStudentId
        )
    }

    private fun fetchRadUserInfo(): CampusNetSnapshot {
        val now = System.currentTimeMillis()
        val cb = "jQuery112400000000000000000_$now"
        val url = "$PORTAL_BASE_URL/cgi-bin/rad_user_info".toHttpUrl().newBuilder()
            .addQueryParameter("callback", cb)
            .addQueryParameter("_", now.toString())
            .build()
            .toString()
        return CampusNetParser.parseUserInfo(executeGet(url, referer = "$PORTAL_BASE_URL/"))
    }

    private fun executeGet(url: String, referer: String): String {
        var currentUrl = url.toHttpUrl()
        var currentReferer = referer
        repeat(MAX_REDIRECT_HOPS) {
            val req = Request.Builder()
                .url(currentUrl)
                .get()
                .header("User-Agent", buildUserAgent())
                .header("Referer", currentReferer)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code in 300..399) {
                    val loc = resp.header("Location")?.trim().orEmpty()
                    if (loc.isNotEmpty()) {
                        val next = currentUrl.resolve(loc)
                        if (next != null) {
                            currentReferer = currentUrl.toString()
                            currentUrl = next
                            return@use
                        }
                    }
                }
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful && body.isBlank()) {
                    throw IllegalStateException("校园网服务响应异常（HTTP ${resp.code}）")
                }
                return body
            }
        }
        throw IllegalStateException("校园网网关重定向次数过多")
    }

    private fun buildUserAgent(): String {
        val release = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() } ?: "14"
        return "Mozilla/5.0 (Linux; Android $release) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36 " +
            "BuctOne/${BuildConfig.VERSION_NAME}"
    }
}

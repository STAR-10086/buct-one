package com.buct.one.data.source.campusnet

import android.os.Build
import com.buct.course.BuildConfig
import com.buct.course.login.BuctTlsTrustManager
import com.buct.one.data.model.CampusNetCheckoutRecord
import com.buct.one.data.model.CampusNetPayRecord
import com.buct.one.data.model.CampusNetSnapshot
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 北化校园网网费与账单直连查询客户端：
 * 1. https://tree.buct.edu.cn/cgi-bin/rad_user_info 查询当前网关在线状态与实时计量；
 * 2. http://tree.buct.edu.cn:8800/site/sso 免密同步自服务平台 /home、/log/pay、/log/check-out。
 * HTTPS 请求强制通过 BuctTlsTrustManager 校验证书，绝不经过 WebVPN。
 */
internal class CampusNetClient {

    companion object {
        private const val PORTAL_BASE_URL = "https://tree.buct.edu.cn"
        private const val SELF_SERVICE_BASE_URL = "http://tree.buct.edu.cn:8800"
    }

    private val trustManager = BuctTlsTrustManager()

    private class InMemoryCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            val list = store.computeIfAbsent(host) { mutableListOf() }
            synchronized(list) {
                for (cookie in cookies) {
                    list.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                    if (cookie.expiresAt > System.currentTimeMillis() || cookie.expiresAt == Long.MAX_VALUE) {
                        list.add(cookie)
                    }
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val list = store[url.host] ?: return emptyList()
            synchronized(list) {
                list.removeAll { it.expiresAt <= now }
                return list.filter { it.matches(url) }.toList()
            }
        }

        fun clear() = store.clear()
    }

    private val cookieJar = InMemoryCookieJar()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(trustManager.sslSocketFactory(), trustManager)
        .cookieJar(cookieJar)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun fetchBillingSnapshot(fallbackStudentId: String): CampusNetSnapshot {
        val radRes = runCatching { fetchRadUserInfo() }
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
            throw (radRes.exceptionOrNull()
                ?: selfServiceError
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
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", buildUserAgent())
            .header("Referer", referer)
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful && body.isBlank()) {
                throw IllegalStateException("校园网服务响应异常（HTTP ${resp.code}）")
            }
            return body
        }
    }

    private fun buildUserAgent(): String {
        val release = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() } ?: "14"
        return "Mozilla/5.0 (Linux; Android $release) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36 " +
            "BuctOne/${BuildConfig.VERSION_NAME}"
    }
}

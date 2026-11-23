package com.buct.one.data.source.electricity

import android.os.Build
import com.buct.course.BuildConfig
import com.buct.course.login.BuctTlsTrustManager
import com.buct.one.data.model.ElectricityReading
import com.buct.one.data.model.ElectricityRoomTarget
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal class ElectricityGatewayException(
    val httpCode: Int,
    message: String,
    val retryAfterSeconds: Int? = null,
    val isBanned: Boolean = false
) : IOException(message)

/**
 * 宿舍电费查询网关（https://ele.buct.one）原生客户端。
 * 强制通过 BuctTlsTrustManager 校验证书，公网直连不走 WebVPN。
 */
internal class ElectricityClient {

    companion object {
        private const val BASE_URL = "https://ele.buct.one"
    }

    private val trustManager = BuctTlsTrustManager()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(trustManager.sslSocketFactory(), trustManager)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun queryBalance(
        target: ElectricityRoomTarget,
        clientId: String,
        studentId: String,
        signSecret: String
    ): ElectricityReading {
        val url = "$BASE_URL/v1/electricity".toHttpUrl().newBuilder()
            .addQueryParameter("schoolid", target.schoolid)
            .addQueryParameter("roomid", target.roomid)
            .build()

        val reqBuilder = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", buildUserAgent())
            .header("X-Client-Id", clientId)
            .header("X-Student-Id", studentId)

        val secret = signSecret.trim()
        if (secret.isNotBlank()) {
            val timestampSec = System.currentTimeMillis() / 1000L
            val sign = sha256Hex("$studentId:$clientId:${target.roomid}:$timestampSec:$secret")
            reqBuilder.header("X-Timestamp", timestampSec.toString())
            reqBuilder.header("X-Sign", sign)
        }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val parsedErr = ElectricityParser.parseError(
                    httpCode = resp.code,
                    responseBody = body,
                    retryAfterHeader = resp.header("Retry-After")
                )
                throw ElectricityGatewayException(
                    httpCode = resp.code,
                    message = parsedErr.message,
                    retryAfterSeconds = parsedErr.retryAfterSeconds,
                    isBanned = parsedErr.isBanned
                )
            }
            return ElectricityParser.parseBalanceResponse(body, target)
        }
    }

    private fun buildUserAgent(): String {
        val release = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() } ?: "14"
        return "Mozilla/5.0 (Linux; Android $release) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36 " +
            "BuctOne/${BuildConfig.VERSION_NAME}"
    }

    private fun sha256Hex(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(v.toString(16))
        }
        return sb.toString()
    }
}

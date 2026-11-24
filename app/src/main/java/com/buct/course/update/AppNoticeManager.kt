package com.buct.course.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.buct.course.BuildConfig
import com.buct.course.login.BuctDns
import com.buct.course.login.BuctTlsTrustManager
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AppNotice(
    val id: String,
    val appId: String,
    val title: String,
    val summary: String,
    val markdown: String,
    val level: String,
    val popup: Boolean,
    val pinned: Boolean,
    val dismissible: Boolean,
    val actionText: String,
    val actionUrl: String,
    val minVersionCode: Int,
    val maxVersionCode: Int,
    val publishedAt: Long,
    val expiresAt: Long?
)

/**
 * 轻量多应用公告拉取与本地已读状态管理（对接 https://notice.buct.one）。
 */
class AppNoticeManager private constructor(private val context: Context) {

    companion object {
        private const val NOTICE_API_BASE = "https://notice.buct.one/api/notices"
        private const val APP_ID = "buct-one"
        private const val PREFS_NAME = "app_notice_prefs"
        private const val KEY_DISMISSED_IDS = "dismissed_notice_ids"
        private const val KEY_CACHED_JSON = "cached_notices_json"

        @Volatile
        private var instance: AppNoticeManager? = null

        fun getInstance(context: Context): AppNoticeManager {
            return instance ?: synchronized(this) {
                instance ?: AppNoticeManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val client: OkHttpClient by lazy {
        val trustManager = BuctTlsTrustManager()
        OkHttpClient.Builder()
            .sslSocketFactory(trustManager.sslSocketFactory(), trustManager)
            .dns(BuctDns)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isDismissed(id: String): Boolean {
        if (id.isBlank()) return true
        val set = prefs().getStringSet(KEY_DISMISSED_IDS, emptySet()).orEmpty()
        return id in set
    }

    fun markDismissed(id: String) {
        if (id.isBlank()) return
        val current = prefs().getStringSet(KEY_DISMISSED_IDS, emptySet()).orEmpty().toMutableSet()
        if (current.add(id)) {
            prefs().edit().putStringSet(KEY_DISMISSED_IDS, current).apply()
        }
    }

    fun getCachedNotices(): List<AppNotice> {
        val raw = prefs().getString(KEY_CACHED_JSON, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching { parseNoticesJson(raw) }.getOrDefault(emptyList())
    }

    fun fetchNotices(onComplete: (Result<List<AppNotice>>) -> Unit) {
        val url = "$NOTICE_API_BASE?app=$APP_ID&versionCode=${BuildConfig.VERSION_CODE}"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { onComplete(Result.failure(e)) }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        mainHandler.post {
                            onComplete(Result.failure(IOException("HTTP ${resp.code}")))
                        }
                        return
                    }
                    val body = resp.body?.string().orEmpty()
                    val parsed = runCatching { parseNoticesJson(body) }
                    if (parsed.isSuccess) {
                        prefs().edit().putString(KEY_CACHED_JSON, body).apply()
                    }
                    mainHandler.post { onComplete(parsed) }
                }
            }
        })
    }

    private fun parseNoticesJson(raw: String): List<AppNotice> {
        val root = JSONObject(raw)
        val arr = root.optJSONArray("notices") ?: JSONArray()
        val now = System.currentTimeMillis()
        return buildList(arr.length()) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").trim()
                val title = obj.optString("title", "").trim()
                if (id.isEmpty() || title.isEmpty()) continue
                val expiresAt = if (obj.has("expiresAt") && !obj.isNull("expiresAt")) {
                    obj.optLong("expiresAt", 0L).takeIf { it > 0L }
                } else {
                    null
                }
                if (expiresAt != null && expiresAt <= now) continue
                add(
                    AppNotice(
                        id = id,
                        appId = obj.optString("appId", APP_ID),
                        title = title,
                        summary = obj.optString("summary", ""),
                        markdown = obj.optString("markdown", ""),
                        level = obj.optString("level", "info"),
                        popup = obj.optBoolean("popup", false),
                        pinned = obj.optBoolean("pinned", false),
                        dismissible = obj.optBoolean("dismissible", true),
                        actionText = obj.optString("actionText", ""),
                        actionUrl = obj.optString("actionUrl", ""),
                        minVersionCode = obj.optInt("minVersionCode", 0),
                        maxVersionCode = obj.optInt("maxVersionCode", 0),
                        publishedAt = obj.optLong("publishedAt", 0L),
                        expiresAt = expiresAt
                    )
                )
            }
        }
    }
}

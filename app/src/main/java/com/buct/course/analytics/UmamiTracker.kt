package com.buct.course.analytics

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import com.buct.course.BuildConfig
import com.buct.course.CampusNetActivity
import com.buct.course.EcardActivity
import com.buct.course.ElectricityActivity
import com.buct.course.JwNoticeActivity
import com.buct.course.MailActivity
import com.buct.course.MainActivity
import com.buct.course.PortalActivity
import com.buct.course.ScsActivity
import com.buct.course.SjcxActivity
import com.buct.course.TheolCourseActivity
import com.buct.course.TheolSubmitWebViewActivity
import com.buct.course.TyglActivity
import com.buct.course.login.BuctTlsTrustManager
import com.buct.course.manager.UserManager
import com.buct.course.ui.widget.WidgetConfigActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 自托管 Umami（stats.buct.one）轻量级匿名统计客户端。
 *
 * - 零第三方 SDK，复用 OkHttp + [BuctTlsTrustManager]；
 * - 仅使用本地随机生成的 UUIDv4 作为 Umami `id`（Distinct ID），绝不采集学号、姓名、Cookie、ANDROID_ID 或硬件指纹；
 * - L1：按北京时间每日首次进入主界面上报 1 次 `/app/launch`（统计 DAU / WAU / MAU、留存率、版本与系统分布）；
 * - L2：单次进程会话内首次访问各主标签或二级功能模块时去重上报 1 次（统计功能模块热度排行）；
 * - 支持在「设置 -> 数据与安全 -> 匿名使用统计」中随时实时开关。
 */
object UmamiTracker {
    private const val PREFS_NAME = "umami_analytics"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_INSTALL_ID = "install_id"
    private const val KEY_LAST_LAUNCH_DAY = "last_launch_day"
    private const val NO_BACKUP_ID_FILE = "umami_install_id"

    private const val ENDPOINT = "https://stats.buct.one/api/send"
    private const val WEBSITE_ID = "f9d777c9-625a-4cc9-93f0-694c9c1cb57a"
    private const val HOSTNAME = "app.buct.one"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val visitedPathsInSession: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var installed = false

    @Volatile
    private var inFlightLaunchDay: String? = null

    private val httpClient: OkHttpClient by lazy {
        val trustManager = BuctTlsTrustManager()
        OkHttpClient.Builder()
            .sslSocketFactory(trustManager.sslSocketFactory(), trustManager)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun install(application: Application) {
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                when (activity) {
                    is MainActivity -> trackDailyLaunchIfNeeded(activity)
                    is MailActivity -> trackPageVisit(activity, "/module/mail", "校园邮箱")
                    is JwNoticeActivity -> trackPageVisit(activity, "/module/jw_notice", "教务消息")
                    is PortalActivity -> trackPageVisit(activity, "/module/portal", "i北化")
                    is SjcxActivity -> trackPageVisit(activity, "/module/sjcx", "实践教学与讲座")
                    is ScsActivity -> trackPageVisit(activity, "/module/scs", "第二课堂与素质学分")
                    is TyglActivity -> trackPageVisit(activity, "/module/tygl", "体育管理")
                    is EcardActivity -> trackPageVisit(activity, "/module/ecard", "校园一卡通")
                    is CampusNetActivity -> trackPageVisit(activity, "/module/campus_net", "校园网网费")
                    is ElectricityActivity -> trackPageVisit(activity, "/module/electricity", "宿舍电费")
                    is TheolCourseActivity -> trackPageVisit(activity, "/module/theol_course", "北化在线")
                    is TheolSubmitWebViewActivity -> trackPageVisit(activity, "/module/theol_submit", "作业在线提交")
                    is WidgetConfigActivity -> trackPageVisit(activity, "/module/widget_config", "桌面小组件配置")
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        if (!enabled) {
            visitedPathsInSession.clear()
        }
    }

    /** L1：每日首次进入主界面时上报日活心跳（同天去重，演示模式与关闭状态自动跳过）。 */
    fun trackDailyLaunchIfNeeded(context: Context) {
        if (!shouldTrack(context)) return
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = beijingDay()
        if (prefs.getString(KEY_LAST_LAUNCH_DAY, null) == today) return
        if (inFlightLaunchDay == today) return
        inFlightLaunchDay = today

        val installId = getOrCreateInstallId(appCtx, prefs)
        val screen = screenSize(appCtx)
        val schoolId = currentSchoolId()
        scope.launch {
            try {
                val ok = sendEvent(
                    installId = installId,
                    screen = screen,
                    urlPath = "/app/launch",
                    title = "应用日活",
                    schoolId = schoolId,
                    includeDeviceMeta = true
                )
                if (ok) {
                    prefs.edit().putString(KEY_LAST_LAUNCH_DAY, today).apply()
                }
            } finally {
                if (inFlightLaunchDay == today) {
                    inFlightLaunchDay = null
                }
            }
        }
    }

    /** L2：单次会话内首次访问主标签或二级模块时去重上报一次页面访问。 */
    fun trackPageVisit(context: Context, urlPath: String, title: String) {
        if (!shouldTrack(context)) return
        if (!visitedPathsInSession.add(urlPath)) return
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val installId = getOrCreateInstallId(appCtx, prefs)
        val screen = screenSize(appCtx)
        val schoolId = currentSchoolId()
        scope.launch {
            val ok = sendEvent(
                installId = installId,
                screen = screen,
                urlPath = urlPath,
                title = title,
                schoolId = schoolId,
                includeDeviceMeta = false
            )
            if (!ok) {
                visitedPathsInSession.remove(urlPath)
            }
        }
    }

    private fun shouldTrack(context: Context): Boolean {
        if (BuildConfig.UI_PREVIEW) return false
        val userManager = runCatching { UserManager.getInstance() }.getOrNull()
        if (userManager?.isDemoMode == true) return false
        return isEnabled(context)
    }

    private fun currentSchoolId(): String =
        runCatching { UserManager.getInstance().currentSchool?.id.orEmpty() }
            .getOrDefault("")
            .ifBlank { "buct" }

    private fun sendEvent(
        installId: String,
        screen: String,
        urlPath: String,
        title: String,
        schoolId: String,
        includeDeviceMeta: Boolean
    ): Boolean = runCatching {
        val data = JSONObject()
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("school", schoolId)
        if (includeDeviceMeta) {
            data.put("version_code", BuildConfig.VERSION_CODE)
            data.put("sdk_int", Build.VERSION.SDK_INT)
            data.put("brand", Build.BRAND.orEmpty())
        }

        val payload = JSONObject()
            .put("website", WEBSITE_ID)
            .put("hostname", HOSTNAME)
            .put("screen", screen)
            .put("language", Locale.getDefault().toLanguageTag().ifBlank { "zh-CN" })
            .put("title", title)
            .put("url", urlPath)
            .put("id", installId)
            .put("data", data)

        val body = JSONObject()
            .put("type", "event")
            .put("payload", payload)
            .toString()
            .toRequestBody(jsonMedia)

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("User-Agent", buildAndroidUserAgent())
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun buildAndroidUserAgent(): String {
        val release = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() } ?: "14"
        val model = "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}"
            .trim()
            .replace(";", "")
            .replace(")", "")
            .ifBlank { "Android" }
        return "Mozilla/5.0 (Linux; Android $release; $model) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 BuctOne/${BuildConfig.VERSION_NAME}"
    }

    @Synchronized
    private fun getOrCreateInstallId(context: Context, prefs: android.content.SharedPreferences): String {
        val noBackupFile = runCatching { File(context.noBackupFilesDir, NO_BACKUP_ID_FILE) }.getOrNull()
        val fromFile = runCatching {
            noBackupFile?.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim()
        }.getOrNull()?.takeIf { isValidUuid(it) }
        if (fromFile != null) return fromFile

        val fromPrefs = prefs.getString(KEY_INSTALL_ID, null)?.trim()?.takeIf { isValidUuid(it) }
        val resolved = fromPrefs ?: UUID.randomUUID().toString()
        runCatching { noBackupFile?.writeText(resolved, Charsets.UTF_8) }
        if (fromPrefs == null) {
            prefs.edit().putString(KEY_INSTALL_ID, resolved).apply()
        }
        return resolved
    }

    private fun isValidUuid(value: String): Boolean =
        value.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))

    private fun screenSize(context: Context): String {
        val dm = context.resources.displayMetrics
        return "${dm.widthPixels}x${dm.heightPixels}"
    }

    private fun beijingDay(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("GMT+08:00")
    }.format(Date())
}

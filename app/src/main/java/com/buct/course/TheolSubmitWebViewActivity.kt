package com.buct.course

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.buct.course.login.BuctTlsTrustManager
import com.buct.course.manager.AppThemeCoordinator
import com.buct.course.ui.system.GlassWindowHost
import com.buct.course.ui.system.SystemIconButton
import com.buct.course.ui.system.SystemTopBar
import com.buct.course.ui.theme.CourseSelectorTheme
import com.buct.one.data.session.SidPortalSessionVault
import com.buct.one.data.sync.TheolSyncService
import java.net.URLEncoder

/**
 * 北化在线（THEOL / MEOL）作业查看与提交页。
 * 启动前先通过 TheolSyncService 激活 MEOL 会话与课程上下文（index.jsp?courseId=...），
 * 按 Path=/meol 注入 Cookie 并带 Referer 打开目标页面，彻底避免 Permission Denied。
 */
class TheolSubmitWebViewActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_COURSE_NAME = "extra_course_name"
        private const val EXTRA_COURSE_LID = "extra_course_lid"
        private const val MEOL_SSO_LOGIN = "https://course.buct.edu.cn/meol/homepage/common/sso_login.jsp"
        private const val MEOL_HOME = "https://course.buct.edu.cn/meol/homepage/student/index.jsp"
        private const val HW_TASK_REFERER = "https://course.buct.edu.cn/meol/common/hw/student/hwtask.jsp?tagbug=client&strStyle=new03"

        fun launch(
            context: Context,
            url: String,
            title: String,
            courseName: String,
            courseLid: String
        ) {
            val intent = Intent(context, TheolSubmitWebViewActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_COURSE_NAME, courseName)
                putExtra(EXTRA_COURSE_LID, courseLid)
            }
            context.startActivity(intent)
        }
    }

    private val tlsTrustManager by lazy { BuctTlsTrustManager() }
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            ?: if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { arrayOf(it) }
            } else {
                null
            }
        fileChooserCallback?.onReceiveValue(uris)
        fileChooserCallback = null
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppThemeCoordinator.wrapContext(newBase))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rawUrl = intent.getStringExtra(EXTRA_URL).orEmpty().trim()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "北化在线" }
        val courseName = intent.getStringExtra(EXTRA_COURSE_NAME).orEmpty()
        val courseLid = intent.getStringExtra(EXTRA_COURSE_LID).orEmpty().trim()

        val encodedLid = URLEncoder.encode(courseLid, "UTF-8")
        val courseHomeUrl = if (courseLid.isNotBlank()) {
            "https://course.buct.edu.cn/meol/jpk/course/layout/newpage/index.jsp?courseId=$encodedLid"
        } else {
            MEOL_HOME
        }
        val targetUrl = normalizeHttpsUrl(rawUrl.ifBlank { courseHomeUrl })

        setContent {
            CourseSelectorTheme {
                GlassWindowHost {
                    var webView by remember { mutableStateOf<WebView?>(null) }
                    var progress by remember { mutableIntStateOf(0) }
                    var canGoBack by remember { mutableStateOf(false) }
                    var sessionReady by remember { mutableStateOf(false) }
                    var injectedSidCookies by remember { mutableStateOf("") }
                    var injectedMeolCookies by remember { mutableStateOf("") }
                    var initialLoadUrl by remember { mutableStateOf(targetUrl) }
                    var pendingTargetUrl by remember { mutableStateOf<String?>(null) }
                    var ssoRetried by remember { mutableStateOf(false) }
                    var courseWarmedUp by remember { mutableStateOf(false) }

                    LaunchedEffect(Unit) {
                        val res = TheolSyncService.getInstance(this@TheolSubmitWebViewActivity)
                            .prepareCourseWebSession(courseLid)
                        if (res.isSuccess) {
                            val (sid, meol) = res.getOrDefault("" to "")
                            injectedSidCookies = sid
                            injectedMeolCookies = meol
                            initialLoadUrl = targetUrl
                            pendingTargetUrl = null
                            ssoRetried = false
                            courseWarmedUp = false
                        } else {
                            val (sid, meol) = SidPortalSessionVault.readCookies(this@TheolSubmitWebViewActivity)
                            injectedSidCookies = sid
                            injectedMeolCookies = meol
                            if (meol.isBlank() && sid.isNotBlank()) {
                                initialLoadUrl = MEOL_SSO_LOGIN
                                pendingTargetUrl = targetUrl
                                ssoRetried = true
                                courseWarmedUp = false
                            } else if (courseLid.isNotBlank() && targetUrl != courseHomeUrl) {
                                initialLoadUrl = courseHomeUrl
                                pendingTargetUrl = targetUrl
                                ssoRetried = false
                                courseWarmedUp = true
                            } else {
                                initialLoadUrl = targetUrl
                                pendingTargetUrl = null
                                ssoRetried = false
                                courseWarmedUp = false
                            }
                        }
                        sessionReady = true
                    }

                    DisposableEffect(Unit) {
                        onDispose {
                            webView?.apply {
                                stopLoading()
                                destroy()
                            }
                            webView = null
                        }
                    }

                    BackHandler {
                        val view = webView
                        if (view != null && view.canGoBack()) {
                            view.goBack()
                        } else {
                            finish()
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                            .imePadding()
                    ) {
                        SystemTopBar(
                            title = title,
                            subtitle = courseName.ifBlank { "北化在线" },
                            navigationIcon = {
                                SystemIconButton(
                                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回",
                                    onClick = {
                                        val view = webView
                                        if (view != null && view.canGoBack()) {
                                            view.goBack()
                                        } else {
                                            finish()
                                        }
                                    }
                                )
                            },
                            actions = {
                                if (courseLid.isNotBlank()) {
                                    SystemIconButton(
                                        icon = Icons.Outlined.MenuBook,
                                        contentDescription = "课程主页",
                                        onClick = {
                                            pendingTargetUrl = null
                                            webView?.loadUrl(
                                                "https://course.buct.edu.cn/meol/jpk/course/layout/newpage/index.jsp?courseId=$courseLid"
                                            )
                                        }
                                    )
                                }
                                SystemIconButton(
                                    icon = Icons.Default.Refresh,
                                    contentDescription = "刷新",
                                    onClick = { webView?.reload() }
                                )
                            }
                        )

                        if (progress in 1..99) {
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                            )
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            if (!sessionReady) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        text = "正在同步北化在线课程会话…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                        settings.apply {
                                            AppThemeCoordinator.preserveWebContentColors(this)
                                            javaScriptEnabled = true
                                            domStorageEnabled = true
                                            useWideViewPort = true
                                            loadWithOverviewMode = true
                                            setSupportZoom(true)
                                            builtInZoomControls = true
                                            displayZoomControls = false
                                            allowFileAccess = false
                                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                        }

                                        injectCookies(this, injectedSidCookies, injectedMeolCookies)

                                        webChromeClient = object : WebChromeClient() {
                                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                                progress = newProgress
                                            }

                                            override fun onShowFileChooser(
                                                webView: WebView?,
                                                filePathCallback: ValueCallback<Array<Uri>>?,
                                                fileChooserParams: FileChooserParams?
                                            ): Boolean {
                                                fileChooserCallback?.onReceiveValue(null)
                                                fileChooserCallback = filePathCallback
                                                val chooserIntent = runCatching {
                                                    fileChooserParams?.createIntent()
                                                }.getOrNull() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                                    addCategory(Intent.CATEGORY_OPENABLE)
                                                    type = "*/*"
                                                }
                                                return try {
                                                    filePickerLauncher.launch(chooserIntent)
                                                    true
                                                } catch (_: Exception) {
                                                    fileChooserCallback?.onReceiveValue(null)
                                                    fileChooserCallback = null
                                                    false
                                                }
                                            }
                                        }

                                        webViewClient = object : WebViewClient() {
                                            override fun onPageStarted(
                                                view: WebView?,
                                                url: String?,
                                                favicon: Bitmap?
                                            ) {
                                                canGoBack = view?.canGoBack() == true
                                            }

                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                canGoBack = view?.canGoBack() == true
                                                if (view == null || url.isNullOrBlank()) return

                                                syncCookiesToVault()

                                                val uri = runCatching { Uri.parse(url) }.getOrNull()
                                                val host = uri?.host.orEmpty()
                                                val path = uri?.encodedPath.orEmpty()
                                                val isSsoLanding = path.equals("/meol/homepage/common/sso_login.jsp", ignoreCase = true)
                                                val inMeol = host.equals("course.buct.edu.cn", ignoreCase = true) &&
                                                    path.startsWith("/meol/")
                                                val isLoginPage = path.contains("login", ignoreCase = true) && !isSsoLanding
                                                val isCourseHome = path.equals("/meol/jpk/course/layout/newpage/index.jsp", ignoreCase = true)

                                                val latestSid = SidPortalSessionVault.readCookies(this@TheolSubmitWebViewActivity).first
                                                if (isLoginPage || host.equals("sidportal.buct.edu.cn", ignoreCase = true)) {
                                                    if (!ssoRetried && latestSid.isNotBlank()) {
                                                        ssoRetried = true
                                                        courseWarmedUp = false
                                                        pendingTargetUrl = targetUrl
                                                        view.loadUrl(MEOL_SSO_LOGIN)
                                                    }
                                                    return
                                                }

                                                if (!inMeol || isSsoLanding) return

                                                view.evaluateJavascript(
                                                    "(function(){var t=(document.body&&document.body.innerText)||'';return (t.indexOf('Permission Denied')>=0||t.indexOf('登录时间超时')>=0||t.indexOf('请重新登录')>=0||document.querySelector('input[type=password]')!=null);})()"
                                                ) { rawFlag ->
                                                    if (rawFlag == "true") {
                                                        val sidNow = SidPortalSessionVault.readCookies(this@TheolSubmitWebViewActivity).first
                                                        if (!courseWarmedUp && courseLid.isNotBlank()) {
                                                            courseWarmedUp = true
                                                            pendingTargetUrl = targetUrl
                                                            view.loadUrl(courseHomeUrl)
                                                        } else if (!ssoRetried && sidNow.isNotBlank()) {
                                                            ssoRetried = true
                                                            courseWarmedUp = false
                                                            pendingTargetUrl = targetUrl
                                                            view.loadUrl(MEOL_SSO_LOGIN)
                                                        }
                                                    } else {
                                                        val queued = pendingTargetUrl
                                                        if (queued != null) {
                                                            if (courseLid.isNotBlank() && !courseWarmedUp && !isCourseHome) {
                                                                courseWarmedUp = true
                                                                view.loadUrl(courseHomeUrl)
                                                            } else {
                                                                pendingTargetUrl = null
                                                                view.loadUrl(queued, mapOf("Referer" to HW_TASK_REFERER))
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            override fun shouldOverrideUrlLoading(
                                                view: WebView?,
                                                request: WebResourceRequest?
                                            ): Boolean {
                                                val uri = request?.url ?: return false
                                                val scheme = uri.scheme.orEmpty().lowercase()
                                                val host = uri.host.orEmpty().lowercase()
                                                if (scheme == "http" && host == "course.buct.edu.cn") {
                                                    view?.loadUrl(uri.buildUpon().scheme("https").build().toString())
                                                    return true
                                                }
                                                if (scheme in setOf("http", "https") &&
                                                    (host.endsWith(".buct.edu.cn") || host == "buct.edu.cn")
                                                ) {
                                                    return false
                                                }
                                                runCatching {
                                                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                                                }
                                                return true
                                            }

                                            // 仅针对 *.buct.edu.cn 且经 BuctTlsTrustManager.isTrustedWebViewCertificate 校验通过才放行
                                            @SuppressLint("WebViewClientOnReceivedSslError")
                                            override fun onReceivedSslError(
                                                view: WebView?,
                                                handler: SslErrorHandler?,
                                                error: SslError?
                                            ) {
                                                if (handler == null) return
                                                val host = error?.url?.let {
                                                    runCatching { Uri.parse(it).host.orEmpty() }.getOrDefault("")
                                                }.orEmpty().lowercase()
                                                val isBuctHost = host.endsWith(".buct.edu.cn") || host == "buct.edu.cn"
                                                if (isBuctHost && tlsTrustManager.isTrustedWebViewCertificate(error?.certificate)) {
                                                    handler.proceed()
                                                } else {
                                                    handler.cancel()
                                                }
                                            }
                                        }

                                        webView = this
                                        if (initialLoadUrl == targetUrl) {
                                            loadUrl(initialLoadUrl, mapOf("Referer" to HW_TASK_REFERER))
                                        } else {
                                            loadUrl(initialLoadUrl)
                                        }
                                    }
                                }
                            )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        super.onDestroy()
    }

    private fun normalizeHttpsUrl(url: String): String =
        if (url.startsWith("http://course.buct.edu.cn", ignoreCase = true)) {
            "https://" + url.removePrefix("http://")
        } else {
            url
        }

    private fun injectCookies(view: WebView, sidCookies: String, meolCookies: String) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(view, true)
        // 清理遗留在根路径下的 JSESSIONID，避免与 Tomcat Path=/meol 冲突
        cm.setCookie("https://course.buct.edu.cn/", "JSESSIONID=; Path=/; Max-Age=0")
        fun putMeolHeader(header: String) {
            if (header.isBlank()) return
            header.split(';').forEach { part ->
                val kv = part.trim()
                if (kv.contains('=')) {
                    cm.setCookie("https://course.buct.edu.cn/meol/", "$kv; Path=/meol")
                }
            }
        }
        fun putSidHeader(header: String) {
            if (header.isBlank()) return
            header.split(';').forEach { part ->
                val kv = part.trim()
                val eq = kv.indexOf('=')
                if (eq > 0) {
                    val name = kv.substring(0, eq).trim()
                    if (name == "_zte_cid_") {
                        cm.setCookie("https://buct.edu.cn/", "$kv; Domain=.buct.edu.cn; Path=/")
                    } else {
                        cm.setCookie("https://sidportal.buct.edu.cn/", "$kv; Path=/")
                    }
                }
            }
        }
        putSidHeader(sidCookies)
        putMeolHeader(meolCookies)
        cm.flush()
    }

    private fun syncCookiesToVault() {
        val cm = CookieManager.getInstance()
        val newSid = cm.getCookie("https://sidportal.buct.edu.cn/").orEmpty()
        val newMeol = cm.getCookie("https://course.buct.edu.cn/meol/")
            ?.takeIf { it.isNotBlank() }
            ?: cm.getCookie("https://course.buct.edu.cn/").orEmpty()
        if (newSid.isNotBlank() || newMeol.isNotBlank()) {
            SidPortalSessionVault.saveAll(this, newSid, newMeol)
        }
    }
}

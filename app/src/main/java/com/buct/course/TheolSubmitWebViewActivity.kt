package com.buct.course

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.buct.course.login.BuctTlsTrustManager
import com.buct.course.manager.AppThemeCoordinator
import com.buct.course.ui.system.SystemIconButton
import com.buct.course.ui.system.SystemTopBar
import com.buct.course.ui.theme.CourseSelectorTheme
import com.buct.one.data.model.TheolDownloadedFile
import com.buct.one.data.session.SidPortalSessionVault
import com.buct.one.data.sync.TheolSyncService
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * 北化在线（THEOL / MEOL）课程主页、在线测验与作业提交页。
 * 不再使用 GlassWindowHost 的 layerBackdrop 包裹硬件加速 WebView，统一桌面端 UA，
 * 处理 window.open / target="_blank"、JS 弹窗与退役路径改写，彻底消除白屏与 Permission Denied。
 */
class TheolSubmitWebViewActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_COURSE_NAME = "extra_course_name"
        private const val EXTRA_COURSE_LID = "extra_course_lid"
        private const val MEOL_SSO_LOGIN = "https://course.buct.edu.cn/meol/homepage/common/sso_login.jsp"
        private const val MEOL_HOME = "https://course.buct.edu.cn/meol/welcomepage/student/index.jsp"
        private const val MEOL_LEGACY_HOME_PATH = "/meol/homepage/student/index.jsp"
        private const val MEOL_TEST_LIST_PATH = "/meol/common/question/test/student/list.jsp"
        private const val MEOL_LEGACY_TEST_START_PATH = "/meol/common/question/test/student/test_start.jsp"
        private const val MEOL_COURSE_HOME_PATH = "/meol/jpk/course/layout/newpage/index.jsp"
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
            "https://course.buct.edu.cn$MEOL_COURSE_HOME_PATH?courseId=$encodedLid"
        } else {
            MEOL_HOME
        }
        val targetUrl = sanitizeTargetUrl(rawUrl, encodedLid, courseHomeUrl)
        val targetIsCourseHome = targetUrl.contains(MEOL_COURSE_HOME_PATH, ignoreCase = true)

        setContent {
            CourseSelectorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
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
                    var pageError by remember { mutableStateOf<String?>(null) }
                    val scope = rememberCoroutineScope()
                    var downloadingFileName by remember { mutableStateOf<String?>(null) }
                    var downloadedFile by remember { mutableStateOf<TheolDownloadedFile?>(null) }

                    LaunchedEffect(Unit) {
                        val res = TheolSyncService.getInstance(this@TheolSubmitWebViewActivity)
                            .prepareCourseWebSession(courseLid, targetIsCourseHome)
                        if (res.isSuccess) {
                            val (sid, meol) = res.getOrDefault("" to "")
                            injectedSidCookies = sid
                            injectedMeolCookies = meol
                            initialLoadUrl = targetUrl
                            pendingTargetUrl = null
                            ssoRetried = false
                            courseWarmedUp = targetIsCourseHome
                        } else {
                            val (sid, meol) = SidPortalSessionVault.readCookies(this@TheolSubmitWebViewActivity)
                            injectedSidCookies = sid
                            injectedMeolCookies = meol
                            if (meol.isBlank() && sid.isNotBlank()) {
                                initialLoadUrl = MEOL_SSO_LOGIN
                                pendingTargetUrl = targetUrl
                                ssoRetried = true
                                courseWarmedUp = false
                            } else if (courseLid.isNotBlank() && !targetIsCourseHome) {
                                initialLoadUrl = courseHomeUrl
                                pendingTargetUrl = targetUrl
                                ssoRetried = false
                                courseWarmedUp = true
                            } else {
                                initialLoadUrl = targetUrl
                                pendingTargetUrl = null
                                ssoRetried = false
                                courseWarmedUp = targetIsCourseHome
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
                        if (downloadedFile != null) {
                            downloadedFile = null
                            return@BackHandler
                        }
                        val view = webView
                        if (view != null && view.canGoBack()) {
                            pageError = null
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
                            backdrop = null,
                            navigationIcon = {
                                SystemIconButton(
                                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回",
                                    onClick = {
                                        if (downloadedFile != null) {
                                            downloadedFile = null
                                        } else {
                                            val view = webView
                                            if (view != null && view.canGoBack()) {
                                                pageError = null
                                                view.goBack()
                                            } else {
                                                finish()
                                            }
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
                                            pageError = null
                                            downloadingFileName = null
                                            downloadedFile = null
                                            pendingTargetUrl = null
                                            courseWarmedUp = true
                                            webView?.loadUrl(
                                                courseHomeUrl,
                                                mapOf("Referer" to MEOL_HOME)
                                            )
                                        }
                                    )
                                }
                                SystemIconButton(
                                    icon = Icons.Default.Refresh,
                                    contentDescription = "刷新",
                                    onClick = {
                                        pageError = null
                                        downloadingFileName = null
                                        downloadedFile = null
                                        val view = webView
                                        if (view != null) {
                                            if (view.url.isNullOrBlank()) {
                                                view.loadUrl(
                                                    initialLoadUrl,
                                                    mapOf("Referer" to refererFor(initialLoadUrl, courseHomeUrl))
                                                )
                                            } else {
                                                view.reload()
                                            }
                                        }
                                    }
                                )
                            }
                        )

                        if (progress in 1..99 && pageError == null) {
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
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
                                                allowContentAccess = true
                                                defaultTextEncodingName = "UTF-8"
                                                useWideViewPort = true
                                                loadWithOverviewMode = true
                                                setSupportZoom(true)
                                                builtInZoomControls = true
                                                displayZoomControls = false
                                                allowFileAccess = false
                                                javaScriptCanOpenWindowsAutomatically = true
                                                setSupportMultipleWindows(true)
                                                userAgentString = TheolSyncService.WEB_USER_AGENT
                                                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                            }

                                            injectCookies(this, injectedSidCookies, injectedMeolCookies)

                                            webChromeClient = object : WebChromeClient() {
                                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                                    progress = newProgress
                                                }

                                                override fun onCreateWindow(
                                                    view: WebView?,
                                                    isDialog: Boolean,
                                                    isUserGesture: Boolean,
                                                    resultMsg: Message?
                                                ): Boolean {
                                                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                                    val hostView = view ?: return false
                                                    val child = WebView(hostView.context)
                                                    var dispatched = false
                                                    fun dispatchToHost(uri: Uri?) {
                                                        if (dispatched || uri == null) return
                                                        dispatched = true
                                                        val raw = uri.toString()
                                                        if (raw == "about:blank") return
                                                        val internalUrl = sanitizeBuctNavigationUrl(uri)
                                                        if (internalUrl != null) {
                                                            pageError = null
                                                            hostView.loadUrl(
                                                                internalUrl,
                                                                mapOf("Referer" to (hostView.url ?: courseHomeUrl))
                                                            )
                                                        } else {
                                                            runCatching {
                                                                startActivity(Intent(Intent.ACTION_VIEW, uri))
                                                            }
                                                        }
                                                    }
                                                    child.webViewClient = object : WebViewClient() {
                                                        override fun shouldOverrideUrlLoading(
                                                            c: WebView?,
                                                            request: WebResourceRequest?
                                                        ): Boolean {
                                                            dispatchToHost(request?.url)
                                                            c?.post { runCatching { c.destroy() } }
                                                            return true
                                                        }

                                                        override fun onPageStarted(
                                                            c: WebView?,
                                                            url: String?,
                                                            favicon: Bitmap?
                                                        ) {
                                                            val parsed = url?.let { runCatching { Uri.parse(it) }.getOrNull() }
                                                            dispatchToHost(parsed)
                                                            c?.post { runCatching { c.destroy() } }
                                                        }
                                                    }
                                                    transport.webView = child
                                                    resultMsg.sendToTarget()
                                                    return true
                                                }

                                                override fun onJsAlert(
                                                    view: WebView?,
                                                    url: String?,
                                                    message: String?,
                                                    result: JsResult?
                                                ): Boolean {
                                                    if (isFinishing || isDestroyed) {
                                                        result?.cancel()
                                                        return true
                                                    }
                                                    var settled = false
                                                    AlertDialog.Builder(this@TheolSubmitWebViewActivity)
                                                        .setTitle("页面提示")
                                                        .setMessage(message.orEmpty())
                                                        .setPositiveButton("确定") { _, _ ->
                                                            if (!settled) {
                                                                settled = true
                                                                result?.confirm()
                                                            }
                                                        }
                                                        .setOnDismissListener {
                                                            if (!settled) {
                                                                settled = true
                                                                result?.cancel()
                                                            }
                                                        }
                                                        .show()
                                                    return true
                                                }

                                                override fun onJsConfirm(
                                                    view: WebView?,
                                                    url: String?,
                                                    message: String?,
                                                    result: JsResult?
                                                ): Boolean {
                                                    if (isFinishing || isDestroyed) {
                                                        result?.cancel()
                                                        return true
                                                    }
                                                    var settled = false
                                                    AlertDialog.Builder(this@TheolSubmitWebViewActivity)
                                                        .setTitle("请确认")
                                                        .setMessage(message.orEmpty())
                                                        .setPositiveButton("确定") { _, _ ->
                                                            if (!settled) {
                                                                settled = true
                                                                result?.confirm()
                                                            }
                                                        }
                                                        .setNegativeButton("取消") { _, _ ->
                                                            if (!settled) {
                                                                settled = true
                                                                result?.cancel()
                                                            }
                                                        }
                                                        .setOnDismissListener {
                                                            if (!settled) {
                                                                settled = true
                                                                result?.cancel()
                                                            }
                                                        }
                                                        .show()
                                                    return true
                                                }

                                                override fun onJsPrompt(
                                                    view: WebView?,
                                                    url: String?,
                                                    message: String?,
                                                    defaultValue: String?,
                                                    result: JsPromptResult?
                                                ): Boolean {
                                                    if (isFinishing || isDestroyed) {
                                                        result?.cancel()
                                                        return true
                                                    }
                                                    val input = EditText(this@TheolSubmitWebViewActivity).apply {
                                                        setText(defaultValue.orEmpty())
                                                    }
                                                    var settled = false
                                                    AlertDialog.Builder(this@TheolSubmitWebViewActivity)
                                                        .setTitle(message.orEmpty().ifBlank { "请输入" })
                                                        .setView(input)
                                                        .setPositiveButton("确定") { _, _ ->
                                                            if (!settled) {
                                                                settled = true
                                                                result?.confirm(input.text?.toString().orEmpty())
                                                            }
                                                        }
                                                        .setNegativeButton("取消") { _, _ ->
                                                            if (!settled) {
                                                                settled = true
                                                                result?.cancel()
                                                            }
                                                        }
                                                        .setOnDismissListener {
                                                            if (!settled) {
                                                                settled = true
                                                                result?.cancel()
                                                            }
                                                        }
                                                        .show()
                                                    return true
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
                                                    pageError = null
                                                    canGoBack = view?.canGoBack() == true
                                                }

                                                override fun onReceivedError(
                                                    view: WebView?,
                                                    request: WebResourceRequest?,
                                                    error: WebResourceError?
                                                ) {
                                                    if (request?.isForMainFrame == true) {
                                                        val desc = error?.description?.toString()?.takeIf { it.isNotBlank() }
                                                            ?: "网络连接异常"
                                                        pageError = "页面加载失败：$desc"
                                                        progress = 100
                                                    }
                                                }

                                                override fun onReceivedHttpError(
                                                    view: WebView?,
                                                    request: WebResourceRequest?,
                                                    errorResponse: WebResourceResponse?
                                                ) {
                                                    val code = errorResponse?.statusCode ?: return
                                                    if (request?.isForMainFrame == true && code >= 400) {
                                                        pageError = when (code) {
                                                            401, 403 -> "页面访问受限（HTTP $code），可点击右上角刷新或进入课程主页"
                                                            404 -> "目标页面不存在（HTTP 404），可点击下方进入课程主页"
                                                            else -> "北化在线返回错误（HTTP $code）"
                                                        }
                                                        progress = 100
                                                    }
                                                }

                                                override fun onRenderProcessGone(
                                                    view: WebView?,
                                                    detail: RenderProcessGoneDetail?
                                                ): Boolean {
                                                    view?.post {
                                                        (view.parent as? ViewGroup)?.removeView(view)
                                                        runCatching { view.destroy() }
                                                        if (webView === view) webView = null
                                                        pageError = "网页渲染进程已重置，请返回后重新进入"
                                                        progress = 100
                                                    }
                                                    return true
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
                                                    val isCourseHome = path.equals(MEOL_COURSE_HOME_PATH, ignoreCase = true)

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
                                                        "(function(){var t=(document.body&&document.body.innerText)||'';return (t.indexOf('Permission Denied')>=0||t.indexOf('登录时间超时')>=0||t.indexOf('请重新登录')>=0||t.indexOf('用户身份错误')>=0||document.querySelector('input[type=password]')!=null);})()"
                                                    ) { rawFlag ->
                                                        if (rawFlag == "true") {
                                                            val sidNow = SidPortalSessionVault.readCookies(this@TheolSubmitWebViewActivity).first
                                                            if (!courseWarmedUp && courseLid.isNotBlank() && !isCourseHome) {
                                                                courseWarmedUp = true
                                                                pendingTargetUrl = targetUrl
                                                                view.loadUrl(courseHomeUrl, mapOf("Referer" to MEOL_HOME))
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
                                                                    view.loadUrl(courseHomeUrl, mapOf("Referer" to MEOL_HOME))
                                                                } else {
                                                                    pendingTargetUrl = null
                                                                    view.loadUrl(
                                                                        queued,
                                                                        mapOf("Referer" to refererFor(queued, courseHomeUrl))
                                                                    )
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
                                                    if (scheme in setOf("about", "javascript", "data", "blob")) {
                                                        return false
                                                    }
                                                    val rewrittenBuct = sanitizeBuctNavigationUrl(uri)
                                                    if (rewrittenBuct != null) {
                                                        if (!rewrittenBuct.equals(uri.toString(), ignoreCase = false)) {
                                                            view?.loadUrl(rewrittenBuct)
                                                            return true
                                                        }
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

                                            setDownloadListener { downloadUrl, _, contentDisposition, mimetype, _ ->
                                                if (downloadUrl.isNullOrBlank()) return@setDownloadListener
                                                syncCookiesToVault()
                                                val suggestedName = URLUtil.guessFileName(
                                                    downloadUrl,
                                                    contentDisposition,
                                                    mimetype
                                                )
                                                downloadingFileName = suggestedName
                                                downloadedFile = null
                                                pageError = null
                                                scope.launch {
                                                    val res = TheolSyncService.getInstance(this@TheolSubmitWebViewActivity)
                                                        .downloadAttachment(
                                                            url = downloadUrl,
                                                            suggestedName = suggestedName,
                                                            courseLid = courseLid
                                                        )
                                                    downloadingFileName = null
                                                    res.onSuccess { file ->
                                                        if (file.isHtmlPage) {
                                                            downloadedFile = null
                                                            webView?.loadUrl(
                                                                downloadUrl,
                                                                mapOf("Referer" to (webView?.url ?: courseHomeUrl))
                                                            )
                                                        } else {
                                                            downloadedFile = file
                                                            TheolAttachmentOpener.open(
                                                                this@TheolSubmitWebViewActivity,
                                                                file
                                                            )
                                                        }
                                                    }.onFailure { err ->
                                                        pageError = err.message ?: "附件下载失败，请点击重试"
                                                    }
                                                }
                                            }

                                            webView = this
                                            loadUrl(
                                                initialLoadUrl,
                                                mapOf("Referer" to refererFor(initialLoadUrl, courseHomeUrl))
                                            )
                                        }
                                    }
                                )
                            }

                            pageError?.let { msg ->
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    color = MaterialTheme.colorScheme.background
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(24.dp),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.CloudOff,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(44.dp)
                                        )
                                        Spacer(Modifier.height(14.dp))
                                        Text(
                                            text = "页面暂时无法显示",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = msg,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(Modifier.height(18.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Button(
                                                onClick = {
                                                    pageError = null
                                                    val view = webView
                                                    if (view == null) {
                                                        finish()
                                                    } else if (view.url.isNullOrBlank()) {
                                                        view.loadUrl(
                                                            initialLoadUrl,
                                                            mapOf("Referer" to refererFor(initialLoadUrl, courseHomeUrl))
                                                        )
                                                    } else {
                                                        view.reload()
                                                    }
                                                }
                                            ) {
                                                Text("重试")
                                            }
                                            if (courseLid.isNotBlank()) {
                                                OutlinedButton(
                                                    onClick = {
                                                        pageError = null
                                                        pendingTargetUrl = null
                                                        courseWarmedUp = true
                                                        webView?.loadUrl(
                                                            courseHomeUrl,
                                                            mapOf("Referer" to MEOL_HOME)
                                                        )
                                                    }
                                                ) {
                                                    Text("进入课程主页")
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            downloadingFileName?.let { fileName ->
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.94f)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(24.dp),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                                        Spacer(Modifier.height(14.dp))
                                        Text(
                                            text = "正在下载附件…",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = fileName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }

                            downloadedFile?.let { file ->
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    color = MaterialTheme.colorScheme.background
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(24.dp),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Description,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(44.dp)
                                        )
                                        Spacer(Modifier.height(14.dp))
                                        Text(
                                            text = "附件已下载",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = "${file.fileName}（${TheolAttachmentOpener.formatFileSize(file.sizeBytes)}）",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(Modifier.height(18.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Button(
                                                onClick = {
                                                    if (!TheolAttachmentOpener.open(this@TheolSubmitWebViewActivity, file)) {
                                                        downloadedFile = null
                                                        pageError = "未找到可打开「${file.fileName}」的应用"
                                                    }
                                                }
                                            ) {
                                                Text("打开文件")
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    if (!TheolAttachmentOpener.share(this@TheolSubmitWebViewActivity, file)) {
                                                        downloadedFile = null
                                                        pageError = "无法调用系统分享或保存界面"
                                                    }
                                                }
                                            ) {
                                                Text("分享 / 另存")
                                            }
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        TextButton(onClick = { downloadedFile = null }) {
                                            Text("继续浏览")
                                        }
                                    }
                                }
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

    private fun sanitizeTargetUrl(rawUrl: String, encodedLid: String, courseHomeUrl: String): String {
        val candidate = normalizeHttpsUrl(rawUrl.ifBlank { courseHomeUrl })
        val parsed = runCatching { Uri.parse(candidate) }.getOrNull() ?: return courseHomeUrl
        val host = parsed.host.orEmpty()
        val path = parsed.encodedPath.orEmpty()
        if (host.equals("course.buct.edu.cn", ignoreCase = true)) {
            if (path.equals(MEOL_LEGACY_HOME_PATH, ignoreCase = true)) {
                return MEOL_HOME
            }
            if (path.equals(MEOL_LEGACY_TEST_START_PATH, ignoreCase = true)) {
                return if (encodedLid.isNotBlank()) {
                    "https://course.buct.edu.cn$MEOL_TEST_LIST_PATH?sortColumn=createTime&status=1&tagbug=client&sortDirection=-1&strStyle=new03&cateId=$encodedLid&pagingPage=1&pagingNumberPer=30"
                } else {
                    MEOL_HOME
                }
            }
        }
        return candidate
    }

    private fun sanitizeBuctNavigationUrl(uri: Uri): String? {
        val scheme = uri.scheme.orEmpty().lowercase()
        val host = uri.host.orEmpty().lowercase()
        if (scheme !in setOf("http", "https")) return null
        if (!(host.endsWith(".buct.edu.cn") || host == "buct.edu.cn")) return null
        val path = uri.encodedPath.orEmpty()
        return when {
            host == "course.buct.edu.cn" && path.equals(MEOL_LEGACY_HOME_PATH, ignoreCase = true) -> MEOL_HOME
            scheme == "http" && host == "course.buct.edu.cn" -> uri.buildUpon().scheme("https").build().toString()
            else -> uri.toString()
        }
    }

    private fun refererFor(url: String, courseHomeUrl: String): String = when {
        url.contains(MEOL_COURSE_HOME_PATH, ignoreCase = true) ||
            url.contains("/meol/welcomepage/", ignoreCase = true) -> MEOL_HOME
        url.contains("/meol/common/question/test/", ignoreCase = true) -> courseHomeUrl
        else -> HW_TASK_REFERER
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
                val eq = kv.indexOf('=')
                if (eq > 0) {
                    val name = kv.substring(0, eq).trim()
                    cm.setCookie("https://course.buct.edu.cn/meol/", "$kv; Path=/meol")
                    if (!name.equals("JSESSIONID", ignoreCase = true)) {
                        cm.setCookie("https://course.buct.edu.cn/", "$kv; Path=/")
                    }
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

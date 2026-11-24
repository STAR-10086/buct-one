package com.buct.course

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import com.buct.course.login.BuctWebVpn
import com.buct.course.login.WebLoginNavigation
import com.buct.one.data.session.SidPortalSessionVault
import com.buct.course.ui.screen.WebLoginAddressBar
import com.buct.course.ui.system.GlassPageScaffold
import com.buct.course.ui.system.SystemIconButton
import com.buct.course.ui.system.SystemDialog
import com.buct.course.ui.system.SystemPrimaryButton
import com.buct.course.ui.system.SystemSecondaryButton
import com.buct.course.ui.theme.CourseSelectorTheme

class CookieWebViewActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.buct.course.manager.AppThemeCoordinator.wrapContext(newBase))
    }

    companion object {
        const val EXTRA_COOKIE_RESULT = "cookie_result"
        const val EXTRA_SEARCH_KEYWORD = "search_keyword"
        const val EXTRA_INITIAL_URL = "initial_url"
        const val EXTRA_WEBVPN_MODE = "webvpn_mode"

        internal fun syncBuctAuthCookies(
            context: android.content.Context,
            allowVpnTicket: Boolean
        ): String {
            return runCatching {
                val cm = CookieManager.getInstance()
                cm.flush()
                val sidCookie = cm.getCookie("https://sidportal.buct.edu.cn/login")
                    ?.split(';')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() && !it.startsWith("rememberMe=") }
                    ?.joinToString("; ")
                    .orEmpty()
                if (sidCookie.contains("SOURCEID_TGC=", ignoreCase = true)) {
                    SidPortalSessionVault.saveSidPortalCookies(context.applicationContext, sidCookie)
                    BuctWebVpn.updateSidCookies(sidCookie)
                }

                if (!allowVpnTicket) return@runCatching ""
                val vpnCookies = cm.getCookie(BuctWebVpn.VPN_BASE).orEmpty()
                val ticket = vpnCookies.split(';')
                    .map { it.trim() }
                    .firstNotNullOfOrNull { seg ->
                        val eq = seg.indexOf('=')
                        if (eq <= 0) return@firstNotNullOfOrNull null
                        val name = seg.substring(0, eq).trim()
                        val value = seg.substring(eq + 1).trim()
                        if (name.equals(BuctWebVpn.VPN_COOKIE_NAME, ignoreCase = true) &&
                            value.isNotEmpty() &&
                            !value.equals("deleteMe", ignoreCase = true)
                        ) {
                            value
                        } else {
                            null
                        }
                    }.orEmpty()
                if (ticket.isNotEmpty()) {
                    BuctWebVpn.updateTicket(ticket)
                }
                ticket
            }.getOrDefault("")
        }
    }

    private fun closeWithCancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val searchKeyword = intent.getStringExtra(EXTRA_SEARCH_KEYWORD) ?: "教务系统"
        val initialUrl = intent.getStringExtra(EXTRA_INITIAL_URL).orEmpty()
        val webVpnMode = intent.getBooleanExtra(EXTRA_WEBVPN_MODE, false)

        setContent {
            CourseSelectorTheme {
                CookieWebViewScreen(
                    initialSearchKeyword = searchKeyword,
                    initialUrl = initialUrl,
                    webVpnMode = webVpnMode,
                    onCookieExtracted = { cookie ->
                        syncBuctAuthCookies(this@CookieWebViewActivity, allowVpnTicket = webVpnMode)
                        val resultIntent = Intent().apply {
                            putExtra(EXTRA_COOKIE_RESULT, cookie)
                        }
                        setResult(RESULT_OK, resultIntent)
                        finish()
                    },
                    onBack = { closeWithCancel() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CookieWebViewScreen(
    initialSearchKeyword: String,
    initialUrl: String = "",
    webVpnMode: Boolean = false,
    onCookieExtracted: (String) -> Unit,
    onBack: () -> Unit
) {
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var pageTitle by remember { mutableStateOf("加载中...") }
    var isLoading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var showTutorial by remember { mutableStateOf(!webVpnMode) }
    var canGoBack by remember { mutableStateOf(false) }
    var showCookieDialog by remember { mutableStateOf(false) }
    var extractedCookie by remember { mutableStateOf("") }
    val searchUrl = remember(initialSearchKeyword, initialUrl) {
        initialUrl.ifBlank { WebLoginNavigation.searchUrl(initialSearchKeyword) }
    }
    val context = LocalContext.current

    DisposableEffect(Unit) {
        onDispose { webView?.apply { stopLoading(); destroy() }; webView = null }
    }

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }

    // Cookie extraction function - improved to get all cookies
    fun extractCookie(): String {
        val cookieManager = CookieManager.getInstance()
        val url = currentUrl.ifEmpty { searchUrl }
        
        return try {
            val allCookies = mutableSetOf<String>()
            
            // 1. Get cookie for current full URL
            cookieManager.getCookie(url)?.let { cookie ->
                if (cookie.isNotEmpty()) {
                    cookie.split(";").forEach { part ->
                        allCookies.add(part.trim())
                    }
                }
            }
            
            // 2. Extract domain and try multiple domain patterns
            val cleanUrl = url.replace("https://", "").replace("http://", "")
            val fullDomain = cleanUrl.split("/")[0]
            
            // Try full domain (e.g., www.example.edu.cn)
            cookieManager.getCookie(fullDomain)?.let { cookie ->
                if (cookie.isNotEmpty()) {
                    cookie.split(";").forEach { part ->
                        allCookies.add(part.trim())
                    }
                }
            }
            
            // Try with http/https prefix
            cookieManager.getCookie("http://$fullDomain")?.let { cookie ->
                if (cookie.isNotEmpty()) {
                    cookie.split(";").forEach { part ->
                        allCookies.add(part.trim())
                    }
                }
            }
            
            cookieManager.getCookie("https://$fullDomain")?.let { cookie ->
                if (cookie.isNotEmpty()) {
                    cookie.split(";").forEach { part ->
                        allCookies.add(part.trim())
                    }
                }
            }
            
            // 3. Try root domain (e.g., example.edu.cn from www.example.edu.cn)
            val parts = fullDomain.split(".")
            if (parts.size > 2) {
                val rootDomain = parts.takeLast(3).joinToString(".")
                cookieManager.getCookie(rootDomain)?.let { cookie ->
                    if (cookie.isNotEmpty()) {
                        cookie.split(";").forEach { part ->
                            allCookies.add(part.trim())
                        }
                    }
                }
                
                // Also try with dot prefix for domain cookies
                cookieManager.getCookie(".$rootDomain")?.let { cookie ->
                    if (cookie.isNotEmpty()) {
                        cookie.split(";").forEach { part ->
                            allCookies.add(part.trim())
                        }
                    }
                }
            }
            
            // Combine all unique cookies
            allCookies.filter { it.contains("=") }.joinToString("; ")
        } catch (e: Exception) {
            ""
        }
    }

    GlassPageScaffold(
        title = if (webVpnMode) "WebVPN 网页认证" else "网页登录",
        subtitle = if (webVpnMode) {
            "完成统一身份认证（含短信验证）后点击同步"
        } else {
            currentUrl.let { android.net.Uri.parse(it).host }.orEmpty().ifBlank { "打开学校教务系统" }
        },
        onBack = { if (canGoBack) webView?.goBack() else onBack() },
        actions = {
            SystemIconButton(Icons.Default.Refresh, "刷新网页", { webView?.reload() })
            if (!webVpnMode) {
                SystemIconButton(Icons.AutoMirrored.Filled.Help, "登录帮助", { showTutorial = true })
            }
        },
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SystemPrimaryButton(
                    text = if (webVpnMode) "同步 WebVPN 登录状态" else "使用当前登录状态",
                    onClick = {
                        if (webVpnMode) {
                            val uri = android.net.Uri.parse(currentUrl)
                            val host = uri.host.orEmpty().lowercase()
                            val path = uri.encodedPath.orEmpty()
                            val allowVpn = host.equals(BuctWebVpn.VPN_HOST, ignoreCase = true) &&
                                !path.startsWith("/login")
                            val syncedTicket = CookieWebViewActivity.syncBuctAuthCookies(
                                context,
                                allowVpnTicket = allowVpn
                            )
                            if (allowVpn && syncedTicket.isNotEmpty()) {
                                onCookieExtracted(syncedTicket)
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    "请先完成统一身份认证登录并等待跳转回 WebVPN 首页后再同步",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else {
                            extractedCookie = extractCookie()
                            if (extractedCookie.isNotEmpty()) showCookieDialog = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = if (webVpnMode) "登录跳转至 WebVPN 首页后，将自动或点击按钮同步认证状态" else "请先在网页中登录学校教务账号",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                WebLoginAddressBar(currentUrl, { input ->
                    val url = WebLoginNavigation.resolveInput(input)
                    if (url != null) webView?.loadUrl(url)
                    else android.widget.Toast.makeText(context, "请输入网址或搜索关键词", android.widget.Toast.LENGTH_SHORT).show()
                }, { webView?.loadUrl(searchUrl) })
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            // WebView
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        settings.apply {
                            com.buct.course.manager.AppThemeCoordinator.preserveWebContentColors(this)
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            defaultTextEncodingName = "UTF-8"
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            userAgentString = "Mozilla/5.0 (Linux; Android 10; SM-G981B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0.3987.162 Mobile Safari/537.36"
                        }

                        // Enable cookies
                        val webViewInstance = this
                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setAcceptThirdPartyCookies(webViewInstance, true)
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                                currentUrl = url ?: ""
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                currentUrl = url ?: ""
                                canGoBack = view?.canGoBack() ?: false

                                // Flush cookies and sync authenticated BUCT/WebVPN tokens
                                CookieManager.getInstance().flush()
                                val uri = android.net.Uri.parse(url.orEmpty())
                                val host = uri.host.orEmpty().lowercase()
                                val path = uri.encodedPath.orEmpty()
                                val isVpnAuthenticatedPage =
                                    host.equals(BuctWebVpn.VPN_HOST, ignoreCase = true) &&
                                        !path.startsWith("/login")
                                if (isVpnAuthenticatedPage || host == "jwglxt.buct.edu.cn") {
                                    CookieWebViewActivity.syncBuctAuthCookies(
                                        context,
                                        allowVpnTicket = isVpnAuthenticatedPage
                                    )
                                }
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                super.onReceivedTitle(view, title)
                                pageTitle = title ?: "加载中..."
                            }
                        }

                        loadUrl(searchUrl)
                        webView = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Loading indicator
            androidx.compose.animation.AnimatedVisibility(
                visible = isLoading,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            }
        }
    }

    // Tutorial Dialog
    if (showTutorial) {
        SystemDialog(
            onDismissRequest = { showTutorial = false },
            backdrop = null,
            useVisualEffects = false,
            icon = {
                Icon(
                    Icons.Default.School,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
            },
            title = {
                Text(
                    "使用教程",
                    fontWeight = FontWeight.Bold
                )
            },
            confirmButton = {
                SystemPrimaryButton(
                    text = "我知道了",
                    onClick = { showTutorial = false },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            TutorialStep(1, "搜索你的学校教务系统")
            TutorialStep(2, "点击搜索结果进入教务网站")
            TutorialStep(3, "输入学号和密码登录")
            TutorialStep(4, "登录成功后，点击下方「获取 Cookie」按钮")
            TutorialStep(5, "Cookie 将自动填充到登录框")

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Lightbulb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "确保登录成功后再获取 Cookie",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }

    // Cookie Result Dialog
    if (showCookieDialog) {
        SystemDialog(
            onDismissRequest = { showCookieDialog = false },
            backdrop = null,
            useVisualEffects = false,
            icon = {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(48.dp)
                )
            },
            title = {
                Text(
                    "使用登录状态",
                    fontWeight = FontWeight.Bold
                )
            },
            confirmButton = {
                SystemPrimaryButton(
                    text = "返回应用登录",
                    onClick = {
                        showCookieDialog = false
                        onCookieExtracted(extractedCookie)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                SystemSecondaryButton(
                    text = "继续浏览",
                    onClick = { showCookieDialog = false },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            Text(
                text = "确认已登录学校教务账号后，返回应用完成登录。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TutorialStep(number: Int, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(28.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primary
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

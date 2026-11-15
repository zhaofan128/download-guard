package com.dlguard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.dlguard.DownloadGuardApp
import com.dlguard.core.Formatters
import com.dlguard.core.LinkSniffer
import com.dlguard.ui.theme.DownloadGuardTheme

/**
 * 网页嗅探页：很多下载站的「下载」按钮是 JS 绑定，长按也复制不出地址。
 * 这里用 WebView 把页面真跑起来，三条路同时抓：
 *
 *  - [android.webkit.DownloadListener]：页面自己发起下载时，拿到最终 URL；
 *  - [WebViewClient.shouldOverrideUrlLoading]：脚本跳转到 .apk 时拦下来；
 *  - 注入 [LinkSniffer.SCAN_SCRIPT]：扫 DOM 里的 a[href] / data-* / onclick，
 *    把页面上所有可疑直链列成候选清单。
 *
 * 确认后直接进 48 线程引擎，不需要用户复制任何东西。
 */
class SniffActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PAGE_URL = "extra_page_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val pageUrl = intent.getStringExtra(EXTRA_PAGE_URL).orEmpty()
        setContent {
            DownloadGuardTheme(
                darkTheme = isSystemInDarkTheme(),
                dynamicColor = false,
                lowRamDevice = false
            ) {
                if (pageUrl.isBlank()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("没有收到网页地址", style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    SniffScreen(
                        pageUrl = pageUrl,
                        onClose = { finish() },
                        onStartDownload = { url, name, referer ->
                            // 下载站常校验来源页/登录态：把 WebView 当前的 Referer 与 Cookie
                            // 一并交给下载引擎，否则直链会直接 403
                            val cookie = runCatching {
                                CookieManager.getInstance().getCookie(referer)
                            }.getOrNull()
                            (application as? DownloadGuardApp)?.container?.downloadCenter
                                ?.start(url, name, referer = referer, cookie = cookie)
                            finish()
                        }
                    )
                }
            }
        }
    }
}

private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SniffScreen(
    pageUrl: String,
    onClose: () -> Unit,
    onStartDownload: (String, String?, String) -> Unit
) {
    val context = LocalContext.current
    var currentUrl by remember { mutableStateOf(pageUrl) }
    var title by remember { mutableStateOf(pageUrl.substringAfter("://").substringBefore('/')) }
    var progress by remember { mutableStateOf(0.08f) }
    var loading by remember { mutableStateOf(true) }
    var listExpanded by remember { mutableStateOf(true) }
    val candidatesState = remember { mutableStateOf<List<LinkSniffer.Candidate>>(emptyList()) }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    var pendingInfo by remember { mutableStateOf<LinkSniffer.Probe?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // 站点用 window.open 弹下载页时不让它开新窗口，强制在当前 WebView 内导航，
                // 这样才拦得到最终地址
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = MOBILE_UA
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    loading = true
                    progress = 0.08f
                    pageError = null
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    // 只处理主文档失败；子资源（广告/统计）失败不该挡住整页
                    if (request.isForMainFrame) {
                        pageError = error.description?.toString()?.takeIf { it.isNotBlank() }
                            ?: "页面加载失败"
                        loading = false
                    }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    loading = false
                    progress = 1f
                    currentUrl = url ?: currentUrl
                    // 动态按钮通常晚于 onPageFinished 渲染，延迟再扫一次
                    view.postDelayed({
                        view.evaluateJavascript(LinkSniffer.SCAN_SCRIPT) { raw ->
                            val found = LinkSniffer.parseJsResult(currentUrl, raw)
                            if (found.isNotEmpty()) candidatesState.value = found
                        }
                    }, LinkSniffer.SCAN_DELAY_MS)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val target = request.url.toString()
                    if (LinkSniffer.looksLikeFile(target)) {
                        pendingUrl = target
                        return true
                    }
                    return false
                }
            }
            setWebChromeClient(object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress / 100f
                    if (newProgress >= 100) loading = false
                }

                override fun onReceivedTitle(view: WebView, newTitle: String?) {
                    if (!newTitle.isNullOrBlank()) title = newTitle
                }
            })
            // 页面点了「下载」：这里拿到的就是浏览器本来要下的地址
            setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    pendingUrl = url
                    if (contentDisposition.isNotBlank()) {
                        pendingInfo = LinkSniffer.Probe(
                            finalUrl = url,
                            fileName = contentDisposition.substringAfter("filename=", "")
                                .trim().trim('"').takeIf { it.isNotBlank() },
                            size = -1,
                            contentType = mimeType.orEmpty()
                        )
                    }
                }
            }
            loadUrl(pageUrl)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onClose()
    }

    // 拿到待确认地址后解析真实文件名与大小（HEAD，失败不影响下载）
    LaunchedEffect(pendingUrl) {
        val target = pendingUrl ?: return@LaunchedEffect
        if (pendingInfo?.fileName != null && pendingInfo?.size != -1L) return@LaunchedEffect
        resolving = true
        val info = LinkSniffer.probe(target)
        if (pendingUrl == target) {
            pendingInfo = info ?: pendingInfo
            resolving = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(52.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "←",
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clickable(onClick = onClose)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp)
            )
            Text(
                text = "↻",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier
                    .clickable {
                        candidatesState.value = emptyList()
                        webView.reload()
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
            Text(
                text = "↗",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier
                    .clickable {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        // 细进度条：不依赖 M3 版本差异
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }

        Box(Modifier.weight(1f)) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize()
            )
            pageError?.let { message ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(28.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "页面打不开",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { pageError = null; webView.reload() }) {
                        Text("重试")
                    }
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))
                        }
                    }) {
                        Text("用浏览器打开")
                    }
                }
            }
        }

        if (candidatesState.value.isEmpty() && !loading && pageError == null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                tonalElevation = 1.dp
            ) {
                Text(
                    text = "点页面里的「下载」按钮即可，无需复制链接",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(vertical = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        if (candidatesState.value.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { listExpanded = !listExpanded }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "发现 ${candidatesState.value.size} 个下载链接",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (listExpanded) "收起" else "展开",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (listExpanded) {
                        LazyColumn(Modifier.heightIn(max = 250.dp)) {
                            items(candidatesState.value, key = { it.url }) { candidate ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { pendingUrl = candidate.url }
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = candidate.text.ifBlank {
                                            candidate.url.substringAfterLast('/').ifBlank { "下载链接" }
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = candidate.url,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(0.5.dp)
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                )
                            }
                        }
                    }
                    Spacer(Modifier.navigationBarsPadding())
                }
            }
        }
    }

    val target = pendingUrl
    if (target != null) {
        AlertDialog(
            onDismissRequest = {
                pendingUrl = null
                pendingInfo = null
            },
            title = {
                Text(if (resolving) "正在解析链接" else "用 48 线程加速下载")
            },
            text = {
                Column {
                    if (resolving) {
                        Text("正在获取真实文件名与大小…", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(
                            text = pendingInfo?.fileName ?: target.substringAfterLast('/').ifBlank { "未知文件" },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = buildString {
                                val size = pendingInfo?.size ?: -1L
                                append(if (size > 0) Formatters.bytes(size) else "大小未知")
                                val type = pendingInfo?.contentType.orEmpty()
                                if (type.isNotBlank()) append(" · ").append(type.substringBefore(';'))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = target,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !resolving,
                    onClick = {
                        onStartDownload(target, pendingInfo?.fileName, currentUrl)
                        pendingUrl = null
                        pendingInfo = null
                    }
                ) {
                    Text("开始下载")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingUrl = null
                    pendingInfo = null
                }) {
                    Text("取消")
                }
            }
        )
    }
}

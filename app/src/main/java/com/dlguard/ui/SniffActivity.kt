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
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.JavascriptInterface
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
 *    把页面上所有可疑直链列成候选清单；
 *  - [WebViewClient.shouldInterceptRequest]：从**网络层**看 XHR / Fetch，
 *    抓接口返回、DOM 里根本没有的直链。
 *
 * 另外顺手把页面里的官方 SHA-256 / MD5 抓出来，作为下载后的校验基准候选。
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
                        onStartDownload = { url, name, referer, expectedHash ->
                            // 下载站常校验来源页/登录态：把 WebView 当前的 Referer 与 Cookie
                            // 一并交给下载引擎，否则直链会直接 403
                            val cookie = runCatching {
                                CookieManager.getInstance().getCookie(referer)
                            }.getOrNull()
                            // 页面链接已被重写成网关地址，下载前必须还原，否则代理会打到网关自己
                            val realUrl = com.dlguard.relay.RelayUrls.extractOriginal(url) ?: url
                            val realReferer = com.dlguard.relay.RelayUrls.extractOriginal(referer) ?: referer
                            (application as? DownloadGuardApp)?.container?.downloadCenter
                                ?.start(
                                    realUrl, name,
                                    referer = realReferer,
                                    cookie = cookie,
                                    expectedHash = expectedHash
                                )
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
    onStartDownload: (String, String?, String, String?) -> Unit
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
    // 网络层抓到的候选：shouldInterceptRequest 在后台线程回调，必须用并发容器
    val wireCandidates = remember {
        java.util.concurrent.ConcurrentHashMap<String, LinkSniffer.Candidate>()
    }
    var pageHashes by remember { mutableStateOf<List<LinkSniffer.HashHint>>(emptyList()) }
    var selectedHash by remember { mutableStateOf<LinkSniffer.HashHint?>(null) }

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
            // 接口嗅探的落点：页面 JS 把 fetch/XHR 的响应体送进来，这里解析成候选地址。
            // 只认 @JavascriptInterface 注解的方法，未注解的一律不可见。
            addJavascriptInterface(object {
                @JavascriptInterface
                fun onPayload(text: String?, baseUrl: String?) {
                    if (text.isNullOrBlank()) return
                    val base = baseUrl?.takeIf { it.isNotBlank() } ?: pageUrl
                    val hits = LinkSniffer.extractUrlsFromPayload(text, base)
                    if (hits.isEmpty()) return
                    hits.forEach { wireCandidates[it.url] = it }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        candidatesState.value = LinkSniffer.mergeCandidates(
                            currentUrl, candidatesState.value, wireCandidates.values
                        )
                    }
                }
            }, "DGJSON")

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    loading = true
                    progress = 0.08f
                    pageError = null
                    // 尽早埋点：对「打开就自动拉接口再跳转」的站点，晚了就抓不到那次响应
                    view.evaluateJavascript(LinkSniffer.PAYLOAD_SCRIPT, null)
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
                    // 主文档可能已经重建，window 上的钩子随之丢失；幂等脚本再挂一次，
                    // 用户随后点「下载」触发的 XHR/fetch 就都能被读到
                    view.evaluateJavascript(LinkSniffer.PAYLOAD_SCRIPT, null)
                    // 动态按钮通常晚于 onPageFinished 渲染，延迟再扫一次
                    view.postDelayed({
                        view.evaluateJavascript(LinkSniffer.SCAN_SCRIPT) { raw ->
                            val dom = LinkSniffer.parseJsResult(currentUrl, raw)
                            // DOM 与网络层两路合并：网络层抓到的地址页面元素里往往没有
                            val merged = LinkSniffer.mergeCandidates(
                                currentUrl, dom, wireCandidates.values
                            )
                            if (merged.isNotEmpty()) candidatesState.value = merged
                        }
                    }, LinkSniffer.SCAN_DELAY_MS)
                    // 校验值常写在页面底部或详情里，比按钮渲染更晚，再等一会儿
                    view.postDelayed({
                        view.evaluateJavascript(LinkSniffer.TEXT_SCRIPT) { raw ->
                            val text = LinkSniffer.parseJsText(raw)
                            if (text.isNotBlank()) {
                                val hints = LinkSniffer.extractHashes(text)
                                if (hints.isNotEmpty()) pageHashes = hints
                            }
                        }
                    }, LinkSniffer.SCAN_DELAY_MS + 600)
                }

                /**
                 * 网络层拦截：页面用 fetch/XHR 拉回来的东西在这里过一遍。
                 *
                 * 只观察、不改变任何请求——始终返回 null，页面该怎么加载还怎么加载。
                 * 拿到的是**请求**信息（没有响应头），所以用 URL 后缀 + Accept 头判断，
                 * 命中的再交给用户的候选列表，由 [LinkSniffer.probe] 真正确认。
                 */
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    if (!request.isForMainFrame) {
                        runCatching {
                            val target = request.url.toString()
                            val accept = request.requestHeaders["Accept"].orEmpty()
                            if (LinkSniffer.looksLikeWireDownload(target, accept, request.method)) {
                                wireCandidates[target] = LinkSniffer.Candidate(
                                    url = target,
                                    text = target.substringAfterLast('/').take(80),
                                    score = 0,
                                    origin = LinkSniffer.ORIGIN_WIRE
                                )
                            }
                        }
                    }
                    return null
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
                // 接力模式：WebView 拿到的是网关的 /file?url=…，要换回原始地址再交给下载器
                val realUrl = com.dlguard.relay.RelayUrls.extractOriginal(url) ?: url
                if (realUrl.startsWith("http://") || realUrl.startsWith("https://")) {
                    pendingUrl = realUrl
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
            loadUrl(com.dlguard.relay.RelayUrls.browsingUrl(pageUrl))
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

    // 网络层候选是边浏览边冒出来的，定时并进列表，不用等整页加载完
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_200)
            if (wireCandidates.isEmpty()) continue
            val merged = LinkSniffer.mergeCandidates(
                currentUrl, candidatesState.value, wireCandidates.values
            )
            if (merged.size > candidatesState.value.size) candidatesState.value = merged
        }
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
                                        .clickable {
                                            pendingUrl = candidate.url
                                            selectedHash = null
                                        }
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = candidate.text.ifBlank {
                                                candidate.url.substringAfterLast('/')
                                                    .ifBlank { "下载链接" }
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        val kind = LinkSniffer.streamKind(candidate.url)
                                        val badge = when {
                                            kind != null -> kind.label
                                            candidate.origin == LinkSniffer.ORIGIN_WIRE -> "网络层"
                                            else -> null
                                        }
                                        if (badge != null) {
                                            Text(
                                                text = badge,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (kind != null) {
                                                    MaterialTheme.colorScheme.error
                                                } else {
                                                    MaterialTheme.colorScheme.primary
                                                },
                                                modifier = Modifier.padding(start = 8.dp)
                                            )
                                        }
                                    }
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
                selectedHash = null
            },
            title = {
                Text(if (resolving) "正在解析链接" else "加入下载队列")
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
                        // 流媒体：HLS 现在能真下（点播 + 标准 AES-128），其余仍然只能交给专用工具
                        val stream = LinkSniffer.streamKind(target)
                        if (stream != null) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = when (stream) {
                                    LinkSniffer.StreamKind.HLS_PLAYLIST ->
                                        "这是 HLS 播放列表（m3u8）。可以直接下载：按分片顺序拼成一个 .ts 文件，" +
                                            "标准 AES-128 加密会自动解密；直播流与 DRM（SAMPLE-AES）会明确拒绝。"
                                    LinkSniffer.StreamKind.DASH_MANIFEST ->
                                        "这是 DASH 清单（mpd），内置下载器还不支持，建议用 ffmpeg 之类的工具。"
                                    LinkSniffer.StreamKind.SEGMENT ->
                                        "这是流媒体分片，单独下只有几秒内容。要完整视频请选同目录下的 .m3u8 播放列表。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (stream == LinkSniffer.StreamKind.HLS_PLAYLIST) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                        }
                        // 页面里写着的官方校验值：只作为候选，默认不选。
                        // 自动采纳等于让页面同时控制文件与校验基准，反而失去意义。
                        if (pageHashes.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "页面里发现 ${pageHashes.size} 个校验值，下载完可自动比对",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            Spacer(Modifier.height(4.dp))
                            pageHashes.take(3).forEach { hint ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedHash = if (selectedHash == hint) null else hint
                                        }
                                        .padding(vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${hint.algorithm}  ${hint.value.take(20)}…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = if (selectedHash == hint) "已选用" else "选用",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selectedHash == hint) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !resolving,
                    onClick = {
                        onStartDownload(
                            target,
                            pendingInfo?.fileName,
                            currentUrl,
                            selectedHash?.let { "${it.algorithm}:${it.value}" }
                        )
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
                    selectedHash = null
                }) {
                    Text("取消")
                }
            }
        )
    }
}

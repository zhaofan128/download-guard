package com.dlguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.dlguard.data.CapturedLinks
import com.dlguard.shizuku.ShizukuHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 浏览器无障碍爬取服务。
 *
 * 抓取逻辑（都是真实事件，不做猜测）：
 *  1. TYPE_VIEW_CLICKED / TYPE_VIEW_LONG_CLICKED：浏览器里点击/长按超链接时，
 *     AccessibilityNodeInfo.getUrl() 直接给出目标 URL（Chrome/Edge/Via 等内核支持）；
 *     拿不到 url 时再回退读节点与祖先节点的 text。
 *  2. TYPE_WINDOW_STATE_CHANGED：页面切换时对节点树做一次轻量扫描，只扫含 http 的文本，
 *     且有 2 秒节流——避免每个页面都全量遍历造成卡顿。
 *
 * 只有 URL 指向明确的文件直链（安装包/压缩包/文档/音视频）才会：
 * 入 CapturedLinks + 发「一键极速下载」通知。
 * 普通浏览行为完全不记录（本服务没有存储浏览历史的路径，这是隐私上的硬保证）。
 *
 * v1.9.0 起支持「跨应用」：微信/QQ/钉钉等内嵌 WebView 里的下载页也能抓，
 * 但边界严格收窄——只处理用户**主动点击**的节点（不含定时全窗扫描），
 * 只认文件扩展名白名单，且必须由用户在设置里显式开启（默认关闭）。
 *
 * 保活：服务被杀/被关后，由 MonitorService 的 20 秒心跳配合 Shizuku
 * （PrivilegeOps.enableSelfAccessibility）把系统开关写回来。
 */
class DownloadAccessibilityService : AccessibilityService() {

    companion object {
        /** 主流浏览器与内置 WebView 容器包名。 */
        val BROWSER_PACKAGES = setOf(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
            "com.microsoft.emmx", "com.microsoft.emmx.beta",
            "org.mozilla.firefox", "org.mozilla.fenix", "org.mozilla.focus",
            "com.opera.browser", "com.opera.mini.native",
            "com.UCMobile", "com.UCMobile.intl", "com.quark.browser",
            "com.tencent.mtt", "com.tencent.mtt.x5",
            "com.baidu.searchbox", "com.baidu.searchbox.lite",
            "com.heytap.browser", "com.mi.globalbrowser", "com.android.browser",
            "com.huawei.browser", "com.sec.android.app.sbrowser",
            "com.vivaldi.browser", "com.brave.browser", "com.kiwibrowser.browser",
            "mark.via.gp", "com.mmbox.browser", "com.ibrowser"
        )

        /**
         * 跨应用抓取的白名单：内嵌 WebView 的社交/网盘/内容平台。
         * 只在这些 App 里、且用户主动点击时工作，不做后台全窗扫描。
         */
        val CROSS_APP_PACKAGES = setOf(
            "com.tencent.mm",            // 微信
            "com.tencent.mobileqq",      // QQ
            "com.tencent.tim",           // TIM
            "com.tencent.wework",        // 企业微信
            "com.alibaba.android.rimet", // 钉钉
            "com.ss.android.lark",       // 飞书
            "com.sina.weibo",            // 微博
            "com.xingin.xhs",            // 小红书
            "com.ss.android.ugc.aweme",  // 抖音
            "com.taobao.taobao",         // 淘宝
            "com.taobao.idlefish",       // 闲鱼
            "com.jingdong.app.mall",     // 京东
            "com.baidu.netdisk",         // 百度网盘
            "com.alicloud.databox",      // 阿里云盘
            "com.quark.browser",         // 夸克（部分版本不在 BROWSER_PACKAGES 命中）
            "com.UCMobile"
        )

        /** 只认这些后缀：足够覆盖下载场景，又不会把普通网页当文件。 */
        private val FILE_EXTENSIONS = setOf(
            "apk", "apks", "xapk", "apkm", "zip", "7z", "rar", "tar", "gz", "bz2", "xz",
            "pdf", "epub", "mobi", "azw3", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "mp4", "mkv", "avi", "mov", "webm", "mp3", "flac", "wav", "m4a", "ape",
            "iso", "img", "bin", "exe", "msi", "dmg", "pkg", "torrent"
        )

        private const val SCAN_THROTTLE_MS = 2_000L
        private const val MAX_SCAN_NODES = 400
        private const val MAX_URL_LEN = 4096
    }

    private lateinit var capturedLinks: CapturedLinks
    private var lastScanAt = 0L
    private var lastUrl: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 跨应用抓取开关：默认关闭，只有用户在设置页显式打开才扩展到社交 App。 */
    @Volatile
    private var crossAppCapture = false

    /** 跨应用点击去重：同一条链接在 8 秒内只提示一次（内嵌页常见一次点击多个事件）。 */
    private var lastCrossCaptureAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        val container = (applicationContext as com.dlguard.DownloadGuardApp).container
        capturedLinks = container.capturedLinks
        scope.launch {
            runCatching {
                container.settings.settings.collect { crossAppCapture = it.crossAppCapture }
            }
        }
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 200
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        val isBrowser = pkg in BROWSER_PACKAGES || pkg.endsWith(".browser") || pkg.contains("browser")
        val isCrossApp = crossAppCapture && pkg in CROSS_APP_PACKAGES
        if (!isBrowser && !isCrossApp) return
        if (isCrossApp && !isBrowser) {
            // 跨应用模式只响应「用户主动点击」，不做窗口切换/内容变化的自动扫描：
            // 既避免读到聊天列表，也避免内嵌页频繁刷新造成耗电。
            if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
                event.eventType != AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
            ) {
                return
            }
            val node = event.source ?: return
            val url = captureUrlFromNode(node)
            if (url != null) {
                val now = System.currentTimeMillis()
                if (now - lastCrossCaptureAt > 8_000L) {
                    lastCrossCaptureAt = now
                    maybeCapture(url, "跨应用")
                }
            }
            return
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                // 点击/长按：先查节点自身与祖先链的文本；很多浏览器会把 href 放进
                // contentDescription 或文本节点，命中即抓；没命中再扫当前窗口树
                // （Chrome 长按链接时底部出现的 URL 预览条就是一个普通文本节点）
                val node = event.source
                if (node != null && !captureFromNode(node)) {
                    scanWindow(force = true)
                } else if (node == null) {
                    scanWindow(force = true)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val now = System.currentTimeMillis()
                if (now - lastScanAt < SCAN_THROTTLE_MS) return
                lastScanAt = now
                scanWindow()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 长按链接后预览条/菜单出现会触发窗口内容变化，节流扫描
                scanWindow()
            }
        }
    }

    override fun onInterrupt() {
        // 无障碍被系统打断（如全屏手势冲突）时不做任何处理，等下一次事件自然恢复
    }

    /**
     * 从点击节点沿祖先链找链接文本。
     * AccessibilityNodeInfo 没有「目标 URL」属性（SDK 确认无 getUrl），
     * 真实可读的是节点的 text 与 contentDescription——多数浏览器会把 href 放在其中之一。
     * 返回 true 表示已捕获，不需要再扫窗口。
     */
    private fun captureFromNode(node: AccessibilityNodeInfo): Boolean {
        val url = captureUrlFromNode(node) ?: return false
        maybeCapture(url, "无障碍")
        return true
    }

    /** 沿节点祖先链找 URL 文本；找不到返回 null（供浏览器与跨应用两条路径复用）。 */
    private fun captureUrlFromNode(node: AccessibilityNodeInfo): String? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 6) {
            val fromText = extractUrlFromText(current.text?.toString())
                ?: extractUrlFromText(current.contentDescription?.toString())
            if (fromText != null) return fromText
            current = current.parent
            hops++
        }
        return null
    }

    /**
     * 页面窗口轻量扫描：只读 text / contentDescription 含 http 的节点，最多 MAX_SCAN_NODES 个。
     */
    private fun scanWindow(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastScanAt < SCAN_THROTTLE_MS) return
        lastScanAt = now
        val root = rootInActiveWindow ?: return
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_SCAN_NODES) {
            val node = queue.removeFirstOrNull() ?: break
            visited++
            val textUrl = extractUrlFromText(node.text?.toString())
                ?: extractUrlFromText(node.contentDescription?.toString())
            if (textUrl != null && isInstallPackageLink(textUrl)) {
                maybeCapture(textUrl, "无障碍扫描")
                return
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
    }

    private fun extractUrlFromText(text: String?): String? {
        if (text.isNullOrBlank() || text.length > MAX_URL_LEN) return null
        val regex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
        val match = regex.find(text) ?: return null
        return match.value.trimEnd('.', ',', '，', '。', ')', '）')
    }

    private fun maybeCapture(url: String, source: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        if (url.length > MAX_URL_LEN) return
        if (!isPackageLink(url)) return
        if (url == lastUrl && System.currentTimeMillis() - lastScanAt < 5_000) return
        lastUrl = url
        lastScanAt = System.currentTimeMillis()
        capturedLinks.record(url, guessFileName(url), source = source)
        // 通知只在 Shizuku 未授权时也照发：链接抓取本身不依赖 Shizuku
        runCatching { NotificationHelper(this).notifyClipboardLink(url) }
    }

    /** 后台扫描只认安装包/压缩包，避免页面上随手提及的 pdf/mp4 也弹通知。 */
    private fun isInstallPackageLink(url: String): Boolean {
        val path = url.lowercase().substringBefore('?').substringBefore('#').substringAfterLast('/')
        return path.substringAfterLast('.', "") in setOf("apk", "apks", "xapk", "apkm", "zip")
    }

    private fun isPackageLink(url: String): Boolean {
        val lower = url.lowercase().substringBefore('?').substringBefore('#')
        val path = lower.substringAfterLast('/')
        val ext = path.substringAfterLast('.', "")
        if (ext.isNotEmpty() && ext in FILE_EXTENSIONS) return true
        // 下载站常见的无扩展名直链：/download?id=… /soft/123 之类
        if (path.isNotBlank() && !path.contains('.') && path.contains("apk")) return true
        return lower.contains("/download") && lower.contains("apk")
    }

    private fun guessFileName(url: String): String? =
        url.substringAfterLast('/').substringBefore('?').takeIf { it.contains('.') }
}

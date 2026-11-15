package com.dlguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.dlguard.data.CapturedLinks
import com.dlguard.shizuku.ShizukuHelper

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
 * 只有 URL 指向 .apk/.apks/.xapk/.zip 直链才会：入 CapturedLinks + 发「一键极速下载」通知。
 * 普通浏览行为完全不记录（本服务没有存储浏览历史的路径，这是隐私上的硬保证）。
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

        private const val SCAN_THROTTLE_MS = 2_000L
        private const val MAX_SCAN_NODES = 400
        private const val MAX_URL_LEN = 4096
    }

    private lateinit var capturedLinks: CapturedLinks
    private var lastScanAt = 0L
    private var lastUrl: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        capturedLinks = (applicationContext as com.dlguard.DownloadGuardApp).container.capturedLinks
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
        if (!isBrowser) return
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
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 6) {
            val fromText = extractUrlFromText(current.text?.toString())
                ?: extractUrlFromText(current.contentDescription?.toString())
            if (fromText != null) {
                maybeCapture(fromText, "无障碍")
                return true
            }
            current = current.parent
            hops++
        }
        return false
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
            if (textUrl != null && isPackageLink(textUrl)) {
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

    private fun isPackageLink(url: String): Boolean {
        val lower = url.lowercase().substringBefore('?')
        if (lower.endsWith(".apk") || lower.endsWith(".apks") ||
            lower.endsWith(".xapk") || lower.endsWith(".zip")
        ) return true
        val path = lower.substringAfterLast('/')
        return path.contains("apk")
    }

    private fun guessFileName(url: String): String? =
        url.substringAfterLast('/').substringBefore('?').takeIf { it.contains('.') }
}

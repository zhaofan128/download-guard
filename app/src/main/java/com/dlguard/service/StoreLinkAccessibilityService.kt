package com.dlguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.dlguard.data.CapturedLinks

/**
 * 商店版「浏览器下载链接识别」。
 *
 * 这份代码同时是审核材料，能力边界写得越死越好：
 *  - **只在浏览器里工作**：包名白名单不含微信、QQ、钉钉、微博、抖音等任何聊天/社交应用
 *  - **只订阅点击与长按**：不订阅页面切换（typeWindowStateChanged）、不订阅内容变化
 *    （typeWindowContentChanged），系统层面就没有「持续读取屏幕」的事件来源
 *  - **只在点击回调里读一次**：用户点了才读被点节点及其祖先的文本，最多 120 个节点，
 *    不做定时全窗遍历，不缓存页面内容
 *  - **不做任何自动化**：未申请 canPerformGestures，不会点击、滑动、输入、修改他人界面
 *  - **不碰输入框**：只读 text 与 contentDescription，密码框内容在无障碍树里本就是掩码
 *  - **不落盘不上传**：命中的链接只进内存候选列表，由用户点「下载」才真正开始下载
 *
 * 它能做的事只有一件：用户在浏览器里点了一个 .apk / .zip / .pdf 这类下载链接，
 * 把这一条链接的地址交给下载器，省掉「长按 → 复制链接 → 切回 App → 粘贴」。
 */
class StoreLinkAccessibilityService : AccessibilityService() {

    companion object {
        /**
         * 当前实例。用于在 App 内关开关时直接 disableSelf()，
         * 用户不必再跑一趟系统设置。清空点见 onUnbind / onDestroy。
         */
        @Volatile
        private var instance: StoreLinkAccessibilityService? = null

        /** 正在运行则自我停用并返回 true；未运行返回 false（调用方据此决定要不要跳设置）。 */
        fun disableSelfIfRunning(): Boolean {
            val service = instance ?: return false
            return runCatching { service.disableSelf() }.isSuccess
        }

        /**
         * 浏览器与浏览器内核容器。
         * 刻意只列浏览器：任何社交/办公/内容类应用都不在这里，这是与完整版最大的区别。
         */
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

        /** 只认这些后缀：覆盖下载场景，又不会把普通网页当文件。 */
        private val FILE_EXTENSIONS = setOf(
            "apk", "apks", "xapk", "apkm", "zip", "7z", "rar", "tar", "gz", "bz2", "xz",
            "pdf", "epub", "mobi", "azw3", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "mp4", "mkv", "avi", "mov", "webm", "mp3", "flac", "wav", "m4a", "ape",
            "iso", "img", "bin", "exe", "msi", "dmg", "pkg", "torrent"
        )

        private const val MAX_NODES = 120
        private const val MAX_URL_LEN = 4096
        private const val DEDUP_WINDOW_MS = 5_000L
    }

    private lateinit var capturedLinks: CapturedLinks
    private var lastUrl: String? = null
    private var lastCaptureAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        capturedLinks = (applicationContext as com.dlguard.DownloadGuardApp).container.capturedLinks
        // 事件类型与 res/xml/accessibility_service_config_store.xml 保持一致：
        // 只订阅点击类事件，页面切换/内容变化一概不收。
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 300
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
        ) {
            return
        }
        val pkg = event.packageName?.toString() ?: return
        if (!isBrowser(pkg)) return

        val node = event.source
        if (node != null && captureFromNode(node)) return
        // 点击处没带 URL 时，唯一的一次补救：读当前窗口找长按弹出的链接预览条。
        // 只在这次点击回调内执行，有节点上限，不排队、不重试。
        scanActiveWindowOnce()
    }

    override fun onInterrupt() {
        // 系统打断（全屏手势冲突等）时什么都不做，等下一次点击事件自然恢复
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun isBrowser(pkg: String): Boolean =
        pkg in BROWSER_PACKAGES || pkg.endsWith(".browser") || pkg.contains("browser")

    /** 沿点击节点向上找 6 层，读 text / contentDescription 里的 URL。 */
    private fun captureFromNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 6) {
            val url = extractUrl(current.text?.toString())
                ?: extractUrl(current.contentDescription?.toString())
            if (url != null) {
                capture(url)
                return true
            }
            current = current.parent
            hops++
        }
        return false
    }

    /** 单次广度优先，最多 MAX_NODES 个节点，命中即返回。 */
    private fun scanActiveWindowOnce() {
        val root = rootInActiveWindow ?: return
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirstOrNull() ?: break
            visited++
            val url = extractUrl(node.text?.toString())
                ?: extractUrl(node.contentDescription?.toString())
            if (url != null && isDownloadLink(url)) {
                capture(url)
                return
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
    }

    private fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank() || text.length > MAX_URL_LEN) return null
        val match = Regex("https?://\\S+", RegexOption.IGNORE_CASE).find(text) ?: return null
        return match.value.trimEnd('.', ',', '，', '。', ')', '）')
    }

    private fun capture(url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        if (url.length > MAX_URL_LEN) return
        if (!isDownloadLink(url)) return
        val now = System.currentTimeMillis()
        if (url == lastUrl && now - lastCaptureAt < DEDUP_WINDOW_MS) return
        lastUrl = url
        lastCaptureAt = now
        capturedLinks.record(url, guessFileName(url), source = "浏览器")
        runCatching { NotificationHelper(this).notifyClipboardLink(url) }
    }

    private fun isDownloadLink(url: String): Boolean {
        val lower = url.lowercase().substringBefore('?').substringBefore('#')
        val path = lower.substringAfterLast('/')
        val ext = path.substringAfterLast('.', "")
        if (ext.isNotEmpty() && ext in FILE_EXTENSIONS) return true
        // 下载站常见的无扩展名直链：/download?id=… 之类
        return path.isNotBlank() && !path.contains('.') && path.contains("apk")
    }

    private fun guessFileName(url: String): String? =
        url.substringAfterLast('/').substringBefore('?').takeIf { it.contains('.') }
}

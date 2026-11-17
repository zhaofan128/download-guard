package com.dlguard.xposed

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * LSPosed 增强分支（可选，默认不参与构建）：
 *  1. hook DownloadManager.addCompletedDownload —— 在浏览器把文件登记为「下载完成」之前校验，损坏则返回 -1 阻止登记；
 *  2. hook DownloadManager$DownloadReceiver.onReceive —— 若该类在目标进程加载，则在处理完成广播前拦截；
 *  3. 对已知浏览器包内的下载完成类做尽力而为的 hook，类名随版本变化，失败会写日志但不影响宿主。
 *
 * 重要：本模块不做任何「假校验」，校验逻辑与主 App 同源（EOCD + 全 entry CRC32）。
 * 大文件（>200MB）只做 EOCD 检查，避免阻塞宿主主线程导致 ANR。
 */
class GuardHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "[DownloadGuard-Xposed]"

        /** 回传广播发给模块 relay 的包名与确认广播的 action/权限。 */
        private const val RELAY_PACKAGE = "com.dlguard.xposed"
        const val ACTION_TAKEOVER_CONFIRM = "com.dlguard.action.TAKEOVER_CONFIRM"
        private const val SEND_PERMISSION = "com.dlguard.permission.SEND_CAPTURED_URL"
        private const val CONFIRM_TIMEOUT_MS = 10_000L

        /** RelayReceiver 用它校验「广播确实来自宿主进程」，不可私有。 */
        val TARGET_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.tencent.mtt",
            "com.UCMobile",
            "com.baidu.searchbox",
            "com.android.browser",
            "com.miui.browser",
            "com.heytap.browser",
            "com.vivo.browser",
            "com.quark.browser",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.tencent.mobileqq",
            "com.tencent.mm"
        )

        /** 待确认删除的系统下载登记表（顶层确认接收器访问，不可私有）。 */
        @JvmField
        val pendingRemovals = ConcurrentHashMap<Long, PendingRemoval>()
        private val confirmReceiver = HostTakeoverConfirmReceiver()
        private var confirmRegistered = false
        private val confirmHandler = Handler(Looper.getMainLooper())

        data class PendingRemoval(val manager: DownloadManager, val registeredAt: Long)

        fun log(message: String) {
            XposedBridge.log("$TAG $message")
        }

        fun log(error: Throwable) {
            XposedBridge.log("$TAG ${error.message}")
        }

        /**
         * 在宿主进程里动态注册确认接收器（仅一次）。要求发送者持有
         * SEND_CAPTURED_URL 权限：只有同一发布密钥签名的主 App / 模块能发确认广播。
         */
        fun registerConfirmReceiver(context: Context) {
            synchronized(confirmReceiver) {
                if (confirmRegistered) {
                    sweepExpired()
                    return
                }
                val filter = IntentFilter(ACTION_TAKEOVER_CONFIRM)
                runCatching {
                    if (Build.VERSION.SDK_INT >= 33) {
                        context.applicationContext.registerReceiver(
                            confirmReceiver, filter, SEND_PERMISSION, null, Context.RECEIVER_EXPORTED
                        )
                    } else {
                        context.applicationContext.registerReceiver(
                            confirmReceiver, filter, SEND_PERMISSION, null
                        )
                    }
                    confirmRegistered = true
                    // 10 秒后清理未确认的登记：确认广播早该到了
                    confirmHandler.postDelayed({
                        sweepExpired()
                        if (pendingRemovals.isEmpty()) {
                            runCatching { context.applicationContext.unregisterReceiver(confirmReceiver) }
                            confirmRegistered = false
                        }
                    }, CONFIRM_TIMEOUT_MS)
                    log("已注册接管确认接收器")
                }.onFailure { log("注册确认接收器失败：${it.message}") }
            }
        }

        fun sweepExpired() {
            val now = SystemClock.elapsedRealtime()
            pendingRemovals.entries.removeIf { now - it.value.registeredAt > CONFIRM_TIMEOUT_MS }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName !in TARGET_PACKAGES) return
        log("注入 ${lpparam.packageName} (${lpparam.processName})")
        hookAddCompletedDownload(lpparam)
        hookDownloadReceiver(lpparam)
        hookWebViewDownloadListener(lpparam)
        hookDownloadManagerEnqueue(lpparam)
        hookBrowserCandidates(lpparam)
    }

    private fun hookAddCompletedDownload(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.app.DownloadManager",
                lpparam.classLoader,
                "addCompletedDownload",
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
                Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val path = param.args.getOrNull(4) as? String ?: return
                        val started = SystemClock.elapsedRealtime()
                        val failure = QuickIntegrity.check(path)
                        if (failure != null) {
                            param.result = -1L
                            log("阻止登记损坏安装包：$path → $failure (${SystemClock.elapsedRealtime() - started}ms)")
                        }
                    }
                }
            )
            log("已 hook DownloadManager.addCompletedDownload")
        }.onFailure { log("addCompletedDownload hook 失败：${it.message}") }
    }

    private fun hookDownloadReceiver(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val clazz = XposedHelpers.findClass(
                "android.app.DownloadManager\$DownloadReceiver",
                lpparam.classLoader
            )
            XposedBridge.hookAllMethods(clazz, "onReceive", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val context = param.args.getOrNull(0) as? Context ?: return
                    val intent = param.args.getOrNull(1) as? android.content.Intent ?: return
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (id <= 0L) return
                    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
                    val cursor = runCatching {
                        manager.query(DownloadManager.Query().setFilterById(id))
                    }.getOrNull() ?: return
                    cursor.use { c ->
                        if (!c.moveToFirst()) return
                        val statusIndex = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        if (statusIndex < 0) return
                        if (c.getInt(statusIndex) != DownloadManager.STATUS_SUCCESSFUL) return
                        val uriIndex = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                        if (uriIndex < 0) return
                        val localUri = c.getString(uriIndex) ?: return
                        val path = runCatching { Uri.parse(localUri).path }.getOrNull() ?: return
                        val failure = QuickIntegrity.check(path)
                        if (failure != null) {
                            param.result = null
                            log("拦截下载完成广播：$path → $failure")
                        }
                    }
                }
            })
            log("已 hook DownloadManager\$DownloadReceiver.onReceive")
        }.onFailure { log("DownloadReceiver hook 跳过：${it.message}") }
    }

    /**
     * 最稳定的「拿到原始 URL」的 hook 点：`WebView.setDownloadListener`。
     * 国产浏览器绝大多数用 WebView 内核，下载开始时会回调 `DownloadListener.onDownloadStart`，
     * 这里把原 listener 包一层，先把 URL/文件名回传给主 App，再原样转发，不改变宿主行为。
     *
     * 回传走模块 relay（见 [RelayReceiver]）：宿主进程没有 signature 权限，直接发
     * 给主 App 会被系统丢弃。WebView 路径标记 kind=webview——浏览器内置下载器
     * 无法从 Xposed 层取消，主 App 只把它记进候选列表，不会自动建任务（否则同
     * 一个文件会被下两遍）。
     */
    private fun hookWebViewDownloadListener(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.webkit.WebView",
                lpparam.classLoader,
                "setDownloadListener",
                "android.webkit.DownloadListener",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val original = param.args.getOrNull(0) as? android.webkit.DownloadListener ?: return
                        val context = (param.thisObject as? android.webkit.WebView)?.context ?: return
                        if (original is CapturingDownloadListener) return
                        val view = param.thisObject as? android.webkit.WebView
                        param.args[0] = CapturingDownloadListener(
                            context,
                            original,
                            lpparam.packageName
                        ).also { it.webView = view }
                    }
                }
            )
            log("已 hook WebView.setDownloadListener")
        }.onFailure { log("WebView 下载监听 hook 失败：${it.message}") }
    }

    /** 把下载开始时的 URL/文件名回传主 App（经模块 relay），然后原样调用宿主 listener。 */
    private class CapturingDownloadListener(
        private val context: Context,
        private val delegate: android.webkit.DownloadListener,
        private val hostPackage: String
    ) : android.webkit.DownloadListener {

        override fun onDownloadStart(
            url: String?,
            userAgent: String?,
            contentDisposition: String?,
            mimetype: String?,
            contentLength: Long
        ) {
            runCatching {
                // 页面地址就是最可靠的 Referer：网盘直链几乎都校验它
                val pageUrl = (webView?.url ?: "").takeIf { it.startsWith("http") }
                report(url, contentDisposition, userAgent, pageUrl, contentLength, mimetype)
            }
            delegate.onDownloadStart(url, userAgent, contentDisposition, mimetype, contentLength)
        }

        var webView: android.webkit.WebView? = null

        private fun report(
            url: String?,
            contentDisposition: String?,
            userAgent: String?,
            referer: String?,
            contentLength: Long,
            mime: String?
        ) {
            if (url.isNullOrBlank()) return
            if (!url.startsWith("http://") && !url.startsWith("https://")) return
            val name = parseFileName(contentDisposition) ?: url.substringAfterLast('/').substringBefore('?')
            val intent = Intent(RelayReceiver.ACTION_RELAY)
                .setPackage(RELAY_PACKAGE)
                .putExtra("url", url)
                .putExtra("name", name.take(200))
                .putExtra("source", "Xposed·WebView")
                .putExtra("referer", referer?.take(4096) ?: "")
                .putExtra("userAgent", userAgent?.take(512) ?: "")
                .putExtra("mime", mime?.take(120) ?: "")
                .putExtra("length", contentLength)
                .putExtra("kind", "webview")
                .putExtra("hostPackage", hostPackage)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            runCatching { context.sendBroadcast(intent) }
                .onFailure { log("WebView 回传广播失败：${it.message}") }
        }

        private fun parseFileName(contentDisposition: String?): String? {
            if (contentDisposition.isNullOrBlank()) return null
            val star = Regex("filename\\*\\s*=\\s*UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(contentDisposition)
            if (star != null) {
                return runCatching { java.net.URLDecoder.decode(star.groupValues[1].trim(), "UTF-8") }.getOrNull()
            }
            val plain = Regex("filename\\s*=\\s*\"?([^\";]+)", RegexOption.IGNORE_CASE).find(contentDisposition)
            return plain?.groupValues?.get(1)?.trim()
        }
    }

    /**
     * 接管系统下载器：网盘 App 里点「下载」很多是直接走 DownloadManager，不经过 WebView
     * 的 DownloadListener。这里在 enqueue 之后把请求的 URI 与请求头回传主 App（经模块
     * relay，宿主没有 signature 权限），并在宿主进程里动态注册一个确认接收器：
     * 主 App 开启「自动接收」且成功建任务后回发确认广播，本模块收到才删除系统那份
     * 下载——没确认绝不删，避免文件凭空消失。
     */
    private fun hookDownloadManagerEnqueue(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.app.DownloadManager",
                lpparam.classLoader,
                "enqueue",
                "android.app.DownloadManager\$Request",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val id = param.result as? Long ?: return
                            if (id <= 0) return
                            val request = param.args.getOrNull(0) ?: return
                            val uri = XposedHelpers.callMethod(request, "getUri") as? Uri ?: return
                            val url = uri.toString()
                            if (!url.startsWith("http://") && !url.startsWith("https://")) return
                            @Suppress("UNCHECKED_CAST")
                            val headers = runCatching {
                                XposedHelpers.callMethod(request, "getHeaders") as? Map<String, String>
                            }.getOrNull().orEmpty()
                            // AOSP 的 DownloadManager.Request 只有私有 mContext，没有公开 getContext()；
                            // 先反射字段，拿不到再退 ActivityThread.currentApplication()，两个都拿不到就不发广播
                            val context = runCatching {
                                XposedHelpers.getObjectField(request, "mContext") as? Context
                            }.getOrNull() ?: runCatching {
                                XposedHelpers.callStaticMethod(
                                    Class.forName("android.app.ActivityThread", false, lpparam.classLoader),
                                    "currentApplication"
                                ) as? Context
                            }.getOrNull()
                            if (context == null) {
                                log("DownloadManager.enqueue 命中但拿不到 Context（mContext/currentApplication 均失败），仅日志不接管")
                                return
                            }
                            val title = runCatching {
                                XposedHelpers.callMethod(request, "getTitle") as? String
                            }.getOrNull()
                            val intent = Intent(RelayReceiver.ACTION_RELAY)
                                .setPackage(RELAY_PACKAGE)
                                .putExtra("url", url)
                                .putExtra("name", (title ?: "").take(200))
                                .putExtra("source", "Xposed·DownloadManager")
                                .putExtra("referer", (headers["Referer"] ?: headers["referer"] ?: "").take(4096))
                                .putExtra("userAgent", (headers["User-Agent"] ?: headers["user-agent"] ?: "").take(512))
                                .putExtra("cookie", (headers["Cookie"] ?: headers["cookie"] ?: "").take(8192))
                                .putExtra("kind", "dm")
                                .putExtra("hostPackage", lpparam.packageName)
                                .putExtra("dlId", id)
                                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                            runCatching { context.sendBroadcast(intent) }
                                .onFailure { log("DownloadManager 回传广播失败：${it.message}") }

                            // 注册确认接收器并登记这个 id：主 App 确认建任务成功后才 remove。
                            // 宿主进程被杀就收不到确认，系统下载保留——宁可下两遍，不能丢文件。
                            val manager = param.thisObject as? DownloadManager ?: return
                            registerConfirmReceiver(context)
                            pendingRemovals[id] = PendingRemoval(manager, SystemClock.elapsedRealtime())
                            log("接管系统下载 id=$id url=${url.take(80)}")
                        }
                    }
                }
            )
            log("已 hook DownloadManager.enqueue")
        }.onFailure { log("DownloadManager.enqueue hook 失败：${it.message}") }
    }

    /** 浏览器自有下载器类名随版本变化，这里只做候选尝试，失败不影响宿主。 */
    private fun hookBrowserCandidates(lpparam: XC_LoadPackage.LoadPackageParam) {
        val candidates = listOf(
            "com.tencent.mtt.download.DownloadReceiver",
            "com.tencent.mtt.browser.download.DownloadCompleteReceiver",
            "com.UCMobile.download.DownloadCompleteReceiver",
            "com.baidu.searchbox.download.DownloadReceiver",
            "com.android.browser.download.DownloadCompleteReceiver",
            "com.miui.browser.download.DownloadCompleteReceiver"
        )
        candidates.forEach { name ->
            runCatching {
                val clazz = XposedHelpers.findClass(name, lpparam.classLoader)
                XposedBridge.hookAllMethods(clazz, "onReceive", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val intent = param.args.getOrNull(1) as? android.content.Intent ?: return
                        val uri = intent.data ?: return
                        val path = uri.path ?: return
                        val failure = QuickIntegrity.check(path)
                        if (failure != null) {
                            param.result = null
                            log("浏览器下载完成回调拦截：$path → $failure")
                        }
                    }
                })
                log("已 hook 浏览器候选类 $name")
            }
        }
    }
}

/**
 * 主 App 确认「已成功建任务」后，删除系统下载器里的那一份。
 * 必须是顶层类：BroadcastReceiver 由系统实例化，内部类拿不到 GuardHook 的实例成员。
 */
class HostTakeoverConfirmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GuardHook.ACTION_TAKEOVER_CONFIRM) return
        val dlId = intent.getLongExtra("dlId", -1L)
        if (dlId <= 0L) return
        val pending = GuardHook.pendingRemovals.remove(dlId) ?: return
        runCatching { pending.manager.remove(dlId) }
            .onSuccess { GuardHook.log("主 App 已接管，删除系统下载 id=$dlId") }
            .onFailure { GuardHook.log("删除系统下载 id=$dlId 失败：${it.message}") }
    }
}

/** 与主 App 同源的轻量校验：EOCD 结构 + 全 entry CRC32（大文件降级为仅 EOCD）。 */
internal object QuickIntegrity {

    private val EXTENSIONS = setOf("apk", "apks", "xapk", "zip")
    private const val MAX_CRC_SIZE = 200L * 1024 * 1024

    fun check(path: String): String? {
        val file = File(path)
        if (!file.exists() || !file.isFile) return null
        val ext = file.name.substringAfterLast('.', "").lowercase()
        if (ext !in EXTENSIONS) return null
        if (file.length() <= 0L) return "空文件"
        if (!hasEocd(file)) return "缺少 EOCD，文件被截断"
        if (file.length() > MAX_CRC_SIZE) return null
        return try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                val buffer = ByteArray(64 * 1024)
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val crc = CRC32()
                    var total = 0L
                    zip.getInputStream(entry).use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            crc.update(buffer, 0, read)
                            total += read
                        }
                    }
                    if (entry.size >= 0 && total != entry.size) {
                        return "条目截断 ${entry.name}"
                    }
                    if (entry.crc >= 0 && crc.value != entry.crc) {
                        return "CRC 错误 ${entry.name}"
                    }
                }
                null
            }
        } catch (t: Throwable) {
            "ZIP 解析异常 ${t.message}"
        }
    }

    private fun hasEocd(file: File): Boolean {
        val length = file.length()
        if (length < 22) return false
        val scan = minOf(length, 22L + 65535).toInt()
        val buffer = ByteArray(scan)
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(length - scan)
                raf.readFully(buffer)
            }
            var index = scan - 22
            while (index >= 0) {
                if (buffer[index] == 0x50.toByte() &&
                    buffer[index + 1] == 0x4B.toByte() &&
                    buffer[index + 2] == 0x05.toByte() &&
                    buffer[index + 3] == 0x06.toByte()
                ) {
                    val comment = (buffer[index + 20].toInt() and 0xFF) or
                        ((buffer[index + 21].toInt() and 0xFF) shl 8)
                    if (index + 22 + comment <= scan) return true
                }
                index--
            }
            false
        } catch (_: Exception) {
            false
        }
    }
}

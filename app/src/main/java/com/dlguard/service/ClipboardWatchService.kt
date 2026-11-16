package com.dlguard.service

import android.app.Notification
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import com.dlguard.DownloadGuardApp
import com.dlguard.R
import com.dlguard.data.CapturedLinks
import com.dlguard.data.SettingsStore
import com.dlguard.shizuku.ClipboardBridge
import com.dlguard.shizuku.ShizukuHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 剪贴板常驻监听服务（前台，specialUse）。
 *
 * Android 10+ 系统禁止后台 App 读取剪贴板，两条真实可行的路径都在这里：
 *  1. Shizuku 就绪 → [ClipboardBridge.readViaShizuku] 以 shell uid 走 IClipboard，
 *     后台也能读（这是「特权剪贴板静默监听」的主体）；
 *  2. 无 Shizuku → 降级为普通 ClipboardManager：
 *     Android 9 及以下可直接读；Android 10+ 只有应用恰好在前台窗口时能读到，
 *     读不到就跳过本轮，绝不报错。
 *
 * 轮询周期 3 秒（HandlerThread 自跑，不占主线程）；发现 .apk/.apks/.xapk/.zip 直链时：
 *  - 记入 CapturedLinks（供重新下载按文件名匹配）；
 *  - 发高优先级通知「一键极速下载」（notifyClipboardLink，点击直达下载）。
 *
 * 开关热生效：SettingsStore.clipboardWatch 关闭时 onCommand/轮询里直接 stopSelf，
 * ViewModel 切开关只是改 DataStore + start/stopService，与本服务无同步耦合。
 */
class ClipboardWatchService : Service() {

    companion object {
        const val ACTION_STOP = "com.dlguard.action.STOP_CLIPBOARD_WATCH"
        const val FOREGROUND_ID = 5001
        private const val POLL_INTERVAL_MS = 3_000L
        private const val MAX_TEXT_LEN = 8192

        /** 敏感文本的占位符：只用于「这条已经看过」的去重，不保存原文。 */
        private const val SENSITIVE_MARKER = "\u0000sensitive"

        /** MonitorService 心跳据此判断剪贴板监听是否活着。 */
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var lastText: String? = null
    private lateinit var capturedLinks: CapturedLinks
    private lateinit var settingsStore: SettingsStore
    private var clipboardManager: ClipboardManager? = null

    private val tick = object : Runnable {
        override fun run() {
            pollClipboard()
            handler?.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        capturedLinks = (applicationContext as DownloadGuardApp).container.capturedLinks
        settingsStore = (applicationContext as DownloadGuardApp).container.settings
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        worker = HandlerThread("clipboard-watch").apply { start() }
        handler = Handler(worker!!.looper)
        startAsForeground()
        isRunning = true
        handler?.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler?.removeCallbacksAndMessages(null)
        worker?.quitSafely()
        worker = null
        handler = null
        isRunning = false
        super.onDestroy()
    }

    private fun startAsForeground() {
        val privileged = ShizukuHelper.status.value == ShizukuHelper.Status.READY
        val notification = Notification.Builder(this, NotificationHelper.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_guard)
            .setContentTitle("剪贴板监听中")
            .setContentText(
                if (privileged) "Shizuku 特权通道 · 发现安装包直链会提醒"
                else "普通模式（后台读取受限）· 发现安装包直链会提醒"
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { startForeground(FOREGROUND_ID, notification) }
    }

    private fun pollClipboard() {
        val watchEnabled = runBlocking {
            runCatching { settingsStore.settings.first().clipboardWatch }.getOrDefault(false)
        }
        if (!watchEnabled) {
            stopSelf()
            return
        }
        val text = readClipboard() ?: return
        if (text == lastText) return
        if (text.length > MAX_TEXT_LEN) {
            lastText = null
            return
        }
        // 隐私闸门：密码、验证码、银行卡这类内容一律不进解析链路，
        // 内存里只留一个占位符做去重，原文不保存、不记录、不上报。
        if (looksSensitive(text)) {
            lastText = SENSITIVE_MARKER
            return
        }
        val url = extractApkUrl(text) ?: run {
            lastText = text
            return
        }
        lastText = text
        capturedLinks.record(url, guessFileName(url), source = "剪贴板")
        runCatching { NotificationHelper(this@ClipboardWatchService).notifyClipboardLink(url) }
    }

    private fun readClipboard(): String? {
        // 路径 1：Shizuku 特权通道（shell uid 不受后台 AppOps 限制）
        val privileged = ClipboardBridge.readViaShizuku()
        if (privileged.isSuccess) return privileged.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        // 路径 2：普通 ClipboardManager（前台窗口有效 / Android 9- 一直有效）
        return runCatching {
            clipboardManager?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.text?.toString()?.trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 是不是不该碰的内容。
     *
     * 判据故意宽松：宁可漏记一个下载链接，也不把用户复制的密码留在内存里。
     * 密码管理器、短信验证码、银行卡信息都会命中这些关键词。
     */
    private fun looksSensitive(text: String): Boolean {
        // 没有 http 链接的纯文本本来就不会被使用（链接提取要求有 URL）
        if (!text.contains("http://", true) && !text.contains("https://", true)) return true
        val lower = text.lowercase()
        val markers = listOf(
            "password", "passwd", "密码", "验证码", "verification code",
            "one-time", "one time", "otp", "2fa", "cvv", "security code",
            "private key", "助记词", "seed phrase"
        )
        return markers.any { lower.contains(it) }
    }

    private fun extractApkUrl(text: String): String? {
        val regex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
        return regex.findAll(text)
            .map { it.value.trimEnd('.', ',', '，', '。', ')', '）') }
            .firstOrNull { url -> isPackageLink(url) }
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

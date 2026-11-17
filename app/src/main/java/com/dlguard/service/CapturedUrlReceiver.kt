package com.dlguard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dlguard.DownloadGuardApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * 接收 LSPosed 模块回传的下载链接，并在用户开启「自动接收」时直接建任务。
 *
 * 模块在目标进程里 hook 两处：
 *  1. WebView `DownloadListener` —— 浏览器 / 网盘 App 的内嵌页面点下载；
 *  2. `DownloadManager.enqueue` —— 网盘 App 直接调系统下载器（这条路上拿得到 Cookie）。
 * 回传内容：URL、文件名、来源页、UA、Cookie、长度。
 *
 * 安全取舍：
 *  - 广播是 signature 级权限，只有同一份 keystore 签出的模块能发；
 *  - 即使被伪造，也只影响候选列表；真正建任务要求用户显式打开 autoIntake，
 *    且 URL 必须过 [acceptUrl] 的协议 / 长度 / 扩展名判定；
 *  - Cookie 只在内存里传给下载器，不写进候选列表文件。
 */
class CapturedUrlReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CAPTURED_URL = "com.dlguard.action.CAPTURED_URL"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_UA = "userAgent"
        const val EXTRA_COOKIE = "cookie"
        const val EXTRA_LENGTH = "length"
        const val EXTRA_MIME = "mime"

        /** 同一 URL 在 5 秒内只接一次：WebView 与 DownloadManager 会重复报同一个文件。 */
        private const val DEDUPE_MS = 5_000L
        private val recent = ConcurrentHashMap<String, Long>()

        /** 只接受常见的下载文件类型，避免任意广播把候选列表刷成广告墙。 */
        private val ALLOWED_EXT = setOf(
            "apk", "apks", "xapk", "zip", "7z", "rar", "tar", "gz", "xz", "bz2", "iso",
            "exe", "msi", "dmg", "pkg", "deb", "rpm", "bin", "torrent", "m3u8",
            "mp4", "mkv", "avi", "mov", "flv", "ts", "mp3", "flac", "wav", "m4a",
            "pdf", "epub", "doc", "docx", "xls", "xlsx", "ppt", "pptx"
        )

        /** 纯函数：广播来的 URL 是否值得记录（仍不会自动下载）。 */
        fun acceptUrl(raw: String?): Boolean {
            val url = raw?.trim().orEmpty()
            if (url.isEmpty() || url.length > 4096) return false
            if (!url.startsWith("http://") && !url.startsWith("https://")) return false
            if (url.any { it.isWhitespace() || it.code < 0x20 }) return false
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
            // 带 user:pass@ 的地址通常是钓鱼/凭据回传，不记录
            if (!uri.userInfo.isNullOrEmpty()) return false
            val path = uri.path.orEmpty().lowercase()
            val ext = path.substringAfterLast('.', "")
            return ext in ALLOWED_EXT || path.contains("/download") || path.contains("/down/")
        }

        /** 一次接管的载荷；所有字段都截断，避免广播里塞超长文本。 */
        data class Intake(
            val url: String,
            val fileName: String?,
            val source: String,
            val referer: String?,
            val userAgent: String?,
            val cookie: String?,
            val size: Long
        )

        /**
         * 纯函数：把广播字段整理成载荷；URL 不过 [acceptUrl] 就返回 null。
         *
         * 单独拆出来是为了能在 JVM 单测里覆盖，不需要 Robolectric 和真实 Intent。
         */
        fun buildIntake(
            url: String?,
            name: String?,
            source: String?,
            referer: String?,
            userAgent: String?,
            cookie: String?,
            length: Long
        ): Intake? {
            val u = url?.trim().orEmpty()
            if (!acceptUrl(u)) return null
            fun clean(v: String?, max: Int): String? =
                v?.trim()?.takeIf { it.isNotEmpty() }?.take(max)
            return Intake(
                url = u,
                fileName = clean(name, 200),
                source = clean(source, 48) ?: "Xposed 模块",
                // Referer 必须是 http(s)：否则等于把一个任意字符串塞进请求头
                referer = clean(referer, 4096)?.takeIf { it.startsWith("http") },
                userAgent = clean(userAgent, 512),
                cookie = clean(cookie, 8192),
                size = length.coerceAtLeast(0)
            )
        }

        /** 纯函数：5 秒窗口内同一个 URL 只放行一次。 */
        fun firstInWindow(url: String, now: Long = System.currentTimeMillis()): Boolean {
            val last = recent[url]
            if (last != null && now - last < DEDUPE_MS) return false
            recent[url] = now
            if (recent.size > 400) {
                recent.entries.removeIf { now - it.value > DEDUPE_MS }
            }
            return true
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_CAPTURED_URL) return
        val payload = buildIntake(
            url = intent.getStringExtra(EXTRA_URL),
            name = intent.getStringExtra(EXTRA_NAME),
            source = intent.getStringExtra(EXTRA_SOURCE),
            referer = intent.getStringExtra(EXTRA_REFERER),
            userAgent = intent.getStringExtra(EXTRA_UA),
            cookie = intent.getStringExtra(EXTRA_COOKIE),
            length = intent.getLongExtra(EXTRA_LENGTH, 0L)
        ) ?: return

        val app = context.applicationContext as? DownloadGuardApp ?: return
        app.container.capturedLinks.record(
            url = payload.url,
            fileName = payload.fileName,
            source = payload.source,
            referer = payload.referer,
            userAgent = payload.userAgent,
            cookie = payload.cookie,
            size = payload.size
        )
        Log.i("CapturedUrlReceiver", "收到模块回传链接：${payload.url.take(80)}")

        val auto = runCatching {
            runBlocking { app.container.settings.settings.first().autoIntake }
        }.getOrDefault(false)
        if (!auto) return
        if (!firstInWindow(payload.url)) return

        val added = app.container.downloadCenter.addTask(
            url = payload.url,
            preferredName = payload.fileName,
            referer = payload.referer,
            cookie = payload.cookie
        )
        Log.i("CapturedUrlReceiver", if (added) "已自动加入下载队列" else "自动入队被拒绝")
    }
}

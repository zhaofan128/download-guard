package com.dlguard.data

import android.content.Context
import android.util.Base64
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

data class CapturedLink(
    val url: String,
    val fileName: String?,
    val capturedAt: Long,
    val source: String,
    /** 发起下载的页面地址；网盘直链缺它会 403。 */
    val referer: String? = null,
    /** 捕获时的浏览器 UA，空则用全局设置。 */
    val userAgent: String? = null,
    /**
     * 登录态 Cookie。只在内存里放着，不写进 captured_links.tsv：
     * 这个文件是明文 TSV，写进去等于把账号会话落在磁盘上。
     */
    val cookie: String? = null,
    val size: Long = 0
)

/**
 * 下载链接旁路捕获库。
 *
 * 解决的问题：普通应用无法读取浏览器/网盘的下载数据库，拿不到原始 URL 就只能让用户手输。
 * 这里把三条合法旁路抓到的链接集中存起来，供「重新下载」按文件名匹配：
 *  1. 通知使用权（`NotificationListenerService`）解析浏览器/网盘下载通知里的 http(s) 链接；
 *  2. 分享入口（`ACTION_SEND`）：用户在浏览器长按链接 → 分享到浏览器下载接管；
 *  3. LSPosed 模块 hook 到 WebView `DownloadListener` 后通过广播回传。
 *
 * 匹配是「尽力而为」：只按文件名/URL 尾部比对，命中就自动填链接，不命中仍走手动粘贴，
 * 不会把不相关的 URL 当成来源。
 */
class CapturedLinks(private val context: Context) {

    companion object {
        private const val FILE_NAME = "captured_links.tsv"
        private const val MAX_ENTRIES = 200
        private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }

    private val lock = Any()
    private val entries = CopyOnWriteArrayList<CapturedLink>()
    private val file: File get() = File(context.filesDir, FILE_NAME)

    init {
        runCatching { load() }
    }

    fun record(
        url: String,
        fileName: String?,
        source: String,
        referer: String? = null,
        userAgent: String? = null,
        cookie: String? = null,
        size: Long = 0
    ) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return
        if (trimmed.length > 4096) return
        val name = fileName?.trim()?.takeIf { it.isNotEmpty() }?.take(200)
        synchronized(lock) {
            entries.removeAll { it.url == trimmed }
            entries.add(0, CapturedLink(
                url = trimmed,
                fileName = name,
                capturedAt = System.currentTimeMillis(),
                source = source,
                referer = referer?.takeIf { it.isNotBlank() }?.take(4096),
                userAgent = userAgent?.takeIf { it.isNotBlank() }?.take(512),
                cookie = cookie?.takeIf { it.isNotBlank() }?.take(8192),
                size = size.coerceAtLeast(0)
            ))
            while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
            runCatching { persist() }
        }
    }

    /** 按文件名找最近的链接；找不到返回 null（调用方据此走手动粘贴）。 */
    fun findByFileName(fileName: String): CapturedLink? {
        val now = System.currentTimeMillis()
        val target = normalize(fileName)
        if (target.isEmpty()) return null
        val candidates = entries.filter { now - it.capturedAt <= MAX_AGE_MS }
        candidates.firstOrNull { it.fileName != null && normalize(it.fileName) == target }?.let { return it }
        candidates.firstOrNull {
            val n = it.fileName?.let { name -> normalize(name) } ?: return@firstOrNull false
            n.isNotEmpty() && (n.contains(target) || target.contains(n))
        }?.let { return it }
        return candidates.firstOrNull { link ->
            val tail = link.url.substringAfterLast('/').substringBefore('?')
            val decoded = runCatching { java.net.URLDecoder.decode(tail, "UTF-8") }.getOrDefault(tail)
            decoded.isNotBlank() && normalize(decoded) == target
        }
    }

    fun recent(limit: Int = 20): List<CapturedLink> = entries.take(limit)

    fun clear() {
        synchronized(lock) {
            entries.clear()
            runCatching { file.delete() }
        }
    }

    private fun normalize(name: String): String {
        var n = name.trim().lowercase()
        val dot = n.lastIndexOf('.')
        if (dot > 0) n = n.substring(0, dot)
        n = n.replace(Regex("\\(\\d+\\)$"), "")
        return n.trim()
    }

    private fun load() {
        if (!file.isFile) return
        file.readLines().forEach { line ->
            val parts = line.split('\t')
            if (parts.size < 4) return@forEach
            val time = parts[0].toLongOrNull() ?: return@forEach
            val name = decode(parts[1])
            val url = decode(parts[2]) ?: return@forEach
            val source = parts[3]
            if (System.currentTimeMillis() - time <= MAX_AGE_MS) {
                entries.add(CapturedLink(url, name, time, source))
            }
        }
        entries.sortByDescending { it.capturedAt }
    }

    private fun persist() {
        val text = entries.take(MAX_ENTRIES).joinToString("\n") { entry ->
            listOf(
                entry.capturedAt.toString(),
                encode(entry.fileName ?: ""),
                encode(entry.url),
                entry.source.replace('\t', ' ')
            ).joinToString("\t")
        }
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun encode(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun decode(value: String): String? = runCatching {
        String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8).takeIf { it.isNotEmpty() }
    }.getOrNull()
}

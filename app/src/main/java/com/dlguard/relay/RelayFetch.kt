package com.dlguard.relay

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 网关侧的「网页代抓」。
 *
 * 为什么需要它：无网设备的 WebView 不会走 App 的 OkHttp 代理（Android 没有给
 * WebView 设置代理的公开接口）。所以让 WebView 直接访问网关的 `/fetch?url=…`，
 * 由网关抓页面、把链接重写成网关地址再返回——WebView 只需要能访问局域网。
 *
 * 安全边界：
 *  - 只允许 http/https；
 *  - 目标解析到私网地址时拒绝（防 SSRF：别让人拿你的手机扫你家路由器）；
 *  - 单页上限 6MB，防止一个视频页把内存打爆。
 */
class RelayFetch(private val client: OkHttpClient) {

    companion object {
        private const val MAX_BYTES = 6 * 1024 * 1024
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36 DownloadGuard/Relay"

        private val FILE_EXTENSIONS = setOf(
            "apk", "apks", "xapk", "apkm", "zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz",
            "pdf", "epub", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "mp4", "mkv", "avi", "mov", "webm", "mp3", "flac", "wav", "m4a",
            "iso", "img", "bin", "exe", "msi", "dmg", "pkg", "torrent"
        )
    }

    data class Resource(
        val bytes: ByteArray,
        val contentType: String,
        /** 非空 = 这是文件，UI 应触发下载而不是渲染。 */
        val fileName: String? = null
    )

    fun fetch(rawUrl: String): Resource? {
        if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) return null
        val uri = runCatching { java.net.URI(rawUrl) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (isPrivateHost(host)) return null

        val request = Request.Builder()
            .url(rawUrl)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val message = "网关抓取失败：HTTP ${response.code}".toByteArray()
                    return@use Resource(message, "text/plain; charset=utf-8")
                }
                val body = response.body ?: return@use null
                val type = body.contentType()?.toString() ?: "application/octet-stream"
                val bytes = body.byteStream().readAtMost(MAX_BYTES)

                if (type.contains("text/html", true) || type.contains("application/xhtml", true)) {
                    val html = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
                    val rewritten = rewrite(html, rawUrl, response.request.url.toString())
                    Resource(rewritten.toByteArray(), "text/html; charset=utf-8")
                } else if (looksLikeFile(uri.path ?: "")) {
                    Resource(bytes, type, nameOf(rawUrl))
                } else {
                    Resource(bytes, type)
                }
            }
        }.getOrNull()
    }

    /** 把页面里的链接全部改成网关地址；不改的话 WebView 会去直连外网，无网设备必然失败。 */
    fun rewrite(html: String, baseUrl: String, finalUrl: String = baseUrl): String {
        val doc = Jsoup.parse(html, finalUrl)
        // <base> 会把相对路径解析到外网，必须去掉
        doc.select("base").remove()

        doc.select("a[href]").forEach { element ->
            val absolute = element.absUrl("href").ifBlank { element.attr("href") }
            if (!absolute.startsWith("http")) return@forEach
            element.attr(
                "href",
                if (looksLikeFile(absolute)) {
                    "/file?url=${encode(absolute)}&name=${encode(nameOf(absolute))}"
                } else {
                    "/fetch?url=${encode(absolute)}"
                }
            )
            // 外链新窗口在无网设备上没有意义，统一当前页打开
            element.removeAttr("target")
        }

        doc.select("img[src],script[src],source[src],video[poster]").forEach { element ->
            val attr = when {
                element.hasAttr("src") -> "src"
                element.hasAttr("poster") -> "poster"
                else -> return@forEach
            }
            val absolute = element.absUrl(attr).ifBlank { element.attr(attr) }
            if (absolute.startsWith("http")) element.attr(attr, "/fetch?url=${encode(absolute)}")
        }

        doc.select("link[href]").forEach { element ->
            val absolute = element.absUrl("href").ifBlank { element.attr("href") }
            if (absolute.startsWith("http")) element.attr("href", "/fetch?url=${encode(absolute)}")
        }

        doc.select("form[action]").forEach { element ->
            val absolute = element.absUrl("action").ifBlank { element.attr("action") }
            if (absolute.startsWith("http")) element.attr("action", "/fetch?url=${encode(absolute)}")
        }

        return doc.outerHtml()
    }

    /** 私网/回环一律拒绝：这个接口是给人看网页的，不是给人扫内网的。 */
    fun isPrivateHost(host: String): Boolean {
        if (host.equals("localhost", true)) return true
        val ip = host.removePrefix("[").removeSuffix("]")
        if (ip.contains(':')) return true
        val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return when (parts[0]) {
            10, 127 -> true
            172 -> parts[1] in 16..31
            192 -> parts[1] == 168
            169 -> parts[1] == 254
            else -> false
        }
    }

    fun looksLikeFile(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext.isNotEmpty() && ext in FILE_EXTENSIONS
    }

    private fun nameOf(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        return URLDecoder.decode(path, "UTF-8").ifBlank { "download.bin" }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(32 * 1024)
        var total = 0
        while (total < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - total))
            if (read < 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }
}

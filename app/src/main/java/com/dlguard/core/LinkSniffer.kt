package com.dlguard.core

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONTokener
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 下载链接嗅探器：解决「下载按钮是 JS、根本复制不出链接」的站点。
 *
 * 三条互补路径：
 *  1. [probe] —— 判断一个 URL 到底是网页还是文件（HEAD 看 Content-Type /
 *     Content-Disposition），网页才进 WebView 嗅探，文件直接交给下载引擎；
 *  2. WebView 侧（[SniffActivity]）：`DownloadListener` 捕获页面自己发起的下载、
 *     `shouldOverrideUrlLoading` 捕获脚本跳转、注入 JS 扫描 DOM 里的直链；
 *  3. [parseJsResult] —— 给 JS 扫描结果打分排序，把真正的安装包顶到最前面。
 */
object LinkSniffer {

    /** 一个候选下载链接。 */
    data class Candidate(
        val url: String,
        val text: String,
        val score: Int
    )

    /** 探测结果：最终地址、文件名、大小、类型。 */
    data class Probe(
        val finalUrl: String,
        val fileName: String?,
        val size: Long,
        val contentType: String
    ) {
        /** 是 HTML 页面（需要进 WebView 嗅探），而不是可下载文件。 */
        val isHtml: Boolean
            get() = contentType.contains("text/html", true) ||
                contentType.contains("application/xhtml", true)
    }

    /** 可下载文件的常见后缀（不含 html/php 这类页面后缀）。 */
    private val FILE_EXTENSIONS = listOf(
        "apk", "apks", "xapk", "zip", "7z", "rar", "tar", "gz", "tgz",
        "mp4", "mkv", "avi", "mov", "pdf", "exe", "msi", "dmg", "iso", "bin"
    )

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36 DownloadGuard/1.4"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    /** URL 是否指向一个文件（按后缀粗判）。 */
    fun looksLikeFile(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return FILE_EXTENSIONS.any { path.endsWith(".$it") }
    }

    /** URL 是否更像网页（没有文件后缀）。只用于按钮文案，真正判定以 [probe] 为准。 */
    fun looksLikeWebPage(url: String): Boolean =
        (url.startsWith("http://") || url.startsWith("https://")) && !looksLikeFile(url)

    /** 从一段分享文本里抓出第一个 http(s) 链接。 */
    fun firstUrl(text: String): String? {
        val match = Regex("https?://\\S+", RegexOption.IGNORE_CASE).find(text) ?: return null
        return match.value.trimEnd('.', ',', '，', '。', ')', '）', ']', '】')
    }

    /**
     * 探测 URL 的真实类型：HEAD 优先，服务器不支持 HEAD 时退回 `Range: 0-0` 的 GET。
     * 返回 null 表示网络失败——调用方应当把它当直链直接尝试下载。
     */
    suspend fun probe(url: String): Probe? = withContext(Dispatchers.IO) {
        runCatching {
            var response = client.newCall(
                Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", UA)
                    .build()
            ).execute()

            var type = response.header("Content-Type").orEmpty()
            // 部分站点 HEAD 返回 405 / 空类型，换 Range GET 再探一次
            if (!response.isSuccessful || type.isBlank()) {
                response.close()
                response = client.newCall(
                    Request.Builder()
                        .url(url)
                        .header("Range", "bytes=0-0")
                        .header("User-Agent", UA)
                        .build()
                ).execute()
            }

            response.use { r ->
                val contentType = r.header("Content-Type").orEmpty()
                val disposition = r.header("Content-Disposition").orEmpty()
                val name = fileNameFrom(disposition)
                    ?: fileNameFromUrl(r.request.url.toString())
                val size = r.header("Content-Range")
                    ?.substringAfter('/', "")
                    ?.toLongOrNull()
                    ?: r.header("Content-Length")?.toLongOrNull()
                    ?: -1L
                Probe(
                    finalUrl = r.request.url.toString(),
                    fileName = name,
                    size = size,
                    contentType = contentType
                )
            }
        }.getOrNull()
    }

    /**
     * 解析 WebView 注入脚本的返回值。
     *
     * [raw] 是 `evaluateJavascript` 回传的 JSON 编码字符串，形如 `"[{\"url\":...}]"`，
     * 需要先解一层字符串再当数组解析。
     */
    fun parseJsResult(pageUrl: String, raw: String?): List<Candidate> {
        if (raw.isNullOrBlank() || raw == "null") return emptyList()
        val payload = runCatching {
            (JSONTokener(raw).nextValue() as? String) ?: raw
        }.getOrDefault(raw)
        val array = runCatching { JSONArray(payload) }.getOrNull() ?: return emptyList()

        val seen = LinkedHashMap<String, Candidate>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val rawUrl = obj.optString("url").trim()
            if (rawUrl.isEmpty()) continue
            if (rawUrl.startsWith("javascript:", true) || rawUrl.startsWith("data:")) continue
            val absolute = absolutize(pageUrl, rawUrl) ?: continue
            if (!absolute.startsWith("http://") && !absolute.startsWith("https://")) continue
            if (seen.containsKey(absolute)) continue
            val text = obj.optString("text").trim().take(80)
            seen[absolute] = Candidate(absolute, text, score(absolute, text))
        }
        return seen.values.sortedByDescending { it.score }.take(40)
    }

    /** 注入脚本：扫 a[href] / data-* / onclick 里的直链，返回 JSON 数组。 */
    val SCAN_SCRIPT: String = """
        (function () {
          var exts = /\.(apk|apks|xapk|zip|7z|rar|tar|gz|tgz|mp4|mkv|pdf|exe|msi|dmg|iso)($|[?#])/i;
          var found = [];
          function push(u, t) {
            if (!u) return;
            u = String(u).trim();
            if (!u || u.indexOf('javascript:') === 0 || u.indexOf('data:') === 0) return;
            if (/^https?:\/\//i.test(u) || u.charAt(0) === '/' || u.indexOf('../') === 0) {
              found.push({ url: u, text: (t || '').replace(/\s+/g, ' ').trim().slice(0, 80) });
            }
          }
          var links = document.querySelectorAll('a[href]');
          for (var i = 0; i < links.length; i++) {
            var a = links[i], h = a.getAttribute('href') || '';
            if (exts.test(h) || a.hasAttribute('download')) push(h, a.textContent);
          }
          var attrs = ['data-url', 'data-href', 'data-src', 'data-download', 'data-link'];
          var nodes = document.querySelectorAll('[data-url],[data-href],[data-src],[data-download],[data-link]');
          for (var j = 0; j < nodes.length; j++) {
            for (var k = 0; k < attrs.length; k++) {
              var v = nodes[j].getAttribute(attrs[k]);
              if (v && (exts.test(v) || /down|apk|file/i.test(v))) push(v, nodes[j].textContent);
            }
          }
          var clickables = document.querySelectorAll('[onclick]');
          for (var m = 0; m < clickables.length; m++) {
            var code = clickables[m].getAttribute('onclick') || '';
            var hits = code.match(/['"](https?:\/\/[^'"]+|\/[^'"]*(?:down|apk|file)[^'"]*)['"]/ig) || [];
            for (var n = 0; n < hits.length; n++) {
              push(hits[n].replace(/^['"]|['"]$/g, ''), clickables[m].textContent);
            }
          }
          var seen = {}, out = [];
          for (var p = 0; p < found.length; p++) {
            if (!seen[found[p].url]) { seen[found[p].url] = 1; out.push(found[p]); }
            if (out.length >= 60) break;
          }
          return JSON.stringify(out);
        })()
    """.trimIndent()

    /** 页面加载完成后延迟再扫一次，给动态渲染的按钮留时间。 */
    const val SCAN_DELAY_MS = 900L

    private fun absolutize(base: String, link: String): String? = runCatching {
        URI(base).resolve(link).toString()
    }.getOrNull()

    private fun score(url: String, text: String): Int {
        val path = url.substringBefore('?').lowercase(Locale.ROOT)
        var score = when {
            path.endsWith(".apk") || path.endsWith(".apks") || path.endsWith(".xapk") -> 100
            path.endsWith(".zip") || path.endsWith(".7z") || path.endsWith(".rar") -> 70
            FILE_EXTENSIONS.any { path.endsWith(".$it") } -> 40
            else -> 0
        }
        val full = url.lowercase(Locale.ROOT)
        if (full.contains("download") || full.contains("/down")) score += 30
        if (full.contains("apk")) score += 25
        if (text.contains("下载")) score += 20
        // 广告位/横幅基本都是坑，压到候选末尾
        if (full.contains("ad.") || full.contains("/ads") || full.contains("banner")) score -= 60
        return score
    }

    private fun fileNameFrom(disposition: String): String? {
        if (disposition.isBlank()) return null
        // RFC 5987: filename*=UTF-8''%E4%B8%AD.apk
        val encoded = Regex("filename\\*\\s*=\\s*[^']*''([^;]+)", RegexOption.IGNORE_CASE)
            .find(disposition)?.groupValues?.get(1)
        if (!encoded.isNullOrBlank()) {
            return runCatching { URLDecoder.decode(encoded.trim(), "UTF-8") }.getOrNull()
        }
        val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(disposition)?.groupValues?.get(1)?.trim()
        return plain?.takeIf { it.isNotBlank() }
    }

    private fun fileNameFromUrl(url: String): String? {
        val raw = url.substringBefore('?').substringAfterLast('/').trim()
        if (raw.isBlank()) return null
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        if (!looksLikeFile(decoded)) return null
        return decoded.take(120)
    }
}

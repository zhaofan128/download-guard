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
 *  3. [parseJsResult] —— 给 JS 扫描结果打分排序，把真正的安装包顶到最前面；
 *  4. `shouldInterceptRequest`（[looksLikeWireDownload]）—— 从**网络层**直接看
 *     XHR / Fetch 请求，DOM 里没有的直链（接口返回的 JSON 里的地址）也能抓到。
 */
object LinkSniffer {

    /** 一个候选下载链接。 */
    data class Candidate(
        val url: String,
        val text: String,
        val score: Int,
        /** 来源：DOM = 页面元素扫描，WIRE = 网络层 XHR/Fetch 拦截。 */
        val origin: String = ORIGIN_DOM
    )

    const val ORIGIN_DOM = "dom"
    const val ORIGIN_WIRE = "wire"
    /** 从接口响应体（JSON / 文本）里解析出来的地址。 */
    const val ORIGIN_API = "api"

    /** 流媒体类型：播放列表交给专用工具，分片单独下没有意义。 */
    enum class StreamKind(val label: String) {
        HLS_PLAYLIST("HLS 播放列表"),
        DASH_MANIFEST("DASH 清单"),
        SEGMENT("流媒体分片")
    }

    /** 页面里找到的官方校验值。 */
    data class HashHint(
        val algorithm: String,
        val value: String,
        /** 附近出现的文件名，供 UI 关联展示。 */
        val fileName: String?
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
        "mp4", "mkv", "avi", "mov", "m3u8", "pdf", "exe", "msi", "dmg", "iso", "bin"
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

    /**
     * 流媒体判定。
     *
     * m3u8 是**播放列表**，本身只有几 KB；ts / m4s 是**分片**，单下其中一个只有几秒内容。
     * 从 2.1.0 起 m3u8 交给内置的 HLS 下载器（点播 + 标准 AES-128），
     * mpd / 裸分片仍然只能交给 ffmpeg / N_m3u8DL 之类的专用工具。
     */
    fun streamKind(url: String): StreamKind? {
        val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return when {
            path.endsWith(".m3u8") -> StreamKind.HLS_PLAYLIST
            path.endsWith(".mpd") -> StreamKind.DASH_MANIFEST
            path.endsWith(".ts") || path.endsWith(".m4s") -> StreamKind.SEGMENT
            else -> null
        }
    }

    /**
     * 网络层候选判定：`shouldInterceptRequest` 只能看到**请求**，看不到响应头，
     * 因此用三路信号 —— URL 后缀、Accept 头、是否 XHR。
     *
     * 这是 DOM 扫描的补充：越来越多的下载站把真实地址放在接口返回的 JSON 里，
     * 由 fetch() 拉到 blob 再触发下载，页面上根本没有带 href 的元素。
     */
    fun looksLikeWireDownload(url: String, accept: String, method: String): Boolean {
        if (!url.startsWith("http", true)) return false
        if (method.equals("OPTIONS", true) || method.equals("HEAD", true)) return false
        if (streamKind(url) != null) return true
        if (looksLikeFile(url)) return true
        val a = accept.lowercase(Locale.ROOT)
        return a.contains("application/octet-stream") ||
            a.contains("application/vnd.android.package-archive") ||
            a.contains("application/zip") ||
            a.contains("application/x-7z-compressed")
    }

    /**
     * 从页面文本里提取官方公布的校验值。
     *
     * 打标签的（`SHA256: xxx` / `MD5：xxx`）优先，其次是裸的 64/32 位十六进制串。
     * 只作为**候选**给用户确认，绝不自动当成基准 —— 页面内容本身就是不可信输入，
     * 自动采纳等于让攻击者同时控制文件与校验值。
     */
    fun extractHashes(text: String): List<HashHint> {
        if (text.isBlank()) return emptyList()
        val body = text.take(262_144)
        val out = LinkedHashMap<String, HashHint>()

        fun remember(algo: String, value: String, at: Int) {
            val key = "$algo:${value.lowercase(Locale.ROOT)}"
            if (out.containsKey(key)) return
            // 抓哈希附近 240 字符里的文件名，便于用户确认是同一个文件
            val from = (at - 120).coerceAtLeast(0)
            val to = (at + 120).coerceAtMost(body.length)
            val window = body.substring(from, to)
            val name = Regex("""[\w\u4e00-\u9fa5.\-+()（）\[\]【】 ]{1,80}\.[A-Za-z0-9]{2,5}""")
                .find(window)?.value?.trim()
                ?.takeIf { it.contains('.') && !it.contains(' ') || looksLikeFile(it) }
            out[key] = HashHint(algo, value.lowercase(Locale.ROOT), name)
        }

        val tagged = listOf(
            "SHA-256" to Regex("""(?i)\bSHA-?256\b[^0-9A-Fa-f]{0,16}([0-9A-Fa-f]{64})"""),
            "MD5" to Regex("""(?i)\bMD5\b[^0-9A-Fa-f]{0,16}([0-9A-Fa-f]{32})"""),
            "SHA-1" to Regex("""(?i)\bSHA-?1\b[^0-9A-Fa-f]{0,16}([0-9A-Fa-f]{40})""")
        )
        for ((algo, regex) in tagged) {
            regex.findAll(body).take(6).forEach { m ->
                remember(algo, m.groupValues[1], m.range.first)
            }
        }
        // 裸哈希：没有标签时按长度判断，可信度更低但仍可能是官方值
        Regex("""\b[0-9A-Fa-f]{64}\b""").findAll(body).take(4).forEach { m ->
            remember("SHA-256", m.value, m.range.first)
        }
        Regex("""\b[0-9A-Fa-f]{32}\b""").findAll(body).take(4).forEach { m ->
            remember("MD5", m.value, m.range.first)
        }
        return out.values.take(6)
    }

    /**
     * 合并多路候选：DOM 扫描 + 网络层拦截。
     *
     * 同一地址可能两路都抓到，这时保留**网络层**那条 —— 它是页面真正发起请求的地址，
     * 比 DOM 里的猜测可靠。
     */
    fun mergeCandidates(
        pageUrl: String,
        dom: Collection<Candidate>,
        wire: Collection<Candidate>
    ): List<Candidate> {
        val merged = LinkedHashMap<String, Candidate>()
        dom.forEach { merged[it.url] = it }
        wire.forEach { candidate ->
            val wireScore = score(candidate.url, candidate.text) + 25
            merged[candidate.url] = candidate.copy(
                score = maxOf(wireScore, merged[candidate.url]?.score ?: 0),
                // 接口里解析出来的保留 api 来源，界面上才能说清「这条是从返回体里挖到的」
                origin = if (candidate.origin == ORIGIN_API) ORIGIN_API else ORIGIN_WIRE
            )
        }
        return merged.values.sortedByDescending { it.score }.take(40)
    }

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
            seen[absolute] = Candidate(absolute, text, score(absolute, text), ORIGIN_DOM)
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

    /** 解 evaluateJavascript 回传的 JSON 编码字符串（比 JSONTokener 少一层异常处理）。 */
    fun parseJsText(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching { (JSONTokener(raw).nextValue() as? String) ?: raw }.getOrDefault(raw)
    }

    /** 抓页面可见文本，供 [extractHashes] 提取官方校验值。 */
    const val TEXT_SCRIPT: String = """
        (function () {
          try {
            var t = document.body ? document.body.innerText : '';
            return (t || '').slice(0, 200000);
          } catch (e) { return ''; }
        })()
    """

    /**
     * 接口嗅探：钩住 fetch / XHR，读**响应体**里的下载地址。
     *
     * 为什么需要它：不少下载站页面上没有链接，点「下载」时前端先请求一个接口，
     * 拿到 JSON 里的真实地址再 `location.href = data.url`。`shouldInterceptRequest`
     * 只能看到请求，看不到响应体，这类站点在旧实现里一条候选都抓不到。
     *
     * 约束：
     *  - 只读 json/text 响应，且限制 256KB，避免把视频流整个读进内存；
     *  - `fetch` 用 clone() 读，绝不消费页面自己要用的那个 Response；
     *  - 全程 try/catch，任何一处失败都不影响页面本身。
     */
    val PAYLOAD_SCRIPT: String = """
        (function () {
          if (window.__dgPayloadHooked) return 'already';
          window.__dgPayloadHooked = true;
          function report(text, base) {
            try {
              if (!text || text.length > 262144) return;
              if (!/https?:|\.apk|\.zip|\.exe|download/i.test(text)) return;
              if (window.DGJSON && window.DGJSON.onPayload) {
                window.DGJSON.onPayload(String(text), String(base || location.href));
              }
            } catch (e) {}
          }
          try {
            var of = window.fetch;
            if (of) {
              window.fetch = function () {
                var p = of.apply(this, arguments);
                try {
                  p.then(function (r) {
                    try {
                      var ct = (r.headers && r.headers.get && r.headers.get('content-type')) || '';
                      if (r.clone && /json|text/i.test(ct)) {
                        r.clone().text().then(function (t) { report(t, r.url); }).catch(function () {});
                      }
                    } catch (e) {}
                  }).catch(function () {});
                } catch (e) {}
                return p;
              };
            }
          } catch (e) {}
          try {
            var oo = XMLHttpRequest.prototype.open;
            var os = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function (m, u) {
              try { this.__dgUrl = u; } catch (e) {}
              return oo.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function () {
              var x = this;
              try {
                x.addEventListener('load', function () {
                  try {
                    var ct = x.getResponseHeader('content-type') || '';
                    if (/json|text/i.test(ct)) {
                      report(x.responseText, x.responseURL || x.__dgUrl);
                    }
                  } catch (e) {}
                });
              } catch (e) {}
              return os.apply(this, arguments);
            };
          } catch (e) {}
          return 'ok';
        })()
    """.trimIndent()

    /**
     * 从接口响应体里提取下载地址（纯函数，便于单测）。
     *
     * 处理三种常见形态：
     *  - 标准 JSON：`{"url":"https://x.com/a.apk"}`
     *  - 转义斜杠：`{"url":"https:\/\/x.com\/a.apk"}`（PHP/Java 序列化常见）
     *  - 相对路径：`{"url":"/down/123.apk"}`
     */
    fun extractUrlsFromPayload(text: String, baseUrl: String, limit: Int = 20): List<Candidate> {
        if (text.isBlank() || text.length > 262_144) return emptyList()
        val normalized = text.replace("\\/", "/")
        val pattern = Regex("""(?:https?:)?//[^\s"'<>\\]+|/[A-Za-z0-9_\-./%?=&]*(?:down|apk|file)[A-Za-z0-9_\-./%?=&]*""", RegexOption.IGNORE_CASE)
        val out = LinkedHashMap<String, Candidate>()
        for (match in pattern.findAll(normalized)) {
            val raw = match.value.trimEnd('.', ',', ')', ']', '}')
            val absolute = when {
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("/") -> absolutize(baseUrl, raw) ?: continue
                else -> raw
            }
            if (!absolute.startsWith("http", true)) continue
            // 只保留「像文件」或路径里明确带下载语义的，避免把接口自身的 /api/xxx 也列出来
            if (!looksLikeFile(absolute) && !absolute.contains("download", true) &&
                !absolute.contains("apk", true) && !absolute.contains("/down", true)
            ) continue
            if (out.size >= limit) break
            out.getOrPut(absolute) {
                Candidate(
                    url = absolute,
                    text = absolute.substringAfterLast('/').take(80).ifBlank { "接口返回" },
                    score = score(absolute, "接口返回") + 5,
                    origin = ORIGIN_API
                )
            }
        }
        return out.values.toList()
    }

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
        // 流媒体：单下分片没意义，排在安装包后面
        if (streamKind(url) != null) score -= 45
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

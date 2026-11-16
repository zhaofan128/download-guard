package com.dlguard.integrity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 旁挂哈希文件自动嗅探（Sidecar Hash）。
 *
 * 现实场景：GitHub Release、F-Droid 镜像、大量开源项目会把 `xxx.apk.sha256`（或 `.md5`）
 * 作为独立小文件放在下载地址旁边。用户手里其实有官方校验值，只是懒得复制粘贴——
 * 于是「外部哈希比对」这个能力等于没被用起来。
 *
 * 这里在任务开始、且用户没填校验值时才去试：把 URL 加上常见后缀，GET 前 8KB 解析。
 * 命中就自动当作本次任务的官方基准，下载完成后直接给出「一致 ✓ / 不符 ⚠」。
 *
 * 安全约束（很重要）：
 *  - 只接受**纯文本**响应，HTML 页面一律丢弃（避免把错误页里的 hash 当成校验值）；
 *  - 文本里如果明确写了**别的文件名**，拒绝使用（同目录常有多个产物的校验文件）；
 *  - 只认 32/40/64 位十六进制，且必须是整段独立 token（避免从长串里截一段）。
 */
object SidecarHash {

    data class Found(
        val algorithm: String,
        val hash: String,
        val sourceUrl: String
    )

    private const val MAX_BYTES = 8 * 1024L
    private const val TIMEOUT_MS = 2_500L

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(TIMEOUT_MS * 2, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val sha256 = Regex("(?<![0-9a-fA-F])([0-9a-fA-F]{64})(?![0-9a-fA-F])")
    private val sha1 = Regex("(?<![0-9a-fA-F])([0-9a-fA-F]{40})(?![0-9a-fA-F])")
    private val md5 = Regex("(?<![0-9a-fA-F])([0-9a-fA-F]{32})(?![0-9a-fA-F])")

    /** 候选地址：主流的四种命名习惯。 */
    private fun candidates(url: String): List<String> {
        val clean = url.substringBefore('#')
        val noQuery = clean.substringBefore('?')
        val result = LinkedHashSet<String>()
        result += "$clean.sha256"
        result += "$noQuery.sha256sum"
        result += "$clean.md5"
        result += "$noQuery.sha1"
        // file.apk → file.sha256（部分镜像这么放）
        val dot = noQuery.lastIndexOf('.')
        val slash = noQuery.lastIndexOf('/')
        if (dot > slash + 1) result += noQuery.substring(0, dot) + ".sha256"
        return result.take(4)
    }

    suspend fun discover(url: String, fileName: String): Found? = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@withContext null
        // 总预算 2.5 秒：探测是「顺手做的事」，不能为了它把首包拖成十几秒。
        // 候选里第一个 404 会立刻返回，真正耗时的是「服务器不响应」，所以这里兜总时长。
        withTimeoutOrNull(2_500L) {
            for (candidate in candidates(url)) {
                val text = fetch(candidate) ?: continue
                val found = parse(text, fileName, candidate) ?: continue
                return@withTimeoutOrNull found
            }
            null
        }
    }

    private fun fetch(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/plain, application/octet-stream;q=0.8, */*;q=0.5")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val type = response.header("Content-Type").orEmpty().lowercase()
            if (type.contains("text/html")) return@use null
            val body = response.body ?: return@use null
            if (body.contentLength() > MAX_BYTES) return@use null
            val source = body.source()
            source.request(MAX_BYTES)
            val buffer = source.buffer
            val size = minOf(buffer.size, MAX_BYTES).toInt()
            val bytes = ByteArray(size)
            buffer.read(bytes, 0, size)
            String(bytes, Charsets.UTF_8)
        }
    }.getOrNull()

    /**
     * 解析校验文件文本。
     * 支持：`<hash>  file`、`SHA256 (file) = <hash>`、纯 hash 一行。
     */
    fun parse(text: String?, fileName: String, sourceUrl: String = ""): Found? {
        if (text.isNullOrBlank()) return null
        if (text.contains("<html", true) || text.contains("<!doctype", true)) return null
        val normalized = text.trim()
        // 提到了别的文件名 → 这份校验文件不是给当前文件的，拒绝
        val mentioned = Regex("(?:SHA(?:256|1|512)|MD5)\\s*\\(([^)]+)\\)", RegexOption.IGNORE_CASE)
            .findAll(normalized).map { it.groupValues[1].trim() }.toList() +
            normalized.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    // 只认 `<hash>  <file>` 两段式；`SHA256 (file) = hash` 已由上面的正则覆盖
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size < 2) return@mapNotNull null
                    if (!parts[0].matches(Regex("[0-9a-fA-F]{32,128}"))) return@mapNotNull null
                    parts[1].trimStart('*')
                }
                .toList()
        val target = fileName.substringAfterLast('/')
        if (mentioned.isNotEmpty() && mentioned.none { it.equals(target, ignoreCase = true) }) {
            return null
        }
        val (algorithm, match) = when {
            sha256.containsMatchIn(normalized) -> "SHA-256" to sha256.find(normalized)
            sha1.containsMatchIn(normalized) -> "SHA-1" to sha1.find(normalized)
            md5.containsMatchIn(normalized) -> "MD5" to md5.find(normalized)
            else -> return null
        }
        val hash = match?.groupValues?.getOrNull(1)?.lowercase() ?: return null
        return Found(algorithm, hash, sourceUrl)
    }
}

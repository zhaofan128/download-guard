package com.dlguard.download.hls

import com.dlguard.download.DownloadOutcome
import com.dlguard.download.DownloadProgress
import com.dlguard.download.DownloadStage
import com.dlguard.integrity.IntegrityResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HLS（m3u8）点播下载器。
 *
 * 做的事很有限，但每一步都真实执行：
 *  1. 拉播放列表；主列表（master）按码率挑最高的一路，再拉媒体列表
 *  2. 拒绝直播（没有 ENDLIST 且不是 VOD）与 DRM（SAMPLE-AES / 非 identity KEYFORMAT）
 *  3. 按顺序逐片下载、拼接；AES-128 用标准密钥解密（密钥本身就是播放列表里给的 HTTP 地址）
 *  4. 每下一片把「播放列表指纹 + 已完成片号 + 已写字节」记进 `.part.hls`，中断后可续传
 *  5. 收尾交给调用方注入的 [verify]（与普通下载共用同一套完整性判定）
 *
 * 明确不做的事：不猜密钥、不绕 DRM、不合并音频轨（分离音轨的流会缺音轨，UI 会提示）。
 * 分片顺序下载而不是并发：HLS 单片通常只有几百 KB，顺序写省掉了乱序缓冲与续传对齐，
 * 断点续传才有确定的语义。
 */
class HlsDownloader(
    private val client: OkHttpClient,
    /** 目标目录（与普通下载共用同一套目录解析，含无存储权限时的降级目录）。 */
    private val targetDir: () -> File,
    /** 收尾校验：与普通下载注入同一个检查器，保证结论口径一致。 */
    private val verify: suspend (File, Long) -> IntegrityResult,
    private val onWifi: () -> Boolean = { true },
    private val userAgent: String = DEFAULT_UA
) {

    suspend fun download(
        url: String,
        preferredName: String? = null,
        requireWifi: Boolean = false,
        referer: String? = null,
        cookie: String? = null,
        onProgress: (DownloadProgress) -> Unit = {}
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return@withContext DownloadOutcome(error = "链接为空", url = url)
        if (requireWifi && !onWifi()) {
            return@withContext DownloadOutcome(
                error = "已开启「仅 Wi-Fi 下载」，当前不是 Wi-Fi 网络",
                url = trimmed
            )
        }

        val headers = buildMap {
            put("User-Agent", userAgent)
            put("Accept", "*/*")
            referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
        }

        // ---------- 1. 播放列表 ----------
        var playlistUrl = trimmed
        var media: HlsMediaPlaylist? = null
        var playlistText = ""
        var hops = 0
        while (media == null) {
            if (hops > 3) {
                return@withContext DownloadOutcome(error = "播放列表嵌套层级过深，不像正常的 HLS", url = trimmed)
            }
            val body = when (val fetched = fetchText(playlistUrl, headers)) {
                is Fetch.Failed -> return@withContext DownloadOutcome(
                    error = fetched.reason,
                    url = trimmed,
                    httpCode = fetched.code
                )
                is Fetch.Ok -> fetched.body
            }
            when (val parsed = HlsPlaylistParser.parse(body, playlistUrl)) {
                is HlsParseResult.Invalid ->
                    return@withContext DownloadOutcome(error = parsed.reason, url = trimmed)
                is HlsParseResult.Master -> {
                    playlistUrl = HlsPlaylistParser.pickBest(parsed).uri
                    hops++
                }
                is HlsParseResult.Media -> {
                    media = parsed.playlist
                    playlistText = body
                }
            }
        }
        val playlist = media ?: return@withContext DownloadOutcome(error = "未能解析播放列表", url = trimmed)

        if (!playlist.ended && !playlist.vod) {
            return@withContext DownloadOutcome(
                error = "这是直播流（播放列表没有 #EXT-X-ENDLIST），直播没有「下完」的终点，已停止",
                url = trimmed
            )
        }
        if (playlist.segments.size > MAX_SEGMENTS) {
            return@withContext DownloadOutcome(
                error = "播放列表有 ${playlist.segments.size} 个分片，超出单任务上限（${MAX_SEGMENTS}）",
                url = trimmed
            )
        }

        // ---------- 2. 落盘位置与续传 ----------
        val dir = targetDir().let { if (!it.exists()) it.mkdirs(); it }
        val extension = if (playlist.initSegment != null) "mp4" else "ts"
        val target = uniqueTarget(dir, baseName(preferredName, playlistUrl), extension)
        val part = File(dir, target.name + ".part")
        val sidecar = File(dir, target.name + ".part.hls")
        val fingerprint = sha256Hex(playlistText)

        var startIndex = 0
        var written = 0L
        val saved = readResume(sidecar)
        if (saved != null && saved.fingerprint == fingerprint && part.isFile &&
            part.length() == saved.bytes && saved.nextIndex in 0..playlist.segments.size
        ) {
            startIndex = saved.nextIndex
            written = saved.bytes
        }
        // 续传记录对不上（播放列表变了 / 文件被改过）就从 0 开始，宁可重下也不拼出坏文件
        if (!part.exists()) part.createNewFile()
        if (part.length() < written) {
            // 记录里的字节数比实际文件大：上次写到一半就断电了，缓冲没刷盘，只能重来
            startIndex = 0
            written = 0L
        }
        if (part.length() != written) {
            runCatching { RandomAccessFile(part, "rw").use { it.setLength(written) } }
        }

        var totalEstimate = 0L
        var inFlight = 0L
        var lastEmitAt = 0L
        var lastEmitBytes = 0L

        fun report(stage: DownloadStage = DownloadStage.DOWNLOADING, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastEmitAt < 200L) return
            val dt = (now - lastEmitAt).coerceAtLeast(1L)
            val delta = written + inFlight - lastEmitBytes
            val speed = if (lastEmitAt == 0L) 0L else (delta * 1000 / dt).coerceAtLeast(0L)
            lastEmitAt = now
            lastEmitBytes = written + inFlight
            onProgress(
                DownloadProgress(
                    downloadedBytes = written + inFlight,
                    totalBytes = if (totalEstimate > 0) totalEstimate else 0L,
                    bytesPerSecond = speed,
                    attempt = 1,
                    connections = 1,
                    segmented = false,
                    stage = stage
                )
            )
        }
        report(force = true)

        val out = FileOutputStream(part, true)
        try {
            var index = startIndex
            // fMP4 的初始化段必须排在最前，续传时它已经在文件里了
            if (playlist.initSegment != null && startIndex == 0) {
                val initBytes = fetchBytes(playlist.initSegment, headers) { n ->
                    inFlight = n
                    report()
                }
                out.write(initBytes)
                written += initBytes.size
                inFlight = 0
            }
            while (index < playlist.segments.size) {
                currentCoroutineContext().ensureActive()
                val segment = playlist.segments[index]
                val raw = fetchSegment(segment, headers) { extra ->
                    inFlight = extra
                    report()
                }
                val data = if (segment.key?.encrypted == true) {
                    val key = keyFor(segment.key.uri ?: "", headers)
                    val iv = segment.key.iv ?: sequenceIv(segment.sequence)
                    decrypt(raw, key, iv)
                } else {
                    raw
                }
                out.write(data)
                written += data.size
                inFlight = 0
                index++
                totalEstimate = if (index > 0) (written * playlist.segments.size / index) else 0L
                runCatching { sidecar.writeText("v1\n$fingerprint\n$index\n$written\n") }
                report(force = true)
            }
            out.flush()
            runCatching { out.fd.sync() }
        } catch (cancel: CancellationException) {
            // 暂停：`.part.hls` 里已经记着最后完成的片号，直接往上抛即可
            throw cancel
        } catch (e: SegmentHttpException) {
            return@withContext DownloadOutcome(
                error = "分片下载失败：${e.message}",
                url = trimmed,
                httpCode = e.code
            )
        } catch (e: Throwable) {
            return@withContext DownloadOutcome(
                error = "HLS 下载中断：${e.message ?: e::class.java.simpleName}",
                url = trimmed
            )
        } finally {
            runCatching { out.close() }
        }

        if (written <= 0L) {
            runCatching { part.delete() }
            return@withContext DownloadOutcome(error = "没有写入任何数据", url = trimmed)
        }

        // ---------- 3. 收尾 ----------
        val moved = runCatching {
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = false)
                part.delete()
            }
        }
        if (moved.isFailure || !target.isFile) {
            return@withContext DownloadOutcome(
                error = "拼接完成但重命名失败：${moved.exceptionOrNull()?.message ?: "未知原因"}",
                url = trimmed
            )
        }
        runCatching { sidecar.delete() }

        onProgress(
            DownloadProgress(
                downloadedBytes = target.length(),
                totalBytes = target.length(),
                bytesPerSecond = 0,
                attempt = 1,
                connections = 1,
                segmented = false,
                stage = DownloadStage.VERIFYING
            )
        )
        val integrity = verify(target, target.length())
        DownloadOutcome(
            file = target,
            integrity = integrity,
            url = trimmed,
            bytes = target.length(),
            elapsedMs = System.currentTimeMillis() - startedAt,
            connections = 1,
            contentType = "application/vnd.apple.mpegurl"
        )
    }

    // ------------------------------------------------------------ 网络

    private sealed class Fetch {
        data class Ok(val body: String) : Fetch()
        data class Failed(val reason: String, val code: Int?) : Fetch()
    }

    private fun fetchText(url: String, headers: Map<String, String>): Fetch {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Fetch.Failed(describeCode(response.code), response.code)
                }
                val body = response.body?.string() ?: return Fetch.Failed("播放列表响应为空", response.code)
                if (!body.contains("#EXTM3U")) {
                    return Fetch.Failed("返回的不是 HLS 播放列表（可能是登录页或错误页）", response.code)
                }
                Fetch.Ok(body)
            }
        }.getOrElse { Fetch.Failed("拉取播放列表失败：${it.message ?: "网络错误"}", null) }
    }

    private fun fetchSegment(
        segment: HlsSegment,
        headers: Map<String, String>,
        onBytes: (Long) -> Unit
    ): ByteArray = fetchBytes(segment.uri, headers, onBytes)

    private fun fetchBytes(
        url: String,
        headers: Map<String, String>,
        onBytes: (Long) -> Unit
    ): ByteArray {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw SegmentHttpException(response.code, "${describeCode(response.code)}（${url.take(96)}）")
            }
            val body = response.body ?: throw IOException("响应体为空")
            val declared = body.contentLength()
            val buffer = ByteArrayOutputStream(if (declared in 1..MAX_SEGMENT_BYTES) declared.toInt() else 64 * 1024)
            var received = 0L
            body.byteStream().use { input ->
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n <= 0) break
                    buffer.write(chunk, 0, n)
                    received += n
                    onBytes(received)
                }
            }
            if (received <= 0L) throw IOException("分片为空（$url）")
            return buffer.toByteArray()
        }
    }

    private fun keyFor(uri: String, headers: Map<String, String>): ByteArray {
        if (uri.isBlank()) throw IOException("AES-128 缺少密钥地址")
        keyCache[uri]?.let { return it }
        val request = Request.Builder().url(uri).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw SegmentHttpException(response.code, "取密钥失败")
            val bytes = response.body?.bytes() ?: throw IOException("密钥响应为空")
            if (bytes.size != 16) throw IOException("密钥长度 ${bytes.size} 字节，不是 16 字节（可能取到了错误页）")
            keyCache[uri] = bytes
            return bytes
        }
    }

    private fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun sequenceIv(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        var value = sequence
        for (i in 15 downTo 8) {
            iv[i] = (value and 0xFF).toByte()
            value = value shr 8
        }
        return iv
    }

    private fun describeCode(code: Int): String = when (code) {
        401, 403 -> "HTTP $code，链接可能过期或需要浏览器验证"
        404 -> "HTTP 404，分片已不存在"
        429 -> "HTTP 429，请求过快被限流"
        else -> "HTTP $code"
    }

    // ------------------------------------------------------------ 文件

    private data class Resume(val fingerprint: String, val nextIndex: Int, val bytes: Long)

    private fun readResume(file: File): Resume? {
        if (!file.isFile) return null
        return runCatching {
            val lines = file.readLines()
            if (lines.size < 4 || lines[0] != "v1") return null
            Resume(lines[1], lines[2].toInt(), lines[3].toLong())
        }.getOrNull()
    }

    private fun baseName(preferred: String?, playlistUrl: String): String {
        val raw = preferred?.trim()?.takeIf { it.isNotEmpty() }
            ?: playlistUrl.substringBefore('?').substringAfterLast('/').takeIf { it.contains('.') }
            ?: "video"
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim()
        val withoutExtension = cleaned.substringBeforeLast('.', cleaned)
        return withoutExtension.ifBlank { "video" }.take(80)
    }

    private fun uniqueTarget(dir: File, base: String, extension: String): File {
        var candidate = File(dir, "$base.$extension")
        var counter = 2
        while (candidate.exists()) {
            candidate = File(dir, "$base ($counter).$extension")
            counter++
            if (counter > 99) break
        }
        return candidate
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private class SegmentHttpException(val code: Int, message: String) : IOException(message)

    private val keyCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    companion object {
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36 DownloadGuard/2.1"

        /** 单片 64MB：正常 HLS 分片只有几百 KB，超了说明拿到的不是分片。 */
        private const val MAX_SEGMENT_BYTES = 64L * 1024 * 1024

        /** 单任务分片上限，防止一个畸形播放列表把队列拖死。 */
        private const val MAX_SEGMENTS = 20_000
    }
}

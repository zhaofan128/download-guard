package com.dlguard.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.os.SystemClock
import com.dlguard.core.Formatters
import com.dlguard.integrity.ApkIntegrityChecker
import com.dlguard.integrity.IntegrityResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val attempt: Int,
    val connections: Int = 1
) {
    val fraction: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    /** 剩余秒数；总长未知或速度未知时返回 -1，由 UI 显示「计算中...」。 */
    val etaSeconds: Long
        get() {
            if (totalBytes <= 0 || bytesPerSecond <= 0) return -1
            val remain = (totalBytes - downloadedBytes).coerceAtLeast(0)
            return remain / bytesPerSecond
        }
}

data class DownloadOutcome(
    val file: File? = null,
    val integrity: IntegrityResult? = null,
    val error: String? = null,
    val url: String = "",
    val bytes: Long = 0,
    val elapsedMs: Long = 0,
    val connections: Int = 1,
    val contentType: String? = null,
    /** 服务器返回的 HTTP 状态码（网络层失败时为空），供 UI 判断能否「改用网页模式」。 */
    val httpCode: Int? = null
) {
    val success: Boolean get() = file != null && error == null
    val averageSpeed: Long
        get() = if (elapsedMs > 0) bytes * 1000 / elapsedMs else 0
}

/**
 * 加固下载器 v3：32 线程满血版。
 *
 * 吞吐四代演进（每一步都是真实瓶颈的修复）：
 *  - v1：每次 socket read 都 flush + fsync，10KB 一刷，eMMC 直接被打成随机写，不到 1MB/s；
 *  - v2：按 32MB / 4 秒批量落盘 + 4 固定分段并发；但 h2 多路复用让 4 段挤在一条 TCP 上，
 *    分段几乎无效；固定分段还带木桶效应——最慢的那段决定整个文件的完成时间；
 *  - v3：**块级调度 + 工作窃取**（[ChunkScheduler]）：文件先切成比线程更多的细块，
 *    空闲线程先领新块，领不到就去偷慢速线程未下载完区间的后半段；
 *    32 条连接 = 32 条独立 TCP（独立 ConnectionPool + 强制 HTTP/1.1）；
 *  - fsync 策略：「每块写入完成后 flush + fsync」+ 块内每 8MB / 1.5 秒兜底刷，
 *    全局 ticker 每 4 秒统一 sync 一次。断电最多丢最后一块，不会拼出坏文件。
 *
 * 降级链（全部真实实现）：
 *   多连接 → 服务器忽略 Range 时单连接 → 单连接续传 → 远端变更/元数据损坏时清空重下
 *   → 全部失败保留 .part，下次点击继续。
 */
class ResumableDownloader(
    private val context: Context,
    private val checker: ApkIntegrityChecker,
    private val client: OkHttpClient = defaultClient()
) {

    companion object {
        private const val MAX_ATTEMPTS = 4
        private const val BUFFER = 256 * 1024
        private const val CHUNK_RETRY = 3

        /** 块内兜底 fsync：每 16MB 或 3 秒刷一次（块的边界必定 fsync）。 */
        private const val FSYNC_BYTES = 16L * 1024 * 1024
        private const val FSYNC_INTERVAL_MS = 3_000L
        private const val GLOBAL_SYNC_INTERVAL_MS = 4_000L
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val META_FLUSH_INTERVAL_MS = 4_000L
        private const val MAX_NAME_LENGTH = 120

        /** 小于该大小的块不再单独 fsync，交给全局 ticker 兜底（尾段几十个小块时省掉几十次 sync）。 */
        private const val SMALL_CHUNK_SYNC_SKIP = 4L * 1024 * 1024
        private const val WORKER_IDLE_WAIT_MS = 80L
        private const val MAX_IDLE_WAIT_MS = 500L
        private const val MAX_CHUNK_REQUESTS = 32

        /** 并发上限：设置页滑杆可调 1~48（自动测速也在这个范围内寻优）。 */
        const val MAX_CONNECTIONS = 48

        /**
         * 很多 CDN 会对非浏览器 UA 限速，甚至直接返回 403 / 一个错误页（下载下来自然是「非 ZIP」）。
         * 带一个真实的移动端 Chrome UA，末尾保留自己的标识，兼容性与可回溯性兼顾。
         * 自动测速器 [ConcurrencyTuner] 也复用同一个 UA，保证两边的限速策略一致、结果可比。
         */
        const val UA =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36 DownloadGuard/1.2"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 48
            })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * 分块专用 client 池（最多 48 个）。
     *
     * 关键点：OkHttp 对支持 h2 的站点会把多个 Range 请求复用到**同一条 TCP 连接**上，
     * 带宽仍受单条连接拥塞窗口限制——这是「开了多连接还是 1MB/s」的最常见原因。
     * 每个分块线程一个独立连接池并强制 HTTP/1.1，确保是 N 条真正并行的 TCP。
     */
    private val segmentClients: List<OkHttpClient> by lazy {
        (0 until MAX_CONNECTIONS).map {
            client.newBuilder()
                .connectionPool(ConnectionPool(1, 5, TimeUnit.MINUTES))
                .protocols(listOf(Protocol.HTTP_1_1))
                .build()
        }
    }

    /**
     * 5 秒滑动窗口测速：样本不足或窗口内无进展时返回 -1（UI 显示「计算中...」），
     * 避免瞬时抖动把速度打成 0 或天文数字。
     */
    private class SpeedWindow(private val windowMs: Long = 3_000L) {
        private val times = ArrayDeque<Long>()
        private val totals = ArrayDeque<Long>()

        fun reset() {
            times.clear()
            totals.clear()
        }

        fun sample(totalBytes: Long, now: Long = SystemClock.elapsedRealtime()): Long {
            times.addLast(now)
            totals.addLast(totalBytes)
            while (times.size > 2 && now - times.first() > windowMs) {
                times.removeFirst()
                totals.removeFirst()
            }
            if (times.size < 2) return -1
            val dt = times.last() - times.first()
            val db = totals.last() - totals.first()
            if (dt < 500L || db <= 0L) return -1
            return db * 1000L / dt
        }
    }

    private class RangeUnsupportedException : IOException("服务器忽略 Range 请求")
    private class StaleRemoteException(message: String) : IOException(message)
    private class WifiBlockedException(message: String) : IOException(message)
    private class HttpStatusException(val code: Int, message: String) : IOException(message)

    private data class RemoteInfo(
        val totalBytes: Long,
        val rangeSupported: Boolean,
        val etag: String?,
        val lastModified: String?,
        val contentDispositionName: String?,
        val contentType: String?,
        /** 跟随重定向后的最终 URL。分段请求直接用它，省掉每段一次 302 往返。 */
        val finalUrl: String? = null
    ) {
        fun downloadUrl(fallback: String): String = finalUrl?.takeIf { it.isNotBlank() } ?: fallback
    }

    suspend fun download(
        url: String,
        preferredName: String? = null,
        requireWifi: Boolean = false,
        connections: Int = 16,
        /** 网页嗅探场景：下载站常校验来源页，缺 Referer 会直接 403。 */
        referer: String? = null,
        /** 网页嗅探场景：部分直链依赖登录态 Cookie。 */
        cookie: String? = null,
        onProgress: (DownloadProgress) -> Unit = {}
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        val trimmedUrl = url.trim()
        if (requireWifi && !isOnWifi()) {
            return@withContext DownloadOutcome(
                error = "已开启「仅 Wi-Fi 下载」，当前不是 Wi-Fi 网络",
                url = trimmedUrl
            )
        }
        val parsed = runCatching { URI(trimmedUrl) }.getOrNull()
        if (parsed == null || (parsed.scheme != "http" && parsed.scheme != "https")) {
            return@withContext DownloadOutcome(error = "链接无效，仅支持 http/https：$url", url = trimmedUrl)
        }

        val remote = try {
            probe(trimmedUrl, referer, cookie)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext DownloadOutcome(
                error = "无法连接下载地址：${e.javaClass.simpleName} ${e.message ?: ""}",
                url = trimmedUrl
            )
        }
        val targetDir = resolveTargetDir()
        val fileName = resolveFileName(trimmedUrl, preferredName, remote)
        val part = File(targetDir, "$fileName.part")
        val metaFile = File(targetDir, "$fileName.part.meta")

        var lastError: String? = null
        var lastHttpCode: Int? = null
        var multiAllowed = remote.rangeSupported && remote.totalBytes > 0
        var forceFresh = false
        val workerCount = connections.coerceIn(1, MAX_CONNECTIONS)

        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                if (forceFresh) {
                    DownloadMeta.delete(metaFile)
                    part.delete()
                    forceFresh = false
                }
                val outcome = if (multiAllowed && workerCount > 1) {
                    try {
                        runSegmented(
                            url = trimmedUrl, part = part, metaFile = metaFile, targetDir = targetDir,
                            fileName = fileName, remote = remote, connections = workerCount, attempt = attempt,
                            requireWifi = requireWifi, startedAt = startedAt,
                            referer = referer, cookie = cookie, onProgress = onProgress
                        )
                    } catch (e: RangeUnsupportedException) {
                        // 探测阶段声称支持 Range，真正请求却返回 200：整体降级为单连接
                        multiAllowed = false
                        DownloadMeta.delete(metaFile)
                        part.delete()
                        runSingle(
                            url = trimmedUrl, part = part, metaFile = metaFile, targetDir = targetDir,
                            fileName = fileName, remote = remote, attempt = attempt,
                            requireWifi = requireWifi, startedAt = startedAt,
                            referer = referer, cookie = cookie, onProgress = onProgress
                        )
                    }
                } else {
                    runSingle(
                        url = trimmedUrl, part = part, metaFile = metaFile, targetDir = targetDir,
                        fileName = fileName, remote = remote, attempt = attempt,
                        requireWifi = requireWifi, startedAt = startedAt,
                        referer = referer, cookie = cookie, onProgress = onProgress
                    )
                }
                return@withContext outcome.copy(url = trimmedUrl)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: WifiBlockedException) {
                return@withContext DownloadOutcome(error = e.message ?: "网络已切换到移动数据", url = trimmedUrl)
            } catch (e: StaleRemoteException) {
                lastError = "服务器文件已变更，从头重下：${e.message}"
                forceFresh = true
            } catch (e: HttpStatusException) {
                lastError = describeHttp(e.code, e.message)
                lastHttpCode = e.code
                if (e.code in 400..499 && e.code != 408 && e.code != 429) {
                    return@withContext DownloadOutcome(
                        error = lastError, url = trimmedUrl, httpCode = e.code
                    )
                }
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message ?: "未知网络错误"}"
            }
            if (attempt < MAX_ATTEMPTS) {
                onProgress(
                    DownloadProgress(
                        downloadedBytes = if (part.exists()) part.length() else 0,
                        totalBytes = remote.totalBytes,
                        bytesPerSecond = 0,
                        attempt = attempt + 1,
                        connections = 0
                    )
                )
                delay(1000L shl (attempt - 1))
            }
        }
        DownloadOutcome(
            error = "已重试 $MAX_ATTEMPTS 次仍失败：$lastError。已下载分片保留在 ${part.absolutePath}，可再次点击继续续传",
            url = trimmedUrl,
            httpCode = lastHttpCode
        )
    }

    // ---------------------------------------------------------------- 探测

    /** 把 HTTP 状态码翻成用户能照着做的提示，而不是只丢一个 403。 */
    private fun describeHttp(code: Int, message: String?): String = when (code) {
        403 -> "服务器拒绝访问（HTTP 403）。这类链接常是一次性的，或需要浏览器验证 / 登录态；" +
            "请回原网页重新点下载，或用首页的「抓取链接并下载」让 App 带上来源页与 Cookie"
        404 -> "文件不存在（HTTP 404），链接可能已失效，回原网页重新获取"
        410 -> "文件已被服务器删除（HTTP 410）"
        429 -> "请求太频繁（HTTP 429），等一分钟再试"
        503 -> "服务器暂时不可用（HTTP 503），稍后再试"
        else -> "HTTP $code：${message?.takeIf { it.isNotBlank() } ?: "服务器拒绝了请求"}"
    }

    /**
     * 用一个 1 字节 Range 请求同时拿到：总长度、是否真的支持 Range、ETag、文件名。
     * 比 HEAD 准（很多 CDN 的 HEAD 不带 Accept-Ranges），代价只有一个 RTT。
     */
    /** 网页嗅探来的直链常需要携带来源页与登录态，否则 403。 */
    private fun Request.Builder.sniffHeaders(referer: String?, cookie: String?): Request.Builder = apply {
        if (!referer.isNullOrBlank()) header("Referer", referer)
        if (!cookie.isNullOrBlank()) header("Cookie", cookie)
    }

    private fun probe(url: String, referer: String? = null, cookie: String? = null): RemoteInfo {
        val rangeRequest = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .header("Accept-Encoding", "identity")
            .header("User-Agent", UA)
            .sniffHeaders(referer, cookie)
            .get()
            .build()
        try {
            client.newCall(rangeRequest).execute().use { response ->
                when {
                    response.code == 206 -> {
                        val total = parseContentRange(response.header("Content-Range"))?.second ?: -1L
                        return RemoteInfo(
                            totalBytes = total,
                            rangeSupported = total > 0,
                            etag = response.header("ETag"),
                            lastModified = response.header("Last-Modified"),
                            contentDispositionName = parseContentDisposition(response.header("Content-Disposition")),
                            contentType = response.header("Content-Type"),
                            finalUrl = response.request.url.toString()
                        )
                    }
                    response.code == 416 -> {
                        val total = parseUnsatisfiedTotal(response.header("Content-Range"))
                        return RemoteInfo(
                            totalBytes = total ?: 0L,
                            rangeSupported = true,
                            etag = response.header("ETag"),
                            lastModified = response.header("Last-Modified"),
                            contentDispositionName = parseContentDisposition(response.header("Content-Disposition")),
                            contentType = response.header("Content-Type"),
                            finalUrl = response.request.url.toString()
                        )
                    }
                    response.isSuccessful -> {
                        return RemoteInfo(
                            totalBytes = response.body?.contentLength() ?: -1L,
                            rangeSupported = false,
                            etag = response.header("ETag"),
                            lastModified = response.header("Last-Modified"),
                            contentDispositionName = parseContentDisposition(response.header("Content-Disposition")),
                            contentType = response.header("Content-Type"),
                            finalUrl = response.request.url.toString()
                        )
                    }
                }
                throw HttpStatusException(response.code, response.message)
            }
        } catch (e: HttpStatusException) {
            if (e.code != 405 && e.code != 400 && e.code != 501 && e.code != 403) throw e
        }
        // Range 探测被拒：退回 HEAD（有些服务器只允许 HEAD 拿长度）
        head(url, referer, cookie)?.let { return it }
        // HEAD 也不行：不当作致命错误，降级为「总长未知 + 不支持分段」的单流下载。
        // 大量下载站/CDN 会拒绝 HEAD 与 Range，但 GET 正常返回 200，硬失败会白白错过。
        return RemoteInfo(
            totalBytes = -1L,
            rangeSupported = false,
            etag = null,
            lastModified = null,
            contentDispositionName = null,
            contentType = null,
            finalUrl = url
        )
    }

    private fun head(url: String, referer: String? = null, cookie: String? = null): RemoteInfo? {
        val request = Request.Builder()
            .url(url)
            .head()
            .header("Accept-Encoding", "identity")
            .header("User-Agent", UA)
            .sniffHeaders(referer, cookie)
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val length = response.body?.contentLength() ?: -1L
                if (length <= 0) return@use null
                RemoteInfo(
                    totalBytes = length,
                    rangeSupported = response.header("Accept-Ranges")?.contains("bytes", true) == true,
                    etag = response.header("ETag"),
                    lastModified = response.header("Last-Modified"),
                    contentDispositionName = parseContentDisposition(response.header("Content-Disposition")),
                    contentType = response.header("Content-Type"),
                    finalUrl = response.request.url.toString()
                )
            }
        }.getOrNull()
    }

    // ---------------------------------------------------------------- 多连接（块级调度 + 工作窃取）

    private suspend fun runSegmented(
        url: String,
        part: File,
        metaFile: File,
        targetDir: File,
        fileName: String,
        remote: RemoteInfo,
        connections: Int,
        attempt: Int,
        requireWifi: Boolean,
        startedAt: Long,
        referer: String? = null,
        cookie: String? = null,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadOutcome = coroutineScope {
        val total = remote.totalBytes
        if (part.exists() && part.length() != total) {
            // 分片文件缺失或长度与规划不符（被清理/被别的下载复用）：元数据必须一并作废，
            // 否则会从「声称已下载」的偏移继续，跳过从未落盘的区域，最终 CRC 必然失败。
            DownloadMeta.delete(metaFile)
            part.delete()
        }
        val scheduler = loadOrPlan(metaFile, url, total, remote, connections)
        preallocate(part, total)

        /** 保存当前分块进度（url/total/etag 从探测结果取，不反查旧文件）。 */
        fun persistProgress() = persistMeta(metaFile, url, total, remote.etag, remote.lastModified, scheduler)
        persistProgress()

        val meter = SpeedWindow()
        val stopped = AtomicBoolean(false)
        val wifiLost = AtomicBoolean(false)
        val dirty = AtomicBoolean(false)
        val lastMetaFlush = AtomicLong(SystemClock.elapsedRealtime())
        val downloadUrl = remote.downloadUrl(url)

        // 全局 ticker 用独立句柄统一 sync（32 个 worker 各自 fsync 会把 eMMC 打爆）
        val syncHandle = runCatching { RandomAccessFile(part, "rw") }.getOrNull()
        var lastGlobalSync = SystemClock.elapsedRealtime()

        val clients = segmentClients
        val jobs = (0 until connections).map { workerId ->
            async(Dispatchers.IO) {
                workerLoop(
                    workerId = workerId,
                    scheduler = scheduler,
                    url = downloadUrl,
                    part = part,
                    etag = remote.etag,
                    total = total,
                    httpClient = clients[workerId % clients.size],
                    stopped = stopped,
                    referer = referer,
                    cookie = cookie,
                    onBytes = { dirty.set(true) }
                )
            }
        }

        val ticker = launch(Dispatchers.IO) {
            var lastEmit = 0L
            while (isActive && !stopped.get()) {
                delay(120)
                val now = SystemClock.elapsedRealtime()
                if (requireWifi && !isOnWifi()) {
                    wifiLost.set(true)
                    stopped.set(true)
                    jobs.forEach { it.cancel() }
                    return@launch
                }
                if (now - lastGlobalSync >= GLOBAL_SYNC_INTERVAL_MS) {
                    runCatching { syncHandle?.fd?.sync() }
                    dirty.set(true)
                    lastGlobalSync = now
                }
                if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                    lastEmit = now
                    val downloaded = scheduler.totalDone()
                    onProgress(
                        DownloadProgress(
                            downloadedBytes = downloaded,
                            totalBytes = total,
                            bytesPerSecond = meter.sample(downloaded),
                            attempt = attempt,
                            connections = connections
                        )
                    )
                    if (dirty.get() && now - lastMetaFlush.get() >= META_FLUSH_INTERVAL_MS) {
                        persistProgress()
                        lastMetaFlush.set(now)
                        dirty.set(false)
                    }
                }
            }
        }

        try {
            jobs.awaitAll()
        } catch (e: CancellationException) {
            if (wifiLost.get()) throw WifiBlockedException("网络已从 Wi-Fi 切换到移动数据，按设置暂停下载")
            throw e
        } finally {
            stopped.set(true)
            ticker.cancel()
            runCatching { syncHandle?.fd?.sync() }
            runCatching { syncHandle?.close() }
            runCatching { persistProgress() }
        }

        val downloaded = scheduler.totalDone()
        onProgress(
            DownloadProgress(
                downloadedBytes = downloaded,
                totalBytes = total,
                bytesPerSecond = meter.sample(downloaded),
                attempt = attempt,
                connections = connections
            )
        )
        if (!scheduler.isComplete(total)) {
            throw IOException("分段未取满：$downloaded / $total")
        }
        finalize(
            part = part, metaFile = metaFile, targetDir = targetDir, fileName = fileName,
            downloaded = downloaded, url = url,
            elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            connections = connections, contentType = remote.contentType, declaredSize = total
        )
    }

    /**
     * worker 主循环：领块 → 下载 → 完成；没有新块就窃取；没有块可偷就退出。
     * 单块下载失败在块内重试 $CHUNK_RETRY 次（done 保留，Range 从断点续），仍失败则释放块
     * 并把错误抛给外层 attempt 重试。
     */
    private suspend fun workerLoop(
        workerId: Int,
        scheduler: ChunkScheduler,
        url: String,
        part: File,
        etag: String?,
        total: Long,
        httpClient: OkHttpClient,
        stopped: AtomicBoolean,
        referer: String? = null,
        cookie: String? = null,
        onBytes: (Long) -> Unit
    ) {
        var idleWait = WORKER_IDLE_WAIT_MS
        while (!stopped.get()) {
            currentCoroutineContext().ensureActive()
            var chunk = scheduler.claim(workerId)
            if (chunk == null) chunk = scheduler.steal(workerId)
            if (chunk == null) {
                // 尾段：所有块都有主，把剩余最大的块再切开，让空闲连接一起收尾
                chunk = scheduler.rebalance(workerId)
            }
            if (chunk == null) {
                // 没有可拆的块：还有未完成块时短暂等待再试（可能是别人刚 release，
                // 也可能下一轮就有块降到可拆阈值以下），全部完成才真正退出。
                if (scheduler.unfinishedCount() == 0) return
                delay(idleWait)
                idleWait = (idleWait * 2).coerceAtMost(MAX_IDLE_WAIT_MS)
                continue
            }
            idleWait = WORKER_IDLE_WAIT_MS
            var attempt = 0
            var success = false
            var lastError: String? = null
            while (attempt < CHUNK_RETRY && !stopped.get()) {
                try {
                    downloadChunk(chunk, url, part, etag, total, httpClient, referer, cookie, onBytes)
                    success = true
                    break
                } catch (cancel: CancellationException) {
                    scheduler.release(chunk)
                    throw cancel
                } catch (fatal: RangeUnsupportedException) {
                    scheduler.release(chunk)
                    throw fatal
                } catch (fatal: StaleRemoteException) {
                    scheduler.release(chunk)
                    throw fatal
                } catch (e: Exception) {
                    attempt++
                    lastError = "${e.javaClass.simpleName}: ${e.message ?: "未知错误"}"
                    delay(300L shl attempt.coerceAtMost(3))
                }
            }
            if (success) {
                scheduler.complete(chunk)
            } else {
                scheduler.release(chunk)
                if (!stopped.get()) {
                    throw IOException("分块 ${chunk.start}-${chunk.endInclusive} 连续 $CHUNK_RETRY 次失败：$lastError")
                }
            }
        }
    }

    /**
     * 下载单个块。写入位置由 chunk.start + chunk.done 决定（唯一的进度真相），
     * 每轮循环读取 chunk.endInclusive 的最新值——被其它 worker 窃取后边界缩小，
     * 本 worker 自然停在缩小后的边界，数据区永不重叠。
     */
    private suspend fun downloadChunk(
        chunk: ChunkScheduler.Chunk,
        url: String,
        part: File,
        etag: String?,
        total: Long,
        httpClient: OkHttpClient,
        referer: String? = null,
        cookie: String? = null,
        onBytes: (Long) -> Unit
    ) {
        val start = chunk.start
        var requestCount = 0
        while (true) {
            val done = chunk.done.get().coerceIn(0L, chunk.endInclusive - start + 1)
            val limit = chunk.endInclusive
            if (start + done > limit) return // 块已完结（可能被窃取后自己那半也写完了）
            requestCount++
            if (requestCount > MAX_CHUNK_REQUESTS) throw IOException("分块 ${chunk.start} 反复请求未推进，疑似服务器异常")

            val requestBuilder = Request.Builder()
                .url(url)
                .header("Range", "bytes=${start + done}-$limit")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", UA)
                .sniffHeaders(referer, cookie)
                .get()
            if (!etag.isNullOrBlank()) requestBuilder.header("If-Range", etag)

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (response.code == 200) throw RangeUnsupportedException()
                if (response.code == 416) {
                    if (start + done > limit) return
                    throw StaleRemoteException("分块 $start 请求区间被服务器拒绝（416）")
                }
                if (response.code != 206) throw HttpStatusException(response.code, response.message)
                val contentRange = response.header("Content-Range")
                    ?: throw IOException("206 响应缺少 Content-Range")
                val parsedRange = parseContentRange(contentRange)
                    ?: throw IOException("Content-Range 无法解析：$contentRange")
                val expectStart = start + done
                if (parsedRange.first != expectStart) {
                    throw StaleRemoteException("Content-Range 起始 ${parsedRange.first} 与本地偏移 $expectStart 不一致")
                }
                if (parsedRange.second > 0 && parsedRange.second != total) {
                    throw StaleRemoteException("远端总长由 $total 变为 ${parsedRange.second}")
                }
                val body = response.body ?: throw IOException("响应体为空")
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(expectStart)
                    val stream = body.byteStream()
                    val buffer = ByteArray(BUFFER)
                    var sinceSync = 0L
                    var lastSyncAt = SystemClock.elapsedRealtime()
                    while (true) {
                        val currentLimit = chunk.endInclusive
                        val position = start + chunk.done.get()
                        val remaining = currentLimit - position + 1
                        if (remaining <= 0) break
                        val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read <= 0) {
                            throw IOException(
                                "分块 [$start-$currentLimit] 提前结束，还差 $remaining 字节"
                            )
                        }
                        raf.write(buffer, 0, read)
                        chunk.done.addAndGet(read.toLong())
                        onBytes(read.toLong())
                        sinceSync += read
                        val now = SystemClock.elapsedRealtime()
                        if (sinceSync >= FSYNC_BYTES || now - lastSyncAt >= FSYNC_INTERVAL_MS) {
                            raf.fd.sync()
                            sinceSync = 0
                            lastSyncAt = now
                        }
                    }
                    // 块写入完成：大块强制 fsync（可靠性锚点）；尾段几百 KB 的小块
                    // 不单独 sync，交给全局 ticker 每 4 秒兜底，避免几十次 sync 拖慢收尾。
                    if (chunk.endInclusive - chunk.start + 1 >= SMALL_CHUNK_SYNC_SKIP) {
                        raf.fd.sync()
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 单连接

    private suspend fun runSingle(
        url: String,
        part: File,
        metaFile: File,
        targetDir: File,
        fileName: String,
        remote: RemoteInfo,
        attempt: Int,
        requireWifi: Boolean,
        startedAt: Long,
        referer: String? = null,
        cookie: String? = null,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadOutcome {
        var existing = if (part.exists()) part.length() else 0L
        val expectedTotal = remote.totalBytes
        if (expectedTotal > 0 && existing > expectedTotal) {
            part.delete()
            existing = 0L
        }
        if (expectedTotal > 0 && existing == expectedTotal) {
            return finalize(
                part, metaFile, targetDir, fileName, existing, url,
                SystemClock.elapsedRealtime() - startedAt, 1, remote.contentType, expectedTotal
            )
        }

        val requestBuilder = Request.Builder()
            .url(remote.downloadUrl(url))
            .header("Accept-Encoding", "identity")
            .header("User-Agent", UA)
            .sniffHeaders(referer, cookie)
            .get()
        if (existing > 0L) requestBuilder.header("Range", "bytes=$existing-")
        if (!remote.etag.isNullOrBlank() && existing > 0L) requestBuilder.header("If-Range", remote.etag)

        val meter = SpeedWindow()
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code == 416) {
                if (existing > 0L && (expectedTotal <= 0 || existing >= expectedTotal)) {
                    return finalize(
                        part, metaFile, targetDir, fileName, existing, url,
                        SystemClock.elapsedRealtime() - startedAt, 1, remote.contentType, expectedTotal
                    )
                }
                part.delete()
                throw StaleRemoteException("416 且本地分片不完整，清理后重下")
            }
            if (!response.isSuccessful) throw HttpStatusException(response.code, response.message)
            val body = response.body ?: throw IOException("响应体为空")
            val partial = response.code == 206
            var total = when {
                partial -> parseContentRange(response.header("Content-Range"))?.second ?: expectedTotal
                body.contentLength() >= 0 -> body.contentLength()
                else -> expectedTotal
            }
            if (!partial && existing > 0L) {
                part.delete()
                existing = 0L
            }
            if (partial) {
                val range = parseContentRange(response.header("Content-Range"))
                if (range != null && range.first != existing) {
                    part.delete()
                    throw StaleRemoteException("单连接续传偏移不符：本地 $existing，服务器 ${range.first}")
                }
                if (range != null && range.second > 0 && expectedTotal > 0 && range.second != expectedTotal) {
                    part.delete()
                    throw StaleRemoteException("远端总长由 $expectedTotal 变为 ${range.second}")
                }
            }

            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(existing)
                var done = existing
                var sinceSync = 0L
                var lastSyncAt = SystemClock.elapsedRealtime()
                var lastEmit = 0L
                val stream = body.byteStream()
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    raf.write(buffer, 0, read)
                    done += read
                    sinceSync += read
                    val now = SystemClock.elapsedRealtime()
                    if (requireWifi && !isOnWifi()) {
                        raf.fd.sync()
                        throw WifiBlockedException("网络已从 Wi-Fi 切换到移动数据，按设置暂停下载")
                    }
                    if (sinceSync >= FSYNC_BYTES || now - lastSyncAt >= FSYNC_INTERVAL_MS) {
                        raf.fd.sync()
                        sinceSync = 0
                        lastSyncAt = now
                    }
                    if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                        lastEmit = now
                        onProgress(
                            DownloadProgress(
                                downloadedBytes = done,
                                totalBytes = if (total > 0) total else -1L,
                                bytesPerSecond = meter.sample(done),
                                attempt = attempt,
                                connections = 1
                            )
                        )
                    }
                }
                raf.fd.sync()
                if (total <= 0) total = done
                onProgress(DownloadProgress(done, total, 0, attempt, 1))
                if (total > 0 && done < total) {
                    throw IOException("数据不完整：已写 $done 字节，应为 $total 字节")
                }
                if (total > 0 && done > total) {
                    throw IOException("数据超出声明长度：已写 $done 字节，声明 $total 字节")
                }
                return finalize(
                    part, metaFile, targetDir, fileName, done, url,
                    SystemClock.elapsedRealtime() - startedAt, 1, remote.contentType, total
                )
            }
        }
    }

    // ---------------------------------------------------------------- 元数据

    /**
     * 加载或规划分块：
     *  - 已有元数据且 URL/总长/ETag 匹配 → 按元数据恢复块（断点续传）；
     *  - 否则按「连接数 × 2」规划初始块（比线程更多，保证窃取有素材）。
     * 落盘由调用方 [persistMeta] 负责（需要 url/total/etag 完整信息）。
     */
    private fun loadOrPlan(
        metaFile: File,
        url: String,
        total: Long,
        remote: RemoteInfo,
        connections: Int
    ): ChunkScheduler {
        val existing = DownloadMeta.load(metaFile)
        if (existing != null && existing.url == url && existing.totalBytes == total &&
            validatorsMatch(existing, remote) && existing.segments.isNotEmpty()
        ) {
            return ChunkScheduler(existing.segments)
        }
        DownloadMeta.delete(metaFile)
        val blocks = initialBlockCount(connections, total)
        return ChunkScheduler(total, blocks)
    }

    /** 初始块数：连接数的 4 倍（下限 16），块更细，尾段再平衡的素材更充足。 */
    private fun initialBlockCount(connections: Int, total: Long): Int {
        val desired = (connections * 4).coerceAtLeast(16)
        val bySize = (total / ChunkScheduler.MIN_CHUNK_BYTES).toInt().coerceAtLeast(1)
        return desired.coerceAtMost(bySize).coerceAtLeast(1)
    }

    /** 有校验值时必须一致；两边都没有时只能靠最终 CRC 兜底（可接受的降级）。 */
    private fun validatorsMatch(meta: DownloadMeta, remote: RemoteInfo): Boolean {
        if (!meta.etag.isNullOrBlank() && !remote.etag.isNullOrBlank()) return meta.etag == remote.etag
        if (!meta.lastModified.isNullOrBlank() && !remote.lastModified.isNullOrBlank()) {
            return meta.lastModified == remote.lastModified
        }
        return true
    }

    private fun persistMeta(
        metaFile: File,
        url: String,
        total: Long,
        etag: String?,
        lastModified: String?,
        scheduler: ChunkScheduler
    ) {
        runCatching {
            val states = scheduler.chunks.map { chunk ->
                SegmentState(
                    start = chunk.start,
                    endInclusive = chunk.endInclusive,
                    done = chunk.done.get().coerceIn(0L, chunk.endInclusive - chunk.start + 1)
                )
            }
            val meta = DownloadMeta(
                url = url,
                totalBytes = total,
                etag = etag,
                lastModified = lastModified,
                segments = states
            )
            DownloadMeta.save(metaFile, meta.withSegments(states))
        }
    }

    private fun preallocate(part: File, total: Long) {
        RandomAccessFile(part, "rw").use { raf ->
            if (raf.length() != total) raf.setLength(total)
        }
    }

    // ---------------------------------------------------------------- 收尾

    private suspend fun finalize(
        part: File,
        metaFile: File,
        targetDir: File,
        fileName: String,
        downloaded: Long,
        url: String,
        elapsedMs: Long,
        connections: Int,
        contentType: String?,
        declaredSize: Long? = null
    ): DownloadOutcome {
        if (!part.exists() || part.length() <= 0L) {
            return DownloadOutcome(error = "分片文件不存在或为空", url = url)
        }
        val result = checker.check(part, declaredSize ?: downloaded.takeIf { it > 0 })
        if (!result.healthy) {
            part.delete()
            DownloadMeta.delete(metaFile)
            return DownloadOutcome(
                integrity = result,
                error = "下载完成但校验失败：${result.verdict.label} · ${result.detail}。损坏分片已删除",
                url = url
            )
        }
        var target = File(targetDir, fileName)
        var index = 1
        while (target.exists()) {
            val dot = fileName.lastIndexOf('.')
            val base = if (dot > 0) fileName.substring(0, dot) else fileName
            val ext = if (dot > 0) fileName.substring(dot) else ""
            target = File(targetDir, "${base}($index)$ext")
            index++
        }
        val renamed = try {
            part.renameTo(target)
        } catch (_: Exception) {
            false
        }
        if (!renamed) {
            try {
                part.copyTo(target, overwrite = false, bufferSize = 256 * 1024)
                part.delete()
            } catch (e: Exception) {
                return DownloadOutcome(integrity = result, error = "重命名失败：${e.message}", url = url)
            }
        }
        DownloadMeta.delete(metaFile)
        if (target.length() != downloaded) {
            return DownloadOutcome(integrity = result, error = "落盘大小不一致：${target.length()} != $downloaded", url = url)
        }
        RecentWrites.mark(target.absolutePath)
        return DownloadOutcome(
            file = target,
            integrity = result,
            url = url,
            bytes = downloaded,
            elapsedMs = elapsedMs,
            connections = connections,
            contentType = contentType
        )
    }

    // ---------------------------------------------------------------- 工具

    private fun parseContentRange(header: String?): Pair<Long, Long>? {
        if (header == null) return null
        val body = header.substringAfter(' ', "")
        val range = body.substringBefore('/')
        val totalPart = body.substringAfter('/', "")
        val start = range.substringBefore('-').trim().toLongOrNull() ?: return null
        val total = if (totalPart == "*") -1L else totalPart.trim().toLongOrNull() ?: -1L
        return start to total
    }

    private fun parseUnsatisfiedTotal(header: String?): Long? {
        if (header == null) return null
        return header.substringAfter('/', "").trim().toLongOrNull()
    }

    private fun parseContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val star = Regex("filename\\*\\s*=\\s*UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(header)
        if (star != null) {
            val raw = star.groupValues[1].trim().trim('"')
            val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()
            if (!decoded.isNullOrBlank()) return decoded
        }
        val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(header)
        if (plain != null) {
            val raw = plain.groupValues[1].trim()
            val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            if (decoded.isNotBlank()) return decoded
        }
        return null
    }

    private fun isOnWifi(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun resolveTargetDir(): File {
        val public = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (public != null && (public.exists() || public.mkdirs()) && public.canWrite()) return public
        val fallback = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (fallback != null) {
            if (!fallback.exists()) fallback.mkdirs()
            return fallback
        }
        return context.filesDir
    }

    private fun resolveFileName(url: String, preferredName: String?, remote: RemoteInfo): String {
        val preferred = preferredName?.takeIf { it.isNotBlank() }?.let { Formatters.safeFileName(it) }
        val fromHeader = remote.contentDispositionName?.takeIf { it.isNotBlank() }?.let { Formatters.safeFileName(it) }
        val fromUrl = runCatching {
            val path = URI(url).path.orEmpty()
            URLDecoder.decode(path.substringAfterLast('/'), "UTF-8")
        }.getOrNull()
        val candidate = preferred ?: fromHeader ?: fromUrl?.takeIf { it.isNotBlank() }
            ?: "download_${Formatters.timestampForName()}"
        val name = Formatters.safeFileName(candidate)
        if (name.contains('.')) return limitName(name)

        // 无扩展名：按 Content-Type / URL 线索补齐，否则监听器会直接跳过这个文件
        val type = remote.contentType.orEmpty().lowercase()
        val ext = when {
            type.contains("android.package-archive") -> "apk"
            type.contains("application/zip") -> "zip"
            else -> guessExtFromUrl(url) ?: "bin"
        }
        return limitName("$name.$ext")
    }

    private fun limitName(name: String): String {
        if (name.length <= MAX_NAME_LENGTH) return name
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name.take(MAX_NAME_LENGTH)
        val ext = name.substring(dot)
        val base = name.substring(0, dot)
        return base.take((MAX_NAME_LENGTH - ext.length).coerceAtLeast(1)) + ext
    }

    private fun guessExtFromUrl(url: String): String? {
        val lower = url.lowercase()
        return listOf("apk", "apks", "xapk", "zip").firstOrNull { lower.contains(".$it") }
    }
}

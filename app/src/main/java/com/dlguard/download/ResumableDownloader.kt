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
import kotlinx.coroutines.Job
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
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/** 下载阶段：UI 据此把「100% 卡住」翻译成人能看懂的状态。 */
enum class DownloadStage {
    DOWNLOADING,
    /** 整文件哈希 + APK 签名校验（大包十几秒，最容易让人以为死机）。 */
    VERIFYING,
    /** 改名 / 跨卷复制落盘。 */
    FINALIZING
}

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val attempt: Int,
    val connections: Int = 1,
    /** true = 走分块调度，可以在下载过程中热切换并发；false = 单连接流式，切了也没用。 */
    val segmented: Boolean = false,
    val stage: DownloadStage = DownloadStage.DOWNLOADING
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
    val httpCode: Int? = null,
    /** 落盘时发现同名文件，已用本次结果替换（而不是生成「xxx(1).apk」）。 */
    val replacedExisting: Boolean = false
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

    /** 收尾阶段防止 CPU 休眠/进程冻结导致「卡在 100%」。 */
    private val keepAwake = KeepAwake(context)

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

        /**
         * 并发上限 256。
         *
         * 为什么敢开这么大：worker 是阻塞式 socket 读，不是 select/epoll，
         * 256 条连接 ≈ 256 个线程 + 256 条 TCP。手机上实测 256 线程约 30~50MB 内存
         * （每线程 1MB 栈是虚拟内存，RSS 每线程 ~40KB + 每 socket 缓冲 ~64KB），
         * 8GB 设备可以承受；而很多限速 CDN 恰恰要 100+ 连接才跑得满。
         */
        const val MAX_CONNECTIONS = 256

        /**
         * 很多 CDN 会对非浏览器 UA 限速，甚至直接返回 403 / 一个错误页（下载下来自然是「非 ZIP」）。
         * 带一个真实的移动端 Chrome UA，末尾保留自己的标识，兼容性与可回溯性兼顾。
         * 自动测速器 [ConcurrencyTuner] 也复用同一个 UA，保证两边的限速策略一致、结果可比。
         */
        const val UA =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36 DownloadGuard/1.2"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // 双栈竞速：解析后并发探测、按实测 RTT 排序，专治 IPv6 黑洞吃掉 15 秒 connectTimeout
            .dns(RacingDns())
            .dispatcher(Dispatcher().apply {
                maxRequests = 512
                maxRequestsPerHost = 256
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
     * 分块专用 client 池（按需创建，最多 256 个槽位）。
     *
     * 关键点：OkHttp 对支持 h2 的站点会把多个 Range 请求复用到**同一条 TCP 连接**上，
     * 带宽仍受单条连接拥塞窗口限制——这是「开了多连接还是 1MB/s」的最常见原因。
     * 每个分块线程一个独立连接池并强制 HTTP/1.1，确保是 N 条真正并行的 TCP。
     *
     * 256 个 client 只在真正用到时创建：多数任务 16~48 连接就封顶，
     * 没必要为没拉起的槽位预建连接池和清理线程。
     */
    private val clientSlots = java.util.concurrent.ConcurrentHashMap<Int, OkHttpClient>()

    private fun segmentClient(slot: Int): OkHttpClient =
        clientSlots.computeIfAbsent(slot) {
            client.newBuilder()
                .connectionPool(ConnectionPool(1, 5, TimeUnit.MINUTES))
                .protocols(listOf(Protocol.HTTP_1_1))
                .build()
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

    /**
     * 下载进行中可改的并发闸门。
     *
     * 为什么需要：旧版并发数在 `start()` 时被固化成一个 Int 传给调度器，
     * 想改只能取消重下（分片虽然保留了，但重连、重新探测、重新排队要十几秒）。
     * 现在 UI 拖滑杆只改这里的值，[runSegmented] 的调度协程在 250ms 内增删 worker，
     * 已有连接不受影响、一块进度都不丢。
     */
    class ConnectionGovernor(initial: Int) {
        private val target = AtomicInteger(initial.coerceIn(1, MAX_CONNECTIONS))

        /** 当前期望的并行 worker 数。 */
        val wanted: Int get() = target.get()

        /** 请求切到 n 连接；越界自动收敛到 1~256。 */
        fun request(n: Int) {
            target.set(n.coerceIn(1, MAX_CONNECTIONS))
        }
    }

    /**
     * worker 的「退役」信号：并发下调时多出来的 worker 用它主动收尾。
     *
     * 故意**不继承 CancellationException**——父协程会把任何 CancellationException
     * 当成自己也被取消，整条下载链会跟着断掉，这里只是这一个 worker 该下班了。
     */
    private class RetireRequested : Exception("worker 按新并发数退役")

    /**
     * 下载 worker 专用调度器。
     *
     * Dispatchers.IO 默认只给 64 个线程，256 个 worker 会在这排队，连接开了也白开；
     * limitedParallelism(256) 是 IO 的独立视图，不受默认 64 限制。
     */
    private val workerDispatcher = Dispatchers.IO.limitedParallelism(MAX_CONNECTIONS)

    private class RangeUnsupportedException : IOException("服务器忽略 Range 请求")
    private class StaleRemoteException(message: String) : IOException(message)
    private class WifiBlockedException(message: String) : IOException(message)
    private class HttpStatusException(
        val code: Int,
        message: String,
        /** 429/503 时服务器给的 Retry-After（秒）。照着等比自作聪明提前重试有效得多。 */
        val retryAfterSeconds: Int? = null
    ) : IOException(message)

    /** 解析 Retry-After：既支持秒数，也支持 HTTP-date 形式。 */
    private fun retryAfterSeconds(response: Response): Int? {
        val raw = response.header("Retry-After")?.trim().orEmpty()
        if (raw.isEmpty()) return null
        raw.toIntOrNull()?.let { return it.coerceIn(1, 300) }
        val at = runCatching {
            java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                .parse(raw)?.time
        }.getOrNull() ?: return null
        return ((at - System.currentTimeMillis()) / 1000).toInt().coerceIn(1, 300)
    }

    /** 一个下载 worker 的句柄：id 用于挑连接池，retire 用于并发下调时让它优雅收尾。 */
    private class WorkerHandle(
        val id: Int,
        val retire: AtomicBoolean,
        val job: Job
    )

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
        /** 并发闸门：传进来后可在下载过程中热切换连接数，不传则按 [connections] 固定。 */
        governor: ConnectionGovernor? = null,
        /** 插件聚合出的行为策略：限速、重试、去重、改名、归档。 */
        policy: DownloadPolicy = DownloadPolicy(),
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
        val ua = policy.userAgent.takeIf { it.isNotBlank() } ?: UA
        val limiter = RateLimiter(policy.speedLimit)
        val parsed = runCatching { URI(trimmedUrl) }.getOrNull()
        if (parsed == null || (parsed.scheme != "http" && parsed.scheme != "https")) {
            return@withContext DownloadOutcome(error = "链接无效，仅支持 http/https：$url", url = trimmedUrl)
        }

        val remote = try {
            probe(trimmedUrl, ua, referer, cookie)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpStatusException) {
            // 探测阶段就被服务器拒绝：带上状态码，UI 才能给出「用网页模式打开」这类可操作的出口。
            // HTTP/2 没有 reason phrase（message 为空），所以文案完全依赖 code 翻译。
            return@withContext DownloadOutcome(
                error = describeHttp(e.code, e.message),
                url = trimmedUrl,
                httpCode = e.code
            )
        } catch (e: Exception) {
            return@withContext DownloadOutcome(
                error = "无法连接下载地址：${describeNetwork(e)}",
                url = trimmedUrl
            )
        }
        // 探测已经知道是网页：省掉一次完整 GET，也别把 HTML 当文件存下来
        if (isHtmlDocument(remote.contentType, remote.totalBytes)) {
            return@withContext DownloadOutcome(error = DownloadErrors.htmlPage(), url = trimmedUrl)
        }
        val fileName = resolveFileName(trimmedUrl, preferredName, remote, policy)
        val targetDir = resolveTargetDir(fileName, policy)
        val part = File(targetDir, "$fileName.part")
        val metaFile = File(targetDir, "$fileName.part.meta")

        // 去重：目标位置已有同大小文件时不再重下，直接走一次校验当作本次结果
        if (policy.skipDuplicate && remote.totalBytes > 0) {
            val existing = File(targetDir, fileName)
            if (existing.exists() && existing.length() == remote.totalBytes) {
                val check = checker.check(existing, remote.totalBytes)
                if (check.healthy) {
                    RecentWrites.mark(existing.absolutePath)
                    return@withContext DownloadOutcome(
                        file = existing,
                        integrity = check,
                        url = trimmedUrl,
                        bytes = existing.length(),
                        elapsedMs = 0,
                        connections = 0,
                        contentType = remote.contentType
                    )
                }
            }
        }

        var lastError: String? = null
        var lastHttpCode: Int? = null
        var multiAllowed = remote.rangeSupported && remote.totalBytes > 0
        var forceFresh = false
        val gate = governor ?: ConnectionGovernor(connections)
        val workerCount = gate.wanted

        val maxAttempts = policy.maxAttempts.coerceIn(1, 8)
        for (attempt in 1..maxAttempts) {
            try {
                if (forceFresh) {
                    DownloadMeta.delete(metaFile)
                    part.delete()
                    forceFresh = false
                }
                val outcome = if (multiAllowed) {
                    try {
                        runSegmented(
                            url = trimmedUrl, part = part, metaFile = metaFile, targetDir = targetDir,
                            fileName = fileName, remote = remote, connections = workerCount,
                            governor = gate, attempt = attempt, ua = ua, limiter = limiter,
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
                            ua = ua, limiter = limiter,
                            referer = referer, cookie = cookie, onProgress = onProgress
                        )
                    }
                } else {
                    runSingle(
                        url = trimmedUrl, part = part, metaFile = metaFile, targetDir = targetDir,
                        fileName = fileName, remote = remote, attempt = attempt,
                        requireWifi = requireWifi, startedAt = startedAt,
                        ua = ua, limiter = limiter,
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
                // 429：服务器明确要求「过 N 秒再来」，照着等。硬冲只会让封禁时间变长。
                if (e.code == 429 || e.code == 503) {
                    e.retryAfterSeconds?.let { delay(it * 1_000L) }
                }
                if (e.code in 400..499 && e.code != 408 && e.code != 429) {
                    return@withContext DownloadOutcome(
                        error = lastError, url = trimmedUrl, httpCode = e.code
                    )
                }
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message ?: "未知网络错误"}"
            }
            if (attempt < maxAttempts) {
                onProgress(
                    DownloadProgress(
                        downloadedBytes = if (part.exists()) part.length() else 0,
                        totalBytes = remote.totalBytes,
                        bytesPerSecond = 0,
                        attempt = attempt + 1,
                        connections = gate.wanted,
                        segmented = multiAllowed
                    )
                )
                delay(1000L shl (attempt - 1))
            }
        }
        DownloadOutcome(
            error = "已重试 $maxAttempts 次仍失败：$lastError。已下载分片保留在 ${part.absolutePath}，可再次点击继续续传",
            url = trimmedUrl,
            httpCode = lastHttpCode
        )
    }

    // ---------------------------------------------------------------- 探测

    private fun describeHttp(code: Int, message: String?): String = DownloadErrors.http(code, message)

    private fun describeNetwork(e: Exception): String = DownloadErrors.network(e)

    /** 网页嗅探来的直链常需要携带来源页与登录态，否则 403。 */
    private fun Request.Builder.sniffHeaders(referer: String?, cookie: String?): Request.Builder = apply {
        if (!referer.isNullOrBlank()) header("Referer", referer)
        if (!cookie.isNullOrBlank()) header("Cookie", cookie)
    }

    /**
     * 用一个 1 字节 Range 请求同时拿到：总长度、是否真的支持 Range、ETag、文件名。
     * 比 HEAD 准（很多 CDN 的 HEAD 不带 Accept-Ranges），代价只有一个 RTT。
     */
    private fun probe(
        url: String,
        ua: String,
        referer: String? = null,
        cookie: String? = null
    ): RemoteInfo {
        val rangeRequest = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .header("Accept-Encoding", "identity")
            .header("User-Agent", ua)
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
                throw HttpStatusException(response.code, response.message, retryAfterSeconds(response))
            }
        } catch (e: HttpStatusException) {
            // 400/403/405/501：Range 被拒，退 HEAD 再退单流
            // 408/429/5xx：临时性故障，别在探测阶段就判死，交给下载阶段按 Retry-After 退避重试
            val transient = e.code == 408 || e.code == 429 || e.code >= 500
            if (!transient && e.code != 405 && e.code != 400 && e.code != 501 && e.code != 403) throw e
        }
        // Range 探测被拒：退回 HEAD（有些服务器只允许 HEAD 拿长度）
        head(url, ua, referer, cookie)?.let { return it }
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

    private fun head(url: String, ua: String, referer: String? = null, cookie: String? = null): RemoteInfo? {
        val request = Request.Builder()
            .url(url)
            .head()
            .header("Accept-Encoding", "identity")
            .header("User-Agent", ua)
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
        governor: ConnectionGovernor,
        attempt: Int,
        ua: String,
        limiter: RateLimiter,
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
        // 全局 sync 节流阀：48 个 worker 同时 fsync 同一个文件会互相阻塞（内核按 inode 加锁），
        // 实测这是高并发下速度掉到几十 KB/s 的主因之一。改成谁先抢到谁刷，一次刷全文件。
        val syncGate = AtomicLong(SystemClock.elapsedRealtime())

        // 空闲槽位队列：worker id 会随热切换不断增长，若仍用 id % size 取 client，
        // 增删次数超过槽位数后两条活跃 worker 会撞到同一个连接池，后者只能排队等前者释放。
        val freeClients = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        repeat(MAX_CONNECTIONS) { freeClients.offer(it) }
        val fatal = AtomicReference<Throwable?>(null)
        val workers = ConcurrentHashMap<Int, WorkerHandle>()
        val idSeq = AtomicInteger(0)

        /** 拉起一个 worker；id 只增不复用，保证每个 worker 独占一个连接池。 */
        fun spawnWorker() {
            val id = idSeq.getAndIncrement()
            val retire = AtomicBoolean(false)
            val poolIndex = freeClients.poll() ?: (id % MAX_CONNECTIONS)
            val job = launch(workerDispatcher) {
                try {
                    // 错峰 0~248ms：256 条连接同一瞬间发起握手，防火墙很容易判成 SYN 洪泛，
                    // 结果就是整批被 403/429。分散开后同样的请求量显得正常得多。
                    if (poolIndex > 0) delay((poolIndex % 32) * 8L)
                    workerLoop(
                        workerId = id,
                        scheduler = scheduler,
                        url = downloadUrl,
                        part = part,
                        etag = remote.etag,
                        total = total,
                        httpClient = segmentClient(poolIndex),
                        stopped = stopped,
                        retire = retire,
                        syncGate = syncGate,
                        ua = ua,
                        limiter = limiter,
                        referer = referer,
                        cookie = cookie,
                        onBytes = { dirty.set(true) }
                    )
                } catch (_: RetireRequested) {
                    // 并发下调：这个 worker 正常下班，手里的块已 release，别人能接着下
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Throwable) {
                    if (fatal.compareAndSet(null, e)) stopped.set(true)
                } finally {
                    // 归还连接池槽位，后续新增 worker 才能拿到一条没人用的 TCP
                    freeClients.offer(poolIndex)
                }
            }
            workers[id] = WorkerHandle(id, retire, job)
        }

        repeat(connections.coerceAtLeast(1)) { spawnWorker() }

        // 热切换控制器：只比对「期望值」，真正增删 worker 放到后台，滑杆拖动不会卡 UI
        val controller = launch(Dispatchers.IO) {
            var lastWanted = connections
            while (isActive && !stopped.get()) {
                delay(250)
                val want = governor.wanted
                if (want == lastWanted) continue
                lastWanted = want
                val live = workers.values.filter { !it.retire.get() && it.job.isActive }
                when {
                    want > live.size -> repeat(want - live.size) { spawnWorker() }
                    want < live.size -> live.sortedByDescending { it.id }
                        .take(live.size - want)
                        .forEach { it.retire.set(true) }
                }
                workers.entries.removeIf { it.value.job.isCompleted }
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
                    workers.values.forEach { runCatching { it.job.cancel() } }
                    return@launch
                }
                if (now - syncGate.get() >= GLOBAL_SYNC_INTERVAL_MS &&
                    syncGate.compareAndSet(syncGate.get(), now)
                ) {
                    runCatching { syncHandle?.fd?.sync() }
                    dirty.set(true)
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
                            connections = governor.wanted,
                            segmented = true
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
            // 不能只等初始那批：热切换会随时新增 worker，所以循环盯「还有没有活的」
            while (!stopped.get()) {
                if (workers.values.none { it.job.isActive }) break
                delay(80)
            }
            val stillPending = scheduler.unfinishedCount()
            if (!stopped.get() && stillPending > 0) {
                throw IOException("所有连接已退出，仍有 $stillPending 个分块未完成")
            }
            fatal.get()?.let { throw it }
        } catch (e: CancellationException) {
            if (wifiLost.get()) throw WifiBlockedException("网络已从 Wi-Fi 切换到移动数据，按设置暂停下载")
            throw e
        } finally {
            stopped.set(true)
            controller.cancel()
            workers.values.forEach { runCatching { it.job.cancel() } }
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
                connections = governor.wanted,
                segmented = true
            )
        )
        if (!scheduler.isComplete(total)) {
            throw IOException("分段未取满：$downloaded / $total")
        }
        finalize(
            part = part, metaFile = metaFile, targetDir = targetDir, fileName = fileName,
            downloaded = downloaded, url = url,
            elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            connections = connections, contentType = remote.contentType, declaredSize = total,
            onProgress = onProgress, totalBytes = total
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
        retire: AtomicBoolean = AtomicBoolean(false),
        syncGate: AtomicLong = AtomicLong(SystemClock.elapsedRealtime()),
        ua: String = UA,
        limiter: RateLimiter = RateLimiter(0),
        referer: String? = null,
        cookie: String? = null,
        onBytes: (Long) -> Unit
    ) {
        var idleWait = WORKER_IDLE_WAIT_MS
        while (!stopped.get() && !retire.get()) {
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
                    downloadChunk(
                        chunk, url, part, etag, total, httpClient, retire, syncGate,
                        ua, limiter, referer, cookie, onBytes
                    )
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
                } catch (retireSignal: RetireRequested) {
                    // 退役不是错误：把块还回去就走，绝不能被下面的重试逻辑吞掉
                    scheduler.release(chunk)
                    throw retireSignal
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
        retire: AtomicBoolean = AtomicBoolean(false),
        syncGate: AtomicLong = AtomicLong(SystemClock.elapsedRealtime()),
        ua: String = UA,
        limiter: RateLimiter = RateLimiter(0),
        referer: String? = null,
        cookie: String? = null,
        onBytes: (Long) -> Unit
    ) {
        val start = chunk.start
        var requestCount = 0
        while (true) {
            // 并发下调 / 用户取消：在两次请求之间就退出，不必等整块下完
            if (retire.get()) throw RetireRequested()
            currentCoroutineContext().ensureActive()
            val done = chunk.done.get().coerceIn(0L, chunk.endInclusive - start + 1)
            val limit = chunk.endInclusive
            if (start + done > limit) return // 块已完结（可能被窃取后自己那半也写完了）
            requestCount++
            if (requestCount > MAX_CHUNK_REQUESTS) throw IOException("分块 ${chunk.start} 反复请求未推进，疑似服务器异常")

            val requestBuilder = Request.Builder()
                .url(url)
                .header("Range", "bytes=${start + done}-$limit")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", ua)
                .sniffHeaders(referer, cookie)
                .get()
            if (!etag.isNullOrBlank()) requestBuilder.header("If-Range", etag)

            val call = httpClient.newCall(requestBuilder.build())
            // 取消/暂停时协程只是被标记，阻塞中的 socket read 不会自己醒；
            // 这里把 Call 挂到协程完成回调上，取消瞬间直接 cancel 掉这条连接。
            val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion {
                runCatching { call.cancel() }
            }
            try {
            call.execute().use { response ->
                if (response.code == 200) throw RangeUnsupportedException()
                if (response.code == 416) {
                    if (start + done > limit) return
                    throw StaleRemoteException("分块 $start 请求区间被服务器拒绝（416）")
                }
                if (response.code != 206) throw HttpStatusException(response.code, response.message, retryAfterSeconds(response))
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
                        // 旧版缺这一行：job.cancel() 之后阻塞的 read 照样返回数据，
                        // 于是「取消」变成「继续下」，界面还会被旧任务的 onProgress 反复覆盖。
                        currentCoroutineContext().ensureActive()
                        if (retire.get()) throw RetireRequested()
                        if (limiter.enabled) limiter.acquire(read)
                        // 与 rebalance 的边界收缩互斥。拿锁后重新读 done/endInclusive：
                        // 块可能刚被拆走一半，这时按旧边界写会把越界字节也算进 done，
                        // 与新 owner 的计数重复，最终 isComplete() 报「分段未取满」。
                        var written = 0
                        synchronized(chunk) {
                            val limitNow = chunk.endInclusive
                            val posNow = start + chunk.done.get()
                            val writable = minOf(read.toLong(), limitNow - posNow + 1).toInt()
                            if (writable > 0) {
                                raf.seek(posNow)
                                raf.write(buffer, 0, writable)
                                chunk.done.addAndGet(writable.toLong())
                                written = writable
                            }
                        }
                        if (written == 0) break // 整段已被偷走，剩下的交给新 owner
                        onBytes(written.toLong())
                        sinceSync += written
                    }
                    // 块写入完成：大块强制 fsync（可靠性锚点）；尾段几百 KB 的小块
                    // 不单独 sync，交给全局 ticker 每 4 秒兜底，避免几十次 sync 拖慢收尾。
                    if (chunk.endInclusive - chunk.start + 1 >= SMALL_CHUNK_SYNC_SKIP) {
                        val now = SystemClock.elapsedRealtime()
                        val last = syncGate.get()
                        if (now - last >= 2_000L && syncGate.compareAndSet(last, now)) {
                            raf.fd.sync()
                        }
                    }
                }
            }
            } finally {
                cancelHandle?.dispose()
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
        ua: String = UA,
        limiter: RateLimiter = RateLimiter(0),
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
                SystemClock.elapsedRealtime() - startedAt, 1, remote.contentType, expectedTotal,
                onProgress = onProgress, totalBytes = expectedTotal
            )
        }

        val requestBuilder = Request.Builder()
            .url(remote.downloadUrl(url))
            .header("Accept-Encoding", "identity")
            .header("User-Agent", ua)
            .sniffHeaders(referer, cookie)
            .get()
        if (existing > 0L) requestBuilder.header("Range", "bytes=$existing-")
        if (!remote.etag.isNullOrBlank() && existing > 0L) requestBuilder.header("If-Range", remote.etag)

        val meter = SpeedWindow()
        val call = client.newCall(requestBuilder.build())
        // 协程一旦取消就掐断这条 socket：阻塞中的 read 不会自己醒来，
        // 否则用户点了取消要等 readTimeout（60 秒）才真正停。
        currentCoroutineContext()[Job]?.invokeOnCompletion { runCatching { call.cancel() } }
        call.execute().use { response ->
            if (response.code == 416) {
                if (existing > 0L && (expectedTotal <= 0 || existing >= expectedTotal)) {
                    return finalize(
                        part, metaFile, targetDir, fileName, existing, url,
                        SystemClock.elapsedRealtime() - startedAt, 1, remote.contentType, expectedTotal,
                        onProgress = onProgress, totalBytes = expectedTotal
                    )
                }
                part.delete()
                throw StaleRemoteException("416 且本地分片不完整，清理后重下")
            }
            if (!response.isSuccessful) throw HttpStatusException(response.code, response.message, retryAfterSeconds(response))
            // 探测没拿到类型（多半是超时降级成单流）时的第二道闸：
            // 页面不是文件，存下来只是个 .html 空壳，还会让用户以为下载成功了
            if (isHtmlDocument(response.header("Content-Type"), response.body?.contentLength() ?: -1L)) {
                return DownloadOutcome(error = DownloadErrors.htmlPage(), url = url, httpCode = response.code)
            }
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
                    currentCoroutineContext().ensureActive()
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    currentCoroutineContext().ensureActive()
                    if (limiter.enabled) limiter.acquire(read)
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
                    SystemClock.elapsedRealtime() - startedAt, 1, remote.contentType, total,
                    onProgress = onProgress, totalBytes = total
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
        declaredSize: Long? = null,
        /** 用于把「正在校验 / 正在落盘」实时告诉 UI；100% 卡住时用户才知道没死机。 */
        onProgress: ((DownloadProgress) -> Unit)? = null,
        totalBytes: Long = -1L
    ): DownloadOutcome {
        if (!part.exists() || part.length() <= 0L) {
            return DownloadOutcome(error = "分片文件不存在或为空", url = url)
        }
        val reportedTotal = if (totalBytes > 0) totalBytes else downloaded
        onProgress?.invoke(
            DownloadProgress(
                downloadedBytes = downloaded,
                totalBytes = reportedTotal,
                bytesPerSecond = 0,
                attempt = 1,
                connections = connections,
                segmented = true,
                stage = DownloadStage.VERIFYING
            )
        )
        // 校验是纯 CPU + IO 的重活，期间没有网络包，系统很容易判定「空闲」把进程冻结
        val result = keepAwake.holdSuspend("verify") {
            // 关键：磁盘上是 xxx.apk.part，但类型判定必须用最终文件名。
            // 少了 logicalName，扩展名会取到 "part"，L4/L5 静默跳过，界面还显示「校验通过」。
            checker.check(
                part,
                declaredSize ?: downloaded.takeIf { it > 0 },
                logicalName = fileName
            )
        }
        if (!result.healthy) {
            DownloadMeta.delete(metaFile)
            // 不要直接删：删掉等于「文件没了、原因也没了」。移入隔离区，用户还能取回比对。
            val quarantined = runCatching {
                com.dlguard.data.QuarantineManager(context).quarantine(part)
            }.getOrNull()
            val where = when {
                quarantined != null -> "分片已移入隔离区：${quarantined.absolutePath}"
                part.exists() -> "分片保留在：${part.absolutePath}"
                else -> "分片已清理"
            }
            return DownloadOutcome(
                integrity = result,
                error = "下载完成但校验失败：${result.verdict.label} · ${result.detail}。$where",
                url = url
            )
        }
        onProgress?.invoke(
            DownloadProgress(
                downloadedBytes = downloaded,
                totalBytes = reportedTotal,
                bytesPerSecond = 0,
                attempt = 1,
                connections = connections,
                segmented = true,
                stage = DownloadStage.FINALIZING
            )
        )
        // 同名文件：默认**替换**，不再生成「xxx(1).apk」。
        // 旧行为下用户重下一个文件，目录里会躺两份一模一样的安装包，谁也分不清哪份是新的，
        // 「最近校验」里还会出现两条同名记录。
        var target = File(targetDir, fileName)
        var replaced = false
        if (target.exists()) {
            replaced = target.delete()
            if (!replaced) {
                // 删不掉（被其它应用占用、只读挂载）才退化成加序号，至少不会让本次下载白跑
                var index = 1
                val dot = fileName.lastIndexOf('.')
                val base = if (dot > 0) fileName.substring(0, dot) else fileName
                val ext = if (dot > 0) fileName.substring(dot) else ""
                while (target.exists()) {
                    target = File(targetDir, "${base}($index)$ext")
                    index++
                }
            }
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
            contentType = contentType,
            replacedExisting = replaced
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

    /** 按插件策略把文件归档到分类子目录；关掉策略时就是下载根目录。 */
    private fun resolveTargetDir(fileName: String, policy: DownloadPolicy): File {
        val base = resolveTargetDir()
        if (!policy.autoFolder) return base
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val folder = when (ext) {
            "apk", "apks", "xapk", "apkm" -> "Apps"
            "zip", "7z", "rar", "tar", "gz" -> "Archives"
            "mp4", "mkv", "avi", "mov", "webm" -> "Videos"
            "mp3", "flac", "wav", "m4a", "aac" -> "Audio"
            "jpg", "jpeg", "png", "gif", "webp", "heic" -> "Pictures"
            "pdf", "epub", "mobi", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx" -> "Documents"
            else -> "Others"
        }
        val dir = File(base, folder)
        if (!dir.exists() && !dir.mkdirs()) return base
        return if (dir.canWrite()) dir else base
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

    /** HLS 下载器复用同一套目录解析：公共下载目录不可写时同样降级到应用私有目录。 */
    fun targetDirectory(): File = resolveTargetDir()

    /** HLS 下载器复用同一套「仅 Wi-Fi」判定，避免两套实现判出两种结果。 */
    fun isOnWifiNetwork(): Boolean = isOnWifi()

    private fun resolveFileName(
        url: String,
        preferredName: String?,
        remote: RemoteInfo,
        policy: DownloadPolicy
    ): String {
        val rawPreferred = preferredName?.takeIf { it.isNotBlank() }
        val preferred = rawPreferred
            ?.let { if (policy.cleanName) cleanFileName(it) else it }
            ?.let { Formatters.safeFileName(it) }
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

    /**
     * 去广告后缀：下载站常把文件名拼成「[www.xxx.com]App_v1.2.apk」、
     * 「软件名_绿色版_下载之家.apk」，留着既难看又容易在不同站点重复下同一份文件。
     *
     * 规则本体在 [FileNameSanitizer]，这里再套一层 runCatching：命名的清洗再出问题，
     * 也只退回原始文件名，绝不能让一条正则把整个下载任务打成失败。
     */
    private fun cleanFileName(name: String): String =
        runCatching { FileNameSanitizer.clean(name) }.getOrDefault(name)

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

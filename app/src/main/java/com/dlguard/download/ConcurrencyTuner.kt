package com.dlguard.download

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 自动并发测速器 v3。
 *
 * v2 的两个致命缺陷在真机上暴露得很清楚：
 *
 *  1. **档位之间不可比**：每档都从文件 1/3 处起，但「起点 + 并发数 × 2MB」的覆盖范围
 *     随并发线性膨胀——2 连接只摸到 4MB 热区，48 连接要摸到 96MB、必然踩进慢区，
 *     于是高并发档被慢区拖低，得出「加连接不升反降」的错误结论。
 *     v3 固定一个 48MB 窗口，所有档位**在完全相同的字节区间**上切分，唯一变量只剩并发数。
 *
 *  2. **切换档位要等 5 秒**：`futures.forEach { it.get(1.6s) }` 是串行等待，任何一条
 *     挂住的连接都会让后面每条各等一次 1.6 秒，最坏 48 × 1.6 = 76 秒。
 *     v3 改用 `invokeAll(tasks, 总超时)` 一次性收口，整档硬上限 3 秒（含握手）。
 *
 * 采样口径：
 *  - 计时从**首字节到达**开始（握手/排队不计），到**最后一个字节到达**结束；
 *  - 采样窗口 1.2 秒，够跑出稳态，又不会让用户觉得慢；
 *  - 每档最多读 48MB（实际受 1.2 秒限制，通常 3~8MB）。
 *
 * 推荐口径：
 *  - 取「达到峰值 95% 的最小并发」为工程最优，但不低于 [MIN_RECOMMENDED]（8）——
 *    单连接跑满带宽时选 1 虽然省资源，却抗不住抖动；
 *  - **若用户当前设置已达峰值 95%，保留用户的值**，绝不把 48 无故降成 2。
 */
class ConcurrencyTuner(
    baseClient: OkHttpClient,
    private val maxConcurrency: Int = ResumableDownloader.MAX_CONNECTIONS,
    /** 起始并发（插件可配），阶梯会从该值开始。 */
    private val startConcurrency: Int = 1,
    /** 判停阈值：保留给 UI 文案（「提速不足 X%」），v3 不再提前中断阶梯。 */
    private val gainThreshold: Double = 1.05,
    /** 用户当前设置值：一定会被探测，达标时保留，不被覆盖。 */
    private val currentSetting: Int = 0
) {

    data class Probe(val concurrency: Int, val bytesPerSecond: Long)

    data class Result(
        /** 最终写回设置的值（可能等于用户原值）。 */
        val best: Int,
        /** 该值对应的实测吞吐（B/s）。 */
        val bestSpeed: Long,
        /** 全部探测记录（并发数 → 实测速度），UI 展示完整曲线。 */
        val probes: List<Probe>,
        /** 停止原因（人类可读，带速度对比）。 */
        val stoppedBecause: String,
        /** 是否判定为「带宽已饱和」：再加连接也不会更快。 */
        val bandwidthSaturated: Boolean = false,
        /** 是否保留了用户原设置（没有覆盖）。 */
        val keptCurrent: Boolean = false
    )

    companion object {
        /**
         * 探测阶梯：数量少、跨度大。旧版 11 档每档 5 秒，用户要等近一分钟；
         * 现在 7 档 × 1.2 秒 ≈ 8.4 秒就能看完整条曲线。
         */
        val LADDER = listOf(1, 2, 4, 8, 16, 32, 48)

        /** 按起始值/上限/当前设置裁剪后的实际阶梯。 */
        fun ladderFor(start: Int, max: Int, current: Int = 0): List<Int> {
            val begin = start.coerceIn(1, 48)
            val end = max.coerceIn(begin, 48)
            val base = (listOf(begin) + LADDER.filter { it > begin && it <= end }).distinct()
            val withCurrent = if (current in (begin + 1)..end) base + current else base
            return withCurrent.distinct().sorted()
        }

        /** 单档稳态采样时长。 */
        private const val LEVEL_TIME_MS = 1_200L

        /** 整档硬超时（含 TCP/TLS 握手与排队）。 */
        private const val LEVEL_TIMEOUT_MS = 3_000L

        /** 所有档位共用的探测窗口：48MB，保证字节级可比。 */
        private const val WINDOW_BYTES = 48L * 1024 * 1024

        /** 单连接最小段长。 */
        private const val MIN_SEGMENT = 96L * 1024

        /** 文件太小（< 8MB）时多连接本来就无意义。 */
        private const val MIN_TOTAL = 8L * 1024 * 1024

        /** 推荐值下限：低于这个数虽然可能同样快，但抗抖动能力差。 */
        const val MIN_RECOMMENDED = 8

        /** 判定「跑满带宽」的阈值：达到峰值的 95%。 */
        private const val SATURATION_RATIO = 0.95

        private const val BUFFER = 64 * 1024

        fun formatSpeed(bytesPerSecond: Long): String = when {
            bytesPerSecond <= 0 -> "测速失败"
            bytesPerSecond >= 1024 * 1024 ->
                String.format(Locale.CHINA, "%.2f MB/s", bytesPerSecond / 1024.0 / 1024.0)
            else -> String.format(Locale.CHINA, "%.0f KB/s", bytesPerSecond / 1024.0)
        }
    }

    private val client: OkHttpClient = baseClient.newBuilder()
        .dispatcher(Dispatcher().apply {
            // 48 连接同步 execute 会占满默认配额，必须放宽，否则最后几条要排队
            maxRequests = 128
            maxRequestsPerHost = 128
        })
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        // 关键：callTimeout 保证挂住的 call 到点必死，不会在下一档继续偷带宽
        .callTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    /**
     * 执行自动测速。每完成一档回调一次 [onProbe]（探测结果 + 档位序号，从 1 起）。
     * 挂起函数，在 IO 线程池上执行网络；取消安全。
     */
    suspend fun tune(
        url: String,
        onProbe: (probe: Probe, step: Int, totalSteps: Int) -> Unit = { _, _, _ -> }
    ): Result = withContext(Dispatchers.IO) {
        val total = probeTotal(url)
        if (total == null || total < MIN_TOTAL) {
            return@withContext Result(
                best = 1,
                bestSpeed = 0,
                probes = emptyList(),
                stoppedBecause = "服务器不支持 Range 请求或文件太小（不足 8MB），多连接无从谈起，保持单连接"
            )
        }

        val ladder = ladderFor(startConcurrency, maxConcurrency, currentSetting)
        val probes = ArrayList<Probe>(ladder.size)

        // v3 不做提前判停：用户明确要看 48 连接到底能跑多少，
        // 提前停在 2 连接正是上一版「推荐 2 但 48 更快」的观感来源。
        for ((index, concurrency) in ladder.withIndex()) {
            val speed = probeLevel(url, total, concurrency)
            val probe = Probe(concurrency, speed)
            probes += probe
            onProbe(probe, index + 1, ladder.size)
        }

        decide(probes)
    }

    /** 根据完整曲线给出推荐值；用户当前值达标时原样保留。 */
    internal fun decide(probes: List<Probe>): Result {
        val valid = probes.filter { it.bytesPerSecond > 0 }
        if (valid.isEmpty()) {
            return Result(
                best = currentSetting.takeIf { it > 0 } ?: startConcurrency,
                bestSpeed = 0,
                probes = probes,
                stoppedBecause = "所有档位都没测到数据，请检查链接是否需要登录或已失效"
            )
        }

        val peakProbe = valid.maxByOrNull { it.bytesPerSecond }!!
        val peak = peakProbe.bytesPerSecond
        val target = (peak * SATURATION_RATIO).toLong()

        // 工程最优：达到峰值 95% 的最小并发，但不低于 8
        val minimalGood = valid.firstOrNull { it.bytesPerSecond >= target }?.concurrency
            ?: peakProbe.concurrency
        val recommended = minimalGood.coerceAtLeast(MIN_RECOMMENDED)

        // 用户当前值实测达标 → 保留，不覆盖
        val currentProbe = probes.firstOrNull { it.concurrency == currentSetting && it.bytesPerSecond > 0 }
        val keepCurrent = currentProbe != null && currentProbe.bytesPerSecond >= target

        val applied = if (keepCurrent) currentSetting else recommended
        val appliedProbe = probes.firstOrNull { it.concurrency == applied }
        val appliedSpeed = appliedProbe?.bytesPerSecond ?: 0
        val maxProbe = valid.last()
        // 带宽饱和的两种形态：峰值出现在最高档之前，或最高档相对次高档提升不足阈值
        val withoutTop = valid.dropLast(1).maxOfOrNull { it.bytesPerSecond } ?: 0L
        val saturated = peakProbe.concurrency < maxProbe.concurrency ||
            (withoutTop > 0 && maxProbe.bytesPerSecond.toDouble() < withoutTop * gainThreshold)

        val note = when {
            keepCurrent -> buildString {
                append("你设置的 $currentSetting 连接实测 ${formatSpeed(currentProbe!!.bytesPerSecond)}，")
                append("达到带宽峰值 ${formatSpeed(peak)} 的 ${percentOf(currentProbe.bytesPerSecond, peak)}")
                append(" → 无需降级，保留 $currentSetting 连接")
            }
            applied == recommended && currentSetting > 0 && recommended < currentSetting -> buildString {
                append("$recommended 连接实测 ${formatSpeed(appliedSpeed)}，")
                append("与 $currentSetting 连接的 ${formatSpeed(currentProbe?.bytesPerSecond ?: 0)} 持平")
                append("（峰值 ${formatSpeed(peak)}）→ 带宽已跑满，$recommended 连接足够，已从 $currentSetting 下调")
            }
            saturated -> buildString {
                append("$applied 连接实测 ${formatSpeed(appliedSpeed)}，")
                append("再加到 ${maxProbe.concurrency} 连接也只有 ${formatSpeed(maxProbe.bytesPerSecond)}")
                append(" → 带宽已跑满，$applied 连接最划算")
            }
            else -> buildString {
                append("$applied 连接实测 ${formatSpeed(appliedSpeed)}，")
                append("${maxProbe.concurrency} 连接仍有 ${formatSpeed(maxProbe.bytesPerSecond)}")
                append(" → 还没顶到带宽上限，已采用 $applied 连接")
            }
        }

        return Result(
            best = applied,
            bestSpeed = appliedSpeed,
            probes = probes,
            stoppedBecause = note,
            bandwidthSaturated = saturated,
            keptCurrent = keepCurrent
        )
    }

    private fun percentOf(value: Long, base: Long): String {
        if (base <= 0) return "—"
        return String.format(Locale.CHINA, "%.0f%%", value * 100.0 / base)
    }

    /** Range 探测：必须拿到 206 + Content-Range 总长，否则说明服务器不支持分段。 */
    private fun probeTotal(url: String): Long? {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .header("User-Agent", ResumableDownloader.UA)
            .header("Cache-Control", "no-cache")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (response.code != 206) return@runCatching null
                response.header("Content-Range")
                    ?.substringAfter('/', "")
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 }
            }
        }.getOrNull()
    }

    /**
     * 单档探测：N 条并发连接把**同一个 48MB 窗口**切成 N 段，返回实测吞吐（B/s）。
     *
     * 所有档位窗口起点相同、总字节区间相同，因此档位间的差异只来自并发数本身。
     * 计时从首字节到达开始，到最后一个字节到达结束——握手开销不计入，
     * 这和真实下载的稳态吞吐口径一致。
     */
    private fun probeLevel(url: String, total: Long, concurrency: Int): Long {
        val window = minOf(WINDOW_BYTES, total)
        val maxStart = (total - window).coerceAtLeast(0L)
        val windowStart = (total / 3L).coerceIn(0L, maxStart)
        val windowEnd = windowStart + window - 1

        val seg = (window / concurrency).coerceAtLeast(MIN_SEGMENT)
        val quota = AtomicLong(window)
        val requestAt = SystemClock.elapsedRealtime()
        val dataStart = AtomicLong(0L)
        val lastData = AtomicLong(0L)
        val done = AtomicLong(0L)

        val tasks = (0 until concurrency).map { index ->
            Callable {
                runCatching {
                    val start = windowStart + index.toLong() * seg
                    if (start > windowEnd) return@runCatching
                    val end = (start + seg - 1).coerceAtMost(windowEnd)
                    val request = Request.Builder()
                        .url(url)
                        .header("Range", "bytes=$start-$end")
                        .header("User-Agent", ResumableDownloader.UA)
                        .header("Cache-Control", "no-cache")
                        .header("Pragma", "no-cache")
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (response.code != 206 && !response.isSuccessful) return@runCatching
                        val input = response.body?.byteStream() ?: return@runCatching
                        val buffer = ByteArray(BUFFER)
                        var localLeft = end - start + 1
                        while (localLeft > 0) {
                            val dl = lastData.get()
                            // 采样窗口从首字节起算 1.2 秒；未收到首字节时用整档硬超时兜底
                            val deadline = if (dl > 0) dataStart.get() + LEVEL_TIME_MS
                            else requestAt + LEVEL_TIMEOUT_MS
                            if (SystemClock.elapsedRealtime() > deadline) break
                            if (quota.get() <= 0) break
                            val want = minOf(buffer.size.toLong(), localLeft, quota.get()).toInt()
                            val read = input.read(buffer, 0, want)
                            if (read <= 0) break
                            val now = SystemClock.elapsedRealtime()
                            dataStart.compareAndSet(0L, now)
                            lastData.set(now)
                            localLeft -= read
                            quota.addAndGet(-read.toLong())
                            done.addAndGet(read.toLong())
                        }
                    }
                }
            }
        }

        val pool = Executors.newFixedThreadPool(concurrency) { r ->
            Thread(r, "tune-probe").apply { isDaemon = true }
        }
        try {
            // 一次性收口：总超时 = 握手预算 + 采样窗口，不会逐条累加
            pool.invokeAll(tasks, LEVEL_TIMEOUT_MS + LEVEL_TIME_MS, TimeUnit.MILLISECONDS)
        } finally {
            pool.shutdownNow()
        }

        val begin = dataStart.get()
        val end = lastData.get()
        if (begin <= 0 || end <= begin || done.get() <= 0) return -1
        val elapsed = (end - begin).coerceAtLeast(120L)
        return done.get() * 1000L / elapsed
    }
}

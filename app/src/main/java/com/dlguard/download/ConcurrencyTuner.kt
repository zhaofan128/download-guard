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
 * v5 的变化（用户要求「测速压到极限」）：
 *  - 连两档也不跑了：**只测上限档一档 600ms**，约 0.7 秒出结论（v4 要 2~3 秒）；
 *  - 只有实测速度 < 1.5MB/s（低带宽，高连接握手开销占比大、测不准）才补测 16 档 500ms；
 *  - 高并发被 CDN 惩罚的场景交给下载中的「自适应降并发」兜底（连续 8 秒掉速自动降），
 *    测速阶段不再花时间验证它；
 *  - 推荐值就是实测峰值档，不做「最省资源」折中。
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
        /** 兼容保留：UI 曲线展示仍可能引用。极速模式下不再按阶梯逐档跑。 */
        val LADDER = listOf(1, 2, 4, 8, 16, 32, 48)

        /** 主测档位采样时长：600ms 已能跑出稳态，极限压时间就压在这里。 */
        private const val LEVEL_TIME_MS = 600L

        /** 主测档硬超时（含握手）：256 连接握手要排队，预算放宽到 2.4 秒。 */
        private const val LEVEL_TIMEOUT_MS = 2_400L

        /** 补测档（低带宽时才跑）：16 连接在慢网络上握手快、测得更准。 */
        private const val FALLBACK_LEVEL = 16

        /** 补测档的采样时长与硬超时。 */
        private const val FALLBACK_TIME_MS = 500L
        private const val FALLBACK_TIMEOUT_MS = 1_800L

        /** 主测结果低于该速度（1.5MB/s）时视为低带宽，补测一档。 */
        private const val LOW_BANDWIDTH_BPS = 1536L * 1024

        /** UI 进度步数：主测 1 步 + 可能的补测 1 步。 */
        fun fastSteps(max: Int): Int = 2

        /**
         * 探测窗口：32MB。256 连接时每段 125KB（≥ 32KB 最小段长），
         * 窗口再小高并发档会被最小段长截断，测不满 256 条连接。
         */
        private const val WINDOW_BYTES = 32L * 1024 * 1024

        /** 单连接最小段长。降到 32KB，让 256 连接每条都有活干。 */
        private const val MIN_SEGMENT = 32L * 1024

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
            // 同步 execute() 不走这个配额，但保险起见放宽，避免任何隐式排队
            maxRequests = 512
            maxRequestsPerHost = 256
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

        val top = maxConcurrency.coerceIn(2, ResumableDownloader.MAX_CONNECTIONS)
        val probes = ArrayList<Probe>(2)

        // 极速主测：只测上限档一档，600ms 采样
        val topSpeed = probeLevel(url, total, top, LEVEL_TIME_MS, LEVEL_TIMEOUT_MS)
        probes += Probe(top, topSpeed)
        onProbe(probes.last(), 1, 2)

        if (topSpeed >= LOW_BANDWIDTH_BPS || topSpeed < 0 || top <= FALLBACK_LEVEL) {
            // 速度健康（或整档失败——补测也大概率失败）：直接出结论
            return@withContext decide(probes)
        }

        // 低带宽：256 条连接的握手开销占比过大、测不准，补测 16 档取峰值
        val fallbackSpeed = probeLevel(url, total, FALLBACK_LEVEL, FALLBACK_TIME_MS, FALLBACK_TIMEOUT_MS)
        probes += Probe(FALLBACK_LEVEL, fallbackSpeed)
        onProbe(probes.last(), 2, 2)

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

        // 用户要的是「最快」：直接给峰值所在档位，不再做「最小够用」的折中。
        // 高并发档即使只快 5%，抗抖动能力也更好，没必要刻意降下去。
        val recommended = peakProbe.concurrency

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
    private fun probeLevel(
        url: String,
        total: Long,
        concurrency: Int,
        sampleMs: Long = LEVEL_TIME_MS,
        timeoutMs: Long = LEVEL_TIMEOUT_MS
    ): Long {
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
                            val deadline = if (dl > 0) dataStart.get() + sampleMs
                            else requestAt + timeoutMs
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

        // cached 池：按需建线程，主测 256 并发时才真的到 256，空档不占内存
        val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "tune-probe").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 }
        }
        try {
            // 一次性收口：总超时 = 握手预算 + 采样窗口，不会逐条累加
            pool.invokeAll(tasks, timeoutMs + sampleMs, TimeUnit.MILLISECONDS)
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

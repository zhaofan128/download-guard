package com.dlguard.download

import java.util.concurrent.atomic.AtomicLong

/**
 * 下载行为策略：由插件配置聚合而来，注入 [ResumableDownloader]。
 *
 * 为什么要这一层：插件如果只是「一个开关 + 一段说明」，用户点完发现什么也没变，
 * 市场就永远长不出生态。这里把插件配置翻译成下载器真正会执行的参数——
 * 限速、重试次数、去重、改名、归档、自动打开——开关一拨，行为立刻不同。
 */
data class DownloadPolicy(
    /** 全局限速（字节/秒）。0 表示不限。 */
    val speedLimit: Long = 0,
    /** 单个文件最多重试几次。 */
    val maxAttempts: Int = 4,
    /** 目标位置已有同大小文件时直接复用，不再重下。 */
    val skipDuplicate: Boolean = false,
    /** 文件名去广告后缀。 */
    val cleanName: Boolean = true,
    /** 按扩展名归档到子目录（Download/Apps、Download/Videos…）。 */
    val autoFolder: Boolean = false,
    /** 下载并校验通过后自动拉起打开。 */
    val autoOpen: Boolean = false,
    /** 自定义 User-Agent；空串表示用内置移动端 Chrome UA。 */
    val userAgent: String = ""
)

/**
 * 令牌桶限速器（全局共享，48 个 worker 合计不超过设定速率）。
 *
 * 实现要点：桶容量 = 1 秒的量，按实际经过的时间补充令牌。
 * 不用 `delay` 是因为读循环跑在阻塞 IO 线程上，挂起会打乱 OkHttp 的连接复用节奏。
 */
class RateLimiter(bytesPerSecond: Long) {

    private val rate = bytesPerSecond.coerceAtLeast(0)
    private val lock = Object()
    private var allowance = rate.toDouble()
    private val last = AtomicLong(System.nanoTime())

    val enabled: Boolean get() = rate > 0

    /** 取走 [bytes] 个令牌；额度不足时阻塞到够为止。未启用时立即返回。 */
    fun acquire(bytes: Int) {
        if (rate <= 0 || bytes <= 0) return
        synchronized(lock) {
            while (true) {
                val now = System.nanoTime()
                val prev = last.getAndSet(now)
                val elapsedSec = (now - prev).coerceAtLeast(0L) / 1_000_000_000.0
                // 桶上限就是 1 秒的量，避免长时间空闲后突然爆发
                allowance = (allowance + elapsedSec * rate).coerceAtMost(rate.toDouble())
                if (allowance >= bytes) {
                    allowance -= bytes
                    return
                }
                val needMs = ((bytes - allowance) / rate * 1000.0).toLong().coerceIn(1L, 200L)
                runCatching { lock.wait(needMs) }
            }
        }
    }
}

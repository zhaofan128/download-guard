package com.dlguard.download

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 双栈竞速解析（Happy Eyeballs 的工程化变体）。
 *
 * 问题现场：很多 CDN 同时有 A / AAAA 记录，但移动网络下 IPv6 常常是「有地址、没路由」——
 * 系统解析器把 IPv6 排在前面，OkHttp 老老实实先连它，于是卡满 15 秒 connectTimeout
 * 才回退到 IPv4。用户感知就是「首包要等十几秒」。
 *
 * 真正的 RFC 8305 需要 socket 层竞速（250ms 错峰并发 connect），OkHttp 不暴露那一层。
 * 这里用等效做法：**解析后对所有候选地址并发做一次 TCP 443 握手探测，按实测 RTT 排序返回**。
 *  - 连得通的地址按快慢排，第一条就是最快路径；
 *  - 连不通的地址（典型是 IPv6 黑洞）排到最后，不再浪费 connectTimeout；
 *  - 探测失败不丢地址，只是降级排序——443 不通不代表 80 不通。
 *
 * 成本：每个 host 每 60 秒最多一次探测（结果缓存），最坏多花 ~350ms，换掉的是十几秒的等待。
 */
class RacingDns(private val delegate: Dns = Dns.SYSTEM) : Dns {

    companion object {
        /** 缓存 60 秒：兼顾「CDN 就近调度」与「切网后尽快换路」。 */
        private const val CACHE_TTL_MS = 60_000L
        private const val PROBE_TIMEOUT_MS = 320
        private const val MAX_PROBE = 6
        private const val PROBE_PORT = 443

        /** 网络切换时由 App 调用：下一批连接重新解析、重新竞速。 */
        @Volatile
        private var generation = 0L

        fun invalidate() {
            generation++
        }
    }

    private data class CacheEntry(
        val addresses: List<InetAddress>,
        val at: Long,
        val gen: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "racing-dns").apply { isDaemon = true }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        val gen = generation
        cache[hostname]?.let { hit ->
            if (hit.gen == gen && now - hit.at < CACHE_TTL_MS) return hit.addresses
        }
        val resolved = delegate.lookup(hostname)
        // 只有真正的双栈结果才值得探测：纯 IPv4 / 纯 IPv6 没有「走哪条协议」的选择，
        // 排序收益接近于零，却要平白多等 300ms——这类 host 直接缓存原序。
        val dualStack = resolved.any { it is Inet4Address } && resolved.any { it is Inet6Address }
        if (resolved.size <= 1 || !dualStack) {
            cache[hostname] = CacheEntry(resolved, now, gen)
            return resolved
        }
        val ordered = race(resolved)
        cache[hostname] = CacheEntry(ordered, now, gen)
        return ordered
    }

    private fun race(addresses: List<InetAddress>): List<InetAddress> {
        val candidates = addresses.take(MAX_PROBE)
        val futures = candidates.map { address ->
            pool.submit(Callable {
                val started = System.nanoTime()
                val elapsed = try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(address, PROBE_PORT), PROBE_TIMEOUT_MS)
                    }
                    (System.nanoTime() - started) / 1_000_000
                } catch (_: Exception) {
                    Long.MAX_VALUE
                }
                address to elapsed
            })
        }
        val results = futures.mapNotNull { future ->
            runCatching { future.get((PROBE_TIMEOUT_MS + 180).toLong(), TimeUnit.MILLISECONDS) }.getOrNull()
        }
        if (results.isEmpty() || results.all { it.second == Long.MAX_VALUE }) {
            // 全部探测失败：可能是网络禁止 443 探测，保持系统原序，不做「聪明」的排序
            return addresses
        }
        val alive = results.filter { it.second != Long.MAX_VALUE }
            .sortedBy { it.second }
            .map { it.first }
        val dead = results.filter { it.second == Long.MAX_VALUE }.map { it.first }
        return alive + dead + addresses.drop(MAX_PROBE)
    }
}

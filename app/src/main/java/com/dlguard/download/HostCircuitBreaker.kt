package com.dlguard.download

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * 全局主机熔断器（v1.9.0）。
 *
 * 为什么需要「全局」：1.8.0 的退让是**单任务**的——任务 A 收到 429 后自己降到 8 连接，
 * 但同一站点的任务 B、C 仍在用 256 并发猛冲。对服务器来说这是同一个 IP 的三倍压力，
 * 结果就是整段 IP 被拉黑，谁也下不动。
 *
 * 这里按 **host**（而不是 URL）记账，所有任务共享同一份「这个站点现在能承受多少并发」：
 *  - 429/503 是明确的「你太快了」→ 按 256/128/64/32/16/8/4/2/1 阶梯逐级减半；
 *  - 403 多半是防盗链/权限问题而不是速率问题 → 最多降到 16，避免把可用连接数砍没；
 *  - 触发后给一个短冷却（3~30 秒，随连续次数递增），期间新任务先等冷却再发首个请求；
 *  - 连续成功会逐步恢复额度，站点不再限流时自动回到用户设定值。
 *
 * 去重：256 个 worker 同时收到 429 时只记 1 次（750ms 窗口），否则一次失败就能把 strikes 打满。
 */
object HostCircuitBreaker {

    /** 与 [ResumableDownloader.MAX_CONNECTIONS] 保持一致。 */
    const val MAX_CAP = 256

    /** 连续 60 秒没有任何限流事件，就认为站点已恢复，额度直接回满。 */
    private const val DECAY_MS = 60_000L

    /** 同 host 的限流事件合并窗口：并发 worker 同时 429 只算一次。 */
    private const val RECORD_GAP_MS = 750L

    /** 每成功多少次把 strikes 减 1（恢复是渐进的，不是一成功就满血）。 */
    private const val SUCCESS_PER_RECOVERY = 24

    private val ladder = intArrayOf(256, 128, 64, 32, 16, 8, 4, 2, 1)

    data class HostState(
        val host: String,
        val strikes: Int,
        val cap: Int,
        /** 冷却截止时间戳；0 表示不在冷却中。 */
        val cooldownUntil: Long,
        val lastEventAt: Long
    )

    private class Entry {
        var strikes = 0
        var cap = MAX_CAP
        var lastEventAt = 0L
        var cooldownUntil = 0L
        var successStreak = 0
    }

    private val hosts = ConcurrentHashMap<String, Entry>()

    /** 从 URL 提取记账 key；解析失败返回 null（调用方按不熔断处理）。 */
    fun hostOf(url: String): String? = runCatching {
        val uri = URI(url)
        uri.host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 该 host 当前允许的最大并发。无记录 / 已过衰减期时返回 [MAX_CAP]。 */
    fun capFor(host: String): Int {
        val entry = hosts[host] ?: return MAX_CAP
        synchronized(entry) {
            decayLocked(entry, System.currentTimeMillis())
            return entry.cap.coerceIn(1, MAX_CAP)
        }
    }

    /** 冷却剩余毫秒；0 表示可以立即发请求。 */
    fun cooldownMs(host: String): Long {
        val entry = hosts[host] ?: return 0
        synchronized(entry) {
            val now = System.currentTimeMillis()
            decayLocked(entry, now)
            return (entry.cooldownUntil - now).coerceAtLeast(0)
        }
    }

    /**
     * 记录一次限流/拒绝，返回该 host 的新并发上限。
     * 调用方应立即把正在运行的 gate 降到这个值。
     */
    fun onThrottle(host: String, code: Int, retryAfterSeconds: Int? = null): Int {
        val now = System.currentTimeMillis()
        val entry = hosts.getOrPut(host) { Entry() }
        synchronized(entry) {
            decayLocked(entry, now)
            // 并发 worker 的同一波失败只记一次
            if (now - entry.lastEventAt < RECORD_GAP_MS) return entry.cap
            entry.lastEventAt = now
            entry.strikes = (entry.strikes + 1).coerceAtMost(ladder.lastIndex + 4)
            entry.successStreak = 0
            var cap = ladder[entry.strikes.coerceAtMost(ladder.lastIndex)]
            if (code == 403) {
                // 403 是权限/防盗链，不是速率信号：保持一定并发，别把下载器降成单线程
                cap = cap.coerceAtLeast(16)
            }
            entry.cap = cap
            val baseCooldown = when {
                code == 403 -> 0L
                entry.strikes <= 2 -> 3_000L
                entry.strikes <= 4 -> 8_000L
                entry.strikes <= 6 -> 15_000L
                else -> 30_000L
            }
            // 服务器给了 Retry-After 就以它为准（上限 60 秒，避免一个坏头把任务挂死）
            val cooldown = retryAfterSeconds?.let { it.coerceIn(1, 60) * 1_000L } ?: baseCooldown
            entry.cooldownUntil = maxOf(entry.cooldownUntil, now + cooldown)
            return entry.cap
        }
    }

    /** 记录一次成功响应：攒够 [SUCCESS_PER_RECOVERY] 次就把额度恢复一档。 */
    fun onSuccess(host: String) {
        val entry = hosts[host] ?: return
        synchronized(entry) {
            if (entry.strikes <= 0 && entry.cap >= MAX_CAP) return
            entry.successStreak++
            if (entry.successStreak >= SUCCESS_PER_RECOVERY) {
                entry.successStreak = 0
                entry.strikes = (entry.strikes - 1).coerceAtLeast(0)
                entry.cap = ladder[entry.strikes.coerceAtMost(ladder.lastIndex)]
            }
        }
    }

    /** 设置页 / 诊断用：当前所有被限流过的 host。 */
    fun snapshot(): List<HostState> {
        val now = System.currentTimeMillis()
        return hosts.mapNotNull { (host, entry) ->
            synchronized(entry) {
                decayLocked(entry, now)
                if (entry.strikes <= 0 && entry.cap >= MAX_CAP) {
                    null
                } else {
                    HostState(host, entry.strikes, entry.cap, entry.cooldownUntil, entry.lastEventAt)
                }
            }
        }.sortedByDescending { it.strikes }
    }

    /** 测试与「网络切换」时清空：换网后按新链路重新评估。 */
    fun reset() = hosts.clear()

    private fun decayLocked(entry: Entry, now: Long) {
        if (entry.lastEventAt > 0 && now - entry.lastEventAt > DECAY_MS) {
            entry.strikes = 0
            entry.cap = MAX_CAP
            entry.cooldownUntil = 0
            entry.successStreak = 0
        }
    }
}

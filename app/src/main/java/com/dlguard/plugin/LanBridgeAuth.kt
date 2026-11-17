package com.dlguard.plugin

/**
 * 局域网下载桥的鉴权纯逻辑（可单测，不碰 socket）。
 *
 * 之前这里有两个问题：
 *  1. 根页面 `GET /` 把 token 明文拼进 HTML，且 CORS 是 `*` —— 同一 Wi-Fi 下
 *     任何人先打开这个页面就能读到 token，再 `POST /api/download` 让手机替他下任意 URL；
 *  2. `headers["x-dg-token"] != token` 是普通字符串比较，理论上可被逐字节计时爆破。
 *
 * token 现在只在 App 内展示；比较改为常量时间；连续失败还会被窗口限流。
 */
object LanBridgeAuth {

    /** 一个窗口内允许的失败次数。 */
    const val MAX_FAILURES = 8

    /** 限流窗口。 */
    const val WINDOW_MS = 60_000L

    /**
     * 常量时间比较：长度不同直接 false，长度相同时始终走完全部字节。
     * 差异只体现在「比较了多少字节」，不体现在「第几位开始不同」。
     */
    fun matches(provided: String?, expected: String): Boolean {
        if (provided == null) return false
        val a = provided.toByteArray(Charsets.UTF_8)
        val b = expected.toByteArray(Charsets.UTF_8)
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    /** 是否还在冷却期。 */
    fun isLockedOut(failures: Int, windowStartedAt: Long, now: Long): Boolean =
        failures >= MAX_FAILURES && now - windowStartedAt < WINDOW_MS

    /** 窗口过期后计数归零。 */
    fun nextFailureCount(failures: Int, windowStartedAt: Long, now: Long): Int =
        if (now - windowStartedAt >= WINDOW_MS) 1 else failures + 1

    /** 窗口起点：过期就重置为当前时间。 */
    fun nextWindowStart(windowStartedAt: Long, now: Long): Long =
        if (now - windowStartedAt >= WINDOW_MS) now else windowStartedAt

    /** 根页面展示用：绝不回显 token。 */
    fun landingHint(port: Int): String =
        "服务运行中，端口 $port。Token 只在手机 App 的「浏览器下载桥」详情页显示，" +
            "不会通过网页下发；请先在 App 内复制，再填到浏览器扩展里。"
}

package com.dlguard.download

import java.util.concurrent.ConcurrentHashMap

/**
 * 内置下载器刚写完的文件会被 FileObserver 再次捕获，这里做一个短时标记，
 * 避免同一次下载在校验链路里产生两条重复记录。
 */
object RecentWrites {
    private val recent = ConcurrentHashMap<String, Long>()
    private const val TTL_MS = 20_000L

    fun mark(path: String) {
        recent[path] = System.currentTimeMillis()
        if (recent.size > 64) {
            val now = System.currentTimeMillis()
            recent.entries.removeIf { now - it.value > TTL_MS }
        }
    }

    fun consume(path: String): Boolean {
        val stamp = recent.remove(path) ?: return false
        return System.currentTimeMillis() - stamp <= TTL_MS
    }
}

package com.dlguard.download

/**
 * 一个下载分段。区间为闭区间 [start, endInclusive]。
 * 纯 Kotlin、无 Android 依赖，便于 JVM 单测覆盖。
 */
data class Segment(
    val index: Int,
    val start: Long,
    val endInclusive: Long
) {
    val length: Long get() = endInclusive - start + 1

    /** 从本段已写入 done 字节处继续时，请求头应当使用的区间。 */
    fun rangeHeader(done: Long): String = "bytes=${start + done}-$endInclusive"
}

/**
 * 分段规划（保留给单连接降级与小文件场景；多连接主路径已升级为
 * [ChunkScheduler] 的动态分块 + 工作窃取调度）。
 *
 * 规则：
 *  - 单段最小 256 KB：48 连接时 12 MB 以上的文件就能吃满全部连接；
 *  - 连接数上限 48，且不超过「总大小 / 256KB」，小文件自动退化；
 *  - 余数分摊到前几段，保证所有段拼接后严格等于 [0, total-1]，不重不漏。
 */
object SegmentPlanner {

    const val MIN_SEGMENT_BYTES = 256L * 1024
    const val MAX_CONNECTIONS = 48

    fun plan(totalBytes: Long, requestedConnections: Int): List<Segment> {
        if (totalBytes <= 0L) return emptyList()
        if (totalBytes <= MIN_SEGMENT_BYTES) {
            return listOf(Segment(0, 0, totalBytes - 1))
        }
        val maxBySize = (totalBytes / MIN_SEGMENT_BYTES).toInt().coerceAtLeast(1)
        val count = requestedConnections.coerceIn(1, MAX_CONNECTIONS).coerceAtMost(maxBySize)
        if (count <= 1) return listOf(Segment(0, 0, totalBytes - 1))

        val base = totalBytes / count
        val remainder = totalBytes % count
        var offset = 0L
        val result = ArrayList<Segment>(count)
        for (i in 0 until count) {
            val len = base + if (i < remainder) 1L else 0L
            if (len <= 0) continue
            result += Segment(i, offset, offset + len - 1)
            offset += len
        }
        return result
    }

    /** 已写入字节数落到 [0, length] 区间，防止元数据被外部改坏后越界。 */
    fun clampDone(segment: Segment, done: Long): Long = done.coerceIn(0L, segment.length)
}

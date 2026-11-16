package com.dlguard.download

import java.util.concurrent.atomic.AtomicLong

/**
 * 动态分块调度器（纯 Kotlin、无 Android 依赖，可 JVM 单测）。
 *
 * 「每线程固定一段」的天然缺陷是木桶效应：48 条连接里只要有一条被服务器/运营商限速，
 * 它手里那段就要拖到整个文件下完为止，其它 47 条连接全部干等。
 *
 * v4 的核心修正（v3 的尾段崩塌）：
 *  - v3 只在「对方已下载 > 0」时窃取，且块只能对半拆一次；下载到最后 48 块时，
 *    每条连接守着一块，块内剩余 10MB+ 也无法再分，尾段实际只有 1~2 条连接在跑，
 *    单连接被限速到 50KB/s 时最后几十 MB 能拖几分钟。
 *  - v4 引入**尾段再平衡** [rebalance]：允许拆分「还没开始下载（done=0）」的块，
 *    并把最小可拆剩余降到 256KB、拆分深度放到 6 层，使最后 15MB 能被切成
 *    ~48 个 300KB 的小块，让所有连接一起收尾。
 *  - 常规窃取仍只挑「剩余最大且已有进展」的块，避免前期把大段切碎。
 *
 * 并发正确性：
 *  - `endInclusive` / `owner` / `completed` / `splitDepth` 用 @Volatile，写侧在调度器锁内；
 *  - 拆分时把原块 endInclusive 收缩到 mid，owner 下一轮读到新边界自然停住；
 *  - 两个 worker 各按绝对偏移写文件，区间不重叠；锁从不嵌套别的锁。
 */
class ChunkScheduler(totalBytes: Long, requestedBlocks: Int) {

    companion object {
        /** 单个块的最小字节数：太小会产生海量小 Range 请求，反而拖慢速度。 */
        const val MIN_CHUNK_BYTES = 1L * 1024 * 1024

        /** 常规窃取的最小剩余：低于 1MB 拆出来的块不值得一次 RTT。 */
        const val MIN_STEAL_BYTES = 1L * 1024 * 1024

        /**
         * 尾段再平衡的最小剩余。
         * 从 256KB 降到 64KB：84MB 的文件按 1MB 切只有 84 块，48 条连接在最后 20MB
         * 会退化成一两块在跑；64KB 一级能让每条连接都攥着一小块同时收尾。
         */
        const val MIN_REBALANCE_BYTES = 64L * 1024

        /** 单块最多被拆几次（每拆一次减半）。10 层 + 64KB 下限，足够把尾段摊到 48 条连接。 */
        const val MAX_SPLIT_DEPTH = 10

        const val NO_OWNER = -1
        const val MAX_BLOCKS = 256
    }

    /** 一个可独立续传的分块，闭区间 [start, endInclusive]。 */
    class Chunk(
        @Volatile var start: Long,
        @Volatile var endInclusive: Long,
        val done: AtomicLong = AtomicLong(0L),
        @Volatile var owner: Int = NO_OWNER,
        @Volatile var completed: Boolean = false,
        /** 已被拆分次数，用于阻止无限碎片化。 */
        @Volatile var splitDepth: Int = 0
    ) {
        val length: Long get() = endInclusive - start + 1

        /** 剩余未下载字节数（不含已写入部分）。 */
        val remaining: Long get() = (endInclusive - start + 1 - done.get()).coerceAtLeast(0)

        fun rangeHeader(): String = "bytes=${start + done.get()}-$endInclusive"

        override fun toString(): String =
            "Chunk[$start-$endInclusive done=${done.get()} owner=$owner split=$splitDepth completed=$completed]"
    }

    private val chunkList: MutableList<Chunk> = buildChunks(totalBytes, requestedBlocks).toMutableList()

    /** 领取游标：让并发 worker 分散到不同块，减少同一把锁上的争抢。 */
    private val cursor = java.util.concurrent.atomic.AtomicInteger(0)

    /** 只读视图：下载器持久化元数据与统计进度时遍历使用。 */
    val chunks: List<Chunk> get() = chunkList

    /**
     * 从断点续传元数据恢复：段区间、已完成字节数原样还原，
     * 所有块恢复为无主状态，等 worker 重新领取续传。
     */
    constructor(states: List<SegmentState>) : this(0L, 1) {
        chunkList.clear()
        states.forEach { state ->
            chunkList.add(Chunk(state.start, state.endInclusive, AtomicLong(state.done)))
        }
    }

    private fun buildChunks(totalBytes: Long, requestedBlocks: Int): List<Chunk> {
        if (totalBytes <= 0L) return emptyList()
        if (totalBytes <= MIN_CHUNK_BYTES) {
            return listOf(Chunk(0L, totalBytes - 1))
        }
        val count = requestedBlocks.coerceIn(1, MAX_BLOCKS)
            .coerceAtMost((totalBytes / MIN_CHUNK_BYTES).toInt().coerceAtLeast(1))
        if (count <= 1) return listOf(Chunk(0L, totalBytes - 1))
        val base = totalBytes / count
        val remainder = totalBytes % count
        var offset = 0L
        val result = ArrayList<Chunk>(count)
        for (i in 0 until count) {
            val len = base + if (i < remainder) 1L else 0L
            if (len <= 0) continue
            result += Chunk(offset, offset + len - 1)
            offset += len
        }
        return result
    }

    /**
     * 领取一个无主且未完成的块。成功后块的 owner 变为 workerId。
     * 没有无主块时返回 null（调用方应改走 [steal]）。
     */
    fun claim(workerId: Int): Chunk? {
        synchronized(this) {
            val n = chunkList.size
            if (n == 0) return null
            // 从游标处开始扫：48 个 worker 同时 claim 时不会全挤在第 0 块上抢同一把锁的同一段
            val begin = (cursor.getAndIncrement() and Int.MAX_VALUE) % n
            for (i in 0 until n) {
                val chunk = chunkList[(begin + i) % n]
                if (chunk.completed) continue
                if (chunk.owner != NO_OWNER) continue
                chunk.owner = workerId
                return chunk
            }
            return null
        }
    }

    /**
     * 常规工作窃取：从**剩余最大且已有进展**的块上拆走后半段。
     *
     * 只在对方已经推进过时才偷，避免浪费它已建立的 TCP 连接；
     * 选剩余最大的块（而非第一个），避免总是盯着开头那一块反复切。
     */
    fun steal(workerId: Int): Chunk? =
        splitLargest(workerId, allowFresh = false, minRemaining = MIN_STEAL_BYTES * 2)

    /**
     * 尾段再平衡：所有块都有主、但很多连接已经空闲时调用。
     *
     * 与 [steal] 的区别：
     *  - 允许拆「done=0、刚被领走还没开始」的块——尾段这些块就是瓶颈；
     *  - 阈值降到 256KB，允许把最后十几 MB 切成几十份，所有连接一起收尾。
     */
    fun rebalance(workerId: Int): Chunk? =
        splitLargest(workerId, allowFresh = true, minRemaining = MIN_REBALANCE_BYTES * 2)

    private fun splitLargest(workerId: Int, allowFresh: Boolean, minRemaining: Long): Chunk? {
        synchronized(this) {
            var best: Chunk? = null
            var bestRemaining = 0L
            for (chunk in chunkList) {
                if (chunk.completed) continue
                if (chunk.owner == workerId) continue
                if (chunk.splitDepth >= MAX_SPLIT_DEPTH) continue
                val done = chunk.done.get()
                if (!allowFresh && done <= 0L) continue
                val remaining = chunk.endInclusive - chunk.start - done + 1
                if (remaining < minRemaining) continue
                if (remaining > bestRemaining) {
                    best = chunk
                    bestRemaining = remaining
                }
            }
            val target = best ?: return null
            val done = target.done.get()
            val remaining = target.endInclusive - target.start - done + 1
            // 对半拆；剩余不足 4 字节时不再拆
            if (remaining < 4L) return null
            val mid = target.start + done + remaining / 2
            if (mid >= target.endInclusive) return null
            val stolen = Chunk(
                start = mid + 1,
                endInclusive = target.endInclusive,
                splitDepth = target.splitDepth + 1
            )
            target.endInclusive = mid
            target.splitDepth += 1
            stolen.owner = workerId
            chunkList.add(stolen)
            return stolen
        }
    }

    /** 标记块完成并释放持有权。 */
    fun complete(chunk: Chunk) {
        synchronized(this) {
            chunk.completed = true
            chunk.owner = NO_OWNER
        }
    }

    /**
     * worker 放弃块（网络错误/被取消）：保留 done，归还持有权，任何 worker 都能重领续传。
     * 恢复点 = start + done，绝不回退。
     */
    fun release(chunk: Chunk) {
        synchronized(this) {
            if (!chunk.completed) chunk.owner = NO_OWNER
        }
    }

    fun totalDone(): Long = chunks.sumOf { it.done.get() }

    fun unfinishedCount(): Int = chunks.count { !it.completed }

    /** 尾段是否还有「值得再分」的块（供 worker 判断要不要继续等活）。 */
    fun hasSplittableWork(): Boolean = synchronized(this) {
        chunkList.any {
            !it.completed && it.splitDepth < MAX_SPLIT_DEPTH &&
                it.endInclusive - it.start - it.done.get() + 1 >= MIN_REBALANCE_BYTES * 2
        }
    }

    /**
     * 全部块完成且总字节数守恒（下载中不会凭空多字节）才返回 true。
     * done 与区间的一致性由下载器写循环保证：done 只增不减，写入位置 = start + done。
     */
    fun isComplete(totalBytes: Long): Boolean {
        synchronized(this) {
            return chunks.all { it.completed } && totalDone() == totalBytes
        }
    }
}

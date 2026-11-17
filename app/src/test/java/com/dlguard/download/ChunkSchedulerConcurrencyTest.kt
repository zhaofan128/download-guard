package com.dlguard.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import kotlin.concurrent.thread

/**
 * 分块调度器的并发用例。
 *
 * 对应两个真实缺陷：
 *  1. `chunkList` 是 ArrayList，`totalDone()` / `unfinishedCount()` 在锁外遍历，
 *     250ms 进度上报正好撞上 rebalance 的 add 就会抛 ConcurrentModificationException，
 *     异常一路冒到任务层，整条下载直接失败；
 *  2. `splitLargest` 改小 `endInclusive` 时，worker 可能正拿着旧的 remaining 在 read，
 *     写出去越界、done 重复计数，最后 isComplete() 报「分段未取满」。
 */
class ChunkSchedulerConcurrencyTest {

    @Test
    fun `进度统计与拆分并发时不再抛异常`() {
        val scheduler = ChunkScheduler(32L * 1024 * 1024, 8)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())

        val readers = (0 until 6).map {
            thread {
                repeat(3000) {
                    runCatching {
                        scheduler.totalDone()
                        scheduler.unfinishedCount()
                        scheduler.hasSplittableWork()
                        scheduler.chunks.size
                    }.onFailure { e -> errors += e }
                }
            }
        }
        val splitter = thread {
            repeat(1500) {
                runCatching { scheduler.rebalance(99) }.onFailure { e -> errors += e }
            }
        }
        (readers + splitter).forEach { it.join() }

        assertTrue("并发期间出现异常：${errors.firstOrNull()}", errors.isEmpty())
    }

    @Test
    fun `反复拆分后区间不重叠且总字节守恒`() {
        val total = 16L * 1024 * 1024
        val scheduler = ChunkScheduler(total, 2)
        repeat(400) { scheduler.rebalance(1) }

        val sorted = scheduler.chunks.sortedBy { it.start }
        assertTrue("拆分后没有任何块", sorted.isNotEmpty())
        assertEquals("首块没有从 0 开始", 0L, sorted.first().start)
        assertEquals("末块没有覆盖到文件末尾", total - 1, sorted.last().endInclusive)
        sorted.zipWithNext().forEach { (left, right) ->
            assertEquals(
                "区块间出现空洞或重叠：$left -> $right",
                left.endInclusive + 1,
                right.start
            )
        }
        assertEquals("总字节数不守恒", total, sorted.sumOf { it.length })
    }

    @Test
    fun `已完成块不会被再次拆走`() {
        val scheduler = ChunkScheduler(8L * 1024 * 1024, 2)
        val first = scheduler.claim(1)!!
        first.done.set(first.length)
        scheduler.complete(first)
        repeat(200) { scheduler.rebalance(2) }
        assertTrue("已完成的块被重新拆分", scheduler.chunks.none { it.start == first.start && !it.completed })
    }
}

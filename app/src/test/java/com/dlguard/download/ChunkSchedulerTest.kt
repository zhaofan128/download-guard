package com.dlguard.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * ChunkScheduler（工作窃取调度器）的行为锁定：
 *  - 分块不重不漏覆盖 [0, total-1]；
 *  - claim 串行发放所有权（同一块不会被两个 worker 同时持有）；
 *  - steal 拆分后两段区间无缝衔接、done 字节守恒；
 *  - release 后块可被重新领取且断点不回退；
 *  - 多线程并发推进时总进度永远等于真实写入量。
 */
class ChunkSchedulerTest {

    private fun assertCover(total: Long, chunks: List<ChunkScheduler.Chunk>) {
        val sorted = chunks.sortedBy { it.start }
        assertEquals(0L, sorted.first().start)
        assertEquals(total - 1, sorted.last().endInclusive)
        sorted.zipWithNext().forEach { (left, right) ->
            assertEquals("分块不连续", left.endInclusive + 1, right.start)
        }
        assertTrue(chunks.all { it.length > 0 })
    }

    @Test
    fun `initial chunks cover file without gaps`() {
        val sizes = listOf(
            1L,
            512L * 1024,
            1024L * 1024,
            16L * 1024 * 1024,
            1L * 1024 * 1024 * 1024,
            3L * 1024 * 1024 * 1024
        )
        val workers = listOf(1, 2, 16, 32)
        sizes.forEach { total ->
            workers.forEach { w ->
                val scheduler = ChunkScheduler(total, w * 2)
                assertTrue("初始块数应大于 0", scheduler.chunks.isNotEmpty())
                assertCover(total, scheduler.chunks)
            }
        }
    }

    @Test
    fun `tiny file becomes exactly one chunk`() {
        val scheduler = ChunkScheduler(512L * 1024, 64)
        assertEquals(1, scheduler.chunks.size)
        assertEquals(512L * 1024 - 1, scheduler.chunks[0].endInclusive)
    }

    @Test
    fun `claim grants exclusive ownership`() {
        val scheduler = ChunkScheduler(16L * 1024 * 1024, 8)
        val chunk0 = scheduler.claim(0)
        val chunk0Again = scheduler.claim(1)
        assertTrue(chunk0 != null)
        assertTrue(chunk0Again != null)
        assertNotEquals(chunk0, chunk0Again)
        assertEquals(0, chunk0!!.owner)
        assertEquals(1, chunk0Again!!.owner)
        // 领完全部块后再 claim 返回 null
        val remaining = scheduler.chunks.size - 2
        repeat(remaining) { scheduler.claim(2) }
        assertNull(scheduler.claim(3))
    }

    @Test
    fun `steal splits the remaining half and keeps bytes conserved`() {
        val scheduler = ChunkScheduler(16L * 1024 * 1024, 4)
        val chunk = scheduler.claim(0)!!
        val originalEnd = chunk.endInclusive
        // worker0 推进 25%
        val advanced = chunk.length / 4
        chunk.done.addAndGet(advanced.toLong())

        val stolen = scheduler.steal(1)!!
        // 窃取块接管原块尾部，两段无缝衔接
        assertEquals(originalEnd, stolen.endInclusive)
        assertEquals(chunk.endInclusive + 1, stolen.start)
        // done 字节守恒：原块保留已推进部分，新块从零开始
        assertEquals(advanced.toLong(), chunk.done.get())
        assertEquals(0L, stolen.done.get())
        // 两段长度之和 = 原块长度（不重不漏）
        assertEquals(originalEnd - chunk.start + 1, chunk.length + stolen.length)
        assertEquals(advanced.toLong(), scheduler.totalDone())
    }

    @Test
    fun `steal refuses untouched or tiny chunks`() {
        val scheduler = ChunkScheduler(16L * 1024 * 1024, 4)
        val untouched = scheduler.claim(0)!!
        scheduler.claim(1)
        // 完全没推进的块不可偷（避免浪费 owner 已建立的 TCP）
        assertNull(scheduler.steal(1))
        // 推进过半但剩余不足 2x MIN_STEAL 时也不拆
        untouched.done.set(untouched.length - 1)
        assertNull(scheduler.steal(1))
    }

    @Test
    fun `rebalance splits untouched chunks so idle workers can join the tail`() {
        val scheduler = ChunkScheduler(16L * 1024 * 1024, 4)
        // 四条连接各领一块，全部还没开始下载
        val chunks = (0 until 4).map { scheduler.claim(it)!! }
        // 常规窃取拒绝「零进展」的块，这正是 v3 尾段只剩一条连接的原因
        assertNull(scheduler.steal(4))
        // 尾段再平衡必须能把大块切开，让空闲连接一起收尾
        val stolen = scheduler.rebalance(4)!!
        assertEquals(0L, stolen.done.get())
        assertEquals(1, stolen.splitDepth)
        val donor = chunks.first { it.endInclusive + 1 == stolen.start }
        assertEquals(1, donor.splitDepth)
        // 拆完仍不重不漏
        assertCover(16L * 1024 * 1024, scheduler.chunks)
    }

    @Test
    fun `rebalance keeps splitting until the tail is fine enough for every worker`() {
        val total = 32L * 1024 * 1024
        val scheduler = ChunkScheduler(total, 4)
        (0 until 4).forEach { scheduler.claim(it) }
        // 模拟 8 条空闲连接依次来要活：应当持续拆出小块，而不是只剩一条连接
        var granted = 0
        for (worker in 4..11) {
            if (scheduler.rebalance(worker) != null) granted++
        }
        assertTrue("尾段应能持续拆出可下载的块（实际 $granted）", granted >= 4)
        assertCover(total, scheduler.chunks)
    }

    @Test
    fun `release keeps checkpoint and chunk can be reclaimed`() {
        val scheduler = ChunkScheduler(8L * 1024 * 1024, 4)
        val chunk = scheduler.claim(0)!!
        val advanced = chunk.length / 2
        chunk.done.addAndGet(advanced.toLong())
        scheduler.release(chunk)
        assertEquals(ChunkScheduler.NO_OWNER, chunk.owner)
        assertFalse(chunk.completed)
        // claim 现在按游标轮转（避免 48 条连接全挤在第一块上），
        // 所以先把其它块都占住，逼它只能领到刚释放的这一块。
        scheduler.chunks.filter { it !== chunk }.forEach { it.owner = 77 }
        val reclaimed = scheduler.claim(5)
        assertEquals(chunk, reclaimed)
        // Range 头从断点续传
        assertEquals("bytes=${chunk.start + advanced}-${chunk.endInclusive}", chunk.rangeHeader())
    }

    @Test
    fun `complete marks chunk and isComplete requires byte conservation`() {
        val scheduler = ChunkScheduler(4L * 1024 * 1024, 4)
        scheduler.chunks.forEach { chunk ->
            scheduler.claim(0)
            chunk.done.set(chunk.length)
            scheduler.complete(chunk)
        }
        assertTrue(scheduler.isComplete(4L * 1024 * 1024))
        // 字节不守恒（比如计数器重复累加）时必须判定失败
        scheduler.chunks[0].done.set(0)
        assertFalse(scheduler.isComplete(4L * 1024 * 1024))
    }

    @Test
    fun `concurrent workers make exactly total progress`() {
        val total = 64L * 1024 * 1024
        val scheduler = ChunkScheduler(total, 32)
        val workers = 16
        val pool = Executors.newFixedThreadPool(workers)
        val written = AtomicLong(0)
        val done = CountDownLatch(workers)
        repeat(workers) { workerId ->
            pool.execute {
                try {
                    while (true) {
                        val chunk = scheduler.claim(workerId) ?: scheduler.steal(workerId) ?: break
                        // 模拟下载：按 256KB 步进推进（真实写循环就是按 read 步进）
                        while (chunk.remaining > 0) {
                            val step = minOf(256L * 1024, chunk.remaining)
                            chunk.done.addAndGet(step)
                            written.addAndGet(step)
                        }
                        scheduler.complete(chunk)
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue("并发调度应在 10 秒内完成", done.await(10, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals("总进度必须与模拟写入量严格相等", total, scheduler.totalDone())
        assertEquals(total, written.get())
        assertTrue(scheduler.isComplete(total))
        assertCover(total, scheduler.chunks)
    }

    @Test
    fun `restore from meta states keeps boundaries`() {
        val states = listOf(
            SegmentState(0, 1023, 512),
            SegmentState(1024, 4095, 0)
        )
        val scheduler = ChunkScheduler(states)
        assertEquals(2, scheduler.chunks.size)
        assertEquals(512L, scheduler.chunks[0].done.get())
        assertCover(4096, scheduler.chunks)
        assertEquals(512L, scheduler.totalDone())
    }
}

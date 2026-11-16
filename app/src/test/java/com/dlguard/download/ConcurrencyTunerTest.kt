package com.dlguard.download

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定「测速推荐不准」这类回归：算法层的行为必须可预测，
 * 不能因为某档采样抖动就把用户手动设好的 48 连接改成 2。
 */
class ConcurrencyTunerTest {

    private fun tuner(current: Int = 0) = ConcurrencyTuner(
        baseClient = OkHttpClient(),
        currentSetting = current
    )

    private fun probe(concurrency: Int, speedMbPerSec: Double) =
        ConcurrencyTuner.Probe(concurrency, (speedMbPerSec * 1024 * 1024).toLong())

    @Test
    fun `user setting reaching peak is kept instead of being downgraded`() {
        // 2/4/8 都跑到 4.40 MB/s，48 连接 4.20 MB/s（峰值 95% 以上）
        val probes = listOf(
            probe(1, 1.0),
            probe(2, 4.40),
            probe(4, 4.40),
            probe(8, 4.40),
            probe(16, 4.38),
            probe(32, 4.25),
            probe(48, 4.20)
        )
        val result = tuner(current = 48).decide(probes)

        assertEquals(48, result.best)
        assertTrue(result.keptCurrent)
        assertTrue(result.bandwidthSaturated)
        assertTrue(result.stoppedBecause.contains("保留 48 连接"))
    }

    @Test
    fun `recommendation picks the peak concurrency`() {
        // 用户要的是「最快」，不是「最省资源」：峰值在哪一档就推哪一档。
        val probes = listOf(probe(16, 2.0), probe(48, 5.6))
        val fresh = tuner(current = 0).decide(probes)
        assertEquals(48, fresh.best)
        assertFalse(fresh.keptCurrent)

        // 当前设置恰好就是峰值档 → 保留，不做无谓改动
        val kept = tuner(current = 48).decide(probes)
        assertEquals(48, kept.best)
        assertTrue(kept.keptCurrent)
    }

    @Test
    fun `low bandwidth still recommends peak when peak is low concurrency`() {
        // 服务器对高并发惩罚：16 比 48 快 → 推荐 16，而不是硬塞 48
        val probes = listOf(probe(16, 3.4), probe(48, 1.2))
        val result = tuner(current = 0).decide(probes)
        assertEquals(16, result.best)
        // probe() 把 MB/s 换算成字节，直接跟探针对齐，别手写魔数
        assertEquals(probes[0].bytesPerSecond, result.bestSpeed)
    }

    @Test
    fun `bandwidth not saturated picks the higher concurrency`() {
        // 48 确实比 16 快 30%，说明没到顶，应采用 48
        val probes = listOf(
            probe(1, 0.6),
            probe(2, 1.1),
            probe(4, 2.0),
            probe(8, 3.2),
            probe(16, 3.9),
            probe(32, 5.0),
            probe(48, 5.6)
        )
        val result = tuner(current = 0).decide(probes)

        assertEquals(48, result.best)
        assertFalse(result.keptCurrent)
        assertFalse(result.bandwidthSaturated)
    }

    @Test
    fun `ladder always includes the user's current setting`() {
        val ladder = ConcurrencyTuner.ladderFor(1, 48, 24)
        assertTrue(ladder.contains(24))
        assertTrue(ladder.contains(48))
        assertEquals(ladder.sorted(), ladder)
    }

    @Test
    fun `failed probes still recommend something sane`() {
        val result = tuner(current = 32).decide(
            listOf(ConcurrencyTuner.Probe(1, -1), ConcurrencyTuner.Probe(2, -1))
        )
        assertEquals(32, result.best)
        assertEquals(0, result.bestSpeed)
    }
}

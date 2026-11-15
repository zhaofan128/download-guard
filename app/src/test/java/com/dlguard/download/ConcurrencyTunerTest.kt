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
    fun `recommendation never goes below eight connections`() {
        // 单连接就摸到带宽上限：工程最优是 1，但对用户应给 8（抗抖动）
        val probes = listOf(probe(1, 4.40), probe(2, 4.40), probe(4, 4.40))
        val result = tuner(current = 4).decide(probes)

        // 用户当前 4 连接也达标 → 先保留 4，不强行改成 8
        assertEquals(4, result.best)
        assertTrue(result.keptCurrent)

        // 没有「当前设置」时按工程下限给 8
        val fresh = tuner(current = 0).decide(probes)
        assertEquals(ConcurrencyTuner.MIN_RECOMMENDED, fresh.best)
        assertFalse(fresh.keptCurrent)
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

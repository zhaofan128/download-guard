package com.dlguard.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentPlannerTest {

    @Test
    fun `segments cover the file without gaps or overlaps`() {
        val sizes = listOf(
            1L,
            1024L,
            1024L * 1024,
            1024L * 1024 + 1,
            10L * 1024 * 1024,
            100L * 1024 * 1024 + 12345,
            3L * 1024 * 1024 * 1024
        )
        val connections = listOf(1, 2, 3, 4, 8)
        sizes.forEach { total ->
            connections.forEach { count ->
                val segments = SegmentPlanner.plan(total, count)
                assertEquals("总长不匹配 total=$total count=$count", total, segments.sumOf { it.length })
                assertEquals(0L, segments.first().start)
                assertEquals(total - 1, segments.last().endInclusive)
                segments.zipWithNext().forEach { (left, right) ->
                    assertEquals("分段不连续", left.endInclusive + 1, right.start)
                }
                assertTrue(segments.all { it.length > 0 })
            }
        }
    }

    @Test
    fun `tiny file falls back to a single segment`() {
        val segments = SegmentPlanner.plan(200 * 1024L, 8)
        assertEquals(1, segments.size)
        assertEquals(0L, segments[0].start)
        assertEquals(200 * 1024L - 1, segments[0].endInclusive)
    }

    @Test
    fun `connection count is capped by file size and hard limit`() {
        // 5 MB / 256KB 最小分段 = 最多 20 段
        assertEquals(20, SegmentPlanner.plan(5L * 1024 * 1024, 32).size)
        // 1 GB 时按请求的连接数走，但不超过 48
        assertEquals(48, SegmentPlanner.plan(1024L * 1024 * 1024, 64).size)
        // 非法并发数收敛为 1
        assertEquals(1, SegmentPlanner.plan(1024L * 1024 * 1024, 0).size)
    }

    @Test
    fun `range header continues from already written bytes`() {
        val segment = Segment(0, 1000, 1999)
        assertEquals("bytes=1000-1999", segment.rangeHeader(0))
        assertEquals("bytes=1500-1999", segment.rangeHeader(500))
        assertEquals(0L, SegmentPlanner.clampDone(segment, -5))
        assertEquals(1000L, SegmentPlanner.clampDone(segment, 5000))
    }
}

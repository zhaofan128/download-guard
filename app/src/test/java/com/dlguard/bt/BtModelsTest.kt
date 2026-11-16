package com.dlguard.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** BT 展示层的纯逻辑：不加载 native，独立可测。 */
class BtModelsTest {

    @Test
    fun `暂停优先于其它状态`() {
        assertEquals(BtState.PAUSED, mapState("downloading", paused = true, isSeeding = false))
    }

    @Test
    fun `做种状态识别`() {
        assertEquals(BtState.SEEDING, mapState("seeding", false, true))
        assertEquals(BtState.SEEDING, mapState("finished", false, false))
    }

    @Test
    fun `下载与校验元数据状态映射`() {
        assertEquals(BtState.DOWNLOADING, mapState("downloading", false, false))
        assertEquals(BtState.FETCHING_METADATA, mapState("downloading_metadata", false, false))
        assertEquals(BtState.CHECKING, mapState("checking_files", false, false))
        assertEquals(BtState.CHECKING, mapState("checking_resume_data", false, false))
    }

    @Test
    fun `未知状态兜底`() {
        assertEquals(BtState.UNKNOWN, mapState("something_new", false, false))
    }

    @Test
    fun `分享率计算与除零保护`() {
        assertEquals(2f, shareRatio(200, 100), 0.0001f)
        assertEquals(0f, shareRatio(200, 0), 0.0001f)
        assertEquals(0f, shareRatio(0, 0), 0.0001f)
    }

    @Test
    fun `字节格式化`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("2.0 MB", formatBytes(2 * 1024 * 1024))
        assertEquals("1.0 GB", formatBytes(1024L * 1024 * 1024))
    }

    @Test
    fun `速率格式化带每秒后缀`() {
        assertEquals("0 B/s", formatRate(0))
        assertEquals("1.0 MB/s", formatRate(1024 * 1024L))
    }

    @Test
    fun `每个状态都有中文标签`() {
        BtState.values().forEach { assertTrue(it.label.isNotBlank()) }
    }
}

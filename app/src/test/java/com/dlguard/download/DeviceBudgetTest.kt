package com.dlguard.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceBudgetTest {

    private fun mb(n: Long) = n * 1024 * 1024

    @Test
    fun `低内存设备把并发压到 32 以内`() {
        assertEquals(32, DeviceBudget.maxConnections(mb(96)))
        assertEquals(64, DeviceBudget.maxConnections(mb(256)))
        assertEquals(128, DeviceBudget.maxConnections(mb(512)))
        assertEquals(ResumableDownloader.MAX_CONNECTIONS, DeviceBudget.maxConnections(mb(1024)))
    }

    @Test
    fun `永不返回小于 8 的值`() {
        listOf(0L, mb(1), mb(16), mb(64)).forEach { mem ->
            assertTrue("内存 $mem 时给出了过小的并发", DeviceBudget.maxConnections(mem) >= 8)
        }
    }

    @Test
    fun `缓冲总量不超过堆的四分之一`() {
        listOf(mb(128), mb(256), mb(512), mb(1024), mb(2048)).forEach { mem ->
            val buffers = DeviceBudget.maxConnections(mem) * DeviceBudget.BUFFER_BYTES
            assertTrue(
                "内存 $mem 时缓冲区 $buffers 超过堆的 1/4",
                buffers <= mem / 4
            )
        }
    }
}

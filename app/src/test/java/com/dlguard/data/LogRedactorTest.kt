package com.dlguard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志脱敏：既要真的藏住凭证，又不能把排障信息（域名、路径形态、参数名）一起藏掉。
 */
class LogRedactorTest {

    @Test
    fun `credential params are masked but keys remain`() {
        val masked = LogRedactor.url("https://cdn.test.com/f.apk?token=abc123&sign=deadbeef&id=42")
        assertTrue(masked.contains("token=***"))
        assertTrue(masked.contains("sign=***"))
        assertTrue(masked.contains("id=42"))
        assertFalse(masked.contains("abc123"))
        assertFalse(masked.contains("deadbeef"))
    }

    @Test
    fun `long opaque value is masked even with unknown key`() {
        val masked = LogRedactor.url("https://x.test/f.zip?xq=" + "A".repeat(40))
        assertTrue(masked.contains("xq=***"))
    }

    @Test
    fun `host and file name are preserved`() {
        val masked = LogRedactor.url("https://dl.example.com:8443/releases/app-1.9.0.apk")
        assertEquals("https://dl.example.com:8443/releases/app-1.9.0.apk", masked)
    }

    @Test
    fun `local path keeps only the last two segments`() {
        val masked = LogRedactor.path("/storage/emulated/0/Download/app.apk")
        assertEquals("…/Download/app.apk", masked)
        assertFalse(masked.contains("emulated"))
    }

    @Test
    fun `footer carries redacted tag`() {
        assertTrue(LogRedactor.footer().contains("已脱敏"))
    }
}

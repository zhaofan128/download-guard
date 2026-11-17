package com.dlguard.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanBridgeAuthTest {

    @Test
    fun `token 比较只认完全相等`() {
        assertTrue(LanBridgeAuth.matches("abc123", "abc123"))
        assertFalse(LanBridgeAuth.matches("abc124", "abc123"))
        assertFalse(LanBridgeAuth.matches("abc12", "abc123"))
        assertFalse(LanBridgeAuth.matches("", "abc123"))
        assertFalse(LanBridgeAuth.matches(null, "abc123"))
    }

    @Test
    fun `长度不同也走固定路径不提前返回内容差异`() {
        // 长度不同必然 false；这里主要锁住「不抛异常、不因 null 崩」
        assertFalse(LanBridgeAuth.matches("x".repeat(4096), "abc123"))
    }

    @Test
    fun `连续失败达到阈值后进入冷却`() {
        val start = 1_000L
        assertFalse(LanBridgeAuth.isLockedOut(LanBridgeAuth.MAX_FAILURES - 1, start, start + 100))
        assertTrue(LanBridgeAuth.isLockedOut(LanBridgeAuth.MAX_FAILURES, start, start + 100))
        // 窗口过期后自动解锁
        assertFalse(LanBridgeAuth.isLockedOut(LanBridgeAuth.MAX_FAILURES, start, start + LanBridgeAuth.WINDOW_MS))
    }

    @Test
    fun `窗口过期后计数从头开始`() {
        val start = 1_000L
        assertEquals(3, LanBridgeAuth.nextFailureCount(2, start, start + 500))
        assertEquals(1, LanBridgeAuth.nextFailureCount(7, start, start + LanBridgeAuth.WINDOW_MS))
        assertEquals(start, LanBridgeAuth.nextWindowStart(start, start + 500))
        assertEquals(start + LanBridgeAuth.WINDOW_MS, LanBridgeAuth.nextWindowStart(start, start + LanBridgeAuth.WINDOW_MS))
    }

    @Test
    fun `根页面提示里绝不包含 token`() {
        val text = LanBridgeAuth.landingHint(8787)
        assertTrue(text.contains("8787"))
        assertTrue(text.contains("App"))
        assertFalse("落地页提示不应包含任何 token 形式的内容", text.contains("Token："))
    }
}

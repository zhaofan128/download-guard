package com.dlguard.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接力开关的状态决策。
 *
 * 这是 2.1.1 修复的核心：开关是受控组件，任何「拒绝写入」都会让 checked 卡住，
 * 导致用户关不掉。这些用例把「每次点击都必须落盘」钉成硬约束。
 */
class RelayToggleTest {

    @Test
    fun `关闭必须落盘 即使网关地址为空`() {
        assertTrue("关闭接力永远不能因为任何校验被拦下",
            RelayToggle.shouldPersist(false))
    }

    @Test
    fun `开启也必须落盘 地址为空只给提示不拦截`() {
        assertTrue("开启被拒会让受控开关的 checked 卡在 false，用户永远关不掉",
            RelayToggle.shouldPersist(true))
    }

    @Test
    fun `关闭提示是恢复直连`() {
        assertTrue(RelayToggle.message(false, "192.168.43.1", 8899).contains("恢复本机直连"))
    }

    @Test
    fun `开启但没有地址时提示明确说明仍走直连`() {
        val msg = RelayToggle.message(true, "", 8899)
        assertTrue(msg.contains("网关地址"))
        assertTrue("必须说清楚当前没有真的劫持下载", msg.contains("本机网络"))
    }

    @Test
    fun `开启且填了地址时提示带上端点和端口`() {
        val msg = RelayToggle.message(true, " 192.168.43.1 ", 8899)
        assertTrue(msg.contains("192.168.43.1:8899"))
    }

    @Test
    fun `地址为空时 active 为 false 开关显示开也不劫持下载`() {
        val beforeEnabled = RelayState.clientEnabled
        val beforeHost = RelayState.host
        try {
            RelayState.clientEnabled = true
            RelayState.host = ""
            assertFalse("空地址时绝不能走代理，否则下载全打到空 host",
                RelayState.active)
        } finally {
            RelayState.clientEnabled = beforeEnabled
            RelayState.host = beforeHost
        }
    }

    @Test
    fun `地址和开关都在时 active 为 true`() {
        val beforeEnabled = RelayState.clientEnabled
        val beforeHost = RelayState.host
        try {
            RelayState.clientEnabled = true
            RelayState.host = "192.168.43.1"
            assertTrue(RelayState.active)
        } finally {
            RelayState.clientEnabled = beforeEnabled
            RelayState.host = beforeHost
        }
    }
}

package com.dlguard.download

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class SwitchableDnsTest {

    private class FakeDns(private val addresses: List<InetAddress>) : Dns {
        var calls = 0
        override fun lookup(hostname: String): List<InetAddress> {
            calls++
            return addresses
        }
    }

    private val v4 = InetAddress.getByName("10.0.0.1")
    private val v6 = InetAddress.getByName("10.0.0.2")

    @Test
    fun `开关关闭时退回系统 DNS`() {
        val racing = FakeDns(listOf(v6))
        val fallback = FakeDns(listOf(v4))
        val dns = SwitchableDns({ false }, racing, fallback)
        assertEquals(listOf(v4), dns.lookup("example.com"))
        assertEquals("关闭时不应再走竞速解析", 0, racing.calls)
    }

    @Test
    fun `开关打开时使用竞速解析`() {
        val racing = FakeDns(listOf(v6))
        val fallback = FakeDns(listOf(v4))
        val dns = SwitchableDns({ true }, racing, fallback)
        assertEquals(listOf(v6), dns.lookup("example.com"))
        assertEquals("打开时不应走系统 DNS", 0, fallback.calls)
    }

    @Test
    fun `开关读取抛异常时保持默认开启`() {
        val racing = FakeDns(listOf(v6))
        val dns = SwitchableDns({ error("prefs 崩了") }, racing, FakeDns(listOf(v4)))
        assertEquals(listOf(v6), dns.lookup("example.com"))
    }
}

package com.dlguard.download

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 全局熔断的契约：
 *  - 429 按阶梯降级，同 host 共享；
 *  - 并发 worker 的同一波失败只记一次（750ms 合并窗口）；
 *  - 403 不被砍成 1 连接（那是权限问题，不是速率问题）；
 *  - 连续成功会逐档恢复，闲置 60 秒直接回满。
 */
class HostCircuitBreakerTest {

    @Before
    fun setUp() = HostCircuitBreaker.reset()

    @After
    fun tearDown() = HostCircuitBreaker.reset()

    @Test
    fun `host extraction ignores port and www prefix`() {
        assertEquals("example.com", HostCircuitBreaker.hostOf("https://www.example.com:8443/a/b?x=1"))
        assertEquals("cdn.test.cn", HostCircuitBreaker.hostOf("http://cdn.test.cn/file.apk"))
        assertEquals(null, HostCircuitBreaker.hostOf("file:///sdcard/a.apk"))
    }

    @Test
    fun `429 halves ladder step by step`() {
        val host = "rate.example.com"
        assertEquals(256, HostCircuitBreaker.capFor(host))
        assertEquals(128, HostCircuitBreaker.onThrottle(host, 429))
        // 合并窗口内的第二次不计
        assertEquals(128, HostCircuitBreaker.onThrottle(host, 429))
        // 越过窗口再记
        Thread.sleep(800)
        assertEquals(64, HostCircuitBreaker.onThrottle(host, 429))
        Thread.sleep(800)
        assertEquals(32, HostCircuitBreaker.onThrottle(host, 429))
        assertTrue(HostCircuitBreaker.cooldownMs(host) > 0)
    }

    @Test
    fun `403 never drops below sixteen connections`() {
        val host = "hotlink.example.com"
        repeat(6) {
            HostCircuitBreaker.onThrottle(host, 403)
            Thread.sleep(800)
        }
        assertEquals(16, HostCircuitBreaker.capFor(host))
    }

    @Test
    fun `success gradually restores capacity`() {
        val host = "recover.example.com"
        HostCircuitBreaker.onThrottle(host, 429)
        Thread.sleep(800)
        HostCircuitBreaker.onThrottle(host, 429)
        assertEquals(64, HostCircuitBreaker.capFor(host))
        repeat(24) { HostCircuitBreaker.onSuccess(host) }
        assertEquals(128, HostCircuitBreaker.capFor(host))
        repeat(24) { HostCircuitBreaker.onSuccess(host) }
        assertEquals(256, HostCircuitBreaker.capFor(host))
    }

    @Test
    fun `different hosts do not affect each other`() {
        HostCircuitBreaker.onThrottle("a.example.com", 429)
        assertEquals(128, HostCircuitBreaker.capFor("a.example.com"))
        assertEquals(256, HostCircuitBreaker.capFor("b.example.com"))
    }
}

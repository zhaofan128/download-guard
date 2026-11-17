package com.dlguard.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 捕获广播的入口过滤。
 *
 * 广播现在有 signature 级权限兜底，但过滤器本身也要能独立测试：
 * 第三方应用发不进来不等于「发进来的都值得记录」。
 */
class CapturedUrlReceiverTest {

    @Test
    fun `常见安装包与压缩包放行`() {
        assertTrue(CapturedUrlReceiver.acceptUrl("https://dl.example.com/app.apk"))
        assertTrue(CapturedUrlReceiver.acceptUrl("https://dl.example.com/pack.zip?sign=1"))
        assertTrue(CapturedUrlReceiver.acceptUrl("http://dl.example.com/a/b.APK"))
    }

    @Test
    fun `非 http 协议拒绝`() {
        assertFalse(CapturedUrlReceiver.acceptUrl("javascript:alert(1)"))
        assertFalse(CapturedUrlReceiver.acceptUrl("file:///etc/passwd"))
        assertFalse(CapturedUrlReceiver.acceptUrl("intent://x#Intent;end"))
        assertFalse(CapturedUrlReceiver.acceptUrl(""))
    }

    @Test
    fun `超长与空白字符拒绝`() {
        assertFalse(CapturedUrlReceiver.acceptUrl("https://a.com/" + "x".repeat(5000) + ".apk"))
        assertFalse(CapturedUrlReceiver.acceptUrl("https://a.com/a b.apk"))
        assertFalse(CapturedUrlReceiver.acceptUrl("https://a.com/a\nb.apk"))
    }

    @Test
    fun `带凭据的地址拒绝`() {
        assertFalse(CapturedUrlReceiver.acceptUrl("https://user:pass@dl.example.com/a.apk"))
    }

    @Test
    fun `普通网页与接口地址不记录`() {
        assertFalse(CapturedUrlReceiver.acceptUrl("https://example.com/index.html"))
        assertFalse(CapturedUrlReceiver.acceptUrl("https://example.com/api/detail?id=1"))
    }

    @Test
    fun `下载路径即使没有扩展名也放行`() {
        assertTrue(CapturedUrlReceiver.acceptUrl("https://example.com/download/12345"))
    }
}

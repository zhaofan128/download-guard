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

    @Test
    fun `载荷保留来源页与登录态`() {
        val p = CapturedUrlReceiver.buildIntake(
            url = "https://cdn.example.com/file.zip",
            name = "安装包.zip",
            source = "Xposed·DownloadManager",
            referer = "https://pan.example.com/s/abc",
            userAgent = "Mozilla/5.0 Test",
            cookie = "BDUSS=1; STOKEN=2",
            length = 1024
        )
        assertTrue(p != null)
        requireNotNull(p)
        assertTrue(p.referer == "https://pan.example.com/s/abc")
        assertTrue(p.cookie == "BDUSS=1; STOKEN=2")
        assertTrue(p.size == 1024L)
        assertTrue(p.fileName == "安装包.zip")
    }

    @Test
    fun `非 http 的 Referer 被丢弃`() {
        val p = CapturedUrlReceiver.buildIntake(
            url = "https://cdn.example.com/file.apk",
            name = null,
            source = null,
            referer = "javascript:alert(1)",
            userAgent = null,
            cookie = null,
            length = -5
        )
        requireNotNull(p)
        assertTrue(p.referer == null)
        // 负数长度按 0 处理，不能变成「已下完」
        assertTrue(p.size == 0L)
        assertTrue(p.source == "Xposed 模块")
    }

    @Test
    fun `超长字段被截断`() {
        val p = CapturedUrlReceiver.buildIntake(
            url = "https://cdn.example.com/file.apk",
            name = "n".repeat(1000),
            source = "s".repeat(200),
            referer = "https://example.com/" + "p".repeat(9000),
            userAgent = "u".repeat(2000),
            cookie = "c".repeat(20000),
            length = 1
        )
        requireNotNull(p)
        assertTrue(p.fileName!!.length == 200)
        assertTrue(p.source.length == 48)
        assertTrue(p.referer!!.length == 4096)
        assertTrue(p.userAgent!!.length == 512)
        assertTrue(p.cookie!!.length == 8192)
    }

    @Test
    fun `不合法 URL 不产生载荷`() {
        val p = CapturedUrlReceiver.buildIntake(
            url = "https://example.com/index.html",
            name = "x", source = "s", referer = null, userAgent = null, cookie = null, length = 0
        )
        assertTrue(p == null)
    }

    @Test
    fun `同一链接五秒内只放行一次`() {
        val url = "https://cdn.example.com/dedupe-" + System.nanoTime() + ".apk"
        assertTrue(CapturedUrlReceiver.firstInWindow(url, now = 1_000_000))
        assertFalse(CapturedUrlReceiver.firstInWindow(url, now = 1_000_000 + 4_999))
        // 窗口过去后重新放行：断点续传/重试场景不能永久拉黑同一个地址
        assertTrue(CapturedUrlReceiver.firstInWindow(url, now = 1_000_000 + 5_001))
    }

    @Test
    fun `确认回执只发给模块已知宿主`() {
        // 与 Xposed 模块 GuardHook.TARGET_PACKAGES 同步的列表：命中才放行
        assertTrue(CapturedUrlReceiver.validHostPackage("com.android.chrome"))
        assertTrue(CapturedUrlReceiver.validHostPackage(" com.tencent.mtt "))
        // 不在列表里的一律拒绝：自家包、模块包、任意格式合法的第三方包
        assertFalse(CapturedUrlReceiver.validHostPackage("com.dlguard"))
        assertFalse(CapturedUrlReceiver.validHostPackage("com.dlguard.xposed"))
        assertFalse(CapturedUrlReceiver.validHostPackage("com.evil.attacker"))
        assertFalse(CapturedUrlReceiver.validHostPackage(""))
        assertFalse(CapturedUrlReceiver.validHostPackage(null))
    }
}

package com.dlguard.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网页代抓的重写规则测试。
 *
 * 这里不测网络，只测「无网设备拿到的那份 HTML 是否所有入口都指向网关」——
 * 漏一个就会导致页面点开后白屏（WebView 去直连外网，必然失败）。
 */
class RelayFetchTest {

    private val fetcher = RelayFetch(okhttp3.OkHttpClient())

    @Test
    fun `文件链接被改成 file 端点`() {
        val html = """<html><body><a href="/download/app.apk">下载</a></body></html>"""
        val out = fetcher.rewrite(html, "https://example.com/page")
        assertTrue(out.contains("/file?url="))
        assertTrue(out.contains("app.apk"))
        assertFalse("原始外链不能留在页面里", out.contains("href=\"/download/app.apk\""))
    }

    @Test
    fun `普通链接被改成 fetch 端点`() {
        val html = """<html><body><a href="/next/page">下一页</a></body></html>"""
        val out = fetcher.rewrite(html, "https://example.com/page")
        assertTrue(out.contains("/fetch?url="))
        assertTrue(out.contains("next"))
    }

    @Test
    fun `图片与样式也走网关`() {
        val html = """
            <html><head><link rel="stylesheet" href="/style.css"></head>
            <body><img src="https://cdn.example.com/a.png"></body></html>
        """.trimIndent()
        val out = fetcher.rewrite(html, "https://example.com/page")
        assertTrue(out.contains("/fetch?url=https%3A%2F%2Fcdn.example.com%2Fa.png"))
        assertTrue(out.contains("/fetch?url=https%3A%2F%2Fexample.com%2Fstyle.css"))
    }

    @Test
    fun `base 标签被移除防止相对路径逃逸`() {
        val html = """<html><head><base href="https://evil.example.com/"></head><body>x</body></html>"""
        val out = fetcher.rewrite(html, "https://example.com/page")
        assertFalse(out.contains("<base"))
    }

    @Test
    fun `外链的 target 被去掉`() {
        val html = """<a href="https://example.com/x" target="_blank">x</a>"""
        val out = fetcher.rewrite(html, "https://example.com/page")
        assertFalse(out.contains("target=\"_blank\""))
    }

    @Test
    fun `私网地址被识别并拒绝`() {
        assertTrue(fetcher.isPrivateHost("192.168.1.1"))
        assertTrue(fetcher.isPrivateHost("10.0.0.8"))
        assertTrue(fetcher.isPrivateHost("172.16.3.4"))
        assertTrue(fetcher.isPrivateHost("127.0.0.1"))
        assertTrue(fetcher.isPrivateHost("localhost"))
        assertFalse(fetcher.isPrivateHost("example.com"))
        assertFalse(fetcher.isPrivateHost("8.8.8.8"))
    }

    @Test
    fun `私网 URL 不会被抓取`() {
        assertTrue(fetcher.fetch("http://192.168.1.1/admin") == null)
        assertTrue(fetcher.fetch("ftp://example.com/x") == null)
    }

    @Test
    fun `文件后缀识别覆盖常见类型`() {
        assertTrue(fetcher.looksLikeFile("https://x.com/a.apk"))
        assertTrue(fetcher.looksLikeFile("https://x.com/a.zip?token=1"))
        assertTrue(fetcher.looksLikeFile("https://x.com/a.pdf#page=2"))
        assertFalse(fetcher.looksLikeFile("https://x.com/page.html"))
        assertFalse(fetcher.looksLikeFile("https://x.com/download"))
    }

    @Test
    fun `非 http 地址不做重写`() {
        val html = """<a href="magnet:?xt=urn:btih:abc">磁力</a><a href="javascript:void(0)">js</a>"""
        val out = fetcher.rewrite(html, "https://example.com/page")
        assertTrue(out.contains("magnet:"))
        assertTrue(out.contains("javascript:"))
    }
}

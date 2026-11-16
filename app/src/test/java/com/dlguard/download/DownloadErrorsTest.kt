package com.dlguard.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 失败文案：界面上只有「无法连接下载地址：HttpStatusException」时用户完全无从下手，
 * 这里把「状态码 → 该做什么」的映射钉死。
 */
class DownloadErrorsTest {

    @Test
    fun `鉴权与失效链接给出可操作提示`() {
        assertTrue(DownloadErrors.http(401, null).startsWith("需要登录后才能下载（HTTP 401）"))
        assertTrue(DownloadErrors.http(403, null).startsWith("服务器拒绝访问（HTTP 403）"))
        assertTrue(DownloadErrors.http(404, null).contains("HTTP 404"))
        assertTrue(DownloadErrors.http(410, null).contains("HTTP 410"))
        assertTrue(DownloadErrors.http(429, null).contains("HTTP 429"))
        assertTrue(DownloadErrors.http(503, null).contains("HTTP 503"))
    }

    @Test
    fun `未收录的状态码仍带上 code 与服务器原话`() {
        assertEquals("HTTP 418：I'm a teapot", DownloadErrors.http(418, "I'm a teapot"))
        assertEquals("HTTP 400：服务器拒绝了请求", DownloadErrors.http(400, "  "))
        assertEquals("HTTP 502：服务器拒绝了请求", DownloadErrors.http(502, null))
    }

    @Test
    fun `网络异常翻译成人话`() {
        assertTrue(DownloadErrors.network(UnknownHostException("qfile.qq.com")).contains("域名解析失败"))
        assertTrue(DownloadErrors.network(SocketTimeoutException("timeout")).contains("连接超时"))
        assertTrue(DownloadErrors.network(ConnectException("refused")).contains("连接被拒绝"))
        assertTrue(DownloadErrors.network(IllegalStateException("boom")).contains("IllegalStateException"))
    }

    @Test
    fun `网页响应按页面处理而不是当文件存`() {
        assertTrue(isHtmlDocument("text/html; charset=utf-8", -1L))
        assertTrue(isHtmlDocument("application/xhtml+xml", 1024L))
        // 少数站点给真实文件配错类型，大文件放行
        assertFalse(isHtmlDocument("text/html", 8L * 1024 * 1024))
        assertFalse(isHtmlDocument("application/vnd.android.package-archive", -1L))
        assertFalse(isHtmlDocument(null, 100L))
        assertTrue(DownloadErrors.htmlPage().contains("网页"))
    }
}

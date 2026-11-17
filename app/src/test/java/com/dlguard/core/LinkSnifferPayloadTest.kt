package com.dlguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接口响应体解析。
 *
 * README 早就写了「能抓接口返回 JSON 里的地址」，但旧实现只看请求头和 URL，
 * 从来没读过响应体 —— 也就是这条能力当时并不存在。现在补上解析器，用真实
 * 站点常见的三种返回形态锁住行为。
 */
class LinkSnifferPayloadTest {

    private val base = "https://www.example.com/detail/123"

    @Test
    fun `标准 JSON 里的直链`() {
        val body = """{"code":0,"data":{"url":"https://dl.example.com/app/weixin_8050.apk","size":123}}"""
        val hits = LinkSniffer.extractUrlsFromPayload(body, base)
        assertEquals(1, hits.size)
        assertEquals("https://dl.example.com/app/weixin_8050.apk", hits.first().url)
        assertEquals(LinkSniffer.ORIGIN_API, hits.first().origin)
    }

    @Test
    fun `转义斜杠的 JSON`() {
        val body = """{"url":"https:\/\/dl.example.com\/pkg\/tool.zip?sign=abc"}"""
        val hits = LinkSniffer.extractUrlsFromPayload(body, base)
        assertEquals(1, hits.size)
        assertTrue(hits.first().url.startsWith("https://dl.example.com/pkg/tool.zip"))
    }

    @Test
    fun `相对路径按来源页补全`() {
        val body = """{"url":"/down/file.apk"}"""
        val hits = LinkSniffer.extractUrlsFromPayload(body, base)
        assertEquals(1, hits.size)
        assertEquals("https://www.example.com/down/file.apk", hits.first().url)
    }

    @Test
    fun `接口自身地址不会被当成下载候选`() {
        val body = """{"api":"https://www.example.com/api/v1/detail","msg":"ok"}"""
        assertTrue(LinkSniffer.extractUrlsFromPayload(body, base).isEmpty())
    }

    @Test
    fun `重复地址只保留一条`() {
        val body = """
            {"list":[
              {"url":"https://dl.example.com/a.apk"},
              {"url":"https://dl.example.com/a.apk"},
              {"url":"https://dl.example.com/b.apk"}
            ]}
        """.trimIndent()
        val hits = LinkSniffer.extractUrlsFromPayload(body, base)
        assertEquals(2, hits.size)
        assertEquals(hits.size, hits.map { it.url }.distinct().size)
    }

    @Test
    fun `256KB 边界`() {
        assertFalse(LinkSniffer.payloadTooLarge(262_144))
        assertTrue(LinkSniffer.payloadTooLarge(262_145))
    }

    @Test
    fun `超长响应体直接放弃`() {
        val body = "x".repeat(300_000) + "https://dl.example.com/a.apk"
        assertTrue(LinkSniffer.extractUrlsFromPayload(body, base).isEmpty())
    }

    @Test
    fun `纯文本里夹着的下载页地址`() {
        val body = "请访问 https://dl.example.com/download/xyz 获取安装包"
        val hits = LinkSniffer.extractUrlsFromPayload(body, base)
        assertEquals(1, hits.size)
    }
}

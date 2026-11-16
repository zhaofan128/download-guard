package com.dlguard.core

import com.dlguard.integrity.FileHasher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外部哈希比对与网络层嗅探的纯函数契约。
 *
 * 这两块都是「用户信任」相关的能力：哈希比错了会把好文件判成坏的，
 * 网络层判宽了会把广告/统计请求塞进候选列表。所以边界要锁住。
 */
class HashAndSnifferTest {

    // ---------------------------------------------------------------- 哈希解析

    @Test
    fun `parses sha256 with common label styles`() {
        val hex = "a".repeat(64)
        listOf(
            "SHA256: $hex",
            "sha-256 = $hex",
            "SHA-256：$hex",
            "文件校验值\nSHA256\n$hex"
        ).forEach { raw ->
            val parsed = FileHasher.parseBaseline(raw)
            assertNotNull("应能解析：$raw", parsed)
            assertEquals("SHA-256", parsed!!.first)
            assertEquals(hex, parsed.second)
        }
    }

    @Test
    fun `parses bare hex by length`() {
        val sha = "b".repeat(64)
        val md5 = "c".repeat(32)
        assertEquals("SHA-256" to sha, FileHasher.parseBaseline(sha))
        assertEquals("MD5" to md5, FileHasher.parseBaseline(md5))
        assertEquals("SHA-1" to "d".repeat(40), FileHasher.parseBaseline("d".repeat(40)))
    }

    @Test
    fun `rejects malformed or mismatched baselines`() {
        assertNull(FileHasher.parseBaseline(""))
        assertNull(FileHasher.parseBaseline("没有哈希的一段说明"))
        // 标签写 SHA-256 但给的是 32 位：长度不符，宁可拒绝也不猜
        assertNull(FileHasher.parseBaseline("SHA256: " + "e".repeat(32)))
    }

    @Test
    fun `matches ignores case and rejects null`() {
        val hex = "AbCd".repeat(16)
        assertTrue(FileHasher.matches(hex.lowercase(), hex))
        assertFalse(FileHasher.matches(null, hex))
        assertFalse(FileHasher.matches(hex, "f".repeat(64)))
    }

    // ---------------------------------------------------------------- 页面哈希提取

    @Test
    fun `extracts tagged hashes from page text`() {
        val sha = "1".repeat(64)
        val md5 = "2".repeat(32)
        val page = """
            软件名称：示例工具 v2.1
            文件名：tool-2.1.apk
            文件大小：48.2 MB
            MD5：$md5
            SHA256: $sha
            下载地址：https://example.com/tool-2.1.apk
        """.trimIndent()

        val hints = LinkSniffer.extractHashes(page)
        assertTrue(hints.any { it.algorithm == "SHA-256" && it.value == sha })
        assertTrue(hints.any { it.algorithm == "MD5" && it.value == md5 })
    }

    @Test
    fun `extract hashes returns empty for plain text`() {
        val hints = LinkSniffer.extractHashes("这页只有一句说明，没有任何校验值和长十六进制串")
        assertTrue(hints.isEmpty())
    }

    @Test
    fun `hash hints are deduplicated`() {
        val sha = "3".repeat(64)
        val page = "SHA256: $sha\nSHA-256: $sha\nsha256=$sha"
        val hints = LinkSniffer.extractHashes(page)
        assertEquals(1, hints.count { it.value == sha })
    }

    // ---------------------------------------------------------------- 网络层判定

    @Test
    fun `wire detection accepts file urls and binary accept headers`() {
        assertTrue(
            LinkSniffer.looksLikeWireDownload(
                "https://cdn.example.com/a/tool.apk", "", "GET"
            )
        )
        assertTrue(
            LinkSniffer.looksLikeWireDownload(
                "https://api.example.com/download?id=9",
                "application/octet-stream",
                "GET"
            )
        )
        assertTrue(
            LinkSniffer.looksLikeWireDownload(
                "https://api.example.com/file/9", "application/zip", "POST"
            )
        )
    }

    @Test
    fun `wire detection ignores pages json and preflight`() {
        // 普通页面/接口：不進候选，否则列表会被统计请求淹没
        assertFalse(
            LinkSniffer.looksLikeWireDownload(
                "https://example.com/index.html", "text/html", "GET"
            )
        )
        assertFalse(
            LinkSniffer.looksLikeWireDownload(
                "https://api.example.com/list", "application/json", "GET"
            )
        )
        // OPTIONS 预检不是真实下载请求
        assertFalse(
            LinkSniffer.looksLikeWireDownload(
                "https://cdn.example.com/a.apk", "application/octet-stream", "OPTIONS"
            )
        )
        assertFalse(
            LinkSniffer.looksLikeWireDownload("blob:https://a.com/x", "*/*", "GET")
        )
    }

    // ---------------------------------------------------------------- 流媒体

    @Test
    fun `stream kinds are recognised and never treated as plain files`() {
        assertEquals(LinkSniffer.StreamKind.HLS_PLAYLIST, LinkSniffer.streamKind("https://a.com/v.m3u8?t=1"))
        assertEquals(LinkSniffer.StreamKind.DASH_MANIFEST, LinkSniffer.streamKind("https://a.com/v.mpd"))
        assertEquals(LinkSniffer.StreamKind.SEGMENT, LinkSniffer.streamKind("https://a.com/seg/1.ts"))
        assertNull(LinkSniffer.streamKind("https://a.com/v.mp4"))
    }

    @Test
    fun `stream urls rank below installers`() {
        val merged = LinkSniffer.mergeCandidates(
            "https://a.com/page",
            dom = emptyList(),
            wire = listOf(
                LinkSniffer.Candidate("https://a.com/v.m3u8", "视频", 0, LinkSniffer.ORIGIN_WIRE),
                LinkSniffer.Candidate("https://a.com/app.apk", "安装包", 0, LinkSniffer.ORIGIN_WIRE)
            )
        )
        assertEquals("https://a.com/app.apk", merged.first().url)
    }

    // ---------------------------------------------------------------- 多路合并

    @Test
    fun `merge keeps wire origin and deduplicates`() {
        val url = "https://a.com/app.apk"
        val merged = LinkSniffer.mergeCandidates(
            "https://a.com/page",
            dom = listOf(LinkSniffer.Candidate(url, "下载", 120, LinkSniffer.ORIGIN_DOM)),
            wire = listOf(LinkSniffer.Candidate(url, "", 0, LinkSniffer.ORIGIN_WIRE))
        )
        assertEquals(1, merged.size)
        assertEquals(LinkSniffer.ORIGIN_WIRE, merged.first().origin)
    }
}

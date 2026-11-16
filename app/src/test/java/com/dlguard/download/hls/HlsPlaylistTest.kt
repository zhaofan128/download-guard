package com.dlguard.download.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放列表解析是 HLS 下载的地基：解析错了，后面下得再快也是垃圾。
 * 这几条用例覆盖的都是真实 m3u8 里会出现的写法（相对地址、带签名查询串、
 * 引号里的逗号、密钥轮换、DRM 标记），以及必须明确拒绝的两类（DRM / 直播）。
 */
class HlsPlaylistTest {

    private val base = "https://cdn.example.com/vod/2024/index.m3u8?sign=abc123"

    @Test
    fun `media playlist parses segments and endlist`() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:6.0,
            seg0.ts
            #EXTINF:4.5,
            seg1.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val result = HlsPlaylistParser.parse(text, base)
        assertTrue(result is HlsParseResult.Media)
        val playlist = (result as HlsParseResult.Media).playlist
        assertEquals(2, playlist.segments.size)
        assertEquals(10.5, playlist.totalDurationSec, 0.001)
        assertTrue(playlist.ended)
        assertEquals("https://cdn.example.com/vod/2024/seg0.ts?sign=abc123", playlist.segments[0].uri)
        assertNull(playlist.segments[0].key)
        assertFalse(playlist.hasEncryption)
    }

    @Test
    fun `master playlist keeps variants sorted by bandwidth`() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            high/index.m3u8
        """.trimIndent()

        val result = HlsPlaylistParser.parse(text, base)
        assertTrue(result is HlsParseResult.Master)
        val variants = (result as HlsParseResult.Master).variants
        assertEquals(2, variants.size)
        assertEquals(5_000_000L, variants[0].bandwidth)
        assertEquals("1920x1080", variants[0].resolution)
        assertEquals("https://cdn.example.com/vod/2024/high/index.m3u8?sign=abc123", variants[0].uri)
    }

    @Test
    fun `absolute segment url is left untouched`() {
        val text = """
            #EXTM3U
            #EXTINF:2,
            https://other.cdn.net/a.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = (HlsPlaylistParser.parse(text, base) as HlsParseResult.Media).playlist
        assertEquals("https://other.cdn.net/a.ts", playlist.segments[0].uri)
    }

    @Test
    fun `aes-128 key is captured with explicit iv`() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x000102030405060708090a0b0c0d0e0f
            #EXTINF:2,
            a.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = (HlsPlaylistParser.parse(text, base) as HlsParseResult.Media).playlist
        assertTrue(playlist.hasEncryption)
        val key = playlist.segments[0].key
        assertEquals("AES-128", key?.method)
        assertEquals("https://cdn.example.com/vod/2024/key.bin?sign=abc123", key?.uri)
        assertEquals(16, key?.iv?.size)
        assertEquals(0x0f.toByte(), key?.iv?.get(15))
    }

    @Test
    fun `key none clears encryption`() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="k1.bin"
            #EXTINF:2,
            a.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:2,
            b.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = (HlsPlaylistParser.parse(text, base) as HlsParseResult.Media).playlist
        assertEquals("AES-128", playlist.segments[0].key?.method)
        assertNull(playlist.segments[1].key)
    }

    @Test
    fun `sample-aes drm is refused`() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://key",KEYFORMAT="com.apple.streamingkeydelivery"
            #EXTINF:2,
            a.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val result = HlsPlaylistParser.parse(text, base)
        assertTrue(result is HlsParseResult.Invalid)
        assertTrue((result as HlsParseResult.Invalid).reason.contains("不做绕过"))
    }

    @Test
    fun `non identity keyformat is refused`() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="k.bin",KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
            #EXTINF:2,
            a.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val result = HlsPlaylistParser.parse(text, base)
        assertTrue(result is HlsParseResult.Invalid)
    }

    @Test
    fun `live playlist is marked not ended`() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXTINF:4,
            a.ts
            #EXTINF:4,
            b.ts
        """.trimIndent()

        val playlist = (HlsPlaylistParser.parse(text, base) as HlsParseResult.Media).playlist
        assertFalse(playlist.ended)
        assertFalse(playlist.vod)
    }

    @Test
    fun `vod type without endlist is treated as vod`() {
        val text = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:4,
            a.ts
        """.trimIndent()

        val playlist = (HlsPlaylistParser.parse(text, base) as HlsParseResult.Media).playlist
        assertFalse(playlist.ended)
        assertTrue(playlist.vod)
    }

    @Test
    fun `html error page is not a playlist`() {
        val result = HlsPlaylistParser.parse("<html><body>403 Forbidden</body></html>", base)
        assertTrue(result is HlsParseResult.Invalid)
    }

    @Test
    fun `attribute parser keeps commas inside quotes`() {
        val attrs = HlsPlaylistParser.parseAttributes(
            "BANDWIDTH=512000,CODECS=\"avc1.4d401e,mp4a.40.2\",RESOLUTION=1280x720"
        )
        assertEquals("512000", attrs["BANDWIDTH"])
        assertEquals("avc1.4d401e,mp4a.40.2", attrs["CODECS"])
        assertEquals("1280x720", attrs["RESOLUTION"])
    }

    @Test
    fun `hls url detection`() {
        assertTrue(HlsUrls.isPlaylist("https://a.com/v.m3u8?x=1"))
        assertFalse(HlsUrls.isPlaylist("https://a.com/v.mp4"))
        assertFalse(HlsUrls.isPlaylist("https://a.com/list.m3u8.txt"))
    }
}

package com.dlguard.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 磁力解析：BT 引擎的入口，解析错了后面全白搭。 */
class MagnetTest {

    @Test
    fun `识别磁力前缀`() {
        assertTrue(Magnet.isMagnet("magnet:?xt=urn:btih:abc"))
        assertTrue(Magnet.isMagnet("  MAGNET:?xt=urn:btih:abc  "))
        assertFalse(Magnet.isMagnet("https://example.com/a.torrent"))
    }

    @Test
    fun `解析40位hex与显示名`() {
        val hash = "c12fe1c06bba254a9dc9f519b335aa7c1367a88a"
        val p = Magnet.parse("magnet:?xt=urn:btih:$hash&dn=Big+File.zip")!!
        assertEquals(hash, p.infoHash)
        assertEquals("Big File.zip", p.displayName)
    }

    @Test
    fun `解析base32形式并转成hex`() {
        // RFC 4648 向量：MFRGGZDF... 解出的是 ASCII a-t（61 62 63 ... 74）
        val p = Magnet.parse("magnet:?xt=urn:btih:MFRGGZDFMZTWQ2LKNNWG23TPOBYXE43U")!!
        assertEquals("6162636465666768696a6b6c6d6e6f7071727374", p.infoHash)
    }

    @Test
    fun `解析多个tracker`() {
        val hash = "c12fe1c06bba254a9dc9f519b335aa7c1367a88a"
        val p = Magnet.parse(
            "magnet:?xt=urn:btih:$hash&tr=http%3A%2F%2Fa.com%2Fannounce&tr=udp%3A%2F%2Fb.com%3A80"
        )!!
        assertEquals(2, p.trackers.size)
        assertEquals("http://a.com/announce", p.trackers[0])
        assertEquals("udp://b.com:80", p.trackers[1])
    }

    @Test
    fun `拒绝v2的btmh而不是猜一个hash`() {
        assertNull(Magnet.parse("magnet:?xt=urn:btmh:1220abcdef&dn=v2"))
    }

    @Test
    fun `拒绝非法hash与空链接`() {
        assertNull(Magnet.parse("magnet:?xt=urn:btih:short"))
        assertNull(Magnet.parse("magnet:"))
        assertNull(Magnet.parse("not a magnet"))
    }

    @Test
    fun `中文显示名用UTF8百分号解码`() {
        val hash = "c12fe1c06bba254a9dc9f519b335aa7c1367a88a"
        val p = Magnet.parse("magnet:?xt=urn:btih:$hash&dn=%E7%94%B5%E5%BD%B1.mp4")!!
        assertEquals("电影.mp4", p.displayName)
    }
}

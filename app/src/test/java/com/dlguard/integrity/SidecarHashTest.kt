package com.dlguard.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 旁挂哈希解析必须「宁可不用，也不能用错」：
 * 同目录常有多个产物的校验文件，拿错文件名比不校验更危险（会给出错误的「一致」结论）。
 */
class SidecarHashTest {

    private val sha = "a".repeat(64)
    private val md5 = "b".repeat(32)
    private val sha1 = "c".repeat(40)

    @Test
    fun `plain single hash line is accepted`() {
        val found = SidecarHash.parse(sha, "app.apk", "https://x/app.apk.sha256")
        assertNotNull(found)
        assertEquals("SHA-256", found!!.algorithm)
        assertEquals(sha, found.hash)
    }

    @Test
    fun `sha256sum format with matching file name`() {
        val text = "$sha  DownloadGuard-1.9.0.apk"
        val found = SidecarHash.parse(text, "DownloadGuard-1.9.0.apk")
        assertEquals(sha, found?.hash)
    }

    @Test
    fun `checksum for another file is rejected`() {
        val text = "$sha  other-release.apk"
        assertNull(SidecarHash.parse(text, "DownloadGuard-1.9.0.apk"))
    }

    @Test
    fun `gnu style with file name`() {
        val text = "SHA256 (DownloadGuard-1.9.0.apk) = $sha"
        assertEquals(sha, SidecarHash.parse(text, "DownloadGuard-1.9.0.apk")?.hash)
    }

    @Test
    fun `md5 and sha1 are detected by length`() {
        assertEquals("MD5", SidecarHash.parse(md5, "a.apk")?.algorithm)
        assertEquals("SHA-1", SidecarHash.parse(sha1, "a.apk")?.algorithm)
    }

    @Test
    fun `html error page is rejected`() {
        val html = "<!DOCTYPE html><html><body>$sha</body></html>"
        assertNull(SidecarHash.parse(html, "a.apk"))
    }

    @Test
    fun `hash bytes inside a longer token are not extracted`() {
        val embedded = "0$sha" + "0"
        assertNull(SidecarHash.parse(embedded, "a.apk"))
    }
}

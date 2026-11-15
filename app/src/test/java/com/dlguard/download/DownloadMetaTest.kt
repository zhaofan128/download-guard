package com.dlguard.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadMetaTest {

    @Test
    fun `round trip keeps url validators and per segment progress`() {
        val meta = DownloadMeta(
            url = "https://example.com/app.apk?sig=1",
            totalBytes = 300,
            etag = "\"abc\"",
            lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
            segments = listOf(
                SegmentState(0, 99, 40),
                SegmentState(100, 199, 100),
                SegmentState(200, 299, 0)
            )
        )
        val parsed = DownloadMeta.parse(meta.toText())
        assertNotNull(parsed)
        assertEquals(meta.url, parsed!!.url)
        assertEquals(300L, parsed.totalBytes)
        assertEquals("\"abc\"", parsed.etag)
        assertEquals(140L, parsed.downloadedBytes)
        assertTrue(!parsed.complete)
    }

    @Test
    fun `gaps in segment table invalidate the whole metadata`() {
        val broken = """
            DLGM1
            url=${java.util.Base64.getEncoder().encodeToString("https://a/b.apk".toByteArray())}
            total=300
            seg=0,99,10
            seg=150,299,0
        """.trimIndent()
        assertNull(DownloadMeta.parse(broken))
    }

    @Test
    fun `corrupted text is rejected instead of resuming garbage`() {
        assertNull(DownloadMeta.parse("not-a-meta-file"))
        assertNull(DownloadMeta.parse("DLGM1\nurl=???\ntotal=abc"))
    }

    @Test
    fun `done bytes are clamped into the segment range`() {
        val text = """
            DLGM1
            url=${java.util.Base64.getEncoder().encodeToString("https://a/b.apk".toByteArray())}
            total=100
            seg=0,99,999
        """.trimIndent()
        val parsed = DownloadMeta.parse(text)
        assertNotNull(parsed)
        assertEquals(100L, parsed!!.segments.first().done)
        assertTrue(parsed.complete)
    }
}

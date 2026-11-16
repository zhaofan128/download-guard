package com.dlguard.download.hls

import com.dlguard.download.DownloadStage
import com.dlguard.integrity.IntegrityResult
import com.dlguard.integrity.Verdict
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HLS 下载器的端到端测试：真的起一个 HTTP 源站，真的拉列表、拉分片、解密、拼接。
 * 断言的是「落到磁盘上的字节」，不是「调用过某个方法」。
 */
class HlsDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private var requested = mutableListOf<String>()

    private val playlist = """
        #EXTM3U
        #EXT-X-TARGETDURATION:2
        #EXT-X-MEDIA-SEQUENCE:0
        #EXTINF:2.0,
        a.ts
        #EXTINF:2.0,
        b.ts
        #EXTINF:2.0,
        c.ts
        #EXT-X-ENDLIST
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = Files.createTempDirectory("hls-test").toFile()
        requested = mutableListOf()
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun downloader(verified: MutableList<Long>? = null): HlsDownloader = HlsDownloader(
        client = OkHttpClient(),
        targetDir = { dir },
        verify = { file, size ->
            verified?.add(size)
            IntegrityResult(Verdict.OK, "ok", file.length(), declaredSize = size)
        }
    )

    private fun serve(handler: (String) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                requested += path
                return handler(path)
            }
        }
    }

    @Test
    fun `segments are concatenated in order`() = runBlocking {
        serve { path ->
            when (path) {
                "/index.m3u8" -> MockResponse().setBody(playlist)
                "/a.ts" -> MockResponse().setBody("AAA")
                "/b.ts" -> MockResponse().setBody("BBB")
                "/c.ts" -> MockResponse().setBody("CCC")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val stages = mutableListOf<DownloadStage>()
        val verified = mutableListOf<Long>()
        val outcome = downloader(verified).download(
            url = server.url("/index.m3u8").toString(),
            preferredName = "clip"
        ) { stages += it.stage }

        assertNull(outcome.error)
        val file = outcome.file
        assertNotNull(file)
        assertEquals("clip.ts", file!!.name)
        assertEquals("AAABBBCCC", file.readText())
        assertEquals(9L, outcome.bytes)
        assertEquals(listOf(9L), verified)
        assertTrue(stages.contains(DownloadStage.VERIFYING))
    }

    @Test
    fun `master playlist picks the highest bandwidth variant`() = runBlocking {
        serve { path ->
            when (path) {
                "/master.m3u8" -> MockResponse().setBody(
                    """
                    #EXTM3U
                    #EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=640x360
                    low/index.m3u8
                    #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080
                    high/index.m3u8
                    """.trimIndent()
                )
                "/low/index.m3u8" -> MockResponse().setBody(playlist.replace("a.ts", "low.ts"))
                "/high/index.m3u8" -> MockResponse().setBody(playlist)
                else -> if (path.endsWith(".ts")) MockResponse().setBody("X")
                else MockResponse().setResponseCode(404)
            }
        }

        val outcome = downloader().download(server.url("/master.m3u8").toString(), "pick")
        assertNull(outcome.error)
        assertTrue(requested.contains("/high/index.m3u8"))
        assertTrue(!requested.contains("/low/index.m3u8"))
        assertEquals("XXX", outcome.file?.readText())
    }

    @Test
    fun `live stream is refused instead of producing a partial file`() = runBlocking {
        val live = playlist.replace("#EXT-X-ENDLIST", "")
        serve { path ->
            if (path == "/live.m3u8") MockResponse().setBody(live) else MockResponse().setResponseCode(404)
        }

        val outcome = downloader().download(server.url("/live.m3u8").toString(), "live")
        assertNull(outcome.file)
        assertTrue(outcome.error.orEmpty().contains("直播"))
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `aes-128 segments are decrypted before writing`() = runBlocking {
        val key = ByteArray(16) { (it + 1).toByte() }
        val plain = "hello-hls".toByteArray()
        val seg0 = encrypt(plain, key, ivFor(0))
        val seg1 = encrypt(plain, key, ivFor(1))
        val encryptedPlaylist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:2
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:2.0,
            e0.ts
            #EXTINF:2.0,
            e1.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        serve { path ->
            when (path) {
                "/enc.m3u8" -> MockResponse().setBody(encryptedPlaylist)
                "/key.bin" -> MockResponse().setBody(Buffer().write(key))
                "/e0.ts" -> MockResponse().setBody(Buffer().write(seg0))
                "/e1.ts" -> MockResponse().setBody(Buffer().write(seg1))
                else -> MockResponse().setResponseCode(404)
            }
        }

        val outcome = downloader().download(server.url("/enc.m3u8").toString(), "enc")
        assertNull(outcome.error)
        assertEquals("hello-hlshello-hls", outcome.file?.readText())
        // 密钥只取一次，两个分片共用
        assertEquals(1, requested.count { it == "/key.bin" })
    }

    @Test
    fun `resume continues from the recorded segment`() = runBlocking {
        serve { path ->
            when (path) {
                "/index.m3u8" -> MockResponse().setBody(playlist)
                "/a.ts" -> MockResponse().setBody("AAA")
                "/b.ts" -> MockResponse().setBody("BBB")
                "/c.ts" -> MockResponse().setBody("CCC")
                else -> MockResponse().setResponseCode(404)
            }
        }
        // 模拟「下完第一片后被系统杀掉」：part 里已有 AAA，旁挂记录写着片号 1
        File(dir, "clip.ts.part").writeText("AAA")
        File(dir, "clip.ts.part.hls").writeText("v1\n${sha256(playlist)}\n1\n3\n")

        val outcome = downloader().download(server.url("/index.m3u8").toString(), "clip")
        assertNull(outcome.error)
        assertEquals("AAABBBCCC", outcome.file?.readText())
        assertTrue(!requested.contains("/a.ts"))
        assertTrue(requested.contains("/b.ts"))
        assertTrue(requested.contains("/c.ts"))
    }

    @Test
    fun `changed playlist invalidates the resume point`() = runBlocking {
        serve { path ->
            when (path) {
                "/index.m3u8" -> MockResponse().setBody(playlist)
                "/a.ts" -> MockResponse().setBody("AAA")
                "/b.ts" -> MockResponse().setBody("BBB")
                "/c.ts" -> MockResponse().setBody("CCC")
                else -> MockResponse().setResponseCode(404)
            }
        }
        File(dir, "clip.ts.part").writeText("OLD-DATA-FROM-ANOTHER-PLAYLIST")
        File(dir, "clip.ts.part.hls").writeText("v1\ndeadbeef\n2\n31\n")

        val outcome = downloader().download(server.url("/index.m3u8").toString(), "clip")
        assertNull(outcome.error)
        assertEquals("AAABBBCCC", outcome.file?.readText())
        assertTrue(requested.contains("/a.ts"))
    }

    @Test
    fun `failing segment reports http code`() = runBlocking {
        serve { path ->
            when (path) {
                "/index.m3u8" -> MockResponse().setBody(playlist)
                "/a.ts" -> MockResponse().setBody("AAA")
                "/b.ts" -> MockResponse().setResponseCode(404)
                else -> MockResponse().setBody("CCC")
            }
        }

        val outcome = downloader().download(server.url("/index.m3u8").toString(), "clip")
        assertNull(outcome.file)
        assertEquals(404, outcome.httpCode)
        assertTrue(outcome.error.orEmpty().contains("分片下载失败"))
    }

    @Test
    fun `wrong sized key is rejected`() = runBlocking {
        val encryptedPlaylist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="short.bin"
            #EXTINF:2.0,
            e0.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        serve { path ->
            when (path) {
                "/enc.m3u8" -> MockResponse().setBody(encryptedPlaylist)
                "/short.bin" -> MockResponse().setBody("1234")
                else -> MockResponse().setBody(Buffer().write(ByteArray(32)))
            }
        }

        val outcome = downloader().download(server.url("/enc.m3u8").toString(), "enc")
        assertNull(outcome.file)
        assertTrue(outcome.error.orEmpty().contains("16 字节"))
    }

    private fun encrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun ivFor(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        var value = sequence
        for (i in 15 downTo 8) {
            iv[i] = (value and 0xFF).toByte()
            value = value shr 8
        }
        return iv
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

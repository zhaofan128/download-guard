package com.dlguard.relay

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 接力网关的端到端测试：真的起一个 HTTP 源站 + 真的起代理，
 * 再用 OkHttp 配代理去下——不是 mock 转发逻辑，链路上每一段都是真 Socket。
 */
class RelayServerTest {

    private lateinit var origin: MockWebServer
    private lateinit var relay: RelayServer
    private var relayPort = 0
    private val token = "test-token-123"

    private val payload = ByteArray(256 * 1024) { (it % 251).toByte() }

    @Before
    fun setUp() {
        origin = MockWebServer()
        origin.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                if (range != null) {
                    val spec = range.removePrefix("bytes=")
                    val start = spec.substringBefore('-').toInt()
                    val end = spec.substringAfter('-').toInt()
                    val slice = payload.copyOfRange(start, end + 1)
                    return MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Type", "application/octet-stream")
                        .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                        .setBody(Buffer().write(slice))
                }
                return MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/octet-stream")
                    .setBody(Buffer().write(payload))
            }
        }
        origin.start()
        relay = RelayServer(token)
        relayPort = relay.start(0)
    }

    @After
    fun tearDown() {
        relay.stop()
        origin.shutdown()
    }

    private fun client(withAuth: Boolean = true): OkHttpClient =
        OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", relayPort)))
            .proxyAuthenticator { _, response ->
                if (withAuth && response.code == 407) {
                    response.request.newBuilder()
                        .header("Proxy-Authorization", Credentials.basic("relay", token))
                        .build()
                } else {
                    null
                }
            }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

    private fun sourceUrl(): String = "http://127.0.0.1:${origin.port}/file.bin"

    @Test
    fun `完整下载经代理后字节与源站一致`() {
        val request = Request.Builder().url(sourceUrl()).build()
        client().newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            val body = response.body?.bytes() ?: ByteArray(0)
            assertEquals(payload.size, body.size)
            assertTrue("内容必须逐字节一致", payload.contentEquals(body))
        }
    }

    @Test
    fun `断点续传的 Range 请求能拿到 206 与正确片段`() {
        val request = Request.Builder()
            .url(sourceUrl())
            .header("Range", "bytes=1024-2047")
            .build()
        client().newCall(request).execute().use { response ->
            assertEquals(206, response.code)
            val slice = response.body?.bytes() ?: ByteArray(0)
            assertEquals(1024, slice.size)
            assertTrue(payload.copyOfRange(1024, 2048).contentEquals(slice))
        }
    }

    @Test
    fun `没有令牌的客户端被 407 拒绝`() {
        val request = Request.Builder().url(sourceUrl()).build()
        val bare = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", relayPort)))
            .build()
        bare.newCall(request).execute().use { response ->
            assertEquals(407, response.code)
        }
    }

    @Test
    fun `错误令牌同样被拒绝`() {
        // 直接走 Socket 看原始响应行：OkHttp 会把「认证重试后仍 407」包装成 ProtocolException，
        // 反而看不到真实状态码
        java.net.Socket("127.0.0.1", relayPort).use { socket ->
            val bad = java.util.Base64.getEncoder()
                .encodeToString("relay:wrong".toByteArray())
            val raw = "GET ${sourceUrl()} HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "Proxy-Authorization: Basic $bad\r\n\r\n"
            socket.getOutputStream().write(raw.toByteArray())
            socket.getOutputStream().flush()
            val status = socket.getInputStream().bufferedReader().readLine()
            assertTrue("期望 407，实际：$status", status.contains("407"))
        }
    }

    @Test
    fun `并发下载互不串流`() {
        val threads = (1..6).map { index ->
            Thread {
                val request = Request.Builder().url("${sourceUrl()}?n=$index").build()
                client().newCall(request).execute().use { r ->
                    assertEquals(200, r.code)
                    val body = r.body?.bytes() ?: ByteArray(0)
                    assertEquals(payload.size, body.size)
                    assertTrue(payload.contentEquals(body))
                }
            }.apply { start() }
        }
        threads.forEach { it.join(20_000) }
        assertEquals(6, threads.count { !it.isAlive })
    }
}

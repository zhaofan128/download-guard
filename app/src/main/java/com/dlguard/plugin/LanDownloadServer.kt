package com.dlguard.plugin

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 局域网下载接收服务（「浏览器下载桥」插件的真实实现）。
 *
 * 浏览器扩展把下载 URL POST 过来 → 交给应用级 [com.dlguard.download.DownloadCenter]
 * 用 48 线程引擎下载并做完整性校验，完成后手机通知栏提示。
 *
 * 协议（极简 HTTP/1.1，不引第三方服务器库）：
 *  - `GET  /api/ping`      → `{"ok":true,"name":"DownloadGuard","version":"x","tokenRequired":true}`
 *  - `POST /api/download`  → body `{"url":"https://…","name":"可选文件名"}`，header `X-DG-Token: <token>`
 *  - `OPTIONS *`           → 204 + CORS（方便电脑浏览器扩展与调试页直接调用）
 *
 * 安全：
 *  - 只监听局域网（0.0.0.0:8787），token 随机 24 位、首次生成后持久化；
 *  - 拒绝非 http/https 链接；同一时间只接受一个下载任务（与 App 内的策略一致）；
 *  - 请求体上限 64KB，防止被当成文件上传口。
 */
class LanDownloadServer(
    private val context: Context,
    /** 端口由插件配置提供，改动后调用 [restart] 生效。 */
    private val portProvider: () -> Int = { PORT },
    private val onDownload: (url: String, name: String?) -> String
) {

    companion object {
        const val PORT = 8787
        private const val TAG = "LanDownloadServer"
        private const val MAX_BODY = 64 * 1024
    }

    private val prefs = context.getSharedPreferences(PluginManager.PREF, Context.MODE_PRIVATE)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newFixedThreadPool(4)
    private val running = AtomicBoolean(false)

    /** 当前生效端口。 */
    val port: Int get() = portProvider().coerceIn(1024, 65535)

    /**
     * 访问令牌：用户在插件配置里自定义时优先用自定义值，
     * 否则首次使用生成 24 位随机串并持久化。
     */
    val token: String
        get() {
            val custom = prefs.getString("lan_token_custom", "").orEmpty().trim()
            if (custom.isNotBlank()) return custom
            return prefs.getString("lan_token", null) ?: run {
                val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
                val random = SecureRandom()
                val value = buildString(24) { append(alphabet[random.nextInt(alphabet.length)]) }
                prefs.edit().putString("lan_token", value).apply()
                value
            }
        }

    val isRunning: Boolean get() = running.get()

    @Synchronized
    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val socket = ServerSocket(port).apply { reuseAddress = true }
            serverSocket = socket
            running.set(true)
            acceptThread = Thread({ acceptLoop(socket) }, "lan-download-accept").apply {
                isDaemon = true
                start()
            }
            Log.i(TAG, "listening on $port")
            true
        } catch (e: Exception) {
            Log.w(TAG, "start failed on $port: ${e.message}")
            running.set(false)
            false
        }
    }

    /** 配置变更（端口 / Token）后重启监听。 */
    @Synchronized
    fun restart(): Boolean {
        stop()
        return start()
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
    }

    /** 本机局域网地址（找不到 wifi/热点地址时返回 null）。 */
    fun localAddress(): String? {
        val ip = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
        return ip?.let { "http://$it:$port" }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "accept: ${e.message}")
                break
            }
            runCatching { pool.execute { handle(client) } }
                .onFailure { runCatching { client.close() } }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 10_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')

            // 读取全部请求头
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtMost(MAX_BODY) ?: 0
            val body = if (contentLength > 0) readBody(input, contentLength) else ByteArray(0)

            when {
                method == "OPTIONS" -> respond(output, 204, "{}", cors = true)

                method == "GET" && path == "/api/ping" -> respond(
                    output, 200,
                    JSONObject()
                        .put("ok", true)
                        .put("name", "DownloadGuard")
                        .put("version", "1.4.0")
                        .put("tokenRequired", true)
                        .toString(),
                    cors = true
                )

                method == "POST" && path == "/api/download" -> {
                    if (headers["x-dg-token"] != token) {
                        respond(output, 401, JSONObject().put("ok", false).put("error", "token 不正确").toString(), cors = true)
                        return
                    }
                    val json = runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrNull()
                    val url = json?.optString("url")?.trim().orEmpty()
                    val name = json?.optString("name")?.trim()?.takeIf { it.isNotBlank() }
                    if (url.isBlank()) {
                        respond(output, 400, JSONObject().put("ok", false).put("error", "缺少 url").toString(), cors = true)
                        return
                    }
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        respond(output, 400, JSONObject().put("ok", false).put("error", "仅支持 http/https").toString(), cors = true)
                        return
                    }
                    val error = onDownload(url, name)
                    if (error.isEmpty()) {
                        respond(output, 200, JSONObject().put("ok", true).put("message", "已交给手机下载").toString(), cors = true)
                    } else {
                        respond(output, 409, JSONObject().put("ok", false).put("error", error).toString(), cors = true)
                    }
                }

                method == "GET" && (path == "/" || path == "/api") -> {
                    val html = """
                        <html><head><meta charset="utf-8"><title>DownloadGuard 浏览器下载桥</title></head>
                        <body style="font-family:sans-serif;padding:32px;background:#0f172a;color:#e2e8f0">
                        <h2>DownloadGuard 浏览器下载桥</h2>
                        <p>服务运行中。把浏览器扩展的服务器地址填成：<code>${localAddress() ?: "http://手机IP:$port"}</code></p>
                        <p>Token：<code>$token</code></p>
                        <p>POST <code>/api/download</code> 提交 {"url":"…","name":"…"}</p>
                        </body></html>
                    """.trimIndent()
                    respond(output, 200, html, contentType = "text/html; charset=utf-8", cors = true)
                }

                else -> respond(output, 404, JSONObject().put("ok", false).put("error", "Not Found").toString(), cors = true)
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun readBody(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read <= 0) break
            offset += read
        }
        return if (offset == length) buffer else buffer.copyOf(offset)
    }

    private fun respond(
        output: OutputStream,
        code: Int,
        body: String,
        contentType: String = "application/json; charset=utf-8",
        cors: Boolean = false
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code ${if (code == 200) "OK" else "ERR"}\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            if (cors) {
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
                append("Access-Control-Allow-Headers: Content-Type, X-DG-Token\r\n")
            }
            append("\r\n")
        }
        runCatching {
            output.write(header.toByteArray(Charsets.US_ASCII))
            output.write(bytes)
            output.flush()
        }
    }
}

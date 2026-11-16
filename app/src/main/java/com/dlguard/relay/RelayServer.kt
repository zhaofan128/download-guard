package com.dlguard.relay

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 接力网关：把本机网络借给附近没有网络的设备。
 *
 * 这是一个**只转发、不缓存**的 HTTP 代理：
 *  - `CONNECT host:443` 走隧道，HTTPS 全程端到端加密，网关看不到内容；
 *  - 普通 `GET http://…` 重写请求行后转发，响应原样流回。
 *
 * 三条硬边界：
 *  1. 只接受**私有网段/回环**来源（热点下的 192.168.x.x），公网 IP 一律拒绝，
 *     避免在咖啡厅连公共 WiFi 时把代理暴露给整个网段；
 *  2. 必须带 `Proxy-Authorization` 令牌，防止同热点下别人白嫖你的流量；
 *  3. 只做流式转发，落盘为零——网关设备不会留下任何下载记录。
 *
 * 纯 Socket 实现，不依赖 Android API，因此可以在 JVM 单元测试里跑完整链路。
 */
class RelayServer(
    private val token: String,
    private val onEvent: (String) -> Unit = {},
    /** 网关设备上的网页代抓器；null 时只提供代理，不提供页面浏览。 */
    private val fetcher: RelayFetch? = null
) {

    companion object {
        const val DEFAULT_PORT = 8899
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_CLIENTS = 64

        /** 本机所有 IPv4 地址（含热点网卡），给 UI 展示「让对方连这个地址」。 */
        fun companionAddresses(): List<String> = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .mapNotNull { it.hostAddress }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "relay-gw").apply { isDaemon = true }
    }
    private val active = AtomicInteger(0)

    /** 累计转发字节，UI 用来显示「已借出多少流量」。 */
    val bytesUpstream = AtomicLong(0)
    val bytesDownstream = AtomicLong(0)

    @Volatile
    var running: Boolean = false
        private set

    /** 启动并返回实际端口（传 0 时由系统分配，测试用）。 */
    fun start(port: Int = DEFAULT_PORT): Int {
        if (running) return serverSocket?.localPort ?: port
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port), 32)
        serverSocket = socket
        running = true
        onEvent("接力网关已启动，端口 ${socket.localPort}")
        Thread({
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                if (active.get() >= MAX_CLIENTS) {
                    runCatching { client.close() }
                    continue
                }
                active.incrementAndGet()
                pool.execute {
                    try {
                        handle(client)
                    } catch (_: Exception) {
                        // 客户端断开是常态，不记错误
                    } finally {
                        active.decrementAndGet()
                        runCatching { client.close() }
                    }
                }
            }
        }, "relay-accept").apply { isDaemon = true }.start()
        return socket.localPort
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        pool.shutdownNow()
        onEvent("接力网关已停止")
    }

    private fun handle(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        // 只服务局域网：公网来源直接拒绝，避免误暴露
        val remote = client.inetAddress
        if (!remote.isSiteLocalAddress && !remote.isLoopbackAddress) {
            respondAndClose(client, "403 Forbidden", "relay only accepts private network clients")
            return
        }

        val input = BufferedInputStream(client.getInputStream(), BUFFER_SIZE)
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) {
            respondAndClose(client, "400 Bad Request", "bad request line")
            return
        }
        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        if (!authorized(headers)) {
            val body = "relay token required"
            val out = "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                "Proxy-Authenticate: Basic realm=\"dlguard-relay\"\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Connection: close\r\n\r\n$body"
            runCatching {
                client.getOutputStream().write(out.toByteArray())
                client.getOutputStream().flush()
            }
            return
        }

        // 无网设备的 WebView 不走系统代理，只能直接访问网关的这两个本地路由
        // 必须精确匹配：`/file.bin` 也以 `/file` 开头，前缀判断会把正常下载劫成空响应
        val localPath = localPathOf(target)
        val localPathOnly = localPath.substringBefore('?')
        if (localPathOnly == "/fetch" || localPathOnly == "/file") {
            serveLocal(client, method, localPath)
            return
        }

        if (method == "CONNECT") {
            tunnel(client, target, headers)
        } else {
            forward(client, method, target, headers)
        }
    }

    /** 把「绝对 URL 形式的代理请求」和「相对路径的本地请求」统一成 path?query。 */
    private fun localPathOf(target: String): String {
        if (!target.startsWith("http://") && !target.startsWith("https://")) return target
        val uri = runCatching { java.net.URI(target) }.getOrNull() ?: return target
        val path = uri.path ?: "/"
        val query = uri.query?.let { "?$it" } ?: ""
        return path + query
    }

    /**
     * 网关本地路由：
     *  - `/fetch?url=…` 抓页面/资源并返回（HTML 已重写链接）；
     *  - `/file?url=…` 返回一个 attachment 空响应，用来触发 WebView 的下载回调，
     *    真正的下载由客户端 App 解析出原始 URL 后走代理完成。
     */
    private fun serveLocal(client: Socket, method: String, pathAndQuery: String) {
        val path = pathAndQuery.substringBefore('?')
        val query = parseQuery(pathAndQuery.substringAfter('?', ""))
        if (token.isNotBlank() && query["token"] != token) {
            respondAndClose(client, "403 Forbidden", "bad relay token")
            return
        }
        if (path == "/file") {
            val name = query["name"]?.takeIf { it.isNotBlank() } ?: "download.bin"
            val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Disposition: attachment; filename=\"$name\"\r\n" +
                "Content-Length: 0\r\nConnection: close\r\n\r\n"
            runCatching {
                client.getOutputStream().write(head.toByteArray())
                client.getOutputStream().flush()
            }
            return
        }
        val url = query["url"]
        if (url.isNullOrBlank()) {
            respondAndClose(client, "400 Bad Request", "missing url")
            return
        }
        val resource = fetcher?.fetch(url)
        if (resource == null) {
            respondAndClose(client, "502 Bad Gateway", "fetch failed: $url")
            return
        }
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: ${resource.contentType}\r\n" +
            "Content-Length: ${resource.bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        runCatching {
            val out = client.getOutputStream()
            out.write(head.toByteArray())
            out.write(resource.bytes)
            out.flush()
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val key = pair.substringBefore('=')
            val value = runCatching {
                java.net.URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            }.getOrDefault(pair.substringAfter('=', ""))
            key to value
        }.toMap()
    }

    /** HTTPS：建立到目标的 TCP，然后双向原样复制。网关无法解密，也不记录。 */
    private fun tunnel(client: Socket, target: String, headers: Map<String, String>) {
        val hostPort = parseHostPort(target, defaultPort = 443)
        val upstream = Socket()
        try {
            upstream.connect(InetSocketAddress(hostPort.first, hostPort.second), CONNECT_TIMEOUT_MS)
            upstream.soTimeout = READ_TIMEOUT_MS
            val ack = "HTTP/1.1 200 Connection Established\r\n\r\n"
            client.getOutputStream().write(ack.toByteArray())
            client.getOutputStream().flush()
            onEvent("隧道 ${hostPort.first}:${hostPort.second}")
            pipe(client, upstream)
        } catch (e: Exception) {
            runCatching { respondAndClose(client, "502 Bad Gateway", "cannot reach ${hostPort.first}") }
        } finally {
            runCatching { upstream.close() }
        }
    }

    /** 明文 HTTP：改请求行、剥掉 proxy-* 头，响应直接流回。 */
    private fun forward(
        client: Socket,
        method: String,
        target: String,
        headers: Map<String, String>
    ) {
        val url = try {
            URL(target)
        } catch (e: Exception) {
            respondAndClose(client, "400 Bad Request", "absolute URL required")
            return
        }
        if (url.protocol != "http") {
            respondAndClose(client, "400 Bad Request", "unsupported scheme")
            return
        }
        val port = if (url.port > 0) url.port else 80
        val upstream = Socket()
        try {
            upstream.connect(InetSocketAddress(url.host, port), CONNECT_TIMEOUT_MS)
            upstream.soTimeout = READ_TIMEOUT_MS

            val path = (url.file.ifBlank { "/" })
            val sb = StringBuilder()
            sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            headers.forEach { (key, value) ->
                if (key.startsWith("proxy-") || key == "connection" ||
                    key == "keep-alive" || key == "transfer-encoding"
                ) {
                    return@forEach
                }
                sb.append(key).append(": ").append(value).append("\r\n")
            }
            sb.append("Connection: close\r\n\r\n")
            upstream.getOutputStream().write(sb.toString().toByteArray())
            upstream.getOutputStream().flush()

            // POST/PUT 的 body 按 Content-Length 原样转发
            val length = headers["content-length"]?.toLongOrNull() ?: 0L
            if (length > 0) {
                copyExactly(client.getInputStream(), upstream.getOutputStream(), length)
            }

            onEvent("转发 $method ${url.host}")
            pipe(upstream, client)
        } catch (e: Exception) {
            runCatching { respondAndClose(client, "502 Bad Gateway", "cannot reach ${url.host}") }
        } finally {
            runCatching { upstream.close() }
        }
    }

    /** 双向复制，任意一侧 EOF 就结束。两个方向分别计数，用于 UI 展示流量。 */
    private fun pipe(a: Socket, b: Socket) {
        val up = Thread {
            runCatching {
                val copied = copy(a.getInputStream(), b.getOutputStream())
                bytesUpstream.addAndGet(copied)
            }
            runCatching { b.shutdownOutput() }
        }.apply { isDaemon = true; start() }

        runCatching {
            val copied = copy(b.getInputStream(), a.getOutputStream())
            bytesDownstream.addAndGet(copied)
        }
        runCatching { a.shutdownOutput() }
        runCatching { up.join(2_000) }
    }

    private fun copy(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            output.flush()
            total += read
        }
        return total
    }

    private fun copyExactly(input: InputStream, output: OutputStream, length: Long) {
        var remaining = length
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
        output.flush()
    }

    private fun authorized(headers: Map<String, String>): Boolean {
        if (token.isBlank()) return true
        val raw = headers["proxy-authorization"] ?: return false
        if (!raw.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = runCatching {
            String(Base64.getDecoder().decode(raw.substring(6).trim()))
        }.getOrNull() ?: return false
        val supplied = decoded.substringAfter(':', decoded)
        return supplied == token
    }

    private fun parseHostPort(target: String, defaultPort: Int): Pair<String, Int> {
        val hostPort = target.substringAfter("://", target)
        val slash = hostPort.indexOf('/')
        val clean = if (slash >= 0) hostPort.substring(0, slash) else hostPort
        val colon = clean.lastIndexOf(':')
        return if (colon > 0 && clean.indexOf(']') < colon) {
            clean.substring(0, colon) to (clean.substring(colon + 1).toIntOrNull() ?: defaultPort)
        } else {
            clean to defaultPort
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder(128)
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun respondAndClose(client: Socket, status: String, message: String) {
        runCatching {
            val body = message.toByteArray()
            val head = "HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            client.getOutputStream().write(head.toByteArray() + body)
            client.getOutputStream().flush()
        }
    }

    /** 网关在热点下的地址，UI 直接展示给客户端输入。 */
    fun localAddresses(): List<String> = companionAddresses()
}

package com.dlguard.integrity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 通用文件哈希器。
 *
 * 为什么单独写一份而不复用 [ApkSignatureVerifier]：那个算的是 APK **签名块**里的
 * 分块内容摘要（v2 规范专用格式），跟公开发布的「文件 SHA-256」完全不是一回事。
 * 用户拿官网给的 SHA-256 来比对时必须按整文件字节算，否则永远对不上。
 *
 * 实现是单趟流式读取（256KB buffer），200MB 文件约 0.5~1 秒，内存恒定。
 * 每个缓冲区读满就检查协程取消，用户中途退出不会把 IO 卡住。
 */
object FileHasher {

    private const val BUFFER = 256 * 1024

    /** 计算哈希，返回小写十六进制串；文件不可读时返回 null。 */
    suspend fun digest(
        file: File,
        algorithm: String,
        onProgress: ((Long) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        runCatching {
            val md = MessageDigest.getInstance(algorithm)
            val buffer = ByteArray(BUFFER)
            var done = 0L
            file.inputStream().use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    md.update(buffer, 0, read)
                    done += read
                    onProgress?.invoke(done)
                }
            }
            md.digest().toHex()
        }.getOrNull()
    }

    suspend fun sha256(file: File, onProgress: ((Long) -> Unit)? = null): String? =
        digest(file, "SHA-256", onProgress)

    suspend fun md5(file: File, onProgress: ((Long) -> Unit)? = null): String? =
        digest(file, "MD5", onProgress)

    /** 按算法名分发，供比对 UI 与下载完成校验共用。 */
    suspend fun byAlgorithm(file: File, algorithm: String): String? = when {
        algorithm.contains("256", true) -> sha256(file)
        algorithm.contains("MD5", true) -> md5(file)
        algorithm.contains("1", true) -> digest(file, "SHA-1")
        else -> null
    }

    /**
     * 解析用户粘贴的校验值，容忍常见写法：
     * `SHA256: abc…`、`sha-256 = abc…`、`MD5：abc…`，以及裸的 32/64/40 位十六进制。
     *
     * 返回 (算法, 小写十六进制)；无法识别时返回 null。
     */
    fun parseBaseline(raw: String): Pair<String, String>? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val hex = Regex("[0-9A-Fa-f]{32,128}").find(text)?.value
            ?: return null
        val algorithm = when {
            Regex("(?i)md5").containsMatchIn(text) -> "MD5"
            Regex("(?i)sha-?1\\b").containsMatchIn(text) -> "SHA-1"
            Regex("(?i)sha-?256").containsMatchIn(text) -> "SHA-256"
            hex.length == 64 -> "SHA-256"
            hex.length == 32 -> "MD5"
            hex.length == 40 -> "SHA-1"
            else -> return null
        }
        val expectedLen = when (algorithm) {
            "MD5" -> 32
            "SHA-1" -> 40
            else -> 64
        }
        if (hex.length != expectedLen) return null
        return algorithm to hex.lowercase(Locale.ROOT)
    }

    /** 比较两个十六进制串（大小写不敏感）。 */
    fun matches(actual: String?, expected: String): Boolean =
        actual != null && actual.equals(expected.trim(), ignoreCase = true)

    /** 展示用缩写：`a1b2c3d4…9f8e`。 */
    fun shorten(hex: String, head: Int = 12, tail: Int = 8): String =
        if (hex.length <= head + tail + 1) hex else "${hex.take(head)}…${hex.takeLast(tail)}"

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (b in this@toHex) {
            val v = b.toInt() and 0xFF
            append(HEX[v ushr 4])
            append(HEX[v and 0x0F])
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

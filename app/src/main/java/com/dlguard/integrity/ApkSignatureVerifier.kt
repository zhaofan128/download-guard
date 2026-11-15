package com.dlguard.integrity

import android.content.pm.PackageManager
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.io.SequenceInputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.SignatureException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.zip.ZipFile

/**
 * APK Signature Scheme v2 真实性校验（只做能确定性判定的部分）。
 *
 * v1 的「证书能解析」只能说明签名块没坏，不能说明内容没被改；真正能证明「整包字节未被篡改」的是
 * v2/v3 的分块摘要。这里实现 v2 校验：
 *  1. 定位 APK Signing Block（中央目录前 16 字节魔数 `APK Sig Block 42`）；
 *  2. 解析 signer：signed data（摘要表 / 证书 / 属性）、签名表、公钥；
 *  3. 按规范重算分块 SHA-256（1MB 一块，`0xa5 || uint32 长度 || 数据`，再对块摘要串做总摘要）；
 *  4. 比对摘要，并用公钥验签 signed data；证书公钥必须与 signer 公钥一致。
 *
 * 判定策略（不制造假结果）：
 *  - 结构解析成功且摘要/签名不匹配 → [Result.Failed]；
 *  - 无 v2 块、算法不支持、Zip64、块过大等无法判定的情况 → [Result.Unsupported]，
 *    上层退回 PackageManager + 证书解析，并在结论里注明「v2 未验证」。
 */
internal object ApkSignatureVerifier {

    private const val MAGIC = "APK Sig Block 42"
    private const val V2_BLOCK_ID = 0x7109871a
    private const val CHUNK_SIZE = 1024 * 1024
    private const val MAX_BLOCK_BYTES = 32L * 1024 * 1024
    private const val MAX_SIGNERS = 16
    private const val MAX_FILE_FOR_V2 = 700L * 1024 * 1024

    sealed interface Result {
        data class Verified(val signerCount: Int, val algorithm: String) : Result
        data class Failed(val reason: String) : Result
        data class Unsupported(val reason: String) : Result
    }

    fun verify(file: File, eocd: EocdInfo): Result {
        if (eocd.zip64) return Result.Unsupported("Zip64 包暂不做 v2 校验")
        if (file.length() > MAX_FILE_FOR_V2) return Result.Unsupported("文件超过 700MB，跳过 v2 摘要重算")
        val cdOffset = eocd.centralDirectoryOffset
        if (cdOffset < 32) return Result.Unsupported("没有空间容纳签名块")
        try {
            RandomAccessFile(file, "r").use { raf ->
                if (cdOffset + 24 > raf.length()) return Result.Unsupported("文件长度不足")
                raf.seek(cdOffset - 24)
                val footer = ByteArray(24)
                raf.readFully(footer)
                if (String(footer, 8, 16, Charsets.US_ASCII) != MAGIC) {
                    return Result.Unsupported("无 APK Signing Block（v1-only 或未签名）")
                }
                val sizeInFooter = readU64(footer, 0)
                if (sizeInFooter < 24 || sizeInFooter > MAX_BLOCK_BYTES) {
                    return Result.Unsupported("签名块长度异常：$sizeInFooter")
                }
                val blockSize = sizeInFooter + 8
                val blockStart = cdOffset - blockSize
                if (blockStart < 0) return Result.Unsupported("签名块起点越界")
                raf.seek(blockStart)
                val headerBytes = ByteArray(8)
                raf.readFully(headerBytes)
                // 注意：签名块里的 uint64 一律小端，RandomAccessFile.readLong() 是大端，不能直接用
                val sizeInHeader = readU64(headerBytes, 0)
                if (sizeInHeader != sizeInFooter) {
                    return Result.Failed("签名块首尾长度不一致（$sizeInHeader != $sizeInFooter），疑似被拼接")
                }
                val pairsLength = sizeInFooter - 24
                if (pairsLength <= 0) return Result.Unsupported("签名块内容为空")
                val pairs = ByteArray(pairsLength.toInt())
                raf.readFully(pairs)
                val v2 = findPair(pairs, V2_BLOCK_ID)
                    ?: return Result.Unsupported("签名块里没有 v2 条目（可能只有 v1/v3）")
                val signers = parseV2(v2) ?: return Result.Unsupported("v2 结构无法解析")
                if (signers.isEmpty()) return Result.Failed("v2 签名者列表为空")
                if (signers.size > MAX_SIGNERS) return Result.Unsupported("签名者数量异常")
                val signer = signers.first()

                val actual = computeContentDigest(raf, blockStart, cdOffset, eocd, digestJca(signer.algorithmId))
                if (!actual.contentEquals(signer.digest)) {
                    return Result.Failed(
                        "v2 内容摘要不匹配（实际 ${actual.toHex().take(16)}…，声明 ${signer.digest.toHex().take(16)}…），文件在签名后被修改"
                    )
                }
                val algorithm = signer.algorithmName
                    ?: return Result.Unsupported("不支持的签名算法 0x${Integer.toHexString(signer.algorithmId)}")
                val jca = ALGO_JCA[signer.algorithmId]
                    ?: return Result.Unsupported("$algorithm 缺少 JCA 映射，已退回证书解析")
                when (val verify = verifySignature(signer, jca, signer.algorithmId)) {
                    null -> return Result.Unsupported("当前系统不支持 $algorithm 验签，已退回证书解析")
                    false -> return Result.Failed("v2 签名验签失败，证书与私钥不匹配或被替换")
                    true -> Unit
                }
                signer.certificate?.let { cert ->
                    val certKey = cert.publicKey?.encoded
                    if (certKey != null && !certKey.contentEquals(signer.publicKey)) {
                        return Result.Failed("v2 证书公钥与签名者公钥不一致")
                    }
                }
                return Result.Verified(signers.size, algorithm)
            }
        } catch (e: Exception) {
            return Result.Unsupported("v2 解析异常：${e.javaClass.simpleName} ${e.message ?: ""}")
        }
    }

    /**
     * 容器包（.apks/.xapk）内所有 split 的签名证书集合必须与 base 完全一致。
     * 返回 null 表示一致或无法判断，返回字符串表示具体不一致条目。
     */
    fun containerSignerMismatch(file: File, pm: PackageManager, cacheDir: File): String? {
        return try {
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.lowercase().endsWith(".apk") }
                    .filter { it.size in 1..(512L * 1024 * 1024) }
                    .take(12)
                    .toList()
                if (entries.size <= 1) return null
                val digests = LinkedHashMap<String, Set<String>>()
                var budget = 200L * 1024 * 1024
                entries.forEach { entry ->
                    if (entry.size > budget) return@forEach
                    budget -= entry.size
                    val temp = File.createTempFile("split_", ".apk", cacheDir)
                    try {
                        zip.getInputStream(entry).use { input ->
                            temp.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
                        }
                        val signers = readSignerDigests(temp, pm) ?: return@forEach
                        digests[entry.name] = signers
                    } finally {
                        temp.delete()
                    }
                }
                if (digests.size <= 1) return null
                val first = digests.entries.first()
                val mismatch = digests.entries.firstOrNull { it.value != first.value }
                if (mismatch != null) {
                    "${first.key} 与 ${mismatch.key} 的签名证书不一致"
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readSignerDigests(file: File, pm: PackageManager): Set<String>? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = runCatching { pm.getPackageArchiveInfo(file.absolutePath, flags) }.getOrNull() ?: return null
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: return null
        if (signatures.isEmpty()) return null
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex()
        }.toSet()
    }

    private data class Signer(
        val algorithmId: Int,
        val algorithmName: String?,
        val digest: ByteArray,
        val signature: ByteArray,
        val publicKey: ByteArray,
        val signedData: ByteArray,
        val certificate: X509Certificate?
    )

    private fun parseV2(value: ByteArray): List<Signer>? {
        val outer = LeReader(value)
        val signersBlob = runCatching { outer.lengthPrefixed() }.getOrNull() ?: return null
        val signersReader = LeReader(signersBlob)
        val signers = mutableListOf<Signer>()
        while (signersReader.remaining() >= 4) {
            val signerBytes = runCatching { signersReader.lengthPrefixed() }.getOrNull() ?: break
            val sr = LeReader(signerBytes)
            val signedData = runCatching { sr.lengthPrefixed() }.getOrNull() ?: continue
            val signaturesBlob = runCatching { sr.lengthPrefixed() }.getOrNull() ?: continue
            val publicKey = runCatching { sr.lengthPrefixed() }.getOrNull() ?: continue

            val sd = LeReader(signedData)
            val digestsBlob = runCatching { sd.lengthPrefixed() }.getOrNull() ?: continue
            val certsBlob = runCatching { sd.lengthPrefixed() }.getOrNull()

            val digests = parseAlgorithmRecords(digestsBlob)
            val signatures = parseAlgorithmRecords(signaturesBlob)
            val algorithmId = PREFERRED_ALGOS.firstOrNull { digests.containsKey(it) && signatures.containsKey(it) }
                ?: digests.keys.firstOrNull { signatures.containsKey(it) }
                ?: digests.keys.firstOrNull()
                ?: continue
            signers += Signer(
                algorithmId = algorithmId,
                algorithmName = ALGO_NAMES[algorithmId],
                digest = digests[algorithmId] ?: ByteArray(0),
                signature = signatures[algorithmId] ?: ByteArray(0),
                publicKey = publicKey,
                signedData = signedData,
                certificate = certsBlob?.let { parseFirstCertificate(it) }
            )
        }
        return signers
    }

    /** 每条记录：uint32 总长 → 内容（uint32 算法 id + length-prefixed 值）。 */
    private fun parseAlgorithmRecords(blob: ByteArray): Map<Int, ByteArray> {
        val reader = LeReader(blob)
        val result = LinkedHashMap<Int, ByteArray>()
        while (reader.remaining() >= 4) {
            val record = runCatching { reader.lengthPrefixed() }.getOrNull() ?: break
            val inner = LeReader(record)
            if (inner.remaining() < 8) break
            val id = inner.u32()
            val value = runCatching { inner.lengthPrefixed() }.getOrNull() ?: break
            result[id] = value
        }
        return result
    }

    private fun parseFirstCertificate(blob: ByteArray): X509Certificate? = try {
        val sequence = LeReader(blob)
        val certs = sequence.lengthPrefixed()
        val reader = LeReader(certs)
        val certBytes = reader.lengthPrefixed()
        val factory = CertificateFactory.getInstance("X.509")
        factory.generateCertificate(certBytes.inputStream()) as? X509Certificate
    } catch (_: Exception) {
        null
    }

    /**
     * v2 的签名算法 ID（APK Signature Scheme v2 规范），SHA-256 优先。
     * 注意 0x0101/0x0102 是 RSA-PSS，0x0103/0x0104 是 RSA-PKCS1；
     * 0x0102/0x0104/0x0202 用 SHA-512，其余用 SHA-256 —— 摘要长度必须跟着算法走，
     * 否则 4096 位 RSA 的包（apksigner 会自动选 SHA-512）会被误判成「文件被修改」。
     */
    private val PREFERRED_ALGOS = listOf(0x0103, 0x0101, 0x0201, 0x0301, 0x0104, 0x0102, 0x0202)
    private val ALGO_NAMES = mapOf(
        0x0101 to "RSA-PSS-SHA256",
        0x0102 to "RSA-PSS-SHA512",
        0x0103 to "RSA-PKCS1-SHA256",
        0x0104 to "RSA-PKCS1-SHA512",
        0x0201 to "ECDSA-SHA256",
        0x0202 to "ECDSA-SHA512",
        0x0301 to "DSA-SHA256"
    )
    private val ALGO_JCA = mapOf(
        0x0101 to "SHA256withRSA/PSS",
        0x0102 to "SHA512withRSA/PSS",
        0x0103 to "SHA256withRSA",
        0x0104 to "SHA512withRSA",
        0x0201 to "SHA256withECDSA",
        0x0202 to "SHA512withECDSA",
        0x0301 to "SHA256withDSA"
    )

    /** 内容摘要的分块算法由签名算法决定：SHA-512 系列用 SHA-512，其余 SHA-256。 */
    private fun digestJca(algorithmId: Int): String = when (algorithmId) {
        0x0102, 0x0104, 0x0202 -> "SHA-512"
        else -> "SHA-256"
    }

    /** null 表示当前 ROM 不支持该算法，不能据此判定失败。 */
    private fun verifySignature(signer: Signer, jca: String, algorithmId: Int): Boolean? {
        return try {
            val verifier = Signature.getInstance(jca)
            val keySpec = X509EncodedKeySpec(signer.publicKey)
            val key = when (algorithmId) {
                0x0101, 0x0102, 0x0103, 0x0104 -> KeyFactory.getInstance("RSA").generatePublic(keySpec)
                0x0201, 0x0202 -> KeyFactory.getInstance("EC").generatePublic(keySpec)
                else -> KeyFactory.getInstance("DSA").generatePublic(keySpec)
            }
            verifier.initVerify(key)
            verifier.update(signer.signedData)
            verifier.verify(signer.signature)
        } catch (_: SignatureException) {
            false
        } catch (_: Exception) {
            null
        }
    }

    private fun computeContentDigest(
        raf: RandomAccessFile,
        blockStart: Long,
        cdOffset: Long,
        eocd: EocdInfo,
        digestAlgo: String
    ): ByteArray {
        // 规范要求「每个 section 单独分块」，最后一块可以短，但绝不能跨 section 拼接；
        // 顶层摘要 = H(0x5a || 块数 || 所有块摘要串)。这两点是最容易写错的地方。
        val eocdLength = (raf.length() - eocd.eocdOffset).coerceAtMost(65557).toInt()
        val eocdBytes = ByteArray(eocdLength)
        raf.seek(eocd.eocdOffset)
        raf.readFully(eocdBytes)
        // section 4 的 cdOffset 字段替换为签名块起点
        writeU32(eocdBytes, 16, blockStart)

        val chunkDigests = ByteArrayOutputStream()
        val perChunk = MessageDigest.getInstance(digestAlgo)
        val buffer = ByteArray(CHUNK_SIZE)
        var chunkCount = 0

        fun digestSection(stream: InputStream, length: Long) {
            var remaining = length
            while (remaining > 0) {
                val want = minOf(CHUNK_SIZE.toLong(), remaining).toInt()
                val read = readChunk(stream, buffer, want)
                if (read <= 0) break
                perChunk.reset()
                perChunk.update(0xa5.toByte())
                perChunk.update(u32le(read))
                perChunk.update(buffer, 0, read)
                chunkDigests.write(perChunk.digest())
                chunkCount++
                remaining -= read
            }
        }

        digestSection(RegionStream(raf, 0, blockStart), blockStart)
        digestSection(RegionStream(raf, cdOffset, eocd.eocdOffset - cdOffset), eocd.eocdOffset - cdOffset)
        digestSection(eocdBytes.inputStream(), eocdLength.toLong())

        if (chunkCount <= 0) throw IllegalStateException("空文件无法计算 v2 摘要")
        val top = MessageDigest.getInstance(digestAlgo)
        top.update(0x5a.toByte())
        top.update(u32le(chunkCount))
        top.update(chunkDigests.toByteArray())
        return top.digest()
    }

    private fun readChunk(stream: InputStream, buffer: ByteArray, want: Int): Int {
        var offset = 0
        while (offset < want) {
            val read = stream.read(buffer, offset, want - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    private class RegionStream(
        private val raf: RandomAccessFile,
        private val start: Long,
        private val length: Long
    ) : InputStream() {
        private var position = 0L
        private val lock = Any()

        override fun read(): Int {
            val one = ByteArray(1)
            val read = read(one, 0, 1)
            return if (read <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= length) return -1
            val toRead = minOf(len.toLong(), length - position).toInt()
            synchronized(lock) {
                raf.seek(start + position)
                val read = raf.read(b, off, toRead)
                if (read > 0) position += read
                return read
            }
        }

        override fun close() = Unit
    }

    private fun findPair(pairs: ByteArray, id: Int): ByteArray? {
        val reader = LeReader(pairs)
        while (reader.remaining() >= 12) {
            val pairLength = reader.u64()
            if (pairLength < 4 || pairLength > reader.remaining()) return null
            val pairId = reader.u32()
            val value = reader.bytes((pairLength - 4).toInt())
            if (pairId == id) return value
        }
        return null
    }

    private class LeReader(private val data: ByteArray) {
        private var pos = 0
        fun remaining(): Int = data.size - pos
        fun u32(): Int {
            val v = (data[pos].toInt() and 0xFF) or
                ((data[pos + 1].toInt() and 0xFF) shl 8) or
                ((data[pos + 2].toInt() and 0xFF) shl 16) or
                ((data[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }

        fun u64(): Long {
            var v = 0L
            for (i in 0 until 8) {
                v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
            }
            pos += 8
            return v
        }

        fun bytes(count: Int): ByteArray {
            require(count >= 0 && pos + count <= data.size) { "读取越界" }
            val out = data.copyOfRange(pos, pos + count)
            pos += count
            return out
        }

        fun lengthPrefixed(): ByteArray {
            val len = u32()
            require(len >= 0 && len <= remaining()) { "长度字段越界" }
            return bytes(len)
        }
    }

    private fun readU64(buffer: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = v or ((buffer[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return v
    }

    private fun writeU32(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun u32le(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

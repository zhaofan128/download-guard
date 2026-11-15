package com.dlguard.integrity

import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * 纯 JDK 实现的 ZIP 结构检查：
 * 1) EOCD(0x06054b50) 是否存在且中央目录偏移/长度合法（识别截断）；
 * 2) 遍历中央目录全部 entry，边读边算 CRC32 与声明值比对；
 * 3) 校验每个 entry 实际解压字节数与声明大小一致。
 */
internal object ZipInspector {

    private const val EOCD_SIGNATURE = 0x06054b50
    private const val CENTRAL_DIR_SIGNATURE = 0x02014b50
    private const val MAX_COMMENT = 65535
    private const val BUFFER = 64 * 1024
    private const val MAX_UNCOMPRESSED = 8L * 1024 * 1024 * 1024

    fun startsWithZipHeader(file: File): Boolean {
        if (file.length() < 4) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val b0 = raf.read()
                val b1 = raf.read()
                b0 == 0x50 && b1 == 0x4b
            }
        } catch (_: Exception) {
            false
        }
    }

    fun scanEocd(file: File): EocdScan {
        val length = file.length()
        if (length < 22L) return EocdScan.NotFound
        val scanLength = minOf(length, 22L + MAX_COMMENT).toInt()
        val buffer = ByteArray(scanLength)
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(length - scanLength)
                raf.readFully(buffer)
            }
        } catch (e: Exception) {
            return EocdScan.Corrupted("无法读取文件尾部：${e.message}")
        }

        var found = -1
        var i = scanLength - 22
        while (i >= 0) {
            if (readIntLe(buffer, i) == EOCD_SIGNATURE) {
                val commentLength = readShortLe(buffer, i + 20)
                if (i + 22 + commentLength <= scanLength) {
                    found = i
                    break
                }
            }
            i--
        }
        if (found < 0) return EocdScan.NotFound

        val entryCount = readShortLe(buffer, found + 10)
        val cdSize = readIntLe(buffer, found + 12).toLong() and 0xFFFFFFFFL
        val cdOffset = readIntLe(buffer, found + 16).toLong() and 0xFFFFFFFFL
        val commentLength = readShortLe(buffer, found + 20)
        val eocdOffset = length - scanLength + found
        val zip64 = cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL || entryCount == 0xFFFF

        if (!zip64) {
            if (cdOffset > eocdOffset) {
                return EocdScan.Corrupted("中央目录偏移($cdOffset)超出文件末尾($eocdOffset)，文件被截断")
            }
            if (cdSize > 0 && cdOffset + cdSize > eocdOffset) {
                return EocdScan.Corrupted("中央目录长度越界：${cdOffset + cdSize} > $eocdOffset")
            }
            if (cdSize > 0 && cdOffset + 4 <= length) {
                val head = ByteArray(4)
                try {
                    RandomAccessFile(file, "r").use { raf ->
                        raf.seek(cdOffset)
                        raf.readFully(head)
                    }
                    val sig = readIntLe(head, 0)
                    // 空目录 / 数字签名 / 正常的中央目录头都合法
                    if (sig != CENTRAL_DIR_SIGNATURE && sig != 0x05054b50 && entryCount > 0) {
                        return EocdScan.Corrupted("中央目录起始签名错误(0x${Integer.toHexString(sig)})，偏移不正确")
                    }
                } catch (_: Exception) {
                    // 读取失败留给后续 ZipFile 校验给出结论
                }
            }
        }

        return EocdScan.Found(
            EocdInfo(
                eocdOffset = eocdOffset,
                centralDirectoryOffset = cdOffset,
                centralDirectorySize = cdSize,
                entryCount = entryCount,
                commentLength = commentLength,
                zip64 = zip64
            )
        )
    }

    fun verifyEntries(file: File, onEntry: ((Int) -> Unit)? = null): EntryCheck {
        var count = 0
        var total = 0L
        return try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    count++
                    onEntry?.invoke(count)
                    if (entry.isDirectory) continue

                    val crc = CRC32()
                    var entryBytes = 0L
                    val buffer = ByteArray(BUFFER)
                    zip.getInputStream(entry).use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            crc.update(buffer, 0, read)
                            entryBytes += read
                            total += read
                            if (total > MAX_UNCOMPRESSED) {
                                return EntryCheck(
                                    entries = count,
                                    uncompressedBytes = total,
                                    errorVerdict = Verdict.UNKNOWN,
                                    detail = "解压体积超过 8GB，疑似异常压缩包，已终止校验",
                                    firstBadEntry = entry.name
                                )
                            }
                        }
                    }
                    if (entry.size >= 0 && entryBytes != entry.size) {
                        return EntryCheck(
                            entries = count,
                            uncompressedBytes = total,
                            errorVerdict = Verdict.TRUNCATED,
                            detail = "条目 ${entry.name} 实际 $entryBytes 字节，声明 ${entry.size} 字节",
                            firstBadEntry = entry.name
                        )
                    }
                    if (entry.crc >= 0 && crc.value != entry.crc) {
                        return EntryCheck(
                            entries = count,
                            uncompressedBytes = total,
                            errorVerdict = Verdict.CRC_ERROR,
                            detail = "条目 ${entry.name} CRC32 不匹配（实际 ${crc.value}，声明 ${entry.crc}）",
                            firstBadEntry = entry.name
                        )
                    }
                }
            }
            EntryCheck(entries = count, uncompressedBytes = total)
        } catch (e: ZipException) {
            val message = e.message ?: "ZIP 解析异常"
            val verdict = if (message.contains("CRC", ignoreCase = true)) Verdict.CRC_ERROR else Verdict.TRUNCATED
            EntryCheck(count, total, verdict, "ZIP 异常：$message")
        } catch (e: EOFException) {
            EntryCheck(count, total, Verdict.TRUNCATED, "读取条目时提前到达文件末尾（截断）")
        } catch (e: Exception) {
            EntryCheck(count, total, Verdict.IO_ERROR, "读取失败：${e.javaClass.simpleName} ${e.message ?: ""}")
        }
    }

    private fun readIntLe(buffer: ByteArray, offset: Int): Int =
        (buffer[offset].toInt() and 0xFF) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 3].toInt() and 0xFF) shl 24)

    private fun readShortLe(buffer: ByteArray, offset: Int): Int =
        (buffer[offset].toInt() and 0xFF) or ((buffer[offset + 1].toInt() and 0xFF) shl 8)
}

package com.dlguard.integrity

import java.io.File
import java.util.zip.ZipFile

/**
 * 轻量 AndroidManifest 解析器（纯 JVM，不依赖 PackageManager）。
 *
 * 为什么需要它：
 * `PackageManager.getPackageArchiveInfo()` 返回 null 只说明「系统不愿意解析这个包」，
 * 不等于「文件坏了」。两种情况在系统 API 里长得一模一样：
 *   1. 字节真的损坏 —— 应该判损坏、进隔离区；
 *   2. 系统策略拒绝解析（最典型：Android 14 起禁止 targetSdkVersion < 23 的应用，
 *      解析器直接返回 null）—— 文件完好，隔离它是误伤。
 *
 * 自己的解析器只要能读出 package / uses-sdk，就证明 AndroidManifest 二进制结构完整，
 * 从而把第 2 种情况从「损坏」里摘出来，并顺手拿到 targetSdk 给出准确原因。
 *
 * 只做读、不做校验：任何异常都收敛成 [Info.error]，绝不向外抛。
 */
object AndroidManifestProbe {

    data class Info(
        val packageName: String? = null,
        val versionName: String? = null,
        val versionCode: Long? = null,
        val minSdk: Int? = null,
        val targetSdk: Int? = null,
        val error: String? = null
    ) {
        /** manifest 二进制可解析 = 文件结构完好。 */
        val parsed: Boolean get() = !packageName.isNullOrBlank()
    }

    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_XML = 0x0003
    private const val CHUNK_RESOURCE_MAP = 0x0180
    private const val CHUNK_START_ELEMENT = 0x0102

    // android:minSdkVersion / targetSdkVersion / versionCode / versionName 的资源 ID。
    // 某些构建工具会把属性名写成空串，只能靠 resource map 反查。
    private const val ATTR_MIN_SDK = 0x0101020C
    private const val ATTR_VERSION_CODE = 0x0101021B
    private const val ATTR_VERSION_NAME = 0x0101021C
    private const val ATTR_TARGET_SDK = 0x01010270

    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11

    private const val MANIFEST_ENTRY = "AndroidManifest.xml"
    private const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024

    fun inspect(file: File): Info {
        if (!file.exists() || !file.isFile) return Info(error = "文件不存在")
        val bytes = try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(MANIFEST_ENTRY)
                    ?: return Info(error = "包内没有 AndroidManifest.xml")
                zip.getInputStream(entry).use { input ->
                    // 用流式读，不信任 entry.size：压缩包声明的大小可能是 -1 或与真实值不符
                    val out = java.io.ByteArrayOutputStream(64 * 1024)
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_MANIFEST_BYTES) {
                            return Info(error = "AndroidManifest.xml 超过 $MAX_MANIFEST_BYTES 字节，跳过自解析")
                        }
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                }
            }
        } catch (e: Throwable) {
            return Info(error = "${e.javaClass.simpleName}: ${e.message ?: "读取失败"}")
        }
        return try {
            parse(bytes)
        } catch (e: Throwable) {
            Info(error = "AXML 解析失败：${e.javaClass.simpleName}: ${e.message ?: ""}")
        }
    }

    /** 直接吃字节，方便单测覆盖各种畸形输入。 */
    fun parse(bytes: ByteArray): Info {
        if (bytes.size < 8) return Info(error = "manifest 长度不足 8 字节")
        if (u16(bytes, 0) != CHUNK_XML) return Info(error = "不是二进制 XML（头标识 0x${u16(bytes, 0).toString(16)}）")

        var strings: StringPool? = null
        var resourceMap: IntArray = IntArray(0)
        var packageName: String? = null
        var versionName: String? = null
        var versionCode: Long? = null
        var minSdk: Int? = null
        var targetSdk: Int? = null
        var sawManifest = false

        var pos = 8
        while (pos + 8 <= bytes.size) {
            val type = u16(bytes, pos)
            val headerSize = u16(bytes, pos + 2)
            val size = i32(bytes, pos + 4)
            if (size < 8 || pos + size > bytes.size) break
            when (type) {
                CHUNK_STRING_POOL -> strings = StringPool.parse(bytes, pos)
                CHUNK_RESOURCE_MAP -> {
                    val count = (size - headerSize).coerceAtLeast(0) / 4
                    resourceMap = IntArray(count) { i32(bytes, pos + headerSize + it * 4) }
                }
                CHUNK_START_ELEMENT -> {
                    val pool = strings ?: return Info(error = "起始标签出现在字符串池之前")
                    val element = pool.get(i32(bytes, pos + 20))
                    val attrCount = u16(bytes, pos + 28)
                    val attrStart = u16(bytes, pos + 24)
                    val attrSize = u16(bytes, pos + 26).takeIf { it >= 20 } ?: 20
                    // attributeStart 相对 ResXMLTree_attrExt（pos + 16），不是相对 chunk 头
                    var attrPos = pos + 16 + attrStart
                    for (index in 0 until attrCount) {
                        if (attrPos + 20 > pos + size) break
                        val nameIndex = i32(bytes, attrPos + 4)
                        val rawValue = i32(bytes, attrPos + 8)
                        val dataType = u8(bytes, attrPos + 15)
                        val data = i32(bytes, attrPos + 16)

                        var name = pool.getOrNull(nameIndex) ?: ""
                        val resId = if (nameIndex in resourceMap.indices) resourceMap[nameIndex] else 0
                        if (name.isBlank() && resId != 0) {
                            name = when (resId) {
                                ATTR_MIN_SDK -> "minSdkVersion"
                                ATTR_TARGET_SDK -> "targetSdkVersion"
                                ATTR_VERSION_CODE -> "versionCode"
                                ATTR_VERSION_NAME -> "versionName"
                                else -> ""
                            }
                        }

                        when (name) {
                            "package" -> packageName = stringValue(pool, rawValue, dataType, data)
                                ?.takeIf { it.isNotBlank() }
                            "versionName" -> versionName = stringValue(pool, rawValue, dataType, data)
                            "versionCode" -> versionCode = intValue(pool, rawValue, dataType, data)
                            "minSdkVersion" -> minSdk = intValue(pool, rawValue, dataType, data)?.toInt()
                            "targetSdkVersion" -> targetSdk = intValue(pool, rawValue, dataType, data)?.toInt()
                        }
                        // 属性名为空、只能靠资源 ID 认出来的情况
                        if (resId == ATTR_MIN_SDK && minSdk == null) {
                            minSdk = intValue(pool, rawValue, dataType, data)?.toInt()
                        }
                        if (resId == ATTR_TARGET_SDK && targetSdk == null) {
                            targetSdk = intValue(pool, rawValue, dataType, data)?.toInt()
                        }
                        if (resId == ATTR_VERSION_CODE && versionCode == null) {
                            versionCode = intValue(pool, rawValue, dataType, data)
                        }
                        if (resId == ATTR_VERSION_NAME && versionName == null) {
                            versionName = stringValue(pool, rawValue, dataType, data)
                        }

                        attrPos += attrSize
                    }
                    if (element == "manifest") sawManifest = true
                    // uses-sdk 一定在 manifest 之后，两个都拿到就可以收工
                    if (sawManifest && packageName != null && (minSdk != null || targetSdk != null)) {
                        return Info(packageName, versionName, versionCode, minSdk, targetSdk)
                    }
                }
            }
            pos += size
        }

        if (packageName.isNullOrBlank()) {
            return Info(
                versionName = versionName,
                versionCode = versionCode,
                minSdk = minSdk,
                targetSdk = targetSdk,
                error = if (sawManifest) "manifest 节点缺少 package 属性" else "没有找到 manifest 起始标签"
            )
        }
        return Info(packageName, versionName, versionCode, minSdk, targetSdk)
    }

    private fun stringValue(pool: StringPool, rawValue: Int, dataType: Int, data: Int): String? = when {
        rawValue >= 0 -> pool.getOrNull(rawValue)
        dataType == TYPE_STRING -> pool.getOrNull(data)
        else -> null
    }

    private fun intValue(pool: StringPool, rawValue: Int, dataType: Int, data: Int): Long? = when {
        dataType == TYPE_INT_DEC || dataType == TYPE_INT_HEX -> data.toLong() and 0xFFFFFFFFL
        // 少数工具会把数字写成字符串属性（rawValue 指向字符串池）
        rawValue >= 0 -> pool.getOrNull(rawValue)?.trim()?.toLongOrNull()
        else -> null
    }

    private class StringPool(private val strings: Array<String>) {
        fun get(index: Int): String = strings.getOrElse(index) { "" }
        fun getOrNull(index: Int): String? = strings.getOrNull(index)

        companion object {
            fun parse(bytes: ByteArray, chunkStart: Int): StringPool? {
                if (chunkStart + 28 > bytes.size) return null
                val headerSize = u16(bytes, chunkStart + 2)
                val size = i32(bytes, chunkStart + 4)
                if (size <= 0 || chunkStart + size > bytes.size) return null
                val count = i32(bytes, chunkStart + 8)
                if (count < 0 || count > 1 shl 20) return null
                val flags = i32(bytes, chunkStart + 16)
                val stringsStart = i32(bytes, chunkStart + 20)
                val utf8 = (flags and 0x100) != 0
                val offsetsBase = chunkStart + headerSize
                if (offsetsBase + count * 4 > bytes.size) return null

                val result = Array(count) { "" }
                val dataStart = chunkStart + stringsStart
                for (index in 0 until count) {
                    val offset = i32(bytes, offsetsBase + index * 4)
                    if (offset < 0) continue
                    val at = dataStart + offset
                    if (at < 0 || at >= bytes.size) continue
                    result[index] = if (utf8) readUtf8(bytes, at) else readUtf16(bytes, at)
                }
                return StringPool(result)
            }

            /** UTF-8 池：先是 UTF-16 长度的变长编码，再是 UTF-8 字节长的变长编码，然后数据 + NUL。 */
            private fun readUtf8(bytes: ByteArray, start: Int): String {
                var pos = start
                // 跳过 UTF-16 字符数（1~2 字节）
                val first = u8(bytes, pos)
                pos += if (first and 0x80 != 0) 2 else 1
                if (pos >= bytes.size) return ""
                val lenFirst = u8(bytes, pos)
                val byteLen: Int
                if (lenFirst and 0x80 != 0) {
                    if (pos + 1 >= bytes.size) return ""
                    byteLen = ((lenFirst and 0x7F) shl 8) or u8(bytes, pos + 1)
                    pos += 2
                } else {
                    byteLen = lenFirst
                    pos += 1
                }
                if (byteLen <= 0 || pos + byteLen > bytes.size) return ""
                return String(bytes, pos, byteLen, Charsets.UTF_8)
            }

            private fun readUtf16(bytes: ByteArray, start: Int): String {
                var pos = start
                if (pos + 2 > bytes.size) return ""
                var len = u16(bytes, pos)
                pos += 2
                if (len and 0x8000 != 0) {
                    if (pos + 2 > bytes.size) return ""
                    len = ((len and 0x7FFF) shl 16) or u16(bytes, pos)
                    pos += 2
                }
                if (len <= 0 || pos + len * 2 > bytes.size) return ""
                return String(bytes, pos, len * 2, Charsets.UTF_16LE)
            }
        }
    }

    // ---------------------------------------------------------------- 小端读取

    private fun u8(bytes: ByteArray, at: Int): Int =
        if (at in bytes.indices) bytes[at].toInt() and 0xFF else 0

    private fun u16(bytes: ByteArray, at: Int): Int {
        if (at < 0 || at + 1 >= bytes.size) return 0
        return (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    }

    private fun i32(bytes: ByteArray, at: Int): Int {
        if (at < 0 || at + 3 >= bytes.size) return 0
        return (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
    }
}

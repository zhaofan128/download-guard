package com.dlguard.download

import java.io.File
import java.io.FileOutputStream
import java.util.Base64

data class SegmentState(
    val start: Long,
    val endInclusive: Long,
    val done: Long
) {
    val length: Long get() = endInclusive - start + 1
    val finished: Boolean get() = done >= length
    fun asSegment(index: Int) = Segment(index, start, endInclusive)
}

/**
 * 断点续传元数据。
 *
 * 为什么需要它：
 *  - 多连接下载时“文件长度”不再等于“已下载字节”，必须逐段记录；
 *  - 记录 ETag / Last-Modified，续传前比对，避免把新版本文件接在旧版本尾部；
 *  - 元数据落盘采用「写临时文件 + rename」，进程被杀不会留下半截 JSON。
 *
 * 文本格式（每行一条，URL/ETag 用 Base64 避免换行与分隔符污染）：
 * ```
 * DLGM1
 * url=<b64>
 * total=123456
 * etag=<b64>            # 可缺省
 * last=<b64>            # 可缺省
 * seg=0,524287,131072
 * ```
 */
data class DownloadMeta(
    val url: String,
    val totalBytes: Long,
    val etag: String? = null,
    val lastModified: String? = null,
    val segments: List<SegmentState> = emptyList()
) {
    fun toText(): String = buildString {
        append("DLGM1\n")
        append("url=").append(b64(url)).append('\n')
        append("total=").append(totalBytes).append('\n')
        etag?.let { append("etag=").append(b64(it)).append('\n') }
        lastModified?.let { append("last=").append(b64(it)).append('\n') }
        segments.forEach {
            append("seg=").append(it.start).append(',').append(it.endInclusive).append(',').append(it.done).append('\n')
        }
    }

    fun withSegments(states: List<SegmentState>): DownloadMeta = copy(segments = states)

    val downloadedBytes: Long get() = segments.sumOf { it.done }

    val complete: Boolean
        get() = totalBytes > 0 && segments.isNotEmpty() &&
            segments.sumOf { it.done } == totalBytes &&
            segments.all { it.start >= 0 && it.endInclusive >= it.start }

    companion object {
        private const val HEADER = "DLGM1"

        fun load(file: File): DownloadMeta? {
            if (!file.isFile) return null
            return runCatching { parse(file.readText()) }.getOrNull()
        }

        fun parse(text: String): DownloadMeta? {
            val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.firstOrNull() != HEADER) return null
            var url: String? = null
            var total = -1L
            var etag: String? = null
            var last: String? = null
            val segments = mutableListOf<SegmentState>()
            lines.drop(1).forEach { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) return@forEach
                val key = line.substring(0, idx)
                val value = line.substring(idx + 1)
                when (key) {
                    "url" -> url = unb64(value)
                    "total" -> total = value.toLongOrNull() ?: -1L
                    "etag" -> etag = unb64(value)
                    "last" -> last = unb64(value)
                    "seg" -> {
                        val parts = value.split(',')
                        if (parts.size == 3) {
                            val start = parts[0].toLongOrNull()
                            val end = parts[1].toLongOrNull()
                            val done = parts[2].toLongOrNull()
                            if (start != null && end != null && done != null &&
                                start >= 0 && end >= start && done >= 0
                            ) {
                                segments += SegmentState(start, end, done.coerceAtMost(end - start + 1))
                            }
                        }
                    }
                }
            }
            val u = url ?: return null
            if (total <= 0) return null
            if (segments.isEmpty()) return null
            // 段区间必须从 0 开始、首尾相接、结束于 total-1，否则整份元数据作废重下
            if (segments.first().start != 0L) return null
            var expect = 0L
            segments.forEach { s ->
                if (s.start != expect) return null
                expect = s.endInclusive + 1
            }
            if (expect != total) return null
            return DownloadMeta(u, total, etag, last, segments)
        }

        fun save(file: File, meta: DownloadMeta) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp, false).use { out ->
                out.write(meta.toText().toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }

        fun delete(file: File) {
            runCatching { if (file.exists()) file.delete() }
        }

        private fun b64(value: String): String =
            Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

        private fun unb64(value: String): String? =
            runCatching { String(Base64.getDecoder().decode(value), Charsets.UTF_8) }.getOrNull()
    }
}

package com.dlguard.download.hls

import java.net.URI
import java.util.Locale

/** 主播放列表（master playlist）里的一个码率分支。 */
data class HlsVariant(
    val uri: String,
    val bandwidth: Long,
    val resolution: String?
)

/**
 * `#EXT-X-KEY` 的解析结果。
 *
 * 只支持 `METHOD=NONE` 与标准的 `AES-128`（密钥是一个普通 HTTP 地址）。
 * `SAMPLE-AES` / `SAMPLE-AES-CTR` 属于 DRM 范畴，遇到直接拒绝——不做绕过。
 */
data class HlsKey(
    val method: String,
    val uri: String?,
    /** 显式 IV；为空时按 RFC 8216 用分片序号推导。 */
    val iv: ByteArray?
) {
    val encrypted: Boolean get() = method.equals("AES-128", true)
}

data class HlsSegment(
    val uri: String,
    /** 媒体序号，AES-128 的默认 IV 由它推导。 */
    val sequence: Long,
    val durationSec: Double,
    val key: HlsKey?
)

data class HlsMediaPlaylist(
    val segments: List<HlsSegment>,
    /** `#EXT-X-MAP` 的初始化段（fMP4 才需要，普通 TS 流为空）。 */
    val initSegment: String?,
    /** 有 `#EXT-X-ENDLIST` 才是点播；没有就是直播，直播没有「下完」这个概念。 */
    val ended: Boolean,
    val targetDurationSec: Int,
    val hasEncryption: Boolean,
    /** `#EXT-X-PLAYLIST-TYPE:VOD`。少数站点省略 ENDLIST 但标了 VOD，按点播处理。 */
    val vod: Boolean = false
) {
    val totalDurationSec: Double get() = segments.sumOf { it.durationSec }
}

sealed class HlsParseResult {
    data class Master(val variants: List<HlsVariant>) : HlsParseResult()
    data class Media(val playlist: HlsMediaPlaylist) : HlsParseResult()
    data class Invalid(val reason: String) : HlsParseResult()
}

/** 只按地址判类型，供任务入口在探测前快速分流。 */
object HlsUrls {
    fun isPlaylist(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return path.endsWith(".m3u8") || path.endsWith(".m3u")
    }
}

/**
 * HLS 播放列表解析（RFC 8216 的常用子集）。
 *
 * 这是纯字符串处理，不碰网络也不依赖 Android，因此能被 JVM 单测直接覆盖。
 * 明确不支持的三类写在注释里，遇到时返回 [HlsParseResult.Invalid] 而不是猜：
 *  - `SAMPLE-AES` / `SAMPLE-AES-CTR`（DRM，不做绕过）
 *  - 非 `identity` 的 `KEYFORMAT`（FairPlay / Widevine 等）
 *  - 直播（没有 `#EXT-X-ENDLIST`）——永远下不完，交给播放器比交给下载器合适
 */
object HlsPlaylistParser {

    private const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024

    fun parse(text: String, baseUrl: String): HlsParseResult {
        if (text.length > MAX_PLAYLIST_BYTES) {
            return HlsParseResult.Invalid("播放列表超过 2MB，不像正常的 m3u8")
        }
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty() || !lines.first().startsWith("#EXTM3U")) {
            return HlsParseResult.Invalid("这不是 HLS 播放列表（缺少 #EXTM3U 头）")
        }

        val variants = mutableListOf<HlsVariant>()
        val segments = mutableListOf<HlsSegment>()
        var pendingBandwidth = -1L
        var pendingResolution: String? = null
        var pendingDuration = 0.0
        var currentKey: HlsKey? = null
        var initSegment: String? = null
        var ended = false
        var targetDuration = 0
        var sequence = 0L
        var hasEncryption = false
        var vod = false

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    pendingBandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: -1L
                    pendingResolution = attrs["RESOLUTION"]
                }

                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    val method = attrs["METHOD"]?.uppercase(Locale.ROOT) ?: "NONE"
                    val keyFormat = attrs["KEYFORMAT"]
                    if (keyFormat != null && !keyFormat.equals("identity", true)) {
                        return HlsParseResult.Invalid("这个流用 $keyFormat 加密（DRM），不做绕过")
                    }
                    if (method.startsWith("SAMPLE-AES")) {
                        return HlsParseResult.Invalid("这个流是 SAMPLE-AES（DRM）加密，不做绕过")
                    }
                    if (method == "NONE") {
                        currentKey = null
                    } else if (method == "AES-128") {
                        val keyUri = attrs["URI"]?.let { resolve(baseUrl, it) }
                            ?: return HlsParseResult.Invalid("#EXT-X-KEY 缺少 URI，无法取密钥")
                        currentKey = HlsKey(method, keyUri, parseIv(attrs["IV"]))
                        hasEncryption = true
                    } else {
                        return HlsParseResult.Invalid("不支持的加密方式 $method")
                    }
                }

                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    attrs["URI"]?.let { initSegment = resolve(baseUrl, it) }
                }

                line.startsWith("#EXTINF:") -> {
                    pendingDuration = line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
                }

                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                }

                line.startsWith("#EXT-X-TARGETDURATION:") -> {
                    targetDuration = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }

                line.startsWith("#EXT-X-ENDLIST") -> ended = true

                line.startsWith("#EXT-X-PLAYLIST-TYPE:") ->
                    vod = line.substringAfter(':').trim().equals("VOD", true)

                // 其它 # 开头的是标签或注释，忽略
                line.startsWith("#") -> Unit

                // 裸地址行：前面是 STREAM-INF 就是码率分支，否则是分片
                else -> {
                    val absolute = resolve(baseUrl, line)
                    if (pendingBandwidth >= 0L) {
                        variants += HlsVariant(absolute, pendingBandwidth, pendingResolution)
                        pendingBandwidth = -1L
                        pendingResolution = null
                    } else {
                        segments += HlsSegment(
                            uri = absolute,
                            sequence = sequence,
                            durationSec = pendingDuration,
                            key = currentKey
                        )
                        sequence++
                        pendingDuration = 0.0
                    }
                }
            }
        }

        if (variants.isNotEmpty() && segments.isEmpty()) {
            return HlsParseResult.Master(variants.sortedByDescending { it.bandwidth })
        }
        if (segments.isEmpty()) {
            return HlsParseResult.Invalid("播放列表里没有任何分片")
        }
        return HlsParseResult.Media(
            HlsMediaPlaylist(
                segments = segments,
                initSegment = initSegment,
                ended = ended,
                targetDurationSec = targetDuration,
                hasEncryption = hasEncryption,
                vod = vod
            )
        )
    }

    /** 从高到低取第一个分支；调用方负责再拉一次媒体列表。 */
    fun pickBest(master: HlsParseResult.Master): HlsVariant = master.variants.first()

    /**
     * 解析 `KEY=VALUE,KEY="VA,LUE"` 形式的属性串。
     * 引号内的逗号不能当分隔符（`CODECS="avc1.4,mp4a.40.2"` 是常见值）。
     */
    internal fun parseAttributes(input: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (chunk in splitOutsideQuotes(input)) {
            val eq = chunk.indexOf('=')
            if (eq <= 0) continue
            val key = chunk.substring(0, eq).trim().uppercase(Locale.ROOT)
            val value = chunk.substring(eq + 1).trim().removeSurrounding("\"")
            if (key.isNotEmpty() && value.isNotEmpty()) out[key] = value
        }
        return out
    }

    private fun splitOutsideQuotes(input: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in input) {
            when {
                ch == '"' -> {
                    inQuotes = !inQuotes
                    current.append(ch)
                }
                ch == ',' && !inQuotes -> {
                    parts += current.toString()
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }

    private fun parseIv(raw: String?): ByteArray? {
        val value = raw?.trim()?.removePrefix("0x")?.removePrefix("0X") ?: return null
        if (value.length != 32 || value.any { it.digitToIntOrNull(16) == null }) return null
        return ByteArray(16) { i -> value.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /**
     * 相对地址补全。分片地址常写成 `seg-1.ts`，也可能只带路径。
     *
     * 额外做一件事：若分片地址本身没有查询串、而播放列表地址带查询串（签名 token 常挂在
     * 这里），把查询串继承过去。这不是 RFC 的默认行为，但缺了它很多 CDN 会直接 403。
     */
    internal fun resolve(baseUrl: String, reference: String): String {
        val ref = reference.trim()
        if (ref.startsWith("http://", true) || ref.startsWith("https://", true)) return ref
        val resolved = runCatching { URI(baseUrl).resolve(ref).toString() }.getOrDefault(ref)
        if (resolved == ref || resolved.contains('?')) return resolved
        val baseQuery = baseUrl.substringAfter('?', "").substringBefore('#')
        return if (baseQuery.isEmpty()) resolved else "$resolved?$baseQuery"
    }
}

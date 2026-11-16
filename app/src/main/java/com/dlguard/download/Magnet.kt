package com.dlguard.download

/**
 * BitTorrent 磁力链接解析。
 *
 * 只做「从 URI 里取出 info hash 和显示名」这一件事，不联网、不做 DHT。
 * 真正的元数据交换交给 libtorrent —— 手写 BT 协议是没有意义的重复劳动，
 * 而且 DHT/PEX/加密握手这些细节自己实现必然比 libtorrent 差。
 */
object Magnet {

    data class Parsed(
        /** 40 位小写十六进制 info hash（BT v1）。 */
        val infoHash: String,
        /** `dn` 参数：种子显示名，可能为空。 */
        val displayName: String?,
        /** `tr` 参数：tracker 列表，可能为空（纯 DHT 也能跑）。 */
        val trackers: List<String>
    )

    fun isMagnet(raw: String): Boolean =
        raw.trim().startsWith("magnet:", ignoreCase = true)

    /**
     * 解析磁力链接。返回 null 表示不是合法磁力，或使用的是暂不支持的 v2（btmh）。
     *
     * 支持两种 btih 写法：
     *  - 40 位 hex：`urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a`
     *  - 32 位 base32：`urn:btih:MFRGGZDFMZTWQ2LKNNWG23TPOBYXE43U`
     */
    fun parse(raw: String): Parsed? {
        val text = raw.trim()
        if (!text.startsWith("magnet:", ignoreCase = true)) return null
        val query = text.substringAfter('?', "")
        if (query.isEmpty()) return null

        var hash: String? = null
        var name: String? = null
        val trackers = mutableListOf<String>()

        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = pair.substringBefore('=').lowercase()
            val value = decode(pair.substringAfter('=', ""))
            when (key) {
                "xt" -> {
                    val lower = value.lowercase()
                    when {
                        lower.startsWith("urn:btih:") -> {
                            val token = value.substring(9)
                            hash = when (token.length) {
                                40 -> token.lowercase().takeIf { it.all { c -> c in "0123456789abcdef" } }
                                32 -> base32ToHex(token)
                                else -> null
                            }
                        }
                        // v2 的 btmh 是 32 字节 SHA-256，libtorrent 需要 2.x 的完整支持，
                        // 这里明确拒绝而不是「猜一个」，避免加进去后永远 0%。
                        lower.startsWith("urn:btmh:") -> return null
                    }
                }
                "dn" -> name = value.takeIf { it.isNotBlank() }
                "tr" -> if (value.isNotBlank()) trackers += value
            }
        }

        val resolved = hash ?: return null
        return Parsed(resolved, name, trackers)
    }

    /** RFC 3986 百分号解码；磁力里的 `dn` 常有中文与 `%20`。 */
    private fun decode(value: String): String {
        if ('%' !in value && '+' !in value) return value
        val out = java.io.ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '%' && i + 2 < value.length -> {
                    val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex == null) {
                        out.write(c.code)
                    } else {
                        out.write(hex)
                        i += 2
                    }
                }
                c == '+' -> out.write(' '.code)
                else -> out.write(c.code)
            }
            i++
        }
        return runCatching { String(out.toByteArray(), Charsets.UTF_8) }.getOrDefault(value)
    }

    /** Base32（RFC 4648，无填充）→ 40 位 hex。 */
    fun base32ToHex(input: String): String? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = input.uppercase().trimEnd('=')
        if (clean.length != 32) return null
        val bytes = java.io.ByteArrayOutputStream(20)
        var buffer = 0L
        var bits = 0
        for (ch in clean) {
            val index = alphabet.indexOf(ch)
            if (index < 0) return null
            buffer = (buffer shl 5) or index.toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                bytes.write(((buffer shr bits) and 0xFF).toInt())
            }
        }
        if (bytes.size() < 20) return null
        return bytes.toByteArray().take(20).joinToString("") { "%02x".format(it) }
    }
}

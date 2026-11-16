package com.dlguard.data

import java.net.URI

/**
 * 导出日志脱敏器。
 *
 * 校验日志里有两类不该跟着文件流出去的东西：
 *  1. 下载链接的查询串——`?token=…&sign=…&session=…` 往往就是一次性的访问凭证；
 *  2. 本地绝对路径——含用户目录/应用私有目录，能反推出设备结构。
 *
 * 这里做「结构性脱敏」而不是简单打码：域名、路径形态、参数名都保留（排障需要），
 * 只把参数值和过长的路径段替换为 `***`。这样日志依然能定位问题，但泄露不了凭证。
 */
object LogRedactor {

    /** 出现在导出文件里的标记，UI 与文件头都会显示。 */
    const val TAG = "已脱敏"

    private val SENSITIVE_KEYS = setOf(
        "token", "access_token", "auth", "authorization", "sign", "signature", "sig",
        "key", "apikey", "api_key", "secret", "password", "passwd", "pwd",
        "session", "sessionid", "sid", "ticket", "expires", "expire", "e", "st",
        "cookie", "code", "state", "nonce"
    )

    fun url(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val uri = runCatching { URI(raw) }.getOrNull() ?: return "***"
        if (uri.scheme != "http" && uri.scheme != "https") return raw.take(24) + "…"
        val path = uri.path.orEmpty()
        val fileName = path.substringAfterLast('/')
        val maskedPath = if (path.length > 80) "/…/$fileName" else path
        val query = uri.rawQuery?.let { q ->
            q.split('&').filter { it.isNotBlank() }.joinToString("&") { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                when {
                    value.isBlank() -> key
                    key.lowercase() in SENSITIVE_KEYS -> "$key=***"
                    // 长随机串（签名/HMAC）即使参数名不认识也一律打码
                    value.length >= 24 && value.none { it == '/' || it == '.' } -> "$key=***"
                    else -> "$key=$value"
                }
            }
        }
        return buildString {
            append(uri.scheme).append("://").append(uri.host ?: "")
            if (uri.port > 0) append(':').append(uri.port)
            append(maskedPath)
            if (!query.isNullOrBlank()) append('?').append(query)
        }
    }

    /** 本地路径只保留最后两段（`Download/xxx.apk`），隐藏 /data/user/0/… 与用户目录。 */
    fun path(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val normalized = raw.replace('\\', '/')
        val parts = normalized.split('/').filter { it.isNotBlank() }
        if (parts.size <= 2) return parts.joinToString("/")
        return "…/" + parts.takeLast(2).joinToString("/")
    }

    /** 导出文件的尾部说明（CSV 注释行）。 */
    fun footer(): String =
        "# $TAG（REDACTED）：下载链接的凭证类查询参数、本地绝对路径已掩码；" +
            "文件名为原始文件名，未做处理。"
}

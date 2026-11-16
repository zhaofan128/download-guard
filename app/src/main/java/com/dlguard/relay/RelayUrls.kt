package com.dlguard.relay

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 无网设备上的 URL 改写规则。
 *
 * 有网时原样返回，保证行为完全不变；接力开启后：
 *  - 网页 → `http://网关/fetch?url=原地址&token=…`
 *  - 文件 → 网关返回 attachment，WebView 触发下载回调，再把 `url=` 解出来交给下载器
 *
 * 这样“打开复制的下载网址”在无网设备上仍然是同一个动作，只是中间多了一跳。
 */
object RelayUrls {

    fun browsingUrl(pageUrl: String): String {
        if (!RelayState.active) return pageUrl
        if (pageUrl.startsWith("magnet:")) return pageUrl
        if (isGatewayUrl(pageUrl)) return pageUrl
        if (!pageUrl.startsWith("http://") && !pageUrl.startsWith("https://")) return pageUrl
        return gatewayFetch(pageUrl)
    }

    fun gatewayFetch(pageUrl: String): String =
        "http://${RelayState.host}:${RelayState.port}/fetch" +
            "?url=${encode(pageUrl)}&token=${encode(RelayState.token)}"

    fun isGatewayUrl(url: String): Boolean {
        if (!RelayState.active) return false
        val prefix = "http://${RelayState.host}:${RelayState.port}/"
        return url.startsWith(prefix)
    }

    /** 从 `/fetch?url=…` 或 `/file?url=…` 里取出原始地址；不是网关地址就返回 null。 */
    fun extractOriginal(url: String): String? {
        if (!RelayState.active) return null
        if (!isGatewayUrl(url)) return null
        if (!url.contains("/fetch?") && !url.contains("/file?")) return null
        val query = url.substringAfter('?', "")
        val raw = query.split('&').firstOrNull { it.startsWith("url=") }?.substringAfter("url=")
            ?: return null
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()
    }

    fun isFileEndpoint(url: String): Boolean = url.contains("/file?")

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

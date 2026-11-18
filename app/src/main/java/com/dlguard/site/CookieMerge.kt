package com.dlguard.site

/**
 * Cookie 头合并。
 *
 * 背景：手机端嗅探到的直链经常和页面**不在同一个域**（页面 `pan.example.com`，
 * 直链 `dl.example.net`）。只按页面地址取 Cookie，直链那边就是裸奔，403 是必然的。
 * 这里把「页面域 Cookie」和「直链域 Cookie」按 name 合并，同名以**后者**（直链域）为准——
 * 直链域的那份才是服务器真正要验的。
 *
 * 这是启发式合并：真实 Cookie 还有 path/domain 维度，同名不同 path 是合法的。
 * 但 HTTP 的 `Cookie` 请求头本身就只能带 `name=value`，无法表达 path，
 * 所以「同名取直链域」在实际场景里是成功率最高的选择。
 */
object CookieMerge {

    /** Set-Cookie 的属性名，用户从 DevTools 整行粘贴时会混进来，必须剔除。 */
    private val ATTRIBUTE_NAMES = setOf(
        "path", "domain", "expires", "max-age", "secure", "httponly", "samesite", "priority"
    )

    /**
     * 按 name 去重合并多个 Cookie 头。后面的来源覆盖前面的同名值，顺序取首次出现的位置。
     * 全部为空时返回 null（调用方据此决定是否发 Cookie 头）。
     */
    fun merge(vararg sources: String?): String? {
        val merged = LinkedHashMap<String, String>()
        for (source in sources) {
            if (source.isNullOrBlank()) continue
            for (part in source.split(';')) {
                val idx = part.indexOf('=')
                if (idx <= 0) continue
                val name = part.substring(0, idx).trim()
                if (name.isEmpty() || name.length > 128) continue
                if (name.lowercase() in ATTRIBUTE_NAMES) continue
                val value = part.substring(idx + 1).trim()
                if (value.length > 4096) continue
                // LinkedHashMap 更新已有键不会改变插入顺序，正好符合「保留首次顺序、用新值」
                merged[name] = value
            }
        }
        if (merged.isEmpty()) return null
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** Cookie 里出现的 name 列表（用于 UI 展示，不含值）。 */
    fun names(cookie: String?): List<String> {
        if (cookie.isNullOrBlank()) return emptyList()
        return cookie.split(';').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null else part.substring(0, idx).trim().takeIf { it.isNotEmpty() }
        }.distinct()
    }

    /**
     * 脱敏展示：只保留 name，值一律替换成 `***`。
     *
     * 候选列表、规则测试、任务详情里都不允许出现 Cookie 明文——
     * 这些界面会被截图、会被录屏、会进日志。
     */
    fun redact(cookie: String?, maxNames: Int = 6): String {
        val names = names(cookie)
        if (names.isEmpty()) return "（无）"
        val shown = names.take(maxNames).joinToString("; ") { "$it=***" }
        return if (names.size > maxNames) "$shown 等 ${names.size} 项" else shown
    }
}

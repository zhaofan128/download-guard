package com.dlguard.site

import java.net.URI
import java.util.Locale

/** Referer 来源策略。 */
object RefererMode {
    /** 用嗅探页 / 分享来源页地址；没有页面时退回直链所在的 origin。 */
    const val PAGE = "page"
    /** 用直链自身作为 Referer（部分站点只校验同域）。 */
    const val DIRECT = "direct"
    /** 用户填写的固定值。 */
    const val CUSTOM = "custom"
    const val NONE = "none"

    val ALL = listOf(PAGE, DIRECT, CUSTOM, NONE)

    fun label(mode: String): String = when (mode) {
        PAGE -> "页面地址"
        DIRECT -> "直链地址"
        CUSTOM -> "自定义"
        NONE -> "不发送"
        else -> mode
    }
}

/** Cookie 来源策略。 */
object CookieMode {
    /** 只取页面域的 Cookie（旧行为）。 */
    const val PAGE = "page"
    /** 只取直链域的 Cookie。 */
    const val DIRECT = "direct"
    /** 两个域按 name 合并，同名以直链域为准（默认，成功率最高）。 */
    const val MERGE = "merge"
    /** 用户填写的固定值。注意：明文存在本机，规则导出时**不会**带上它。 */
    const val CUSTOM = "custom"
    const val NONE = "none"

    val ALL = listOf(MERGE, PAGE, DIRECT, CUSTOM, NONE)

    fun label(mode: String): String = when (mode) {
        MERGE -> "页面 + 直链合并"
        PAGE -> "仅页面域"
        DIRECT -> "仅直链域"
        CUSTOM -> "自定义"
        NONE -> "不发送"
        else -> mode
    }
}

/**
 * 一条站点规则：**纯声明式**，不含任何可执行代码。
 *
 * 设计原则（与插件平台一致）：
 *  - 规则只能描述「请求怎么发、文件怎么命名」，不能读写文件、不能执行脚本；
 *  - 导入的规则必须经过字段白名单校验，未知字段直接忽略，非法值拒绝；
 *  - Cookie 只在本机参与请求，永不写入日志、永不上传。
 */
data class SiteRule(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    /** 数值越大越优先；同优先级时更具体的 host 胜出。 */
    val priority: Int = 100,
    /** 域名匹配：`example.com` 精确匹配；`*.example.com` 匹配自身与所有子域；`*` 匹配全部。 */
    val hosts: List<String> = emptyList(),
    val refererMode: String = RefererMode.PAGE,
    val refererValue: String = "",
    val cookieMode: String = CookieMode.MERGE,
    val cookieValue: String = "",
    val userAgent: String = "",
    val headers: Map<String, String> = emptyMap(),
    /** 0 = 跟随全局设置；1~64 覆盖本规则下任务的初始并发。 */
    val concurrency: Int = 0,
    /** 文件名模板，支持 `{name}` `{ext}` `{host}` `{date}` `{rule}`；空 = 原样。 */
    val fileNameTemplate: String = "",
    /** null = 跟随全局插件配置；true/false = 本规则强制开/关旁挂校验。 */
    val sidecarHash: Boolean? = null,
    /** null = 跟随全局；true = 校验通过后自动打开。 */
    val openWhenDone: Boolean? = null,
    val note: String = ""
) {
    /** 规则摘要（列表页用，不含任何敏感值）。 */
    fun summary(): String {
        val parts = ArrayList<String>()
        parts += "Referer ${RefererMode.label(refererMode)}"
        parts += "Cookie ${CookieMode.label(cookieMode)}"
        if (userAgent.isNotBlank()) parts += "自定义 UA"
        if (headers.isNotEmpty()) parts += "${headers.size} 个额外头"
        if (concurrency > 0) parts += "$concurrency 并发"
        if (fileNameTemplate.isNotBlank()) parts += "文件名模板"
        return parts.joinToString(" · ")
    }

    fun matches(url: String): Boolean {
        val host = SiteRules.hostOf(url) ?: return false
        return hosts.any { SiteRules.matchHost(it, host) }
    }
}

/**
 * 一次请求最终要带的东西。由 [SiteRuleResolver] 计算，交给下载引擎。
 *
 * Cookie 是**明文**，只在内存里流转；任何展示都必须走 [CookieMerge.redact]。
 */
data class RequestProfile(
    val ruleId: String? = null,
    val ruleName: String? = null,
    val referer: String? = null,
    val cookie: String? = null,
    val userAgent: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val concurrency: Int = 0,
    val fileNameTemplate: String = "",
    val sidecarHash: Boolean? = null,
    val openWhenDone: Boolean? = null
) {
    val hasRule: Boolean get() = ruleId != null
    val hasAnyHeader: Boolean
        get() = !referer.isNullOrBlank() || !cookie.isNullOrBlank() ||
            !userAgent.isNullOrBlank() || headers.isNotEmpty()

    /** 给 UI / 测试面板用的脱敏摘要，绝不输出 Cookie 明文。 */
    fun describe(): String {
        val lines = ArrayList<String>()
        lines += "命中规则：${ruleName ?: "（无，使用全局默认）"}"
        lines += "Referer：${referer ?: "不发送"}"
        lines += "Cookie：${CookieMerge.redact(cookie)}"
        lines += "User-Agent：${userAgent?.take(48)?.let { "$it…" } ?: "内置移动端 Chrome"}"
        lines += "额外 Header：${if (headers.isEmpty()) "无" else headers.keys.joinToString(", ")}"
        if (concurrency > 0) lines += "初始并发：$concurrency"
        if (fileNameTemplate.isNotBlank()) lines += "文件名模板：$fileNameTemplate"
        return lines.joinToString("\n")
    }
}

/** 一次规则解析的输入上下文。 */
data class RequestContext(
    val url: String,
    val pageUrl: String? = null,
    val pageCookie: String? = null,
    val explicit: RequestProfile = RequestProfile()
)

/** 解析结果：成功的规则 + 被跳过的条目数 + 致命错误（为 null 表示整体可用）。 */
data class SiteRuleParseResult(
    val rules: List<SiteRule> = emptyList(),
    val skipped: Int = 0,
    val error: String? = null
)

object SiteRules {

    const val SCHEMA = "dlguard.site-rules"
    const val VERSION = 1
    const val MAX_RULES = 200
    const val MAX_HOSTS = 32
    const val MAX_HEADERS = 32

    /** 这些头由下载引擎自己管理，规则不许覆盖——否则会破坏分段/续传，甚至造成请求走私。 */
    private val FORBIDDEN_HEADERS = setOf(
        "host", "content-length", "range", "if-range", "accept-encoding",
        "connection", "transfer-encoding", "te", "upgrade", "trailer",
        "proxy-authorization", "proxy-connection"
    )

    private val HEADER_NAME = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}$")
    private val RULE_ID = Regex("^[A-Za-z0-9._-]{1,64}$")
    private val HOST_PATTERN = Regex("^(\\*\\.)?[A-Za-z0-9._:\\[\\]-]{1,253}$")

    fun hostOf(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        return runCatching { URI(trimmed).host?.lowercase(Locale.ROOT) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    fun originOf(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "$scheme://$host$port"
        }.getOrNull()
    }

    /**
     * 域名匹配。
     *  - `*` 全部匹配
     *  - `*.example.com` 匹配 example.com 本身与任意层级子域
     *  - 其余精确匹配（大小写不敏感，忽略规则里手写的端口）
     */
    fun matchHost(pattern: String, host: String): Boolean {
        val raw = pattern.trim().lowercase(Locale.ROOT)
        if (raw.isEmpty()) return false
        if (raw == "*") return true
        val p = if (raw.startsWith("[")) raw else raw.substringBefore(':').ifBlank { raw }
        val h = host.lowercase(Locale.ROOT)
        return when {
            p.startsWith("*.") -> {
                val base = p.removePrefix("*.")
                h == base || h.endsWith(".$base")
            }
            p.startsWith(".") -> {
                val base = p.removePrefix(".")
                h == base || h.endsWith(".$base")
            }
            else -> h == p
        }
    }

    /**
     * 选出命中 URL 的规则：启用 → host 匹配 → priority 降序 → host 模式更长者优先 → 原顺序。
     * 返回 null 表示没有规则，调用方走全局默认。
     */
    fun pick(rules: List<SiteRule>, url: String): SiteRule? {
        val host = hostOf(url) ?: return null
        return rules.asSequence()
            .filter { it.enabled }
            .mapNotNull { rule ->
                val specificity = rule.hosts
                    .filter { matchHost(it, host) }
                    .maxOfOrNull { it.replace("*", "").trim('.').length }
                if (specificity == null) null else rule to specificity
            }
            .sortedWith(
                compareByDescending<Pair<SiteRule, Int>> { it.first.priority }
                    .thenByDescending { it.second }
            )
            .firstOrNull()
            ?.first
    }

    /** 清洗额外请求头：合法名 + 无 CRLF + 不与引擎头冲突。非法的静默丢弃。 */
    fun sanitizeHeaders(headers: Map<String, String>): Map<String, String> {
        if (headers.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((rawKey, rawValue) in headers) {
            if (out.size >= MAX_HEADERS) break
            val key = rawKey.trim()
            val value = rawValue.trim()
            if (!HEADER_NAME.matches(key)) continue
            if (key.lowercase(Locale.ROOT) in FORBIDDEN_HEADERS) continue
            if (value.length > 4096) continue
            if (value.any { it == '\r' || it == '\n' || it == '\u0000' }) continue
            out[key] = value
        }
        return out
    }

    /**
     * 把用户粘贴的「Key: Value」多行文本解析成请求头表。
     * 空行与 `#` 开头的注释行忽略；没有冒号的行直接丢弃（不猜）。
     */
    fun parseHeaderLines(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            out[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        return sanitizeHeaders(out)
    }

    /**
     * 文件名模板。实现只有一份（下载层 [com.dlguard.download.FileNameTemplate]），
     * 这里仅做转调，避免「预览看到的」和「实际落盘的」两套逻辑漂移。
     */
    fun applyTemplate(template: String, original: String, url: String, ruleName: String? = null): String =
        com.dlguard.download.FileNameTemplate.apply(template, original, url, ruleName)

    // ------------------------------------------------------------ JSON

    /**
     * 序列化。
     *
     * [includeSecrets] 默认 **false**：导出的规则文件会清空自定义 Cookie 的值。
     * 「分享一条规则」不应该顺带把登录态发给别人——这是本功能最容易踩的隐私坑。
     * 本机持久化才传 true。
     */
    fun toJson(rules: List<SiteRule>, pretty: Boolean = true, includeSecrets: Boolean = false): String {
        val root = linkedMapOf<String, Any?>(
            "schema" to SCHEMA,
            "version" to VERSION,
            "rules" to rules.map { rule ->
                linkedMapOf<String, Any?>(
                    "id" to rule.id,
                    "name" to rule.name,
                    "enabled" to rule.enabled,
                    "priority" to rule.priority,
                    "hosts" to rule.hosts,
                    "referer" to linkedMapOf("mode" to rule.refererMode, "value" to rule.refererValue),
                    "cookie" to linkedMapOf(
                        "mode" to rule.cookieMode,
                        "value" to if (includeSecrets) rule.cookieValue else ""
                    ),
                    "userAgent" to rule.userAgent,
                    "headers" to rule.headers,
                    "concurrency" to rule.concurrency,
                    "fileNameTemplate" to rule.fileNameTemplate,
                    "sidecarHash" to rule.sidecarHash,
                    "openWhenDone" to rule.openWhenDone,
                    "note" to rule.note
                )
            }
        )
        val compact = JsonLite.write(root)
        if (!pretty) return compact
        return prettyPrint(compact)
    }

    fun parse(json: String): SiteRuleParseResult {
        val root = try {
            JsonLite.parse(json)
        } catch (e: JsonLite.JsonException) {
            return SiteRuleParseResult(error = "JSON 解析失败：${e.message}")
        }
        val array: List<Any?> = when (root) {
            is List<*> -> root
            is Map<*, *> -> {
                val schema = root["schema"]?.toString()
                if (schema != null && schema != SCHEMA) {
                    return SiteRuleParseResult(error = "不是站点规则文件（schema=$schema）")
                }
                val version = (root["version"] as? Number)?.toInt() ?: VERSION
                if (version > VERSION) {
                    return SiteRuleParseResult(error = "规则版本 $version 高于本机支持的 $VERSION，请升级 App")
                }
                (root["rules"] as? List<*>)
                    ?: return SiteRuleParseResult(error = "缺少 rules 数组")
            }
            else -> return SiteRuleParseResult(error = "根节点必须是对象或数组")
        }
        if (array.size > MAX_RULES) {
            return SiteRuleParseResult(error = "规则数量 ${array.size} 超过上限 $MAX_RULES")
        }
        val out = ArrayList<SiteRule>()
        val seen = HashSet<String>()
        var skipped = 0
        for (item in array) {
            val rule = parseOne(item)
            if (rule == null || !seen.add(rule.id)) {
                skipped++
                continue
            }
            out += rule
        }
        if (out.isEmpty()) {
            return SiteRuleParseResult(
                skipped = skipped,
                error = if (array.isEmpty()) "规则文件里没有规则" else "全部 $skipped 条规则都不合法"
            )
        }
        return SiteRuleParseResult(rules = out, skipped = skipped)
    }

    private fun parseOne(raw: Any?): SiteRule? {
        val map = raw as? Map<*, *> ?: return null
        val id = (map["id"] as? String)?.trim().orEmpty()
        if (!RULE_ID.matches(id)) return null
        val hosts = (map["hosts"] as? List<*>)
            ?.mapNotNull { (it as? String)?.trim()?.lowercase(Locale.ROOT) }
            ?.filter { HOST_PATTERN.matches(it) }
            ?.distinct()
            ?.take(MAX_HOSTS)
            .orEmpty()
        if (hosts.isEmpty()) return null
        val referer = section(map["referer"])
        val cookie = section(map["cookie"])
        val headers = sanitizeHeaders(
            (map["headers"] as? Map<*, *>)
                ?.entries
                ?.filter { it.key is String && it.value is String }
                ?.associate { (it.key as String) to (it.value as String) }
                .orEmpty()
        )
        val refererMode = referer.first.takeIf { it in RefererMode.ALL } ?: RefererMode.PAGE
        val cookieMode = cookie.first.takeIf { it in CookieMode.ALL } ?: CookieMode.MERGE
        return SiteRule(
            id = id,
            name = (map["name"] as? String)?.trim()?.take(80).orEmpty().ifBlank { id },
            enabled = map["enabled"] as? Boolean ?: true,
            priority = ((map["priority"] as? Number)?.toInt() ?: 100).coerceIn(-1000, 1000),
            hosts = hosts,
            refererMode = refererMode,
            refererValue = if (refererMode == RefererMode.CUSTOM) referer.second.take(2048) else "",
            cookieMode = cookieMode,
            cookieValue = if (cookieMode == CookieMode.CUSTOM) cookie.second.take(16 * 1024) else "",
            userAgent = (map["userAgent"] as? String)?.trim()?.take(512).orEmpty(),
            headers = headers,
            concurrency = ((map["concurrency"] as? Number)?.toInt() ?: 0).coerceIn(0, 64),
            fileNameTemplate = (map["fileNameTemplate"] as? String)?.trim()?.take(200).orEmpty(),
            sidecarHash = map["sidecarHash"] as? Boolean,
            openWhenDone = map["openWhenDone"] as? Boolean,
            note = (map["note"] as? String)?.trim()?.take(500).orEmpty()
        )
    }

    /** referer / cookie 既接受 `"page"` 简写，也接受 `{"mode":"page","value":"…"}`。 */
    private fun section(value: Any?): Pair<String, String> = when (value) {
        is String -> value to ""
        is Map<*, *> -> {
            val mode = (value["mode"] as? String)?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val text = (value["value"] as? String).orEmpty()
            mode to text
        }
        else -> "" to ""
    }

    /**
     * 极简缩进：只处理 `{` `}` `[` `]` `,`，字符串内的符号不碰。
     * 够用即可，不追求通用 JSON formatter。
     */
    private fun prettyPrint(compact: String): String {
        val sb = StringBuilder(compact.length + compact.length / 4)
        var depth = 0
        var inString = false
        var escaped = false
        for (ch in compact) {
            if (inString) {
                sb.append(ch)
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> {
                    inString = true
                    sb.append(ch)
                }
                '{', '[' -> {
                    sb.append(ch)
                    depth++
                    sb.append('\n').append("  ".repeat(depth))
                }
                '}', ']' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    sb.append('\n').append("  ".repeat(depth)).append(ch)
                }
                ',' -> {
                    sb.append(ch).append('\n').append("  ".repeat(depth))
                }
                ':' -> sb.append(": ")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}

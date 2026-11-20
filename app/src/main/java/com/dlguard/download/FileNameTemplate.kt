package com.dlguard.download

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件名模板。
 *
 * 支持占位符：
 *  - `{name}` 原文件名（不含扩展名）
 *  - `{ext}`  扩展名（不含点）
 *  - `{host}` 下载地址的域名
 *  - `{date}` 当天日期 yyyyMMdd
 *  - `{rule}` 命中的站点规则名
 *
 * 未知占位符原样保留——用户写错时能一眼看出来，而不是被静默替换成空串。
 * 结果仍要过 [com.dlguard.core.Formatters.safeFileName] 与长度限制。
 */
object FileNameTemplate {

    fun apply(template: String, original: String, url: String, ruleName: String? = null): String {
        if (template.isBlank()) return original
        val dot = original.lastIndexOf('.')
        val hasExt = dot > 0 && dot < original.length - 1
        val base = if (hasExt) original.substring(0, dot) else original
        val ext = if (hasExt) original.substring(dot + 1) else ""
        val date = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
        val out = template
            .replace("{name}", base)
            .replace("{ext}", ext)
            .replace("{host}", host)
            .replace("{date}", date)
            .replace("{rule}", ruleName.orEmpty())
            .trim()
        // 原文件没有扩展名时，"{name}.{ext}" 会留下一个结尾的点——部分文件管理器
        // 会把它当成隐藏/异常文件名，直接去掉
        return if (ext.isEmpty()) out.trimEnd('.') else out
    }
}

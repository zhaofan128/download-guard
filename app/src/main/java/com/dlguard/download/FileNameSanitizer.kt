package com.dlguard.download

/**
 * 下载站文件名去广告。
 *
 * 站点常把文件名拼成「[www.xxx.com]App_v1.2.apk」「软件_绿色版_下载之家.apk」，留着既难看清，
 * 也会让同一份文件在不同站重复下载时落到不同名字上，断点续传就接不上了。
 *
 * 只删「括号里确实带域名」的整段，避免把 `[1080p]`、`【合集】`、`(全集)` 这类正常内容一起删掉。
 * 曾用一条嵌套字符类的正则一次写完，`[[【(（]` 在 java.util.regex 里会被当成嵌套字符类直接
 * 抛 PatternSyntaxException，导致**所有**带推荐文件名的下载当场失败——所以这里改成
 * 「先抓括号组、再判断里面有没有域名」两步走，并且由 [FileNameSanitizerTest] 覆盖。
 */
object FileNameSanitizer {

    /** 一对括号（半角/全角/中文书名号式）及其内容，内容里不允许再出现闭括号。 */
    private val BRACKET_GROUP = Regex("""[\[【(（][^\]】)）]{1,60}[\]】)）]""")

    /** 域名本体：至少一级点分 + 常见后缀，用来判断括号组是不是广告。 */
    private val DOMAIN = Regex(
        """(?:[a-z0-9][a-z0-9-]{0,28}\.)+(?:com|cn|net|org|cc|top|xyz|io|me|tv|info|vip|club)""",
        RegexOption.IGNORE_CASE
    )

    /** 尾部 `_www.xxx.com` 形式。 */
    private val TRAILING_DOMAIN = Regex(
        """[_-](?:www\.)?[a-z0-9-]{2,30}\.(?:com|cn|net|org|cc|top|xyz)""",
        RegexOption.IGNORE_CASE
    )

    /** 尾部营销词，只在紧跟扩展名时删。 */
    private val TRAILING_WORD = Regex(
        """[-_ ](?:下载站|下载之家|绿色版|破解版|官方版|免费版)(?=\.[a-z0-9]{2,5}$)""",
        RegexOption.IGNORE_CASE
    )

    fun clean(name: String): String {
        var cleaned = BRACKET_GROUP.replace(name) { m ->
            if (DOMAIN.containsMatchIn(m.value)) "" else m.value
        }
        cleaned = TRAILING_DOMAIN.replace(cleaned, "")
        cleaned = TRAILING_WORD.replace(cleaned, "")
        cleaned = cleaned.trim().trim('-', '_', ' ')
        return cleaned.ifBlank { name }
    }
}

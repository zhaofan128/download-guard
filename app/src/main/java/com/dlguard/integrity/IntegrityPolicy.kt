package com.dlguard.integrity

/**
 * 「这个文件该按什么规则校验」的判定，单独抽出来便于纯 JVM 覆盖。
 *
 * 只做一件事：区分「压缩/安装包」和「普通文件」。
 * 视频、音频、PDF、图片没有 ZIP 结构，用 EOCD 去校验只会把好文件判成坏的。
 */
object IntegrityPolicy {

    /** 只有这些后缀（或确实以 PK 头开头的文件）才值得做 ZIP 结构校验。 */
    private val ARCHIVE_EXTENSIONS = setOf(
        "apk", "apks", "xapk", "apkm", "zip", "jar", "aab", "obb"
    )

    /**
     * 是否需要走 ZIP 结构校验。
     *
     * 同时看后缀与文件头：`download.php` 这种没有后缀的安装包，靠 PK 头也能认出来；
     * 反过来 `movie.mp4` 即便内容以 PK 开头（极少见）也照样校验，宁可多查一次。
     */
    fun requiresArchiveValidation(fileName: String, startsWithZipHeader: Boolean): Boolean {
        if (startsWithZipHeader) return true
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension in ARCHIVE_EXTENSIONS
    }
}

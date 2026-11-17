package com.dlguard.integrity

/**
 * 判断「该跑哪几级校验」只看**逻辑文件名**，不看磁盘上的物理文件名。
 *
 * 背景：内置下载器是边下边校验的，落盘路径是 `xxx.apk.part`。
 * 早前这里用 `file.name.substringAfterLast('.')`，`.part` 的扩展名是 `part`，
 * 于是 L4（APK 解析）和 L5（v2 签名）被静默跳过，界面仍然显示「校验通过」——
 * 一个只改了签名、但重算过 ZIP CRC 的包会被判成完好。
 *
 * 这个 object 只做纯字符串判断，方便单测把「物理名 / 逻辑名」的组合全覆盖。
 */
object IntegrityTarget {

    /** 需要按安装包处理的后缀（apks/xapk 是分包容器，只做解析不验单包签名）。 */
    val INSTALLER_EXTENSIONS: Set<String> = setOf("apk", "apks", "xapk")

    /** 下载器的临时后缀。 */
    const val PART_SUFFIX = ".part"

    fun extensionOf(name: String): String =
        name.trim().substringAfterLast('.', "").lowercase()

    /**
     * 逻辑名是否需要走 L4/L5。
     *
     * 兼容一种情况：调用方只拿得到物理文件（比如监控服务扫到 `x.apk.part` 残留），
     * 这时把 `.part` 剥掉再判，避免把半成品当普通文件放行。
     */
    fun isInstallerPackage(name: String): Boolean =
        extensionOf(name) in INSTALLER_EXTENSIONS ||
            (name.endsWith(PART_SUFFIX, ignoreCase = true) &&
                extensionOf(name.dropLast(PART_SUFFIX.length)) in INSTALLER_EXTENSIONS)

    /** 单包 APK 才做 v2 分块摘要验签（apks/xapk 是容器，签名在子包上）。 */
    fun isSingleApk(name: String): Boolean =
        extensionOf(name) == "apk" ||
            (name.endsWith(PART_SUFFIX, ignoreCase = true) &&
                extensionOf(name.dropLast(PART_SUFFIX.length)) == "apk")
}

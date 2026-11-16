package com.dlguard.data

import android.content.Context

/**
 * 信任指纹库：记住「用户明确确认过没问题」的文件。
 *
 * 有些包天生过不了结构校验 —— 加壳游戏的自定义段、企业定制的非常规签名、
 * 被二次打包但确实能用的工具。这类文件每次下载都被隔离，用户除了关掉整个
 * 校验功能没有别的办法，这等于把安全功能变成了负担。
 *
 * 所以给一条**显式**通道：用户在隔离区点「信任并恢复」，我们把
 * `SHA-256 + 字节数` 记下来；同名同内容的文件再次出现时直接标为「已信任」，
 * 不再重复隔离。指纹里带 SHA-256 而不是只记文件名，是为了避免
 * 「同名的另一个恶意文件」蹭到这份信任。
 */
class TrustedStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("trusted_files", Context.MODE_PRIVATE)

    private companion object {
        const val KEY = "entries"
        const val SEP = "|"
        const val MAX_ENTRIES = 200
    }

    /** 指纹键：sha256:size，十六进制统一小写。 */
    fun fingerprint(size: Long, sha256: String): String = "${sha256.lowercase()}:$size"

    fun isTrusted(size: Long, sha256: String): Boolean {
        val target = fingerprint(size, sha256)
        return entries().any { it.substringBefore(SEP) == target }
    }

    /** 记录一条信任：`sha256:size|文件名|时间戳`。 */
    fun trust(size: Long, sha256: String, fileName: String) {
        val target = fingerprint(size, sha256)
        val current = entries().filterNot { it.substringBefore(SEP) == target }.toMutableList()
        current.add(
            0,
            listOf(target, fileName.replace(SEP, "_"), System.currentTimeMillis().toString())
                .joinToString(SEP)
        )
        prefs.edit()
            .putStringSet(KEY, current.take(MAX_ENTRIES).toSet())
            .apply()
    }

    /** 撤销一条信任（指纹键或完整条目均可）。 */
    fun revoke(entry: String) {
        val target = entry.substringBefore(SEP)
        val kept = entries().filterNot { it.substringBefore(SEP) == target }
        prefs.edit().putStringSet(KEY, kept.toSet()).apply()
    }

    /** 全部信任条目，最新在前。 */
    fun entries(): List<String> = prefs.getStringSet(KEY, emptySet())
        ?.sortedByDescending { it.substringAfterLast(SEP).toLongOrNull() ?: 0L }
        ?: emptyList()

    fun count(): Int = entries().size
}

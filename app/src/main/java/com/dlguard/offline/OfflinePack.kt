package com.dlguard.offline

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 离线包里的一个文件条目。
 *
 * [localName] 为空 = 只有链接、没有内容（无网时点它会提示「未缓存」）；
 * 非空且 [ready] = 已经落在 `files/` 下，无网也能直接取。
 */
data class OfflineFile(
    val url: String,
    val name: String,
    val size: Long = 0,
    val mime: String? = null,
    val localName: String? = null,
    val ready: Boolean = false
)

/**
 * 一个「离线包」：某个下载页的快照 + 页面里文件的缓存状态。
 *
 * 设计目标是让**无网时的操作路径和有网时完全一致**：打开同一个链接，
 * 看到同一张页面，点同一个文件。区别只在于数据来自本地而不是远端。
 */
data class OfflinePack(
    val id: String,
    val url: String,
    val title: String,
    val savedAt: Long,
    val files: List<OfflineFile>,
    val pageFile: String = "index.html",
    /** 抓取备注：页面过大只存正文、部分资源下载失败等。 */
    val note: String? = null
) {
    val readyCount: Int get() = files.count { it.ready }
    val readyBytes: Long get() = files.filter { it.ready }.sumOf { it.size }
    val totalBytes: Long get() = files.sumOf { it.size }
    val hasPage: Boolean get() = pageFile.isNotBlank()
}

/** URL 归一化：同一个页面复制出 `?utm=…`、末尾斜杠、大小写差异时仍能命中同一个离线包。 */
object UrlKey {

    fun normalize(raw: String): String {
        val text = raw.trim()
        return runCatching {
            val uri = java.net.URI(text)
            val scheme = (uri.scheme ?: "https").lowercase()
            val host = (uri.host ?: return@runCatching text).lowercase()
            val defaultPort = when (scheme) {
                "http" -> 80
                "https" -> 443
                else -> -1
            }
            val port = if (uri.port > 0 && uri.port != defaultPort) ":${uri.port}" else ""
            val path = (uri.path ?: "").ifBlank { "/" }
            val query = uri.query?.takeIf { it.isNotBlank() }?.let { "?$it" } ?: ""
            "$scheme://$host$port$path$query"
        }.getOrDefault(text)
    }

    /** 稳定 id：同一 URL 永远落同一个目录，重复「存离线包」是覆盖而不是堆副本。 */
    fun id(raw: String): String = sha1(normalize(raw)).take(16)

    fun sha1(text: String): String = runCatching {
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }.getOrDefault(text.hashCode().toString())
}

/**
 * 离线包持久层。目录结构：
 *
 * ```
 * files/offline/<id>/
 *   manifest.json     元数据（页面地址、文件清单、缓存状态）
 *   index.html        页面快照（已重写链接）
 *   res/              页面自身资源（css/js/图片）
 *   files/            预取的文件本体
 * ```
 *
 * 全部放在应用私有目录：卸载即清空，也不会被其他应用或媒体扫描器碰到。
 */
class OfflineStore(context: Context) {

    private val root = File(context.filesDir, "offline")

    fun dir(id: String): File = File(root, id).apply { if (!exists()) mkdirs() }
    fun filesDir(id: String): File = File(dir(id), "files").apply { if (!exists()) mkdirs() }
    fun resDir(id: String): File = File(dir(id), "res").apply { if (!exists()) mkdirs() }
    fun pageFile(id: String): File = File(dir(id), "index.html")
    private fun manifestFile(id: String): File = File(dir(id), "manifest.json")

    fun save(pack: OfflinePack) {
        val json = JSONObject().apply {
            put("version", 1)
            put("id", pack.id)
            put("url", pack.url)
            put("title", pack.title)
            put("savedAt", pack.savedAt)
            put("pageFile", pack.pageFile)
            put("note", pack.note ?: JSONObject.NULL)
            put("files", JSONArray().apply {
                pack.files.forEach { f ->
                    put(JSONObject().apply {
                        put("url", f.url)
                        put("name", f.name)
                        put("size", f.size)
                        put("mime", f.mime ?: JSONObject.NULL)
                        put("localName", f.localName ?: JSONObject.NULL)
                        put("ready", f.ready)
                    })
                }
            })
        }
        runCatching { manifestFile(pack.id).writeText(json.toString()) }
    }

    fun load(id: String): OfflinePack? {
        val file = manifestFile(id)
        if (!file.exists()) return null
        return runCatching { parse(JSONObject(file.readText())) }.getOrNull()
    }

    fun list(): List<OfflinePack> {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { load(it.name) }.sortedByDescending { it.savedAt }
    }

    fun delete(id: String) {
        runCatching { File(root, id).deleteRecursively() }
    }

    /** 按 URL 找已存在的离线包（归一化后匹配）。 */
    fun findByUrl(url: String): OfflinePack? {
        val key = UrlKey.normalize(url)
        return list().firstOrNull { UrlKey.normalize(it.url) == key }
    }

    fun rename(id: String, newTitle: String) {
        load(id)?.let { save(it.copy(title = newTitle)) }
    }

    private fun parse(obj: JSONObject): OfflinePack {
        val array = obj.optJSONArray("files") ?: JSONArray()
        val files = buildList {
            for (i in 0 until array.length()) {
                val f = array.optJSONObject(i) ?: continue
                add(
                    OfflineFile(
                        url = f.optString("url"),
                        name = f.optString("name").ifBlank { "文件" },
                        size = f.optLong("size", 0L),
                        mime = f.optString("mime").takeIf { it.isNotBlank() && it != "null" },
                        localName = f.optString("localName").takeIf { it.isNotBlank() && it != "null" },
                        ready = f.optBoolean("ready", false)
                    )
                )
            }
        }
        return OfflinePack(
            id = obj.optString("id"),
            url = obj.optString("url"),
            title = obj.optString("title").ifBlank { obj.optString("url") },
            savedAt = obj.optLong("savedAt", 0L),
            files = files,
            pageFile = obj.optString("pageFile", "index.html"),
            note = obj.optString("note").takeIf { it.isNotBlank() && it != "null" }
        )
    }
}

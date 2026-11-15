package com.dlguard.data

import android.app.DownloadManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ResolvedSource(
    val downloadUrl: String? = null,
    val packageName: String? = null,
    val declaredSize: Long? = null,
    val downloadId: Long? = null,
    val origin: String = "未找到来源记录"
)

/**
 * 尽最大努力反查来源 App / 原始 URL / 声明大小，按可靠性排序逐级降级：
 *  1. 系统 DownloadManager（本应用提交过的任务一定可见）；
 *  2. MediaStore 的 OWNER_PACKAGE_NAME / SIZE；
 *  3. Shizuku 特权查询（shell uid 能看到全系统 DownloadManager 记录，普通 App 只能看到自己的）；
 *  4. 旁路捕获库（通知使用权 / 分享入口 / 无障碍 / LSPosed 模块回传的链接，按文件名匹配）；
 *  5. 以上都没有 → 返回「未找到来源记录」，由 UI 引导手动粘贴，绝不编造 URL。
 */
class DownloadHistoryResolver(
    private val context: Context,
    private val capturedLinks: CapturedLinks? = null
) {

    suspend fun resolve(file: File): ResolvedSource = withContext(Dispatchers.IO) {
        val fromDownloadManager = queryDownloadManager(file)
        val fromMediaStore = queryMediaStore(file)
        val fromShizuku = if (fromDownloadManager.downloadUrl == null) {
            querySystemDownloadsViaShizuku(file)
        } else {
            ResolvedSource()
        }
        val fromCaptured = queryCaptured(file)
        ResolvedSource(
            downloadUrl = fromDownloadManager.downloadUrl
                ?: fromMediaStore.downloadUrl
                ?: fromShizuku.downloadUrl
                ?: fromCaptured.downloadUrl,
            packageName = fromMediaStore.packageName ?: fromDownloadManager.packageName,
            declaredSize = fromDownloadManager.declaredSize
                ?: fromMediaStore.declaredSize
                ?: fromShizuku.declaredSize,
            downloadId = fromDownloadManager.downloadId ?: fromShizuku.downloadId,
            origin = listOfNotNull(
                fromDownloadManager.origin,
                fromMediaStore.origin,
                fromShizuku.origin,
                fromCaptured.origin
            ).distinct().joinToString(" + ").ifBlank { "未找到来源记录" }
        )
    }

    /**
     * Shizuku 特权查询：content query 走 shell uid，可读到其它 App 发起的下载记录。
     * 无 Shizuku / 命令失败时静默返回空结果，不影响前两级降级。
     */
    private fun querySystemDownloadsViaShizuku(file: File): ResolvedSource {
        if (!com.dlguard.shizuku.ShizukuHelper.hasPermission()) return ResolvedSource()
        val rows = com.dlguard.shizuku.PrivilegeOps.querySystemDownloads(50)
            .getOrDefault(emptyList())
        val hit = rows.firstOrNull { row ->
            row.title.equals(file.name, ignoreCase = true) ||
                row.targetFile.endsWith(file.name, ignoreCase = true)
        } ?: return ResolvedSource()
        return ResolvedSource(
            downloadUrl = hit.uri.takeIf { it.startsWith("http") },
            declaredSize = null,
            downloadId = hit.id,
            origin = "Shizuku 特权查询（DownloadManager 全量）"
        )
    }

    private fun queryCaptured(file: File): ResolvedSource {
        val links = capturedLinks ?: return ResolvedSource()
        val hit = links.findByFileName(file.name) ?: return ResolvedSource()
        return ResolvedSource(
            downloadUrl = hit.url,
            origin = "捕获链接（${hit.source}）"
        )
    }

    @Suppress("DEPRECATION")
    private fun queryDownloadManager(file: File): ResolvedSource {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return ResolvedSource()
        return try {
            val query = DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL)
            manager.query(query).use { cursor ->
                if (cursor == null) return ResolvedSource()
                val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_URI)
                val titleIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val sizeIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val localIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                while (cursor.moveToNext()) {
                    val title = if (titleIndex >= 0) cursor.getString(titleIndex) else null
                    val localUri = if (localIndex >= 0) cursor.getString(localIndex) else null
                    val localName = localUri?.let { Uri.parse(it).lastPathSegment }
                    val matches = title.equals(file.name, ignoreCase = true) ||
                        localName.equals(file.name, ignoreCase = true) ||
                        (localUri != null && runCatching {
                            File(Uri.parse(localUri).path ?: "").canonicalPath == file.canonicalPath
                        }.getOrDefault(false))
                    if (matches) {
                        return ResolvedSource(
                            downloadUrl = if (uriIndex >= 0) cursor.getString(uriIndex) else null,
                            declaredSize = if (sizeIndex >= 0) cursor.getLong(sizeIndex).takeIf { it > 0 } else null,
                            downloadId = if (idIndex >= 0) cursor.getLong(idIndex) else null,
                            origin = "DownloadManager 历史"
                        )
                    }
                }
                ResolvedSource(origin = "DownloadManager 无可匹配记录")
            }
        } catch (e: Exception) {
            ResolvedSource(origin = "DownloadManager 查询受限(${e.javaClass.simpleName})")
        }
    }

    private fun queryMediaStore(file: File): ResolvedSource {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ResolvedSource(origin = "MediaStore 需 Android 10+")
        }
        return try {
            val projection = mutableListOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATA
            )
            projection += MediaStore.MediaColumns.OWNER_PACKAGE_NAME
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val args = arrayOf(file.name)
            queryDownloads(collection, projection.toTypedArray(), selection, args, file)
        } catch (e: Exception) {
            ResolvedSource(origin = "MediaStore 查询失败(${e.javaClass.simpleName})")
        }
    }

    private fun queryDownloads(
        collection: Uri,
        projection: Array<String>,
        selection: String,
        args: Array<String>,
        file: File
    ): ResolvedSource {
        val resolver: ContentResolver = context.contentResolver
        resolver.query(collection, projection, selection, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC").use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return ResolvedSource(origin = "MediaStore 无记录")
            val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val ownerIndex = cursor.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            val dataIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex).takeIf { it > 0 } else null
            val owner = if (ownerIndex >= 0) cursor.getString(ownerIndex) else null
            val dataPath = if (dataIndex >= 0) cursor.getString(dataIndex) else null
            if (dataPath != null && !dataPath.endsWith(file.name)) {
                // 同名多份时以路径后缀兜底匹配
                return ResolvedSource(origin = "MediaStore 同名多条", declaredSize = size, packageName = owner)
            }
            return ResolvedSource(
                packageName = owner,
                declaredSize = size,
                origin = "MediaStore"
            )
        }
    }
}

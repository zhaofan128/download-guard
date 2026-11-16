package com.dlguard.data

import android.content.Context
import com.dlguard.core.Formatters
import com.dlguard.data.db.AppDatabase
import com.dlguard.data.db.CheckRecord
import com.dlguard.data.db.WatchedDir
import com.dlguard.integrity.IntegrityResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

data class GuardStats(val total: Int = 0, val broken: Int = 0)

class GuardRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settingsStore: SettingsStore,
    private val quarantineManager: QuarantineManager
) {

    private val recordDao = db.checkRecordDao()

    /** 用户确认信任：结论改为 TRUSTED，记录指向恢复后的新路径。 */
    suspend fun markTrusted(id: Long, path: String, note: String) = withContext(Dispatchers.IO) {
        recordDao.markTrusted(id, path, note)
    }
    private val dirDao = db.watchedDirDao()

    val recent: Flow<List<CheckRecord>> = recordDao.observeRecent(10)
    val allRecords: Flow<List<CheckRecord>> = recordDao.observeAll()
    val stats: Flow<GuardStats> = combine(recordDao.observeTotal(), recordDao.observeBroken()) { total, broken ->
        GuardStats(total, broken)
    }
    val settings: Flow<GuardSettings> = settingsStore.settings
    val customDirs: Flow<List<WatchedDir>> = dirDao.observeAll()

    suspend fun saveResult(
        result: IntegrityResult,
        file: File,
        source: ResolvedSource,
        quarantinedPath: String?
    ): Long = withContext(Dispatchers.IO) {
        // 覆盖同名文件后，旧记录指向同一路径：先清掉，避免「最近校验」出现两条一模一样的条目
        runCatching { recordDao.deleteByPath(file.absolutePath) }
        recordDao.insert(
            CheckRecord(
                filePath = file.absolutePath,
                fileName = file.name,
                sizeBytes = result.sizeBytes,
                verdict = result.verdict.name,
                detail = result.detail,
                checkedAt = System.currentTimeMillis(),
                quarantinedPath = quarantinedPath,
                sourcePackage = source.packageName,
                downloadUrl = source.downloadUrl,
                declaredSize = result.declaredSize,
                entriesChecked = result.entriesChecked,
                elapsedMs = result.elapsedMs
            )
        )
    }

    suspend fun findRecord(id: Long): CheckRecord? = recordDao.findById(id)

    suspend fun findByFileName(name: String): CheckRecord? = recordDao.findByFileName(name)

    suspend fun findByPathAndSize(path: String, size: Long): CheckRecord? =
        recordDao.findByPathAndSize(path, size)

    suspend fun markIgnored(id: Long, ignored: Boolean = true) = recordDao.setIgnored(id, ignored)

    suspend fun setQuarantinePath(id: Long, path: String?) = recordDao.setQuarantinePath(id, path)

    suspend fun deleteRecord(id: Long) = recordDao.deleteById(id)

    suspend fun clearRecords() = recordDao.clearAll()

    suspend fun counts(): GuardStats = GuardStats(recordDao.totalCount(), recordDao.brokenCount())

    suspend fun addCustomDir(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        val trimmed = path.trim()
        if (trimmed.isBlank()) return@withContext Result.failure(IllegalArgumentException("路径不能为空"))
        if (!trimmed.startsWith("/")) return@withContext Result.failure(IllegalArgumentException("请填写绝对路径"))
        val dir = File(trimmed)
        if (!dir.exists() || !dir.isDirectory) {
            return@withContext Result.failure(IllegalArgumentException("目录不存在：$trimmed"))
        }
        if (!dir.canRead()) {
            return@withContext Result.failure(IllegalArgumentException("目录不可读，请先授予全文件访问权限"))
        }
        dirDao.insert(WatchedDir(path = dir.absolutePath, addedAt = System.currentTimeMillis()))
        Result.success(Unit)
    }

    suspend fun removeCustomDir(path: String) = dirDao.delete(path)

    suspend fun allCustomDirs(): List<WatchedDir> = dirDao.getAll()

    suspend fun exportCsv(): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "guard_records_${Formatters.timestampForName()}.csv")
        val records = recordDao.observeAll().first()
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("\uFEFF")
            writer.write("时间,文件名,结论,大小(字节),声明大小(字节),条目数,耗时(ms),来源包名,下载链接,隔离路径,详情\n")
            records.forEach { record ->
                writer.write(
                    listOf(
                        Formatters.dateTime(record.checkedAt),
                        record.fileName,
                        record.verdict,
                        record.sizeBytes.toString(),
                        record.declaredSize?.toString() ?: "",
                        record.entriesChecked.toString(),
                        record.elapsedMs.toString(),
                        record.sourcePackage ?: "",
                        record.downloadUrl ?: "",
                        record.quarantinedPath ?: "",
                        record.detail
                    ).joinToString(",") { Formatters.quoteCsv(it) }
                )
                writer.write("\n")
            }
        }
        file
    }
}

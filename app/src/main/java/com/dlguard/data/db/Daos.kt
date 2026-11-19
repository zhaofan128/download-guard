package com.dlguard.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CheckRecordDao {

    @Insert
    suspend fun insert(record: CheckRecord): Long

    @Query("SELECT * FROM check_records ORDER BY checkedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CheckRecord>>

    @Query("SELECT * FROM check_records ORDER BY checkedAt DESC")
    fun observeAll(): Flow<List<CheckRecord>>

    @Query("SELECT COUNT(*) FROM check_records")
    fun observeTotal(): Flow<Int>

    @Query("SELECT COUNT(*) FROM check_records WHERE verdict NOT IN ('OK','TRUSTED','BLOCKED')")
    fun observeBroken(): Flow<Int>

    @Query("SELECT COUNT(*) FROM check_records")
    suspend fun totalCount(): Int

    @Query("SELECT COUNT(*) FROM check_records WHERE verdict NOT IN ('OK','TRUSTED','BLOCKED')")
    suspend fun brokenCount(): Int

    @Query("SELECT * FROM check_records WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): CheckRecord?

    @Query("SELECT * FROM check_records WHERE fileName = :fileName ORDER BY checkedAt DESC LIMIT 1")
    suspend fun findByFileName(fileName: String): CheckRecord?

    @Query("SELECT * FROM check_records WHERE filePath = :path AND sizeBytes = :size ORDER BY checkedAt DESC LIMIT 1")
    suspend fun findByPathAndSize(path: String, size: Long): CheckRecord?

    /** 同一路径只保留最新一条：重下同一个文件不该在列表里堆出两条同名记录。 */
    @Query("DELETE FROM check_records WHERE filePath = :path")
    suspend fun deleteByPath(path: String)

    @Query("UPDATE check_records SET quarantinedPath = :path WHERE id = :id")
    suspend fun setQuarantinePath(id: Long, path: String?)

    /** 用户确认信任并把文件从隔离区恢复：更新结论、路径，清掉隔离位置。 */
    @Query(
        "UPDATE check_records SET verdict = 'TRUSTED', detail = :detail, " +
            "quarantinedPath = NULL, filePath = :path WHERE id = :id"
    )
    suspend fun markTrusted(id: Long, path: String, detail: String)

    @Query("UPDATE check_records SET ignored = :ignored WHERE id = :id")
    suspend fun setIgnored(id: Long, ignored: Boolean)

    @Query("DELETE FROM check_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM check_records")
    suspend fun clearAll()
}

@Dao
interface WatchedDirDao {

    @Query("SELECT * FROM watched_dirs ORDER BY addedAt ASC")
    fun observeAll(): Flow<List<WatchedDir>>

    @Query("SELECT * FROM watched_dirs ORDER BY addedAt ASC")
    suspend fun getAll(): List<WatchedDir>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(dir: WatchedDir)

    @Query("DELETE FROM watched_dirs WHERE path = :path")
    suspend fun delete(path: String)
}

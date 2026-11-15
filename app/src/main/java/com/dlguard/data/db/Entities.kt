package com.dlguard.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "check_records")
data class CheckRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val fileName: String,
    val sizeBytes: Long,
    val verdict: String,
    val detail: String,
    val checkedAt: Long,
    val quarantinedPath: String? = null,
    val sourcePackage: String? = null,
    val downloadUrl: String? = null,
    val declaredSize: Long? = null,
    val ignored: Boolean = false,
    val entriesChecked: Int = 0,
    val elapsedMs: Long = 0
) {
    val healthy: Boolean get() = verdict == "OK"
}

@Entity(tableName = "watched_dirs")
data class WatchedDir(
    @PrimaryKey val path: String,
    val addedAt: Long,
    val builtIn: Boolean = false
)

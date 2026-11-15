package com.dlguard.data

import android.content.Context
import com.dlguard.core.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 隔离区：/storage/emulated/0/Android/data/<包名>/files/quarantine/
 * 不删除用户文件，保留原名 + 时间戳，便于事后取证或手动恢复。
 */
class QuarantineManager(private val context: Context) {

    val directory: File
        get() {
            val base = context.getExternalFilesDir(null) ?: File(context.filesDir, "external")
            val dir = File(base, "quarantine")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    suspend fun quarantine(source: File): File? = withContext(Dispatchers.IO) {
        if (!source.exists()) return@withContext null
        val dir = directory
        if (!dir.exists() && !dir.mkdirs()) return@withContext null
        val stamp = Formatters.timestampForName()
        val safeName = Formatters.safeFileName(source.name)
        val dot = safeName.lastIndexOf('.')
        val base = if (dot > 0) safeName.substring(0, dot) else safeName
        val ext = if (dot > 0) safeName.substring(dot) else ""
        var target = File(dir, "${base}_$stamp$ext")
        var index = 1
        while (target.exists()) {
            target = File(dir, "${base}_${stamp}_$index$ext")
            index++
        }
        val moved = try {
            source.renameTo(target)
        } catch (_: Exception) {
            false
        }
        if (moved) return@withContext target
        // 跨卷或权限导致 rename 失败时退化为复制后删除源文件
        try {
            source.copyTo(target, overwrite = false, bufferSize = 256 * 1024)
            val sourceLength = source.length()
            val targetLength = target.length()
            if (targetLength != sourceLength) {
                target.delete()
                return@withContext null
            }
            if (source.delete()) target else {
                target.delete()
                null
            }
        } catch (_: Exception) {
            if (target.exists()) target.delete()
            null
        }
    }

    fun list(): List<File> = try {
        directory.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    fun totalSize(): Long = list().sumOf { it.length() }

    fun delete(file: File): Boolean = try {
        file.exists() && file.canonicalPath.startsWith(directory.canonicalPath) && file.delete()
    } catch (_: Exception) {
        false
    }

    suspend fun clear(): Int = withContext(Dispatchers.IO) {
        var count = 0
        list().forEach { if (it.delete()) count++ }
        count
    }
}

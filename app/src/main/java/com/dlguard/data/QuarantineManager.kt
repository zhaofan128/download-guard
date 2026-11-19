package com.dlguard.data

import android.content.Context
import com.dlguard.core.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 隔离文件命名规则。
 *
 * 下载器的临时文件叫 `xxx.apk.part`。如果原样搬进隔离区，用户在列表里看到的是
 * 「xxx.apk.part_20261119-2217」——既认不出是哪个包，也看不出它本来是个 APK。
 * 所以先剥掉 `.part`，再加时间戳；重名时追加序号。
 */
object QuarantineNaming {
    private const val PART_SUFFIX = ".part"

    fun logicalName(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.endsWith(PART_SUFFIX, ignoreCase = true)) {
            trimmed.dropLast(PART_SUFFIX.length).ifBlank { trimmed }
        } else {
            trimmed
        }
    }

    /** index = 0 表示不带序号；同一时间戳下重名才用 1、2… */
    fun fileName(sourceName: String, stamp: String, index: Int = 0): String {
        val safe = Formatters.safeFileName(logicalName(sourceName))
        val dot = safe.lastIndexOf('.')
        val base = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        val suffix = if (index > 0) "_$index" else ""
        return "${base}_$stamp$suffix$ext"
    }
}

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
        var index = 0
        var target = File(dir, QuarantineNaming.fileName(source.name, stamp, index))
        while (target.exists()) {
            index++
            target = File(dir, QuarantineNaming.fileName(source.name, stamp, index))
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

    /**
     * 把隔离的文件放回原位（用户点「信任并恢复」）。
     *
     * 原路径已被别的文件占用时不覆盖，改名成 `xxx_restored_1.apk` ——
     * 恢复操作绝不能悄悄覆盖用户现有的东西。
     * 返回恢复后的文件；失败返回 null。
     */
    suspend fun restore(quarantined: File, originalPath: String): File? = withContext(Dispatchers.IO) {
        if (!quarantined.exists()) return@withContext null
        val original = File(originalPath)
        val dir = original.parentFile ?: return@withContext null
        if (!dir.exists() && !dir.mkdirs()) return@withContext null
        var target = original
        if (target.exists()) {
            val dot = original.name.lastIndexOf('.')
            val base = if (dot > 0) original.name.substring(0, dot) else original.name
            val ext = if (dot > 0) original.name.substring(dot) else ""
            var index = 1
            while (target.exists()) {
                target = File(dir, "${base}_restored_$index$ext")
                index++
            }
        }
        if (runCatching { quarantined.renameTo(target) }.getOrDefault(false)) {
            return@withContext target
        }
        // 跨卷：复制后删源，长度不一致则回滚，不留半截文件
        runCatching {
            quarantined.copyTo(target, overwrite = false, bufferSize = 256 * 1024)
            if (target.length() == quarantined.length() && quarantined.delete()) {
                target
            } else {
                target.delete()
                null
            }
        }.getOrNull()
    }

    suspend fun clear(): Int = withContext(Dispatchers.IO) {
        var count = 0
        list().forEach { if (it.delete()) count++ }
        count
    }
}

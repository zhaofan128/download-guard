package com.dlguard.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 统一的展示格式化工具，避免各页面各自实现导致位数/时区不一致。 */
object Formatters {

    private val dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    private val shortTime = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
    private val fileNameStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)

    fun bytes(size: Long): String {
        if (size < 0) return "未知"
        if (size < 1024) return "$size B"
        val kb = size / 1024.0
        if (kb < 1024) return String.format(Locale.CHINA, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.CHINA, "%.1f MB", mb)
        return String.format(Locale.CHINA, "%.2f GB", mb / 1024.0)
    }

    fun dateTime(timestamp: Long): String = synchronized(dateTime) { dateTime.format(Date(timestamp)) }

    fun shortTime(timestamp: Long): String = synchronized(shortTime) { shortTime.format(Date(timestamp)) }

    fun timestampForName(timestamp: Long = System.currentTimeMillis()): String =
        synchronized(fileNameStamp) { fileNameStamp.format(Date(timestamp)) }

    fun relative(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val diff = (now - timestamp).coerceAtLeast(0)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes} 分钟前"
            minutes < 60 * 24 -> "${minutes / 60} 小时前"
            minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)} 天前"
            else -> shortTime(timestamp)
        }
    }

    fun speed(bytesPerSecond: Long): String = "${bytes(bytesPerSecond)}/s"

    /** 毫秒 → "MM:SS"（>=1 小时为 "H:MM:SS"）。 */
    fun duration(millis: Long): String = durationSeconds(millis / 1000)

    /**
     * 秒 → "MM:SS"（>=1 小时为 "H:MM:SS"）。
     *
     * 与 [duration] 分开成两个函数是有意的：duration 入参是毫秒、本函数入参是秒，
     * 曾经把「秒」传给 duration 导致全部剩余时间被除以 1000 显示成 00:00，
     * 拆开签名让编译器拦住单位错配。
     */
    fun durationSeconds(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) {
            String.format(Locale.CHINA, "%d:%02d:%02d", h, m, sec)
        } else {
            String.format(Locale.CHINA, "%02d:%02d", m, sec)
        }
    }

    /** 去掉路径分隔符与非法字符，同时保留原文件名主体，便于隔离区识别。 */
    fun safeFileName(raw: String): String {
        val name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
        return cleaned.ifBlank { "download_${System.currentTimeMillis()}" }.take(120)
    }

    fun quoteCsv(value: String?): String {
        val v = value ?: ""
        return "\"" + v.replace("\"", "\"\"") + "\""
    }
}

package com.dlguard.core

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃黑匣子：任何未捕获异常（含协程异常冒泡到默认 handler）先落盘再交给原 handler。
 *
 * 为什么必须有它：真机无法连 adb，没有日志就只能靠猜。
 * 有了 last_report，用户在设置页一键分享，崩溃根因一锤定音。
 */
object CrashGuard {

    private const val FILE_NAME = "last_crash_report.txt"
    private const val MAX_BYTES = 64 * 1024

    @Volatile
    private var reportFile: File? = null

    /** Application.onCreate 第一行调用。 */
    fun install(context: Context) {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        reportFile = file
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeReport(file, thread, throwable) }
            // 交回系统原 handler：保留系统崩溃对话框/重启语义，不吞崩溃
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeReport(file: File, thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        val head = buildString {
            appendLine("=== DownloadGuard 崩溃报告 ===")
            appendLine("时间: $timestamp")
            appendLine("版本: ${runCatching { Build.VERSION.SDK_INT }.getOrDefault(-1)}")
            appendLine("线程: ${thread.name}")
            appendLine("异常: ${throwable.javaClass.name}")
            appendLine("消息: ${throwable.message}")
        }
        val stack = android.util.Log.getStackTraceString(throwable).take(MAX_BYTES)
        val cause = throwable.cause?.let {
            "cause: ${it.javaClass.name}: ${it.message}\n${android.util.Log.getStackTraceString(it).take(MAX_BYTES / 2)}"
        } ?: ""
        file.parentFile?.mkdirs()
        file.writeText(head + stack + cause)
    }

    fun hasLastReport(): Boolean = reportFile?.exists() == true && reportFile?.length() ?: 0L > 0

    fun lastReportText(): String? =
        reportFile?.takeIf { it.exists() }?.runCatching { readText() }?.getOrNull()

    fun clear() {
        runCatching { reportFile?.delete() }
    }

    /** 供 UI 显示的短摘要（异常类名 + 消息 + 关键行）。 */
    fun lastReportSummary(lines: Int = 14): String? = lastReportText()
        ?.lineSequence()
        ?.filter { it.isNotBlank() }
        ?.take(lines)
        ?.joinToString("\n")
}

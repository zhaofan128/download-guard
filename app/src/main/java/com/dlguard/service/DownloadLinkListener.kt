package com.dlguard.service

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.dlguard.DownloadGuardApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 下载链接旁路捕获（可选，默认关闭）。
 *
 * 背景：普通应用读不到浏览器/网盘的下载数据库，历史里往往没有 URL。开启「通知使用权」后，
 * 本服务可以读取其他 App 的通知内容，从中提取 http(s) 链接并连同文件名入库；
 * 校验失败时按文件名匹配，命中就能直接重下，命中不了仍然走手动粘贴——不伪造、不硬猜。
 *
 * 隐私边界：只解析通知文本里的 URL 与疑似安装包文件名，不落库通知标题/正文原文，
 * 链接仅保存在应用私有目录，可随时在设置页清空。
 */
class DownloadLinkListener : NotificationListenerService() {

    companion object {
        private const val TAG = "LinkListener"
        private val PACKAGE_EXT = setOf("apk", "apks", "xapk", "zip")
        private val URL_REGEX = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "通知监听已连接")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val packageName = sbn.packageName ?: return
        if (packageName == applicationContext.packageName) return
        val app = applicationContext as? DownloadGuardApp ?: return

        val texts = collectTexts(notification)
        if (texts.isEmpty()) return

        scope.launch {
            val enabled = runCatching { app.container.settings.settings.first().captureNotificationLinks }
                .getOrDefault(false)
            if (!enabled) return@launch

            val fileName = texts.firstOrNull { looksLikePackage(it) }?.trim()?.take(200)
            val url = texts.asSequence()
                .flatMap { URL_REGEX.findAll(it).asSequence() }
                .map { it.value.trimEnd('.', ',', '，', '。', ')', '）', ']', '】') }
                .firstOrNull { candidate ->
                    val lower = candidate.lowercase()
                    PACKAGE_EXT.any { lower.contains(".$it") } ||
                        lower.contains("download") || lower.contains("apk")
                } ?: return@launch
            app.container.capturedLinks.record(url, fileName, "通知·$packageName")
            Log.i(TAG, "捕获链接：$packageName -> ${url.take(80)}")
        }
    }

    private fun collectTexts(notification: Notification): List<String> {
        val extras = notification.extras ?: return emptyList()
        val keys = listOf(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_INFO_TEXT,
            Notification.EXTRA_SUMMARY_TEXT
        )
        return keys.mapNotNull { key ->
            runCatching { extras.getCharSequence(key)?.toString() }.getOrNull()
        }.filter { it.isNotBlank() }
    }

    private fun looksLikePackage(text: String): Boolean {
        val lower = text.lowercase()
        return PACKAGE_EXT.any { lower.endsWith(".$it") || lower.contains(".$it ") }
    }
}

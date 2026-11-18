package com.dlguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dlguard.MainActivity
import com.dlguard.R
import com.dlguard.core.Formatters
import com.dlguard.data.db.CheckRecord
import com.dlguard.integrity.Verdict

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_STATUS = "guard_status"
        const val CHANNEL_ALERT = "guard_alert"
        const val CHANNEL_DOWNLOAD = "guard_download"
        const val STATUS_ID = 1001
        const val DOWNLOAD_ID = 3001

        const val ACTION_REDOWNLOAD = "com.dlguard.action.REDOWNLOAD"
        const val ACTION_DELETE = "com.dlguard.action.DELETE"
        const val ACTION_IGNORE = "com.dlguard.action.IGNORE"

        const val EXTRA_RECORD_ID = "record_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        /** 是否已授予「通知使用权」——读取其他 App 下载通知里链接的前提。 */
        fun isNotificationListenerEnabled(context: Context): Boolean = runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        }.getOrDefault(false)

        fun openNotificationListenerSettings(context: Context) {
            val candidates = listOf(
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
                Intent(android.provider.Settings.ACTION_SETTINGS)
            )
            candidates.forEach { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(intent) }.isSuccess) return
            }
        }
    }

    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val status = NotificationChannel(
            CHANNEL_STATUS,
            "守护状态",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "常驻显示下载目录守护状态"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val alert = NotificationChannel(
            CHANNEL_ALERT,
            "完整性告警",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "安装包校验失败、已隔离时提醒"
            enableVibration(true)
        }
        val download = NotificationChannel(
            CHANNEL_DOWNLOAD,
            "下载进度",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "内置加固下载器的进度与结果"
            setShowBadge(false)
        }
        manager.createNotificationChannel(status)
        manager.createNotificationChannel(alert)
        manager.createNotificationChannel(download)
    }

    fun buildStatusNotification(
        checked: Int,
        broken: Int,
        dirs: Int,
        running: Boolean,
        storageGranted: Boolean
    ): Notification {
        val content = if (com.dlguard.BuildConfig.STORE_BUILD) {
            // 商店版没有目录监听，常驻通知只承担「下载服务运行中」这一个事实
            "下载服务运行中 · 点击返回应用"
        } else when {
            !storageGranted -> "缺少全文件访问权限，无法监听公共下载目录"
            running -> "正在守护 $dirs 个下载目录 · 已校验 $checked · 损坏 $broken"
            else -> "守护已暂停，点击恢复"
        }
        val openIntent = PendingIntent.getActivity(
            context,
            100,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIntent = PendingIntent.getService(
            context,
            101,
            Intent(context, MonitorService::class.java).setAction(
                if (running) MonitorService.ACTION_PAUSE else MonitorService.ACTION_RESUME
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_guard)
            .setContentTitle(if (com.dlguard.BuildConfig.STORE_BUILD) "DownloadGuard" else "浏览器下载接管")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(openIntent)
            .addAction(0, if (running) "暂停守护" else "恢复守护", toggleIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setColor(Color.parseColor("#5EEAD4"))
            .build()
    }

    fun notifyCorrupt(record: CheckRecord) {
        val verdict = runCatching { Verdict.valueOf(record.verdict) }.getOrNull()
        val reason = verdict?.label ?: record.verdict
        val title = "${record.fileName} 校验失败（${reason}）"
        val body = buildString {
            append("已隔离到应用私有目录，原文件未删除。\n")
            append("大小 ${Formatters.bytes(record.sizeBytes)} · ")
            append(Formatters.dateTime(record.checkedAt))
            if (!record.downloadUrl.isNullOrBlank()) {
                append("\n已找到下载链接，可直接重下")
            } else {
                append("\n未找到原始下载链接，请在通知中重新下载或手动处理")
            }
        }
        val notificationId = 2000 + (record.id % 100000).toInt()
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_guard)
            .setContentTitle(title)
            .setContentText(reason + " · 已隔离")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setColor(Color.parseColor("#F87171"))
            .setContentIntent(openRecordIntent(record.id, notificationId))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        builder.addAction(
            R.drawable.ic_stat_guard,
            "重新下载",
            actionIntent(ACTION_REDOWNLOAD, record.id, notificationId, 1)
        )
        builder.addAction(
            R.drawable.ic_stat_guard,
            "删除",
            actionIntent(ACTION_DELETE, record.id, notificationId, 2)
        )
        builder.addAction(
            R.drawable.ic_stat_guard,
            "忽略",
            actionIntent(ACTION_IGNORE, record.id, notificationId, 3)
        )
        notifySafely(notificationId, builder.build())
    }

    fun notifyDownloadStarted(fileName: String): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            200,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_stat_guard)
            .setContentTitle("开始下载 $fileName")
            .setContentText("0% · 正在建立连接")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(0, 0, true)
            .build()
    }

    fun notifyDownloadProgress(fileName: String, progress: Float, text: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
            .setSmallIcon(R.drawable.ic_stat_guard)
            .setContentTitle(fileName)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(1000, (progress * 1000).toInt().coerceIn(0, 1000), progress <= 0f)
        notifySafely(DOWNLOAD_ID, builder.build())
    }

    fun notifyDownloadFinished(success: Boolean, title: String, message: String) {
        val builder = NotificationCompat.Builder(context, if (success) CHANNEL_DOWNLOAD else CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_guard)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(if (success) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setColor(if (success) Color.parseColor("#5EEAD4") else Color.parseColor("#F87171"))
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    201,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        notifySafely(DOWNLOAD_ID, builder.build())
    }

    fun notifyPermissionMissing(message: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_guard)
            .setContentTitle("浏览器下载接管需要授权")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    202,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        notifySafely(4001, builder.build())
    }

    fun notifyClipboardLink(url: String) {
        val intent = PendingIntent.getActivity(
            context,
            300,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_URL, url),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_guard)
            .setContentTitle("发现下载链接")
            .setContentText("点击交给下载守护，支持断点续传与完整性校验")
            .setStyle(NotificationCompat.BigTextStyle().bigText(url))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setColor(Color.parseColor("#5EEAD4"))
            .setContentIntent(intent)
            .addAction(R.drawable.ic_stat_guard, "立即下载", intent)
        notifySafely(4002, builder.build())
    }

    fun cancel(id: Int) = runCatching { manager.cancel(id) }


    private fun openRecordIntent(recordId: Long, notificationId: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_RECORD_ID, recordId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun actionIntent(action: String, recordId: Long, notificationId: Int, offset: Int): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_RECORD_ID, recordId)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        return PendingIntent.getBroadcast(
            context,
            notificationId * 10 + offset,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notifySafely(id: Int, notification: Notification) {
        runCatching { manager.notify(id, notification) }
    }
}

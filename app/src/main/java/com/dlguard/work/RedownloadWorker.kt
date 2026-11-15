package com.dlguard.work

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import com.dlguard.DownloadGuardApp
import com.dlguard.core.Formatters
import com.dlguard.data.ResolvedSource
import com.dlguard.service.GuardState
import com.dlguard.service.NotificationHelper
import java.util.concurrent.TimeUnit

/** 通知「重新下载」触发的后台下载任务：断点续传 + 完成后强制校验 + 落库。 */
class RedownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val KEY_URL = "url"
        private const val KEY_NAME = "name"
        private const val KEY_RECORD_ID = "record_id"

        fun enqueue(context: Context, url: String, fileName: String?, recordId: Long) {
            val request = OneTimeWorkRequestBuilder<RedownloadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_URL to url,
                        KEY_NAME to fileName,
                        KEY_RECORD_ID to recordId
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("redownload")
                .build()
            runCatching {
                WorkManager.getInstance(context)
                    // KEEP：用户连点两次不会把正在跑的下载取消重来
                    .enqueueUniqueWork("redownload_$recordId", ExistingWorkPolicy.KEEP, request)
            }
        }
    }

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val fileName = inputData.getString(KEY_NAME)
        val app = applicationContext as? DownloadGuardApp ?: return Result.failure()
        val notifier = NotificationHelper(applicationContext)
        notifier.createChannels()

        val started = notifier.notifyDownloadStarted(fileName ?: url.substringAfterLast('/'))
        runCatching { setForeground(foregroundInfo(started)) }

        val settings = runCatching { app.container.settings.settings.first() }.getOrNull()
        val wifiOnly = settings?.wifiOnlyDownload ?: false
        val threads = settings?.downloadThreads ?: 4
        val outcome = app.container.downloader.download(
            url = url,
            preferredName = fileName,
            requireWifi = wifiOnly,
            connections = threads,
            onProgress = { progress ->
                notifier.notifyDownloadProgress(
                    fileName = fileName ?: "安装包",
                    progress = progress.fraction,
                    text = buildString {
                        append("${(progress.fraction * 100).toInt()}%")
                        if (progress.totalBytes > 0) {
                            append(" · ${Formatters.bytes(progress.downloadedBytes)} / ${Formatters.bytes(progress.totalBytes)}")
                        } else {
                            append(" · ${Formatters.bytes(progress.downloadedBytes)}")
                        }
                        if (progress.bytesPerSecond > 0) append(" · ${Formatters.speed(progress.bytesPerSecond)}")
                        if (progress.connections > 1) append(" · ${progress.connections} 连接")
                        if (progress.etaSeconds > 0) append(" · 剩余 ${Formatters.durationSeconds(progress.etaSeconds)}")
                        if (progress.attempt > 1) append(" · 第 ${progress.attempt} 次重试")
                    }
                )
            }
        )

        return if (outcome.success) {
            val file = outcome.file!!
            val integrity = outcome.integrity!!
            val recordId = app.container.repository.saveResult(
                result = integrity,
                file = file,
                source = ResolvedSource(
                    downloadUrl = url,
                    declaredSize = integrity.declaredSize,
                    origin = "内置加固下载器"
                ),
                quarantinedPath = null
            )
            val counts = runCatching { app.container.repository.counts() }.getOrNull()
            GuardState.update {
                it.copy(
                    checked = counts?.total ?: it.checked,
                    broken = counts?.broken ?: it.broken,
                    lastCheckedAt = System.currentTimeMillis(),
                    lastFileName = file.name,
                    lastVerdict = integrity.verdict.name
                )
            }
            notifier.notifyDownloadFinished(
                success = true,
                title = "下载完成 · 校验通过",
                message = "${file.name}（${Formatters.bytes(file.length())}）\n${integrity.detail}\n记录 #$recordId"
            )
            Result.success()
        } else {
            val error = outcome.error ?: "未知错误"
            // 校验失败重试无意义；网络/IO 失败按 WorkManager 退避再试一次，避免一次抖动就前功尽弃
            val retryable = !error.contains("校验失败") && runAttemptCount < 2
            if (retryable) {
                notifier.notifyDownloadProgress(
                    fileName = fileName ?: "安装包",
                    progress = 0f,
                    text = "网络中断，稍后自动重试：$error"
                )
                Result.retry()
            } else {
                notifier.notifyDownloadFinished(
                    success = false,
                    title = "下载或校验失败",
                    message = error
                )
                Result.failure()
            }
        }
    }

    private fun foregroundInfo(notification: Notification): ForegroundInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NotificationHelper.DOWNLOAD_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NotificationHelper.DOWNLOAD_ID, notification)
        }
    }
}

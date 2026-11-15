package com.dlguard.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.dlguard.DownloadGuardApp
import com.dlguard.MainActivity
import com.dlguard.work.RedownloadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** 通知上的三个按钮：重新下载 / 删除隔离文件 / 忽略。 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotifyAction"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val recordId = intent.getLongExtra(NotificationHelper.EXTRA_RECORD_ID, -1L)
        val notificationId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIFICATION_ID, 0)
        if (recordId <= 0L) return

        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = appContext as? DownloadGuardApp ?: return@launch
                val repository = app.container.repository
                val record = repository.findRecord(recordId)
                when (action) {
                    NotificationHelper.ACTION_IGNORE -> {
                        repository.markIgnored(recordId, true)
                        cancel(context, notificationId)
                        toast(context, "已忽略本次告警，隔离文件仍保留")
                    }
                    NotificationHelper.ACTION_DELETE -> {
                        val path = record?.quarantinedPath
                        if (!path.isNullOrBlank()) {
                            val deleted = runCatching { File(path).delete() }.getOrDefault(false)
                            if (deleted) repository.setQuarantinePath(recordId, null)
                            Log.i(TAG, "删除隔离文件 $path -> $deleted")
                        }
                        repository.markIgnored(recordId, true)
                        cancel(context, notificationId)
                        toast(context, "隔离文件已删除")
                    }
                    NotificationHelper.ACTION_REDOWNLOAD -> {
                        // 优先用历史 URL，其次用旁路捕获库按文件名匹配；都没有才回到 App 让用户补链接
                        val url = record?.downloadUrl?.takeIf { it.isNotBlank() }
                            ?: record?.fileName?.let { name ->
                                app.container.capturedLinks.findByFileName(name)?.url
                            }
                        if (!url.isNullOrBlank()) {
                            RedownloadWorker.enqueue(context, url, record?.fileName, recordId)
                            cancel(context, notificationId)
                            toast(context, "已加入重新下载队列（后台多连接续传）")
                        } else {
                            toast(context, "没有可用的原始链接，正在打开 App 补充")
                            openApp(context, recordId)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理通知操作失败", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun cancel(context: Context, id: Int) {
        if (id != 0) runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    private fun toast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun openApp(context: Context, recordId: Long = -1L) {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(NotificationHelper.EXTRA_RECORD_ID, recordId)
            )
        }
    }
}

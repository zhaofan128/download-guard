package com.dlguard.relay

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dlguard.service.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 接力网关前台服务。
 *
 * 用前台服务而不是普通 Service：开热点时代理必须活着，普通 Service 在后台
 * 十几秒就会被系统冻结，对面下载直接断流。
 */
class RelayService : Service() {

    companion object {
        const val ACTION_START = "com.dlguard.relay.START"
        const val ACTION_STOP = "com.dlguard.relay.STOP"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_TOKEN = "extra_token"
        private const val NOTIFICATION_ID = 0x524C

        fun start(context: Context, port: Int, token: String) {
            val intent = Intent(context, RelayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_TOKEN, token)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, RelayService::class.java).apply { action = ACTION_STOP }
                )
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: RelayServer? = null
    private var ticker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        val port = intent?.getIntExtra(EXTRA_PORT, RelayServer.DEFAULT_PORT)
            ?: RelayServer.DEFAULT_PORT
        val token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()

        startForegroundSafely(buildNotification("正在启动接力网关…"))
        if (server == null) {
            val created = RelayServer(
                token = token,
                onEvent = { message -> lastMessage = message },
                fetcher = RelayFetch(fetchClient())
            )
            val actualPort = runCatching { created.start(port) }.getOrElse { error ->
                RelayState.lastError = error.message
                lastMessage = "启动失败：${error.message ?: "端口被占用"}"
                shutdown()
                return START_NOT_STICKY
            }
            server = created
            RelayState.gatewayRunning = true
            RelayState.gatewayPort = actualPort
            RelayState.gatewayToken = token
            RelayState.lastError = null
            startTicker()
        }
        return START_STICKY
    }

    @Volatile
    private var lastMessage: String = "等待设备连接"

    /** 网关自己抓页面用独立 client：不能走接力代理，否则会在网关内部自己代理自己。 */
    private fun fetchClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val current = server ?: break
                val up = current.bytesUpstream.get()
                val down = current.bytesDownstream.get()
                val text = if (up + down == 0L) {
                    lastMessage
                } else {
                    "已借出 ${human(up + down)} · ${lastMessage}"
                }
                runCatching {
                    val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                    manager.notify(NOTIFICATION_ID, buildNotification(text))
                }
                delay(2_000)
            }
        }
    }

    private fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun buildNotification(text: String): Notification {
        val open = Intent(this, com.dlguard.MainActivity::class.java)
        val pending = android.app.PendingIntent.getActivity(
            this, 0, open,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_STATUS)
            .setSmallIcon(com.dlguard.R.drawable.ic_stat_guard)
            .setContentTitle("接力网关运行中")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundSafely(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun shutdown() {
        ticker?.cancel()
        ticker = null
        runCatching { server?.stop() }
        server = null
        RelayState.gatewayRunning = false
        RelayState.gatewayPort = 0
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        ticker?.cancel()
        runCatching { server?.stop() }
        server = null
        RelayState.gatewayRunning = false
        RelayState.gatewayPort = 0
        super.onDestroy()
    }
}

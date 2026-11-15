package com.dlguard.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.dlguard.DownloadGuardApp
import com.dlguard.core.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 闹钟级看门狗，补 WorkManager 的短板。
 *
 * WorkManager 的周期任务最小间隔 15 分钟，且会被 Doze/厂商省电策略推迟到几小时；
 * 一旦前台服务被系统杀掉，用户可能整晚都不知道守护已经停了。
 * 这里用 [AlarmManager] 每 15 分钟补一次唤醒：
 *  - Android 12+ 若没有「闹钟与提醒」权限，降级为 `setAndAllowWhileIdle`（不精确但可用）；
 *  - 有权限则 `setExactAndAllowWhileIdle`，误差在秒级；
 *  - 无论走哪条分支，触发后都会重新排下一次，形成自愈链。
 */
object Watchdog {

    const val ACTION_WATCHDOG = "com.dlguard.action.WATCHDOG"
    private const val REQUEST_CODE = 0x9A01
    private const val INTERVAL_MS = 15 * 60 * 1000L
    private const val TAG = "Watchdog"

    fun schedule(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(app, WatchdogReceiver::class.java).setAction(ACTION_WATCHDOG)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = runCatching {
            PendingIntent.getBroadcast(app, REQUEST_CODE, intent, flags)
        }.getOrNull() ?: return
        val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        runCatching {
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
            }
            Log.i(TAG, "看门狗已排程，精确=$exact")
        }.onFailure { Log.w(TAG, "看门狗排程失败：${it.message}") }
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, WatchdogReceiver::class.java).setAction(ACTION_WATCHDOG)
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching { manager.cancel(pending) }
    }
}

/** 闹钟触发：检查设置与运行态，按需重启守护服务，并排下一次。 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Watchdog.ACTION_WATCHDOG) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val app = appContext as? DownloadGuardApp
                val settings = runCatching { app?.container?.settings?.settings?.first() }.getOrNull()
                val allowed = settings?.let { it.autoStart && it.monitorEnabled } ?: true
                val paused = GuardState.snapshot.value.paused
                if (allowed && !paused && !PermissionHelper.hasStorageAccess(appContext)) {
                    Log.i("WatchdogReceiver", "未授权全文件访问，跳过重启")
                } else if (allowed && !paused) {
                    ServiceController.start(appContext)
                    Log.i("WatchdogReceiver", "看门狗已重启守护服务")
                }
            } catch (e: Exception) {
                Log.w("WatchdogReceiver", "看门狗执行失败：${e.message}")
            } finally {
                Watchdog.schedule(appContext)
                pending.finish()
            }
        }
    }
}

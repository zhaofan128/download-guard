package com.dlguard.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Constraints
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.dlguard.work.IntegritySweepWorker
import java.util.concurrent.TimeUnit

object ServiceController {

    fun start(context: Context) {
        val intent = Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_START)
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    fun stop(context: Context) {
        // 只有服务真的在运行才发停止指令：
        // 服务未运行时用 startForegroundService 发 STOP 会冷启动一个「立即自杀」的前台服务，
        // 既浪费又容易在部分 ROM 上触发 ForegroundServiceDidNotStartInTime 崩溃
        if (GuardState.snapshot.value.running || isServiceAlive(context)) {
            val intent = Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_STOP)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
        Watchdog.cancel(context)
    }

    /** 从系统层面确认服务进程是否活着（比状态流更可靠）。 */
    private fun isServiceAlive(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        manager.getRunningServices(64).any {
            it.service.className == MonitorService::class.java.name
        }
    }.getOrDefault(false)

    fun pause(context: Context) {
        val intent = Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_PAUSE)
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    fun resume(context: Context) {
        val intent = Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_RESUME)
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    fun rescan(context: Context) {
        val intent = Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_RESCAN)
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    /** WorkManager 兜底：每 15 分钟检查服务是否存活，被杀则尝试拉起（失败静默，等下一次）。 */
    fun scheduleWatchdog(context: Context) {
        val request = PeriodicWorkRequestBuilder<IntegritySweepWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .setRequiresBatteryNotLow(false)
                    .build()
            )
            .build()
        runCatching {
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("guard_watchdog", ExistingPeriodicWorkPolicy.KEEP, request)
        }
        // 双保险：WorkManager 会被 Doze 推迟，闹钟看门狗负责 15 分钟内拉回
        Watchdog.schedule(context)
    }
}

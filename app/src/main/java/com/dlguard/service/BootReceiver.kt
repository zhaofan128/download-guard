package com.dlguard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dlguard.DownloadGuardApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机自启：
 *  - BOOT_COMPLETED / MY_PACKAGE_REPLACED：解锁后立刻拉起前台服务；
 *  - LOCKED_BOOT_COMPLETED：设备仍锁定，先拉起服务显示通知，服务内部等待 ACTION_USER_UNLOCKED；
 *  - QUICKBOOT_POWERON：部分国产 ROM 的「快速开机」广播。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_HTC_QUICKBOOT = "com.htc.intent.action.QUICKBOOT_POWERON"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val supported = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == ACTION_QUICKBOOT ||
            action == ACTION_HTC_QUICKBOOT
        if (!supported) return
        Log.i(TAG, "收到 $action，准备拉起守护服务")

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val app = context.applicationContext as? DownloadGuardApp
                val allow = try {
                    val settings = app?.container?.settings?.settings?.first()
                    settings?.let { it.autoStart && it.monitorEnabled } ?: true
                } catch (e: Exception) {
                    // 直接启动阶段（locked）无法访问凭据加密存储，按默认开启处理
                    Log.w(TAG, "读取设置失败，按默认自启处理: ${e.message}")
                    true
                }
                if (allow) {
                    ServiceController.start(context)
                    ServiceController.scheduleWatchdog(context)
                } else {
                    Log.i(TAG, "用户已关闭自启开关，跳过")
                }
            } finally {
                pending.finish()
            }
        }
    }
}

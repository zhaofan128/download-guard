package com.dlguard.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dlguard.DownloadGuardApp
import com.dlguard.core.PermissionHelper
import com.dlguard.service.GuardState
import com.dlguard.service.ServiceController
import kotlinx.coroutines.flow.first

/**
 * 15 分钟一次的看门狗：服务被 ROM 杀死后尝试重新拉起，并触发一次补扫。
 * 后台启动前台服务在部分系统上会被拒绝，这里全部静默处理，等待下一次或用户打开 App。
 */
class IntegritySweepWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!PermissionHelper.hasStorageAccess(applicationContext)) return Result.success()
        // 用户明确暂停/停止过时不要偷偷拉起，否则「停止守护」等于没停
        val settings = runCatching {
            (applicationContext as? DownloadGuardApp)?.container?.settings?.settings?.first()
        }.getOrNull()
        if (settings != null && !(settings.autoStart && settings.monitorEnabled)) {
            return Result.success()
        }
        // 无论状态如何都发一次 START（幂等）；服务已被杀时这是唯一的兜底拉起机会
        ServiceController.start(applicationContext)
        if (GuardState.snapshot.value.running) {
            ServiceController.rescan(applicationContext)
        }
        return Result.success()
    }
}

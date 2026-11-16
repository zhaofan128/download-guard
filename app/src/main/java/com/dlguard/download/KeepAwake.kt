package com.dlguard.download

import android.content.Context
import android.os.PowerManager

/**
 * 收尾阶段的局部唤醒锁。
 *
 * 为什么需要：下载进度到 100% 之后还有两件重活——整文件哈希 + APK 签名校验（大包要十几秒），
 * 以及 rename / 跨卷复制落盘。这段时间没有网络 IO，CPU 一旦休眠、进程一旦被后台冻结，
 * 校验就会「卡在 100% 不动」，用户以为死机了强杀后台，结果分片残留、白下一遍。
 *
 * 只申请 PARTIAL_WAKE_LOCK（屏幕可以关），带超时兜底；异常路径也会 release，
 * 不会出现「下载完了电掉得特别快」的经典事故。
 */
class KeepAwake(context: Context) {

    private val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    suspend fun <T> holdSuspend(tag: String, timeoutMs: Long = 10 * 60_000L, block: suspend () -> T): T {
        val manager = power ?: return block()
        val lock = runCatching {
            manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dlguard:$tag").apply {
                setReferenceCounted(false)
                acquire(timeoutMs)
            }
        }.getOrNull()
        return try {
            block()
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }
}

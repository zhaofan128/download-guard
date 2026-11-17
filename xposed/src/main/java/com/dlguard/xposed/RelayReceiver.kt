package com.dlguard.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import de.robv.android.xposed.XposedBridge

/**
 * 宿主进程 → 主 App 的转发站。
 *
 * 为什么需要它：主 App 的 CapturedUrlReceiver 要求 signature 权限
 * `com.dlguard.permission.SEND_CAPTURED_URL`，而宿主（浏览器/网盘 App）
 * 不持有该权限——宿主直接发广播会被系统静默丢弃（这是 3.2.0 链路不通的根因）。
 *
 * 方案：宿主把回传数据显式发给本模块（同发布密钥签名的 APK，天然持有该权限），
 * 本模块校验调用方确实是已知宿主包之后，再转发给主 App。
 * 显式广播（setPackage）不受 Android 8+ 的隐式广播限制，模块被停用后
 * 由 FLAG_INCLUDE_STOPPED_PACKAGES 唤醒。
 */
class RelayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RELAY) return
        val url = intent.getStringExtra("url").orEmpty()
        if (url.isEmpty()) return

        // 只接受宿主的广播：调用方 UID 必须属于已知目标包，防止别的 App 借道伪造回传
        val caller = packageNameOfUid(context, Binder.getCallingUid())
        if (caller !in GuardHook.TARGET_PACKAGES) {
            XposedBridge.log("[DownloadGuard-Xposed] 拒绝非宿主转发：caller=$caller")
            return
        }

        val forwarded = Intent(ACTION_CAPTURED)
            .setPackage(MAIN_APP)
            .putExtra("url", url)
            .putExtra("name", intent.getStringExtra("name"))
            .putExtra("source", intent.getStringExtra("source"))
            .putExtra("referer", intent.getStringExtra("referer"))
            .putExtra("userAgent", intent.getStringExtra("userAgent"))
            .putExtra("cookie", intent.getStringExtra("cookie"))
            .putExtra("mime", intent.getStringExtra("mime"))
            .putExtra("length", intent.getLongExtra("length", 0L))
            .putExtra("kind", intent.getStringExtra("kind"))
            .putExtra("hostPackage", intent.getStringExtra("hostPackage"))
            .putExtra("dlId", intent.getLongExtra("dlId", -1L))
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        runCatching { context.sendBroadcast(forwarded) }
            .onFailure { XposedBridge.log("[DownloadGuard-Xposed] 转发失败：${it.message}") }
    }

    private fun packageNameOfUid(context: Context, uid: Int): String? {
        if (uid <= 0) return null
        return runCatching {
            context.packageManager.getPackagesForUid(uid)?.firstOrNull()
        }.getOrNull()
    }

    companion object {
        const val ACTION_RELAY = "com.dlguard.xposed.action.RELAY"
        const val ACTION_CAPTURED = "com.dlguard.action.CAPTURED_URL"
        const val MAIN_APP = "com.dlguard"
    }
}

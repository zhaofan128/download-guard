package com.dlguard.xposed

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import de.robv.android.xposed.XposedBridge

/**
 * 宿主进程 → 主 App 的转发站（ContentProvider 形态）。
 *
 * 为什么 3.2.2 从广播改成 Provider：BroadcastReceiver.onReceive 由 ActivityManager
 * 调度到应用主线程执行，**不是 Binder 调用线程**——Binder.getCallingUid() 在那里
 * 拿到的不是广播发送方 UID（可能是本进程，也可能是 system 1000，随 ROM 而定），
 * 3.2.1 用它校验宿主会把真正的宿主也拒掉。ContentProvider.call() 天生在 Binder
 * 线程执行，getCallingUid() 就是真实调用方；宿主侧 contentResolver.call() 一行
 * 同步完成，也不用管理 bindService 的生命周期。
 *
 * 权限模型：provider 故意**不加** permission 保护——宿主（浏览器/网盘 App）没有
 * signature 权限，加了会被系统直接拦掉。校验在 call() 里手动做：
 * 调用方 UID 对应的包名必须命中 [GuardHook.TARGET_PACKAGES]，method 白名单只认
 * "relay"。校验不过返回 ok=false，不转发。
 *
 * 转发：本模块进程持有 signature 权限 SEND_CAPTURED_URL（与主 App 同发布密钥签名），
 * 由模块发广播给主 App，主 App 的 receiver 权限检查能过。
 */
class RelayProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_RELAY) return null
        val src = extras ?: Bundle()
        val url = src.getString("url").orEmpty()
        if (url.isEmpty()) {
            return Bundle().apply { putBoolean("ok", false); putString("error", "empty_url") }
        }

        // call() 在 Binder 线程执行，getCallingUid() 是可靠的真实调用方
        val caller = packageNameOfUid(Binder.getCallingUid())
        if (caller !in GuardHook.TARGET_PACKAGES) {
            log("拒绝非宿主转发：caller=$caller")
            return Bundle().apply { putBoolean("ok", false); putString("error", "not_target") }
        }

        val ctx = context
            ?: return Bundle().apply { putBoolean("ok", false); putString("error", "no_context") }
        val forwarded = Intent(ACTION_CAPTURED)
            .setPackage(MAIN_APP)
            .putExtra("url", url)
            .putExtra("name", src.getString("name"))
            .putExtra("source", src.getString("source"))
            .putExtra("referer", src.getString("referer"))
            .putExtra("userAgent", src.getString("userAgent"))
            .putExtra("cookie", src.getString("cookie"))
            .putExtra("mime", src.getString("mime"))
            .putExtra("length", src.getLong("length", 0L))
            .putExtra("kind", src.getString("kind"))
            .putExtra("hostPackage", src.getString("hostPackage"))
            .putExtra("dlId", src.getLong("dlId", -1L))
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        return runCatching { ctx.sendBroadcast(forwarded) }
            .fold(
                onSuccess = {
                    Bundle().apply { putBoolean("ok", true) }
                },
                onFailure = {
                    log("转发主 App 失败：${it.message}")
                    Bundle().apply { putBoolean("ok", false); putString("error", "forward_failed") }
                }
            )
    }

    // Provider 只开放 call/relay 一个口子，其余一律拒绝
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String? = null

    private fun packageNameOfUid(uid: Int): String? {
        if (uid <= 0) return null
        val pm = context?.packageManager ?: return null
        return runCatching { pm.getPackagesForUid(uid)?.firstOrNull() }.getOrNull()
    }

    private fun log(message: String) {
        XposedBridge.log("[DownloadGuard-Xposed] $message")
    }

    companion object {
        const val METHOD_RELAY = "relay"
        const val ACTION_CAPTURED = "com.dlguard.action.CAPTURED_URL"
        const val MAIN_APP = "com.dlguard"

        /** 宿主侧 contentResolver.call 的目标地址（与 manifest authorities 一致）。 */
        val RELAY_URI: Uri = Uri.parse("content://com.dlguard.xposed.relay")
    }
}

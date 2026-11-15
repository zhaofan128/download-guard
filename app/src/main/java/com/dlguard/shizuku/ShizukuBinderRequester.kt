package com.dlguard.shizuku

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import com.dlguard.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Shizuku Binder 主动索取协议（管理器侧 ShizukuReceiver，13.x 全版本有效）。
 *
 * 背景：Shizuku 官方的 binder 分发是「管理器单向推送」（call app 端 ShizukuProvider 的
 * sendBinder / 发 moe.shizuku.api.action.BINDER_RECEIVED 广播）。覆盖安装、App 进程被
 * 杀、推送时进程未启动等场景会错过推送，此后 pingBinder 永远为 false，界面就卡在
 * 「服务未运行」——而服务实际在跑。
 *
 * 管理器同时导出了一个 receiver，接受 `rikka.shizuku.intent.action.REQUEST_BINDER`：
 * App 在 intent 的 "data" Bundle 里放一个回调 binder，管理器 transact(1) 时把
 * Shizuku 服务 binder（writeStrongBinder）与 sourceDir（writeString）写回来。
 * 这是官方为 shell 进程准备的取 binder 通道，此处复用作为 App 端的自救路径。
 *
 * 安全边界：回调 binder 是匿名 binder，只有持有它的管理器进程能 transact；
 * 回传的仅是 binder 引用，授权仍由 Shizuku 服务的权限检查把关。
 */
object ShizukuBinderRequester {

    const val ACTION_REQUEST_BINDER = "rikka.shizuku.intent.action.REQUEST_BINDER"
    const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"

    private const val TRANSACTION_WRITE_BACK = 1
    private const val EXTRA_DATA = "data"
    private const val EXTRA_BINDER_KEY = "binder"

    private const val MAX_ATTEMPTS = 4
    private const val INTERVAL_MS = 1_800L

    /** 管理器回传 binder 的接收端。 */
    private val callbackBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == TRANSACTION_WRITE_BACK) {
                val received = runCatching { data.readStrongBinder() }.getOrNull()
                runCatching { data.readString() }
                if (received != null) {
                    // 与官方 ShizukuProvider.handleSendBinder 同款入口
                    runCatching { Shizuku.onBinderReceived(received, BuildConfig.APPLICATION_ID) }
                }
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    /**
     * 向管理器索取 binder。带重试：管理器进程可能需要被广播拉起后
     * 先与 Shizuku 服务建立连接，第一次往往拿不到。
     * 返回是否拿到了可用的 binder。
     */
    suspend fun request(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        repeat(MAX_ATTEMPTS) { attempt ->
            send(appContext)
            delay(INTERVAL_MS)
            val usable = runCatching { Shizuku.getBinder()?.pingBinder() == true }
                .getOrDefault(false)
            if (usable) return@withContext true
            attempt + 1 // 保持结构清晰：剩余重试次数由 repeat 控制
        }
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    }

    /** 发一次请求广播（显式指向管理器 receiver，广播不会路由到同名 Activity）。 */
    private fun send(context: Context) {
        runCatching {
            val payload = Bundle().apply { putBinder(EXTRA_BINDER_KEY, callbackBinder) }
            val intent = Intent(ACTION_REQUEST_BINDER).apply {
                setPackage(MANAGER_PACKAGE)
                putExtra(EXTRA_DATA, payload)
            }
            context.sendBroadcast(intent)
        }
    }
}

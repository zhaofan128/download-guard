package com.dlguard

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.dlguard.core.CrashGuard
import com.dlguard.data.AppContainer
import com.dlguard.download.HostCircuitBreaker
import com.dlguard.download.RacingDns
import com.dlguard.service.NotificationHelper
import com.dlguard.service.ServiceController
import com.dlguard.shizuku.ShizukuHelper

class DownloadGuardApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 黑匣子必须最先安装：任何未捕获异常（含协程冒泡）都会留下现场
        CrashGuard.install(this)
        container = AppContainer(this)
        // Shizuku 授权中枢尽早初始化：开机自启时剪贴板/无障碍服务可能先于 Activity 运行。
        // 商店版没有声明 Shizuku 权限与 Provider，初始化只会空转，直接跳过。
        if (!BuildConfig.STORE_BUILD) {
            ShizukuHelper.init(this)
        }
        runCatching { NotificationHelper(this).createChannels() }
        // 商店版没有精确闹钟与开机自启，看门狗这条路径整体关闭
        if (!BuildConfig.STORE_BUILD) {
            runCatching { ServiceController.scheduleWatchdog(this) }
        }
        watchNetworkChange()
    }

    /**
     * 网络切换（Wi-Fi ⇄ 5G）时作废旧的双栈解析结果与主机熔断记录：
     * 新链路下的 IPv6 可达性、CDN 就近节点、限流状态都可能完全不同，
     * 沿用旧结论会让我们继续走一条已经不通的路。
     */
    private fun watchNetworkChange() {
        runCatching {
            val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    RacingDns.invalidate()
                    HostCircuitBreaker.reset()
                }
            })
        }
    }
}

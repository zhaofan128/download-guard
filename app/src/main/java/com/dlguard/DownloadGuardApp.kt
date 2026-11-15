package com.dlguard

import android.app.Application
import com.dlguard.core.CrashGuard
import com.dlguard.data.AppContainer
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
        // Shizuku 授权中枢尽早初始化：开机自启时剪贴板/无障碍服务可能先于 Activity 运行
        ShizukuHelper.init(this)
        runCatching { NotificationHelper(this).createChannels() }
        runCatching { ServiceController.scheduleWatchdog(this) }
    }
}

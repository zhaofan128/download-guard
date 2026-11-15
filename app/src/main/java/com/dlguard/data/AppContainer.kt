package com.dlguard.data

import android.app.ActivityManager
import android.content.Context
import com.dlguard.data.db.AppDatabase
import com.dlguard.download.DownloadCenter
import com.dlguard.download.ResumableDownloader
import com.dlguard.integrity.ApkIntegrityChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 极简手写依赖容器（不引入 DI 框架，减少体积与编译复杂度）。
 */
class AppContainer(private val context: Context) {

    /** 应用级协程作用域：插件配置联动这类「不跟随界面生命周期」的写入放这里。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy { AppDatabase.get(context) }
    val settings: SettingsStore by lazy { SettingsStore(context) }
    val quarantine: QuarantineManager by lazy { QuarantineManager(context) }
    val checker: ApkIntegrityChecker by lazy { ApkIntegrityChecker(context) }
    val resolver: DownloadHistoryResolver by lazy { DownloadHistoryResolver(context, capturedLinks) }
    val capturedLinks: CapturedLinks by lazy { CapturedLinks(context) }
    val repository: GuardRepository by lazy {
        GuardRepository(context, database, settings, quarantine)
    }
    val downloader: ResumableDownloader by lazy { ResumableDownloader(context, checker) }

    /** 应用级下载中心：Activity 退出后下载继续。 */
    val downloadCenter: DownloadCenter by lazy {
        DownloadCenter(context, downloader, repository, settings)
    }

    /** 插件系统：内置官方插件 + 第三方插件 + 市场索引 + 局域网下载桥。 */
    val pluginManager: com.dlguard.plugin.PluginManager by lazy {
        com.dlguard.plugin.PluginManager(
            context = context,
            downloadCenterProvider = { downloadCenter },
            onApplyConcurrency = { threads ->
                // 插件配置联动全局并发：写入 DataStore 后再由下载器读取
                appScope.launch { settings.setDownloadThreads(threads) }
            }
        )
    }

    val lowRamDevice: Boolean by lazy {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.isLowRamDevice ?: false
    }
}

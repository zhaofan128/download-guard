package com.dlguard.data

import android.app.ActivityManager
import android.content.Context
import com.dlguard.data.db.AppDatabase
import com.dlguard.download.DownloadCenter
import com.dlguard.download.ResumableDownloader
import com.dlguard.integrity.ApkIntegrityChecker
import com.dlguard.relay.RelayState
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
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

    /** 用户显式确认过「没问题」的文件指纹（加壳包、企业定制包等）。 */
    val trustedStore: TrustedStore by lazy { TrustedStore(context) }
    val checker: ApkIntegrityChecker by lazy { ApkIntegrityChecker(context) }
    val resolver: DownloadHistoryResolver by lazy { DownloadHistoryResolver(context, capturedLinks) }
    val capturedLinks: CapturedLinks by lazy { CapturedLinks(context) }
    val repository: GuardRepository by lazy {
        GuardRepository(context, database, settings, quarantine)
    }
    val downloader: ResumableDownloader by lazy {
        ResumableDownloader(context, checker, relayAwareClient)
    }

    /** HLS（m3u8）点播下载：与普通下载共用目录、只读 Wi-Fi 判定与收尾校验。 */
    val hlsDownloader: com.dlguard.download.hls.HlsDownloader by lazy {
        com.dlguard.download.hls.HlsDownloader(
            client = relayAwareClient,
            targetDir = { downloader.targetDirectory() },
            verify = { file, size -> checker.check(file, size) },
            onWifi = { downloader.isOnWifiNetwork() }
        )
    }

    /** 应用级下载中心：Activity 退出后下载继续。 */
    val downloadCenter: DownloadCenter by lazy {
        DownloadCenter(
            context = context,
            downloader = downloader,
            repository = repository,
            settingsStore = settings,
            policyProvider = { pluginManager.downloadPolicy() },
            // 插件脚本重写：沙箱执行，无网络/文件/反射权限
            urlRewriter = { url -> pluginManager.rewriteUrl(url) },
            hls = hlsDownloader
        )
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

    init {
        // 接力开关要在每个 HTTP 请求的 ProxySelector 里被读到，用 volatile 状态而不是 Flow
        appScope.launch {
            runCatching {
                settings.settings.collect { s ->
                    RelayState.clientEnabled = s.relayClientEnabled
                    RelayState.host = s.relayHost
                    RelayState.port = s.relayPort
                    RelayState.token = s.relayToken
                }
            }
        }
    }

    /**
     * 接力感知的 HTTP 客户端：ProxySelector 每次请求都会读一次 [RelayState]，
     * 所以开关是即时生效的，不需要重建下载器。
     *
     * 认证走 proxyAuthenticator 而不是给每个请求硬塞 header：
     * 后者在关闭接力后会把接入码原样发给目标网站。
     */
    private val relayAwareClient: OkHttpClient by lazy {
        ResumableDownloader.defaultClient().newBuilder()
            .proxySelector(object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> {
                    if (!RelayState.active) return listOf(Proxy.NO_PROXY)
                    return listOf(
                        Proxy(
                            Proxy.Type.HTTP,
                            InetSocketAddress(RelayState.host, RelayState.port)
                        )
                    )
                }

                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
            })
            .proxyAuthenticator { _, response ->
                if (response.code == 407 && RelayState.token.isNotBlank()) {
                    response.request.newBuilder()
                        .header("Proxy-Authorization", Credentials.basic("relay", RelayState.token))
                        .build()
                } else {
                    null
                }
            }
            .build()
    }

    val lowRamDevice: Boolean by lazy {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.isLowRamDevice ?: false
    }
}

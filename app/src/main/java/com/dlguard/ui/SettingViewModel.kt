package com.dlguard.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dlguard.DownloadGuardApp
import com.dlguard.core.PermissionHelper
import com.dlguard.data.GuardSettings
import com.dlguard.service.ClipboardWatchService
import com.dlguard.service.ServiceController
import com.dlguard.relay.RelayService
import com.dlguard.shizuku.PrivilegeOps
import com.dlguard.shizuku.ShizukuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页 ViewModel：所有开关的「热生效引擎」。
 *
 * 防闪退铁律（与需求三逐条对应）：
 *  1. **状态驱动**：UI 只订阅 DataStore 的 settings 流，本类绝不直接持有可变 UI 状态；
 *  2. **视觉即时**：果冻开关点击后本地动画立即播放（JellySwitch 内部实现），
 *     落盘 + 副作用全部异步，期间 UI 不等待、不阻塞主线程；
 *  3. **300ms 防抖**：同一开关连续点击时，上一个未执行的切换任务被取消，只执行最后一个，
 *     服务不会因为手抖被反复 start/stop；
 *  4. **异步副作用**：ServiceController.start/stop、Shizuku IPC、settings put 全部在协程里跑，
 *     严禁 recreate()，严禁在 onChange 里同步阻塞；
 *  5. **Result 封装 + 回滚**：任何副作用抛出异常，落盘值立即写回，DataStore 流把开关拉回旧值，
 *     同时弹 Toast —— 进程绝不会崩。
 */
class SettingViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as DownloadGuardApp).container
    private val context get() = getApplication<Application>()

    companion object {
        /** 同一开关的防抖窗口：窗口内连点只执行最后一次。 */
        const val SWITCH_DEBOUNCE_MS = 300L
        const val SHIZUKU_REQUEST_CODE = 20260
    }

    val settings: StateFlow<GuardSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuardSettings())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Shizuku 授权状态流（首页/设置页共用，ShizukuHelper 内部单例）。 */
    val shizukuStatus: StateFlow<ShizukuHelper.Status> = ShizukuHelper.status

    /** 每个开关的执行中任务；同 key 的新请求会取消旧请求（防抖核心）。 */
    private val switchJobs = HashMap<String, Job>()

    /**
     * 热开关统一入口：防抖 + 异步 + Result 封装。
     * apply 里负责「落盘 → 副作用」，失败时负责把落盘值回滚；
     * 这里统一兜底把异常转成 Toast，不让任何异常逃出协程。
     */
    private fun hotSwitch(key: String, apply: suspend () -> Result<Unit>) {
        switchJobs.remove(key)?.cancel()
        switchJobs[key] = viewModelScope.launch {
            delay(SWITCH_DEBOUNCE_MS)
            val result = try {
                apply()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.onFailure { error ->
                _messages.tryEmit("设置未生效（已回滚）：${error.message ?: "未知错误"}")
            }
            if (switchJobs[key]?.isCancelled != true) switchJobs.remove(key)
        }
    }

    private fun emit(message: String) {
        _messages.tryEmit(message)
    }

    // ------------------------------------------------------------ 守护行为

    fun setAutoStart(value: Boolean) = hotSwitch("auto_start") {
        runCatching {
            container.settings.setAutoStart(value)
            if (value && PermissionHelper.hasStorageAccess(context)) {
                withContext(Dispatchers.IO) { ServiceController.start(context) }
            }
        }.onFailure {
            runCatching { viewModelScope.launch { container.settings.setAutoStart(!value) } }
        }
    }

    fun setNotifications(value: Boolean) = hotSwitch("notifications") {
        runCatching { container.settings.setNotifications(value) }.onFailure {
            runCatching { viewModelScope.launch { container.settings.setNotifications(!value) } }
        }
    }

    fun setHaptics(value: Boolean) = hotSwitch("haptics") {
        runCatching { container.settings.setHaptics(value) }.onFailure {
            runCatching { viewModelScope.launch { container.settings.setHaptics(!value) } }
        }
    }

    fun setDynamicColor(value: Boolean) = hotSwitch("dynamic_color") {
        runCatching { container.settings.setDynamicColor(value) }.onFailure {
            runCatching { viewModelScope.launch { container.settings.setDynamicColor(!value) } }
        }
    }

    fun setPreferInApp(value: Boolean) = hotSwitch("prefer_in_app") {
        runCatching { container.settings.setPreferInAppDownload(value) }.onFailure {
            runCatching { viewModelScope.launch { container.settings.setPreferInAppDownload(!value) } }
        }
    }

    fun setWifiOnly(value: Boolean) = hotSwitch("wifi_only") {
        runCatching { container.settings.setWifiOnlyDownload(value) }.onFailure {
            runCatching { viewModelScope.launch { container.settings.setWifiOnlyDownload(!value) } }
        }
    }

    /**
     * 监控总开关：先落盘（UI 立即响应），再异步启停前台服务。
     * 启停失败（如 Android 12+ 前台服务限制）只弹提示，落盘值不变，下次进 App 时按开关值恢复。
     */
    fun setMonitorEnabled(value: Boolean) = hotSwitch("monitor_enabled") {
        runCatching {
            container.settings.setMonitorEnabled(value)
            withContext(Dispatchers.IO) {
                if (value) ServiceController.start(context) else ServiceController.stop(context)
            }
        }.onFailure {
            runCatching { viewModelScope.launch { container.settings.setMonitorEnabled(!value) } }
        }
    }

    /**
     * 接管剪贴板：开关打开时启动前台监听服务；关闭时发 ACTION_STOP 让服务自己停。
     * 服务内部每轮轮询都会核对 DataStore 的开关值，双保险保证热生效。
     */
    fun setClipboardWatch(value: Boolean) = hotSwitch("clipboard_watch") {
        runCatching {
            container.settings.setClipboardWatch(value)
            val intent = Intent(context, ClipboardWatchService::class.java)
            if (value) {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
                emit(if (ShizukuHelper.hasPermission()) {
                    "剪贴板监听已开启（Shizuku 特权通道）"
                } else {
                    "剪贴板监听已开启（普通模式，后台读取可能受限；连接 Shizuku 后自动升级特权通道）"
                })
            } else {
                context.startService(intent.setAction(ClipboardWatchService.ACTION_STOP))
                emit("剪贴板监听已关闭")
            }
        }.onFailure {
            runCatching { viewModelScope.launch { container.settings.setClipboardWatch(!value) } }
        }
    }

    fun setCaptureNotificationLinks(value: Boolean) = hotSwitch("capture_notification_links") {
        runCatching {
            container.settings.setCaptureNotificationLinks(value)
            if (value && !com.dlguard.service.NotificationHelper.isNotificationListenerEnabled(context)) {
                emit("请先在系统「通知使用权」中勾选浏览器下载接管")
            }
        }.onFailure {
            runCatching { viewModelScope.launch { container.settings.setCaptureNotificationLinks(!value) } }
        }
    }

    fun setAccessibilityCapture(value: Boolean) = hotSwitch("accessibility_capture") {
        runCatching {
            container.settings.setAccessibilityCapture(value)
            // 这几步要读 Settings.Secure（ContentResolver IPC）、跑 Shizuku user service 的 shell，
            // 都在主线程会直接卡住；首次安装后 Shizuku 未就绪时一次 exec 能等上数秒 → ANR。
            val running = withContext(Dispatchers.IO) { isAccessibilityServiceEnabled() }
            when {
                !value -> {
                    if (ShizukuHelper.hasPermission()) {
                        withContext(Dispatchers.IO) { PrivilegeOps.disableSelfAccessibility() }
                    }
                    emit("已关闭无障碍链接抓取")
                }
                running -> emit("无障碍链接抓取已在运行")
                ShizukuHelper.hasPermission() -> {
                    val result = withContext(Dispatchers.IO) { PrivilegeOps.enableSelfAccessibility() }
                    result.fold(
                        onSuccess = { emit("已静默开启无障碍服务（Shizuku）") },
                        onFailure = { error ->
                            PermissionHelper.openAccessibilitySettings(context)
                            emit("特权开启失败（${error.message}），已跳转系统无障碍设置，请手动勾选")
                        }
                    )
                }
                else -> {
                    PermissionHelper.openAccessibilitySettings(context)
                    emit("连接 Shizuku 可一键静默开启；当前请在系统无障碍设置中手动勾选浏览器下载接管")
                }
            }
        }.onFailure {
            runCatching { viewModelScope.launch { container.settings.setAccessibilityCapture(!value) } }
        }
    }

    fun setAutoRepairAccessibility(value: Boolean) = hotSwitch("auto_repair_accessibility") {
        runCatching {
            container.settings.setAutoRepairAccessibility(value)
            if (value && !ShizukuHelper.hasPermission()) {
                emit("心跳保活需要 Shizuku 特权，请在首页连接 Shizuku")
            }
        }.onFailure {
            runCatching { viewModelScope.launch { container.settings.setAutoRepairAccessibility(!value) } }
        }
    }

    /**
     * 跨应用嗅探：只影响无障碍服务的抓取范围，不改变服务本身是否运行。
     * 开启时明确提示边界（只抓文件直链、不记录聊天），避免用户误以为在「监听微信」。
     */
    fun setCrossAppCapture(value: Boolean) = hotSwitch("cross_app_capture") {
        runCatching {
            container.settings.setCrossAppCapture(value)
            emit(
                if (value) "已扩展抓取范围：微信/QQ 等内嵌页里的文件直链会出现在首页候选"
                else "已收窄抓取范围：仅浏览器内的文件直链"
            )
        }.onFailure {
            runCatching { viewModelScope.launch { container.settings.setCrossAppCapture(!value) } }
        }
    }

    /** 首页标题字体：即时生效（HomeScreen 直接订阅 settings）。 */
    fun setTitleFont(value: String) = hotSwitch("title_font_$value") {
        runCatching { container.settings.setTitleFont(value) }
    }

    // ------------------------------------------------------------ 接力下载

    /**
     * 客户端开关：打开后所有下载走网关，关闭恢复本机直连（下次请求即生效）。
     *
     * 关键：无论开/关都必须把状态写进 DataStore。开关是受控组件（checked 来自 settings），
     * 过去「地址为空时拒绝写开启」会让 checked 永远停在 false，用户每次点击都被算成
     * 「开启」→ 再被拒 → 弹「先填地址」，于是关不掉。现在地址为空也允许写入，
     * 只是给出警告；真正是否走代理由 RelayState.active 决定（host 为空时仍走直连）。
     */
    fun setRelayClientEnabled(value: Boolean) = hotSwitch("relay_client_$value") {
        val s = settings.value
        if (com.dlguard.relay.RelayToggle.shouldPersist(value)) {
            runCatching {
                container.settings.setRelayClientEnabled(value)
                emit(com.dlguard.relay.RelayToggle.message(value, s.relayHost, s.relayPort))
            }.onFailure {
                // 写盘失败就回滚到相反状态，避免开关视觉与实际不符
                runCatching { viewModelScope.launch { container.settings.setRelayClientEnabled(!value) } }
            }
        } else {
            Result.success(Unit)
        }
    }

    fun saveRelayTarget(host: String, portText: String, token: String) = hotSwitch("relay_target") {
        runCatching {
            val port = portText.toIntOrNull()?.coerceIn(1, 65535) ?: 8899
            container.settings.setRelayTarget(host, port, token)
            emit("网关参数已保存")
        }
    }

    /** 开启网关：没有接入码就生成一个 6 位码，客户端照着输。 */
    fun startRelayGateway() = hotSwitch("relay_gateway_on") {
        runCatching {
            val current = settings.value
            val token = current.relayToken.takeIf { it.length >= 4 }
                ?: (100000..999999).random().toString()
            if (token != current.relayToken) {
                container.settings.setRelayTarget(current.relayHost, current.relayPort, token)
            }
            RelayService.start(context, current.relayPort.coerceIn(1024, 65535), token)
            emit("接力网关已启动 · 接入码 $token")
        }
    }

    fun stopRelayGateway() = hotSwitch("relay_gateway_off") {
        runCatching {
            RelayService.stop(context)
            emit("接力网关已停止")
        }
    }

    /** 只测 TCP 能不能连上：连得上说明热点/地址没问题，认证是下一步的事。 */
    fun testRelay() {
        val s = settings.value
        if (s.relayHost.isBlank()) {
            emit("先填写网关地址")
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    java.net.Socket().use {
                        it.connect(java.net.InetSocketAddress(s.relayHost, s.relayPort), 3_000)
                    }
                }.isSuccess
            }
            emit(if (ok) "已连上 ${s.relayHost}:${s.relayPort}" else "连不上，检查对方是否开了热点与网关")
        }
    }

    fun setDownloadThreads(value: Int) = hotSwitch("download_threads_$value") {
        runCatching {
            container.settings.setDownloadThreads(value)
            emit("并发连接数已设为 $value，进行中的任务可拖卡片上的滑杆即时切换")
        }
    }

    /** 同时下载任务数：即时生效，调大会自动补位开跑排队中的任务。 */
    fun setMaxParallelTasks(value: Int) = hotSwitch("max_parallel_tasks_$value") {
        runCatching {
            container.settings.setMaxParallelTasks(value)
            container.downloadCenter.setMaxParallel(value)
            emit("同时下载任务数已设为 $value")
        }
    }

    /** 「允许应用安装程序」开关：关掉后点 APK 只提示路径，不再调起安装。 */
    fun setAllowInstaller(value: Boolean) = hotSwitch("allow_installer") {
        runCatching {
            container.settings.setAllowInstaller(value)
            if (value) {
                emit(
                    if (ShizukuHelper.hasPermission()) "已开启，点击校验完成的 APK 将直接安装"
                    else "已开启，但还需要连接 Shizuku 才能安装；请在首页连接"
                )
            } else {
                emit("已关闭，点击 APK 将只提示文件位置")
            }
        }.onFailure {
            runCatching { viewModelScope.launch { container.settings.setAllowInstaller(!value) } }
        }
    }

    // ------------------------------------------------------------ Shizuku 授权中枢

    /** 首页/设置「连接 Shizuku」按钮：按真实状态分流（索取连接 → 授权）。 */
    fun requestShizukuPermission(listener: ShizukuHelperStatusBridge? = null) {
        viewModelScope.launch {
            // refresh / requestPermission 内部都是 binder 往返，全部挪到 IO：
            // 这条路径由用户点击触发，主线程一卡就是「没有响应」
            when (val current = withContext(Dispatchers.IO) { ShizukuHelper.refresh() }) {
                ShizukuHelper.Status.NOT_INSTALLED -> emit("未安装 Shizuku 管理器；可在应用商店或官网安装后回来连接")

                ShizukuHelper.Status.READY -> emit("Shizuku 已连接并授权")
                ShizukuHelper.Status.NEED_PERMISSION -> {
                    val result = withContext(Dispatchers.IO) {
                        ShizukuHelper.requestPermission(SHIZUKU_REQUEST_CODE)
                    }
                    result.onFailure { emit(it.message ?: "无法拉起 Shizuku 授权") }
                }

                // 连接未送达 / binder 已死：先走 REQUEST_BINDER 协议向管理器索取，
                // 管理器进程被广播拉起后几秒内回传；拿到后自动续走授权
                ShizukuHelper.Status.BINDER_PENDING, ShizukuHelper.Status.NOT_RUNNING -> {
                    emit("正在向 Shizuku 管理器索取连接…")
                    ShizukuHelper.requestBinder { ok ->
                        if (!ok) {
                            emit("未能拿到 Shizuku 连接：请确认 Shizuku 管理器里服务正在运行，可尝试重启一次 Shizuku 服务后重试")
                        } else {
                            viewModelScope.launch {
                                when (withContext(Dispatchers.IO) { ShizukuHelper.refresh() }) {
                                    ShizukuHelper.Status.READY -> emit("Shizuku 已连接")
                                    ShizukuHelper.Status.NEED_PERMISSION -> {
                                        val result = withContext(Dispatchers.IO) {
                                            ShizukuHelper.requestPermission(SHIZUKU_REQUEST_CODE)
                                        }
                                        result.onFailure { emit(it.message ?: "无法拉起 Shizuku 授权") }
                                    }
                                    else -> emit("Shizuku 连接已恢复，请稍候刷新状态")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** 免手点授权：appops 直接授予所有文件访问。 */
    fun grantAllFilesAccessViaShizuku() {
        viewModelScope.launch {
            // 一条 appops + 两条 pm grant，全走 Shizuku user service 的同步 exec，必须在 IO
            val result = withContext(Dispatchers.IO) { PrivilegeOps.grantAllFilesAccess() }
            result.fold(
                onSuccess = { emit("已通过 Shizuku 静默授予所有文件访问权限，回到首页即可启动守护") },
                onFailure = { emit("特权授权失败：${it.message}。请手动到系统设置开启") }
            )
        }
    }

    /** 电池白名单：deviceidle whitelist。 */
    fun joinBatteryWhitelistViaShizuku() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { PrivilegeOps.addToBatteryWhitelist() }
            result.fold(
                onSuccess = { emit("已加入电池优化白名单") },
                onFailure = { emit("白名单写入失败：${it.message}。请手动到系统设置开启") }
            )
        }
    }

    /** 一键静默开启无障碍（无 Shizuku 时引导手动）。 */
    fun enableAccessibilityViaShizuku() {
        viewModelScope.launch {
            if (!ShizukuHelper.hasPermission()) {
                PermissionHelper.openAccessibilitySettings(context)
                emit("未连接 Shizuku，已跳转系统无障碍设置，请手动勾选浏览器下载接管")
                return@launch
            }
            val result = withContext(Dispatchers.IO) { PrivilegeOps.enableSelfAccessibility() }
            result.fold(
                onSuccess = { emit("已静默开启无障碍服务") },
                onFailure = {
                    PermissionHelper.openAccessibilitySettings(context)
                    emit("特权开启失败（${it.message}），已跳转系统无障碍设置")
                }
            )
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean =
        android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains("$context.packageName/com.dlguard.service.DownloadAccessibilityService") == true
}

/** Shizuku 授权结果的桥接（Activity 实现后把结果抛回 UI 层）。 */
interface ShizukuHelperStatusBridge

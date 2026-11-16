package com.dlguard.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dlguard.DownloadGuardApp
import com.dlguard.MainActivity
import com.dlguard.core.LinkSniffer
import com.dlguard.core.PermissionHelper
import com.dlguard.data.GuardSettings
import com.dlguard.data.GuardStats
import com.dlguard.data.SettingsStore
import com.dlguard.data.db.CheckRecord
import com.dlguard.data.db.WatchedDir
import com.dlguard.download.ConcurrencyTuner
import com.dlguard.download.ResumableDownloader
import com.dlguard.service.GuardState
import com.dlguard.service.NotificationHelper
import com.dlguard.service.ServiceController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PermissionUiState(
    val storage: Boolean = false,
    val notifications: Boolean = false,
    val ignoringBattery: Boolean = false,
    val serviceRunning: Boolean = false,
    val vendor: String = "",
    /** 是否只有普通读写权限。用于区分「一个都没给」和「给了存储但没给所有文件访问」 */
    val legacyStorage: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as DownloadGuardApp
    private val container = app.container

    val records = container.repository.allRecords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recent = container.repository.recent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<CheckRecord>())
    val stats = container.repository.stats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuardStats())
    val settings = container.repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuardSettings())
    val customDirs = container.repository.customDirs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<WatchedDir>())
    val guard = GuardState.snapshot

    /** 任务队列挂在应用级 DownloadCenter 上，退出界面不会中断；多任务并行。 */
    val tasks = container.downloadCenter.tasks

    /** 插件状态（首页据此决定是否显示「自动测速」等插件化入口）。 */
    val plugins = container.pluginManager.plugins

    val capturedLinks = container.capturedLinks

    private val _permissions = MutableStateFlow(PermissionUiState(vendor = PermissionHelper.vendorLabel()))
    val permissions = _permissions.asStateFlow()

    private val _quarantineFiles = MutableStateFlow<List<File>>(emptyList())
    val quarantineFiles = _quarantineFiles.asStateFlow()

    private val _quarantineBytes = MutableStateFlow(0L)
    val quarantineBytes = _quarantineBytes.asStateFlow()

    private val _clipboardSuggestion = MutableStateFlow<String?>(null)
    val clipboardSuggestion = _clipboardSuggestion.asStateFlow()

    /** 通知「重新下载」跳进来但历史里没有 URL 时，把文件名带到这里，首页据此提示补链接。 */
    private val _pendingFileName = MutableStateFlow<String?>(null)
    val pendingFileName = _pendingFileName.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    /** 权限向导是否可见。无权限时点「启动守护 / 立即扫描」都会拉起它。 */
    private val _permissionGuide = MutableStateFlow(false)
    val permissionGuide = _permissionGuide.asStateFlow()

    /** 本次进程只自动弹一次，避免每次回到首页都糊脸。 */
    private var autoGuideShown = false

    /** 让界面切到指定 Tab（0 首页 / 1 记录 / 2 设置）。 */
    private val _navigation = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val navigation = _navigation.asSharedFlow()

    val quarantinePath: String get() = container.quarantine.directory.absolutePath

    private var lastClipboard: String? = null

    init {
        refreshPermissions()
        refreshQuarantine()
        viewModelScope.launch {
            container.downloadCenter.events.collect { _messages.tryEmit(it) }
        }
    }

    fun refreshPermissions() {
        val context = getApplication<Application>()
        _permissions.value = PermissionUiState(
            storage = PermissionHelper.hasStorageAccess(context),
            notifications = PermissionHelper.hasNotificationPermission(context),
            ignoringBattery = PermissionHelper.isIgnoringBatteryOptimizations(context),
            serviceRunning = GuardState.snapshot.value.running,
            vendor = PermissionHelper.vendorLabel(),
            legacyStorage = PermissionHelper.hasLegacyStoragePermission(context)
        )
    }

    /** 进入首页时调用：没给「所有文件访问」就自动弹一次向导。 */
    fun maybeAutoShowPermissionGuide() {
        if (autoGuideShown) return
        if (PermissionHelper.hasStorageAccess(getApplication())) return
        autoGuideShown = true
        _permissionGuide.value = true
    }

    fun openPermissionGuide() {
        autoGuideShown = true
        _permissionGuide.value = true
    }

    fun dismissPermissionGuide() {
        _permissionGuide.value = false
    }

    fun notifyMessage(text: String) {
        _messages.tryEmit(text)
    }

    /**
     * 从系统设置授权后返回时调用：只有「监控总开关开着」且「未被用户暂停」才拉起服务。
     * 这里必须读 DataStore 的真实值，不能读 StateFlow 的初始默认值——否则用户暂停后
     * 一回到 App 就会被自动恢复。
     */
    fun ensureServiceRunningIfAllowed() {
        val context = getApplication<Application>()
        if (!PermissionHelper.hasStorageAccess(context)) return
        if (GuardState.snapshot.value.running || GuardState.snapshot.value.paused) return
        viewModelScope.launch {
            val settings = runCatching { container.settings.settings.first() }.getOrNull() ?: return@launch
            if (settings.monitorEnabled && !GuardState.snapshot.value.running) {
                ServiceController.start(context)
            }
        }
    }

    /**
     * 用户确认「这个文件我信」：从隔离区放回原位，并把 SHA-256 指纹记进信任库。
     *
     * 这是一条**显式**通道，只由用户点按钮触发。记录里会写清楚原始判定结论，
     * 事后回看能一眼看出「这不是校验通过，是我自己决定信的」。
     */
    fun trustAndRestore(record: CheckRecord) {
        val quarantinedPath = record.quarantinedPath
        if (quarantinedPath.isNullOrBlank()) return
        viewModelScope.launch {
            val quarantined = java.io.File(quarantinedPath)
            if (!quarantined.exists()) {
                _messages.tryEmit("隔离区里已经找不到这个文件了")
                return@launch
            }
            _messages.tryEmit("正在计算文件指纹…")
            val sha = withContext(Dispatchers.IO) {
                com.dlguard.integrity.FileHasher.sha256(quarantined)
            }
            val restored = withContext(Dispatchers.IO) {
                container.quarantine.restore(quarantined, record.filePath)
            }
            if (restored == null) {
                _messages.tryEmit("恢复失败：目标目录不可写")
                return@launch
            }
            if (sha != null) {
                container.trustedStore.trust(restored.length(), sha, restored.name)
            }
            container.repository.markTrusted(
                record.id,
                restored.absolutePath,
                "用户确认信任并从隔离区恢复。" +
                    "SHA-256 ${sha?.let { com.dlguard.integrity.FileHasher.shorten(it) } ?: "未计算"}" +
                    "；原始判定：${record.verdict}"
            )
            refreshQuarantine()
            _messages.tryEmit("已恢复并信任：${restored.name}")
        }
    }

    fun refreshQuarantine() {
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) { container.quarantine.list() }
            _quarantineFiles.value = files
            _quarantineBytes.value = files.sumOf { it.length() }
        }
    }

    fun startGuard() {
        val context = getApplication<Application>()
        // 没有「所有文件访问」时启动只会得到一个收不到任何事件的假服务，直接引导授权
        if (!PermissionHelper.hasStorageAccess(context)) {
            refreshPermissions()
            _permissionGuide.value = true
            _messages.tryEmit("请先授予「所有文件访问权限」")
            return
        }
        // 必须先把开关落盘再拉服务：服务启动时会读这个值，顺序颠倒会被判成「用户已暂停」而拒绝启动
        viewModelScope.launch {
            container.settings.setMonitorEnabled(true)
            ServiceController.start(context)
            kotlinx.coroutines.delay(400)
            refreshPermissions()
        }
    }

    /** 停止守护同时关掉「监控总开关」，否则下次进入 App 会被 onResume 又拉起来。 */
    fun stopGuard() {
        viewModelScope.launch { container.settings.setMonitorEnabled(false) }
        ServiceController.stop(getApplication())
        viewModelScope.launch {
            kotlinx.coroutines.delay(400)
            refreshPermissions()
        }
    }

    fun pauseGuard() {
        // 暂停也要落盘：否则进程被杀后，START_STICKY 或看门狗会把监控偷偷恢复
        viewModelScope.launch { container.settings.setMonitorEnabled(false) }
        ServiceController.pause(getApplication())
    }

    fun resumeGuard() {
        val context = getApplication<Application>()
        if (!PermissionHelper.hasStorageAccess(context)) {
            refreshPermissions()
            _permissionGuide.value = true
            _messages.tryEmit("请先授予「所有文件访问权限」")
            return
        }
        viewModelScope.launch {
            container.settings.setMonitorEnabled(true)
            ServiceController.resume(context)
        }
    }

    fun rescan() {
        val context = getApplication<Application>()
        if (!PermissionHelper.hasStorageAccess(context)) {
            refreshPermissions()
            _permissionGuide.value = true
            _messages.tryEmit("无法扫描：缺少「所有文件访问权限」")
            return
        }
        ServiceController.rescan(context)
        _messages.tryEmit("已触发手动扫描")
    }

    fun simulateBoot() {
        val context = getApplication<Application>()
        ServiceController.scheduleWatchdog(context)
        ServiceController.start(context)
        _messages.tryEmit("已模拟开机自启链路：看门狗已注册，服务正在拉起")
    }

    fun addCustomDir(path: String) {
        viewModelScope.launch {
            val result = container.repository.addCustomDir(path)
            result.onSuccess {
                ServiceController.rescan(getApplication())
                _messages.emit("已添加监听目录")
            }.onFailure {
                _messages.emit(it.message ?: "添加失败")
            }
        }
    }

    fun removeCustomDir(path: String) {
        viewModelScope.launch {
            container.repository.removeCustomDir(path)
            ServiceController.rescan(getApplication())
            _messages.emit("已移除目录")
        }
    }

    fun clearQuarantine() {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) { container.quarantine.clear() }
            refreshQuarantine()
            _messages.emit("已清空隔离区，共删除 $count 个文件")
        }
    }

    fun deleteQuarantine(file: File) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { container.quarantine.delete(file) }
            refreshQuarantine()
            _messages.emit(if (ok) "已删除 ${file.name}" else "删除失败")
        }
    }

    fun deleteRecord(record: CheckRecord) {
        viewModelScope.launch {
            container.repository.deleteRecord(record.id)
        }
    }

    fun clearRecords() {
        viewModelScope.launch {
            container.repository.clearRecords()
            _messages.emit("校验记录已清空（隔离文件不受影响）")
        }
    }

    fun exportLogs(onReady: (File?) -> Unit) {
        viewModelScope.launch {
            val file = runCatching { container.repository.exportCsv() }.getOrNull()
            if (file == null) {
                _messages.emit("导出失败，请检查存储空间")
                onReady(null)
            } else {
                _messages.emit("已导出 ${file.name}")
                onReady(file)
            }
        }
    }

    // ------------------------------------------------------------ 下载

    /** 首页点「下载」前正在探测链接类型（直链 / 网页），用于按钮禁用与文案。 */
    private val _resolving = MutableStateFlow(false)
    val resolving = _resolving.asStateFlow()

    /**
     * 请求打开网页嗅探页。ViewModel 不能直接启动 Activity，
     * 由 MainActivity 收集后拉起 SniffActivity。
     */
    private val _sniffRequest = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val sniffRequest = _sniffRequest.asSharedFlow()

    fun startDownload(url: String) {
        container.downloadCenter.start(url)
    }

    /**
     * 直链被服务器拒绝（403/404/410）时改用网页模式：把 URL 丢给嗅探页，
     * 由 WebView 走完验证（Cloudflare 等）后捕获真实下载并带上 Cookie。
     */
    fun openInSniffer(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            _messages.tryEmit("链接为空，无法打开网页模式")
            return
        }
        _sniffRequest.tryEmit(trimmed)
    }

    /**
     * 智能下载入口：先探测 URL 到底是文件还是网页。
     *
     * 下载站的「下载」按钮几乎都是 JS 绑定，用户根本复制不出真实地址；
     * 这里遇到 text/html 就转交 WebView 嗅探页，由页面自己触发下载回调，
     * 或注入脚本把 DOM 里的直链挖出来。探测失败则当作直链直接尝试。
     */
    fun smartDownload(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            _messages.tryEmit("请先粘贴下载链接或网页地址")
            return
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            _messages.tryEmit("仅支持 http/https 链接")
            return
        }
        if (_resolving.value) return
        _resolving.value = true
        // 接力模式下本机没有外网，probe 必然超时；网页交给网关 /fetch，文件直接走代理下载
        if (com.dlguard.relay.RelayState.active) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    if (looksLikeFileUrl(trimmed)) {
                        container.downloadCenter.start(trimmed)
                        _messages.emit("接力模式：经 ${com.dlguard.relay.RelayState.host} 下载（并发上限 32）")
                    } else {
                        _sniffRequest.tryEmit(trimmed)
                        _messages.emit("接力模式：正在经网关加载页面…")
                    }
                } finally {
                    _resolving.value = false
                }
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val probe = LinkSniffer.probe(trimmed)
                when {
                    // 探测失败：可能是不支持 HEAD 的直链，直接交给下载器再试一次
                    probe == null -> container.downloadCenter.start(trimmed)
                    probe.isHtml -> {
                        _sniffRequest.tryEmit(trimmed)
                        _messages.emit("检测到网页，正在加载页面抓取真实下载地址…")
                    }
                    else -> container.downloadCenter.start(trimmed, probe.fileName)
                }
            } finally {
                _resolving.value = false
            }
        }
    }

    /** 接力模式下没有外网可探测，只能按后缀粗判；判错也只是多开一次 WebView。 */
    private fun looksLikeFileUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in setOf(
            "apk", "apks", "xapk", "apkm", "zip", "7z", "rar", "tar", "gz", "tgz",
            "pdf", "epub", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "mp4", "mkv", "avi", "mov", "webm", "mp3", "flac", "wav", "m4a",
            "iso", "img", "bin", "exe", "msi", "dmg", "pkg", "torrent"
        )
    }

    /** 暂停某个任务：分片与元数据都保留，继续时从断点接着下。 */
    fun pauseTask(id: Long) = container.downloadCenter.pauseTask(id)

    /** 继续（暂停）/ 重试（失败）某个任务。 */
    fun resumeTask(id: Long) = container.downloadCenter.resumeTask(id)

    /** 删除某个任务：正在下载就取消并移除（分片保留）。 */
    fun removeTask(id: Long) = container.downloadCenter.removeTask(id)

    /** 清掉已完成/已失败的任务。 */
    fun clearFinishedTasks() = container.downloadCenter.clearFinished()

    /** 下载中热切换并发：只改该任务的闸门，不重启任务。 */
    fun setTaskConnections(id: Long, connections: Int) =
        container.downloadCenter.setTaskConnections(id, connections)

    /** 设置同时下载的任务数（1~5）。 */
    fun setMaxParallelTasks(n: Int) = container.downloadCenter.setMaxParallel(n)

    // ------------------------------------------------------------ 自动并发测速

    /**
     * 自动测速状态机：从 1 开始逐档递增并发，实测每档吞吐，收益到顶即停，
     * 最优值直接写回设置页的并发滑杆（DataStore 同一条流，两端自动同步）。
     */
    sealed class TuneState {
        data object Idle : TuneState()
        data class Probing(
            /** 正在探测的并发数。 */
            val concurrency: Int,
            /** 该档实测吞吐（B/s），首帧可能是 -1（样本不足）。 */
            val speed: Long,
            /** 当前档位序号（从 1 起）。 */
            val step: Int,
            val totalSteps: Int
        ) : TuneState()

        data class Done(
            val best: Int,
            val speed: Long,
            val note: String,
            /** 完整探测曲线（并发数 → 实测吞吐），首页直接列出来，避免「只给结论」的黑箱感。 */
            val probes: List<com.dlguard.download.ConcurrencyTuner.Probe> = emptyList(),
            /** true 表示保留了你原先手动设置的并发数，没有被改写。 */
            val keptCurrent: Boolean = false
        ) : TuneState()
        data class Failed(val message: String) : TuneState()
    }

    private val _tuneState = MutableStateFlow<TuneState>(TuneState.Idle)
    val tuneState = _tuneState.asStateFlow()

    private var tuneJob: Job? = null

    /** 首页「自动测速并发」按钮：用输入框里的链接实测最优并发数并写回设置。 */
    fun startConcurrencyTune(url: String) {
        val trimmed = url.trim()
        if (tuneJob?.isActive == true) return
        if (container.downloadCenter.isBusy) {
            // 下载占满带宽时测出来的每个档位都被当前任务压着，数字必然失真
            _messages.tryEmit("下载进行中，测速会被当前任务抢占带宽，请先等下载完成或取消后再测")
            return
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            _messages.tryEmit("请先粘贴合法的 http/https 下载链接再测速")
            return
        }
        val tuneConfig = container.pluginManager.config(com.dlguard.plugin.PluginManager.ID_AUTO_TUNE)
        val start = tuneConfig["start"]?.toIntOrNull()?.coerceIn(1, 48) ?: 1
        val max = (tuneConfig["max"]?.toIntOrNull() ?: 48).coerceIn(start, 48)
        val gainPercent = (tuneConfig["gain"]?.toDoubleOrNull() ?: 10.0).coerceIn(5.0, 30.0)
        // 当前设置值一定会被探测：用户手动设了 48，测速就必须给出 48 的真实成绩，
        // 而不是在 2 连接处判停后凭猜测把它降下去。
        val currentThreads = settings.value.downloadThreads.coerceIn(1, max)
        val totalSteps = ConcurrencyTuner.fastSteps(max)
        _tuneState.value = TuneState.Probing(concurrency = start, speed = -1, step = 1, totalSteps = totalSteps)
        tuneJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val tuner = ConcurrencyTuner(
                    baseClient = ResumableDownloader.defaultClient(),
                    maxConcurrency = max,
                    startConcurrency = start,
                    gainThreshold = 1.0 + gainPercent / 100.0,
                    currentSetting = currentThreads
                )
                val result = tuner.tune(trimmed) { probe, step, steps ->
                    _tuneState.value = TuneState.Probing(
                        concurrency = probe.concurrency,
                        speed = probe.bytesPerSecond,
                        step = step,
                        totalSteps = steps
                    )
                }
                // 你的设置已跑满带宽时保持原样，绝不把 48 无故降成 2
                if (!result.keptCurrent) container.settings.setDownloadThreads(result.best)
                _tuneState.value = TuneState.Done(
                    best = result.best,
                    speed = result.bestSpeed,
                    note = result.stoppedBecause,
                    probes = result.probes,
                    keptCurrent = result.keptCurrent
                )
                _messages.emit(
                    if (result.keptCurrent) {
                        "自动测速完成：保留你的 ${result.best} 连接（" +
                            "${ConcurrencyTuner.formatSpeed(result.bestSpeed)}，已达带宽峰值）"
                    } else {
                        "自动测速完成：${result.best} 连接 · " +
                            "${ConcurrencyTuner.formatSpeed(result.bestSpeed)}，已写入设置，下次下载生效"
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _tuneState.value = TuneState.Failed(e.message ?: "测速失败")
                _messages.emit("自动测速失败：${e.message ?: "未知错误"}，请检查网络与链接")
            }
        }
    }

    /**
     * 通知「重新下载」直达：
     *  - 历史记录里有 URL → 直接开始（不再只是弹 Toast 让用户自己找）；
     *  - 没有 URL → 尝试从捕获库按文件名匹配；仍没有才回到首页让用户粘贴。
     */
    fun handleRedownload(recordId: Long) {
        if (recordId <= 0L) return
        _navigation.tryEmit(0)
        viewModelScope.launch {
            val record = runCatching { container.repository.findRecord(recordId) }.getOrNull()
            if (record == null) {
                _messages.emit("记录已不存在，可能已被清理")
                return@launch
            }
            val url = record.downloadUrl?.takeIf { it.isNotBlank() }
                ?: container.capturedLinks.findByFileName(record.fileName)?.url
            if (!url.isNullOrBlank()) {
                container.downloadCenter.start(url, record.fileName)
                _messages.emit("已按原链接重新下载：${record.fileName}")
            } else {
                _pendingFileName.value = record.fileName
                _messages.emit("没有可用的原始链接，请粘贴新链接后下载（目标文件名 ${record.fileName}）")
            }
        }
    }

    fun consumeIntentUrl(url: String?) {
        if (url.isNullOrBlank()) return
        _clipboardSuggestion.value = url
    }

    /** 分享入口：ACTION_SEND 把链接/文本丢进来，能识别就直接预填并自动解析。 */
    fun consumeSharedText(text: String?) {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return
        _navigation.tryEmit(0)
        val link = extractUrl(value) ?: return
        _clipboardSuggestion.value = link
        // 分享动作本身就是「我要下载它」：直接探测类型，是网页就自动抓链接
        _messages.tryEmit("已接收分享链接，正在解析真实下载地址…")
        smartDownload(link)
    }

    fun clearPendingFileName() {
        _pendingFileName.value = null
    }

    fun clearClipboardSuggestion() {
        _clipboardSuggestion.value = null
    }

    /** 前台时读取剪贴板（Android 10+ 后台读取会被系统拒绝，因此只在可见时检测）。 */
    fun checkClipboard() {
        val context = getApplication<Application>()
        if (!settings.value.clipboardWatch) return
        // 直链嗅探由插件控制：插件关闭后不再自动提取，避免与用户预期冲突
        if (!container.pluginManager.isEnabled(com.dlguard.plugin.PluginManager.ID_DIRECT_SNIFF)) return
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = runCatching {
            manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim()
        }.getOrNull() ?: return
        if (text.isBlank() || text == lastClipboard) return
        if (!isDownloadLink(text)) return
        lastClipboard = text
        _clipboardSuggestion.value = text
        runCatching { NotificationHelper(context).notifyClipboardLink(text) }
    }

    private fun extractUrl(text: String): String? {
        val regex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
        return regex.find(text)?.value?.trimEnd('.', ',', '，', '。', ')', '）')
    }

    private fun isDownloadLink(text: String): Boolean {
        if (!text.startsWith("http://") && !text.startsWith("https://")) return false
        // 识别规则由「直链嗅探」插件的配置决定，用户可自行增删
        val plugin = container.pluginManager
        val extensions = plugin
            .configValue(com.dlguard.plugin.PluginManager.ID_DIRECT_SNIFF, "extensions", "apk,apks,xapk,zip")
            .split(',')
            .map { it.trim().lowercase().removePrefix(".") }
            .filter { it.isNotBlank() }
        val keywords = plugin
            .configValue(com.dlguard.plugin.PluginManager.ID_DIRECT_SNIFF, "keywords", "download,apk,file,soft,xiazai")
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }

        val lower = text.lowercase().substringBefore('?')
        if (extensions.any { lower.endsWith(".$it") }) return true
        // 形如 /download?id=... 的直链：不靠扩展名，靠路径关键词识别
        val full = text.lowercase()
        return keywords.any { full.contains(it) }
    }

    // ------------------------------------------------------------ 设置
    // 说明：全部开关的写入与副作用已迁移到 SettingViewModel（热生效引擎：
    // 300ms 防抖 + Result 封装 + 失败回滚 + 异步服务启停）。本类只保留
    // 与设置无关的数据操作（目录管理 / 隔离区 / 记录），避免双引擎并存。

    fun clearCapturedLinks() {
        container.capturedLinks.clear()
        _messages.tryEmit("已清空捕获的链接")
    }

    companion object {
        const val EXTRA_URL = MainActivity.EXTRA_URL
    }
}

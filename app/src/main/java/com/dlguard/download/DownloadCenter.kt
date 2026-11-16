package com.dlguard.download

import android.content.Context
import com.dlguard.data.GuardRepository
import com.dlguard.data.ResolvedSource
import com.dlguard.data.SettingsStore
import com.dlguard.service.GuardState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 应用级下载中心。
 *
 * 为什么要有它：下载原本跑在 `viewModelScope` 里，用户返回桌面或退出 Activity，
 * ViewModel 被清空，协程随之取消——大文件下载到一半就断，这是 v1 的真实 bug。
 * 现在下载挂在 Application 级作用域上，界面只是订阅状态，退出/旋转/切页都不影响下载。
 *
 * v1.6 的三处结构性修复：
 *  1. **任务代号**：每个任务有唯一 id，`onProgress` 与收尾逻辑都先校验代号。
 *     旧版取消不掉旧任务（阻塞 read 不响应 cancel），它的回调会继续覆盖 `_state`，
 *     于是界面在「旧任务的文件名/速度」和「新任务的进度」之间来回跳——用户看到的就是「抽风」。
 *  2. **暂停/继续**：暂停保留 `.part` 与 `.part.meta`，继续时按断点续传，不再让用户只能「取消」。
 *  3. **并发热切换**：拖滑杆只改 [ResumableDownloader.ConnectionGovernor]，不重启任务、不丢进度。
 */
class DownloadCenter(
    private val context: Context,
    private val downloader: ResumableDownloader,
    private val repository: GuardRepository,
    private val settingsStore: SettingsStore,
    /** 插件聚合出的下载策略；每次开始任务时实时读取，插件开关立即生效。 */
    private val policyProvider: () -> DownloadPolicy = { DownloadPolicy() }
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        /** ETA 冻结窗口 3 秒：刷新间隔小于它的更新一律沿用旧值。 */
        private const val ETA_FREEZE_MS = 3_000L
    }

    private val _state = MutableStateFlow<DownloadUiState>(DownloadUiState.Idle)
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private var job: Job? = null

    /** 任务代号：每次新任务 +1；旧任务凭代号作废，绝不写状态。 */
    private val taskSeq = AtomicLong(0)

    @Volatile
    private var currentTask = 0L

    /** 当前任务的并发闸门，热切换直接改它。 */
    @Volatile
    private var governor: ResumableDownloader.ConnectionGovernor? = null

    /** 暂停现场：恢复时按原参数重发，`.part.meta` 让下载器从断点接着下。 */
    private var pausedRequest: Request? = null

    private data class Request(
        val url: String,
        val name: String?,
        val referer: String?,
        val cookie: String?
    )

    /** ETA 冻结窗口：3 秒内 ETA 数值不变化，防止界面乱跳。 */
    private var frozenEta = -1L
    private var etaLastChangeAt = 0L

    // 自适应并发：服务器对高并发惩罚时（很常见），高连接数反而越下越慢。
    // 只在用户本次任务没手动调过滑杆时才自动降，避免跟用户抢控制权。
    private var peakSpeed = 0L
    private var lowSince = 0L
    private var autoAdjusted = false
    private var userAdjusted = false

    val isBusy: Boolean get() = job?.isActive == true

    /** 当前任务的并发数（热切换后即时反映）。 */
    val currentConnections: Int get() = governor?.wanted ?: 0

    @Synchronized
    fun start(
        url: String,
        preferredName: String? = null,
        /** 网页嗅探来源页：下载站校验 Referer 时缺了会 403。 */
        referer: String? = null,
        /** 登录态 Cookie（从 WebView 的 CookieManager 取）。 */
        cookie: String? = null
    ) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            _events.tryEmit("请先粘贴下载链接")
            return
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            _events.tryEmit("仅支持 http/https 链接")
            return
        }
        if (job?.isActive == true) {
            _events.tryEmit("已有下载任务进行中，请等待完成或先暂停/取消")
            return
        }
        startInternal(Request(trimmed, preferredName, referer, cookie))
    }

    /** 真正拉起任务；[start] 与 [resume] 共用。 */
    @Synchronized
    private fun startInternal(request: Request) {
        pausedRequest = null
        frozenEta = -1
        etaLastChangeAt = 0L
        peakSpeed = 0L
        lowSince = 0L
        autoAdjusted = false
        userAdjusted = false

        val taskId = taskSeq.incrementAndGet()
        currentTask = taskId
        lastUrl = request.url
        lastReferer = request.referer
        lastCookie = request.cookie
        val trimmed = request.url

        _state.value = DownloadUiState.Running(
            fileName = request.name ?: guessName(trimmed),
            progress = 0f, downloaded = 0, total = 0, speed = 0, attempt = 1,
            connections = 0,
            hotSwitchable = true
        )

        job = scope.launch {
            val settings = runCatching { settingsStore.settings.first() }.getOrNull()
            val initial = (settings?.downloadThreads ?: 16)
                .coerceIn(1, ResumableDownloader.MAX_CONNECTIONS)
            val gate = ResumableDownloader.ConnectionGovernor(initial)
            governor = gate
            val policy = runCatching { policyProvider() }.getOrDefault(DownloadPolicy())

            val outcome = downloader.download(
                url = trimmed,
                preferredName = request.name,
                requireWifi = settings?.wifiOnlyDownload ?: false,
                connections = initial,
                referer = request.referer,
                cookie = request.cookie,
                governor = gate,
                policy = policy,
                onProgress = { progress ->
                    // 代号不符 = 这是已经被取代的旧任务，它的回调一律丢弃。
                    // 没有这道闸，旧任务取消不掉时会把新任务的界面覆盖成自己的数据。
                    if (currentTask == taskId) {
                        val now = System.currentTimeMillis()
                        val eta = progress.etaSeconds
                        if (eta > 0) {
                            if (eta != frozenEta && now - etaLastChangeAt >= ETA_FREEZE_MS) {
                                frozenEta = eta
                                etaLastChangeAt = now
                            }
                        } else {
                            frozenEta = -1
                            etaLastChangeAt = now
                        }
                        adaptConcurrency(progress, gate)
                        _state.value = DownloadUiState.Running(
                            fileName = request.name ?: guessName(trimmed),
                            progress = progress.fraction,
                            downloaded = progress.downloadedBytes,
                            total = progress.totalBytes,
                            speed = progress.bytesPerSecond,
                            attempt = progress.attempt,
                            connections = progress.connections,
                            etaSeconds = frozenEta,
                            hotSwitchable = progress.segmented
                        )
                    }
                }
            )

            // 收尾同样校验代号：旧任务晚了半拍结束，不能把新任务的成功/失败状态盖掉
            if (currentTask != taskId) return@launch

            if (outcome.success) {
                val file = outcome.file!!
                val integrity = outcome.integrity!!
                withContext(Dispatchers.IO) {
                    runCatching {
                        repository.saveResult(
                            result = integrity,
                            file = file,
                            source = ResolvedSource(
                                downloadUrl = trimmed,
                                declaredSize = integrity.declaredSize,
                                origin = "内置加固下载器（${outcome.connections} 连接）"
                            ),
                            quarantinedPath = null
                        )
                    }
                }
                GuardState.update {
                    it.copy(
                        lastCheckedAt = System.currentTimeMillis(),
                        lastFileName = file.name,
                        lastVerdict = integrity.verdict.name
                    )
                }
                val extra = when {
                    outcome.replacedExisting -> "\n已替换同名的旧文件"
                    outcome.elapsedMs == 0L -> "\n目标目录已有相同文件（大小一致、校验通过），本次未重复下载"
                    else -> ""
                }
                _state.value = DownloadUiState.Success(
                    fileName = file.name,
                    path = file.absolutePath,
                    summary = integrity.summary() +
                        "\n平均 ${speedText(outcome.averageSpeed)} · 用时 ${outcome.elapsedMs / 1000} 秒" + extra
                )
                _events.tryEmit(
                    if (outcome.replacedExisting) "下载完成：${file.name}（已替换旧文件）"
                    else "下载完成：${file.name}"
                )
                // 插件「完成即打开」：只在真校验通过后触发，失败的文件绝不自动拉起
                if (policy.autoOpen) {
                    val opened = runCatching {
                        com.dlguard.core.FileOpener.open(context, file.absolutePath)
                    }.getOrNull()
                    when (opened) {
                        is com.dlguard.core.FileOpener.Result.Failed ->
                            _events.tryEmit("已下载，但自动打开失败：${opened.reason}")
                        com.dlguard.core.FileOpener.Result.NeedInstallPermission ->
                            _events.tryEmit("已下载，首次安装需允许「安装未知应用」")
                        else -> Unit
                    }
                }
            } else {
                _state.value = DownloadUiState.Failed(
                    message = outcome.error ?: "未知错误",
                    sourceUrl = trimmed,
                    httpCode = outcome.httpCode ?: 0
                )
                _events.tryEmit("下载失败")
            }
        }
    }

    /**
     * 自适应降并发：连续 8 秒速度都低于峰值的 40%，且并发 ≥ 16 时，降到 8。
     *
     * 依据：大量下载站的 CDN 会对单 IP 的高并发做惩罚，48 连接反而只有几十 KB/s
     * （用户实测 377KB/s → 68KB/s 就是这个形态）。降下来通常立刻回升。
     * 只在用户没手动调过滑杆时生效，一旦用户拖过滑杆就完全交还给用户。
     */
    private fun adaptConcurrency(
        progress: DownloadProgress,
        gate: ResumableDownloader.ConnectionGovernor
    ) {
        if (autoAdjusted || userAdjusted || !progress.segmented) return
        val speed = progress.bytesPerSecond
        if (speed <= 0) return
        val now = System.currentTimeMillis()
        if (speed > peakSpeed) {
            peakSpeed = speed
            lowSince = 0L
            return
        }
        // 峰值还不够高时不判断，避免小文件/慢网络误触发
        if (peakSpeed < 800 * 1024) return
        if (speed < peakSpeed * 0.4) {
            if (lowSince == 0L) {
                lowSince = now
            } else if (now - lowSince >= 8_000L && gate.wanted >= 16) {
                autoAdjusted = true
                lowSince = 0L
                val from = gate.wanted
                gate.request(8)
                _events.tryEmit("检测到服务器对高并发限速，已自动从 $from 降到 8 连接")
            }
        } else {
            lowSince = 0L
        }
    }

    /**
     * 热切换并发：下载中拖滑杆走这里。
     *
     * 只改闸门值，[ResumableDownloader] 的调度协程会在 250ms 内增删 worker，
     * 已有连接继续跑、进度一分不丢；同时写回设置，下次下载沿用。
     */
    fun setConnections(connections: Int) {
        userAdjusted = true
        val n = connections.coerceIn(1, ResumableDownloader.MAX_CONNECTIONS)
        governor?.request(n)
        val running = _state.value as? DownloadUiState.Running
        if (running != null) {
            _state.value = running.copy(connections = n)
        }
        scope.launch {
            runCatching { settingsStore.setDownloadThreads(n) }
        }
    }

    /** 暂停：记住现场并取消任务，`.part` 与 `.part.meta` 都保留，可继续。 */
    @Synchronized
    fun pause() {
        val running = _state.value as? DownloadUiState.Running ?: return
        val active = job ?: return
        if (!active.isActive) return
        pausedRequest = Request(
            url = lastUrl,
            name = running.fileName,
            referer = lastReferer,
            cookie = lastCookie
        )
        // 代号递增：旧任务就算还在收尾，它的回调也写不进状态了
        currentTask = taskSeq.incrementAndGet()
        active.cancel(CancellationException("用户暂停下载"))
        _state.value = DownloadUiState.Paused(
            fileName = running.fileName,
            downloaded = running.downloaded,
            total = running.total
        )
        _events.tryEmit("已暂停，进度已保存，点「继续」从断点接着下")
    }

    /** 继续：等旧任务彻底退出后再重发，避免两个协程同时写 `.part.meta`。 */
    @Synchronized
    fun resume() {
        val request = pausedRequest ?: run {
            _events.tryEmit("没有可继续的任务，请重新粘贴链接")
            return
        }
        pausedRequest = null
        val previous = job
        _state.value = DownloadUiState.Idle
        job = scope.launch {
            runCatching { previous?.join() }
            startInternal(request)
        }
    }

    @Synchronized
    fun cancel() {
        val active = job
        if (active?.isActive == true) {
            currentTask = taskSeq.incrementAndGet()
            active.cancel(CancellationException("用户取消下载"))
            _events.tryEmit("已取消下载，分片保留可续传")
        }
        pausedRequest = null
        val st = _state.value
        if (st is DownloadUiState.Running || st is DownloadUiState.Paused) {
            _state.value = DownloadUiState.Idle
        }
    }

    fun dismiss() {
        if (!isBusy) {
            pausedRequest = null
            _state.value = DownloadUiState.Idle
        }
    }

    // 最近一次请求的现场，暂停时要用
    private var lastUrl: String = ""
    private var lastReferer: String? = null
    private var lastCookie: String? = null

    /** 外部（如通知里的「重新下载」）带文件名发起时，用这个猜一个可读名称。 */
    private fun guessName(url: String): String {
        val raw = url.substringAfterLast('/').substringBefore('?').ifBlank { "安装包" }
        return raw
    }

    private fun speedText(bytesPerSecond: Long): String = when {
        bytesPerSecond <= 0 -> "速度未知"
        bytesPerSecond >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSecond / 1024f / 1024f)
        else -> "${bytesPerSecond / 1024} KB/s"
    }

    /** 供 UI 打开下载目录时使用。 */
    fun lastFile(): File? = (_state.value as? DownloadUiState.Success)?.path?.let { File(it) }
}

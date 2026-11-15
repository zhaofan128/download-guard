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

/**
 * 应用级下载中心。
 *
 * 为什么要有它：下载原本跑在 `viewModelScope` 里，用户返回桌面或退出 Activity，
 * ViewModel 被清空，协程随之取消——大文件下载到一半就断，这是 v1 的真实 bug。
 * 现在下载挂在 Application 级作用域上，界面只是订阅状态，退出/旋转/切页都不影响下载。
 */
class DownloadCenter(
    private val context: Context,
    private val downloader: ResumableDownloader,
    private val repository: GuardRepository,
    private val settingsStore: SettingsStore
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

    /** ETA 冻结窗口：3 秒内 ETA 数值不变化，防止界面乱跳（需求「3 秒内冻结刷新」）。 */
    private var frozenEta = -1L
    private var etaLastChangeAt = 0L

    val isBusy: Boolean get() = job?.isActive == true

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
            _events.tryEmit("已有下载任务进行中，请等待完成或先取消")
            return
        }
        frozenEta = -1
        etaLastChangeAt = 0L
        _state.value = DownloadUiState.Running(
            fileName = preferredName ?: guessName(trimmed),
            progress = 0f, downloaded = 0, total = 0, speed = 0, attempt = 1
        )
        job = scope.launch {
            val settings = runCatching { settingsStore.settings.first() }.getOrNull()
            val outcome = downloader.download(
                url = trimmed,
                preferredName = preferredName,
                requireWifi = settings?.wifiOnlyDownload ?: false,
                connections = settings?.downloadThreads ?: 16,
                referer = referer,
                cookie = cookie,
                onProgress = { progress ->
                    val now = System.currentTimeMillis()
                    val eta = progress.etaSeconds
                    if (eta > 0) {
                        // ETA 只在「数值变化且距上次变化 ≥ 3 秒」时刷新，其余时间冻结
                        if (eta != frozenEta && now - etaLastChangeAt >= ETA_FREEZE_MS) {
                            frozenEta = eta
                            etaLastChangeAt = now
                        }
                    } else {
                        // 速度为 0（5 秒窗口内无进展）：显示「计算中...」而不是乱跳的 0
                        frozenEta = -1
                        etaLastChangeAt = now
                    }
                    _state.value = DownloadUiState.Running(
                        fileName = preferredName ?: guessName(trimmed),
                        progress = progress.fraction,
                        downloaded = progress.downloadedBytes,
                        total = progress.totalBytes,
                        speed = progress.bytesPerSecond,
                        attempt = progress.attempt,
                        connections = progress.connections,
                        etaSeconds = frozenEta
                    )
                }
            )
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
                _state.value = DownloadUiState.Success(
                    fileName = file.name,
                    path = file.absolutePath,
                    summary = integrity.summary() +
                        "\n平均 ${speedText(outcome.averageSpeed)} · 用时 ${outcome.elapsedMs / 1000} 秒"
                )
                _events.tryEmit("下载完成：${file.name}")
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

    fun cancel() {
        val active = job
        if (active?.isActive == true) {
            active.cancel(CancellationException("用户取消下载"))
            _events.tryEmit("已取消下载，分片保留可续传")
        }
        _state.value = DownloadUiState.Idle
    }

    fun dismiss() {
        if (!isBusy) _state.value = DownloadUiState.Idle
    }

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

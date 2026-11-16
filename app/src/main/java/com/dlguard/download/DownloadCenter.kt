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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 下载任务的阶段。QUEUED 的任务按加入顺序等并行槽位。 */
enum class TaskPhase { QUEUED, RUNNING, PAUSED, FAILED, DONE }

/**
 * UI 层的一个下载任务。
 *
 * 多任务模型下每个任务独立持有自己的进度、速度、并发数与文件名，
 * 互不覆盖——旧单任务模型里「旧任务回调写新任务状态」的串台问题从结构上消失。
 */
data class DownloadTask(
    val id: Long,
    val url: String,
    val preferredName: String? = null,
    /** 网页嗅探来源页：下载站校验 Referer 时缺了会 403。 */
    val referer: String? = null,
    /** 登录态 Cookie（从 WebView 的 CookieManager 取）。 */
    val cookie: String? = null,
    val phase: TaskPhase = TaskPhase.QUEUED,
    val fileName: String,
    val progress: Float = 0f,
    val downloaded: Long = 0,
    val total: Long = 0,
    val speed: Long = 0,
    val connections: Int = 0,
    val etaSeconds: Long = -1,
    val attempt: Int = 1,
    /** 走分块调度时可以在下载中直接改并发；单连接流式下载时为 false。 */
    val hotSwitchable: Boolean = false,
    /** FAILED = 失败原因；DONE = 完成摘要。 */
    val message: String? = null,
    /** DONE 后的文件绝对路径，供「打开文件」。 */
    val filePath: String? = null,
    /** 失败时的 HTTP 状态码（0 = 网络层错误）。 */
    val httpCode: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 应用级下载中心 v2：**多任务队列**。
 *
 * 下载挂在 Application 级作用域上（v1 就如此），界面只是订阅状态；
 * v2 起支持同时挂多个任务：
 *
 *  - 每个任务独立「暂停 / 继续 / 删除 / 重试」，像桌面下载器那样随意操作；
 *  - 「同时下载任务数」上限可配（1~5），超出的自动排队，一个任务结束自动补位；
 *  - 每个任务有独立的并发闸门与 ETA 冻结、自适应降并发状态，互不干扰；
 *  - 并发热切换、断点续传、同名替换等 v1.6 的能力对每个任务分别生效。
 *
 * 任务以 `.part` / `.part.meta` 断点为持久层：暂停只是取消协程，重开任务
 * （继续/重试/重新添加同一链接）都会从断点接着下。
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

    private val lock = Any()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks = _tasks.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private val taskSeq = AtomicLong(0)

    /** 每个任务的运行时资源：job、并发闸门、ETA 冻结与自适应降并发状态。 */
    private class TaskRuntime(val id: Long) {
        var job: Job? = null
        var governor: ResumableDownloader.ConnectionGovernor? = null
        var frozenEta = -1L
        var etaLastChangeAt = 0L
        var peakSpeed = 0L
        var lowSince = 0L
        var autoAdjusted = false
        var userAdjusted = false
    }

    private val runtimes = ConcurrentHashMap<Long, TaskRuntime>()

    /** 同时下载的任务数上限（1~5），设置页可调；变更即时生效，多余任务排队。 */
    @Volatile
    var maxParallel: Int = 2
        private set

    init {
        scope.launch {
            runCatching {
                maxParallel = settingsStore.settings.first().maxParallelTasks.coerceIn(1, 5)
            }
        }
    }

    /** 是否有任务正在下载（测速器用：有任务占带宽时测速必然失真）。 */
    val hasRunning: Boolean get() = _tasks.value.any { it.phase == TaskPhase.RUNNING }

    /** 兼容旧调用方的别名。 */
    val isBusy: Boolean get() = hasRunning

    // ------------------------------------------------------------ 添加任务

    /** 旧接口别名：语义与 [addTask] 完全一致（多任务下不再有「任务进行中就拒绝」）。 */
    fun start(
        url: String,
        preferredName: String? = null,
        referer: String? = null,
        cookie: String? = null
    ) = addTask(url, preferredName, referer, cookie)

    /** 加一个下载任务。并行槽位有空就立刻开跑，没有就排队（顺序 = 加入顺序）。 */
    fun addTask(
        url: String,
        preferredName: String? = null,
        referer: String? = null,
        cookie: String? = null
    ): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            _events.tryEmit("请先粘贴下载链接")
            return false
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            _events.tryEmit("仅支持 http/https 链接")
            return false
        }
        val id = taskSeq.incrementAndGet()
        val task = DownloadTask(
            id = id,
            url = trimmed,
            preferredName = preferredName,
            referer = referer,
            cookie = cookie,
            fileName = preferredName ?: guessName(trimmed)
        )
        runtimes[id] = TaskRuntime(id)
        _tasks.update { existing -> listOf(task) + existing }
        tryStartNext()
        return true
    }

    // ------------------------------------------------------------ 调度

    /**
     * 把排队中的任务按加入顺序填进空闲槽位。
     * 必须持锁调用，避免两个任务同时结束把同一个 QUEUED 任务拉起两次。
     */
    private fun tryStartNext() = synchronized(lock) {
        var slots = maxParallel - _tasks.value.count { it.phase == TaskPhase.RUNNING }
        if (slots <= 0) return
        for (task in _tasks.value) {
            if (slots <= 0) break
            if (task.phase == TaskPhase.QUEUED) {
                startRuntime(task.id)
                slots--
            }
        }
    }

    private fun startRuntime(id: Long) {
        val rt = runtimes[id] ?: return
        if (rt.job?.isActive == true) return
        updateTask(id) { it.copy(phase = TaskPhase.RUNNING, attempt = 1, message = null, speed = 0) }
        rt.job = scope.launch { runTask(rt) }
    }

    /** 真正执行一个任务；[startRuntime] 与「继续/重试」最终都走这里。 */
    private suspend fun runTask(rt: TaskRuntime) {
        val id = rt.id
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        val url = task.url
        try {
            val settings = runCatching { settingsStore.settings.first() }.getOrNull()
            val initial = (settings?.downloadThreads ?: 16)
                .coerceIn(1, ResumableDownloader.MAX_CONNECTIONS)
            val gate = ResumableDownloader.ConnectionGovernor(initial)
            rt.governor = gate
            rt.frozenEta = -1
            rt.etaLastChangeAt = 0L
            rt.peakSpeed = 0L
            rt.lowSince = 0L
            rt.autoAdjusted = false
            val policy = runCatching { policyProvider() }.getOrDefault(DownloadPolicy())

            val outcome = downloader.download(
                url = url,
                preferredName = task.preferredName,
                requireWifi = settings?.wifiOnlyDownload ?: false,
                connections = initial,
                referer = task.referer,
                cookie = task.cookie,
                governor = gate,
                policy = policy,
                onProgress = { progress ->
                    // 任务已被暂停/删除时（job 取消，isActive 立即变 false）丢弃回调，
                    // 防止收尾阶段的旧进度把 PAUSED/已删除状态又写回 RUNNING。
                    if (rt.job?.isActive != true) return@download
                    val now = System.currentTimeMillis()
                    val eta = progress.etaSeconds
                    if (eta > 0) {
                        if (eta != rt.frozenEta && now - rt.etaLastChangeAt >= ETA_FREEZE_MS) {
                            rt.frozenEta = eta
                            rt.etaLastChangeAt = now
                        }
                    } else {
                        rt.frozenEta = -1
                        rt.etaLastChangeAt = now
                    }
                    adaptConcurrency(progress, gate, rt)
                    updateTask(id) { t ->
                        if (t.phase != TaskPhase.RUNNING) t else t.copy(
                            progress = progress.fraction,
                            downloaded = progress.downloadedBytes,
                            total = progress.totalBytes,
                            speed = progress.bytesPerSecond,
                            attempt = progress.attempt,
                            connections = progress.connections,
                            etaSeconds = rt.frozenEta,
                            hotSwitchable = progress.segmented
                        )
                    }
                }
            )

            if (outcome.success) {
                finishSuccess(task, outcome, policy)
            } else {
                updateTask(id) { t ->
                    t.copy(
                        phase = TaskPhase.FAILED,
                        speed = 0,
                        connections = 0,
                        message = outcome.error ?: "未知错误",
                        httpCode = outcome.httpCode ?: 0
                    )
                }
                _events.tryEmit("下载失败：${task.fileName}")
            }
        } catch (cancel: CancellationException) {
            // 暂停 / 删除路径：phase 已由调用方设置（PAUSED 或移除），这里只做退出
            throw cancel
        } catch (e: Throwable) {
            updateTask(id) { t ->
                if (t.phase == TaskPhase.RUNNING) t.copy(
                    phase = TaskPhase.FAILED,
                    speed = 0,
                    connections = 0,
                    message = e.message ?: "下载失败"
                ) else t
            }
            _events.tryEmit("下载失败：${task.fileName}")
        } finally {
            rt.governor = null
            // 无论哪种结束方式，都补一次调度：并行槽位空出来就让排队的任务上
            tryStartNext()
        }
    }

    private suspend fun finishSuccess(
        task: DownloadTask,
        outcome: DownloadOutcome,
        policy: DownloadPolicy
    ) {
        val file = outcome.file ?: return
        val integrity = outcome.integrity ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                repository.saveResult(
                    result = integrity,
                    file = file,
                    source = ResolvedSource(
                        downloadUrl = task.url,
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
        val summary = integrity.summary() +
            "\n平均 ${speedText(outcome.averageSpeed)} · 用时 ${outcome.elapsedMs / 1000} 秒" + extra
        updateTask(task.id) { t ->
            t.copy(
                phase = TaskPhase.DONE,
                progress = 1f,
                downloaded = outcome.bytes,
                total = outcome.bytes,
                speed = 0,
                connections = 0,
                filePath = file.absolutePath,
                message = summary
            )
        }
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
    }

    /**
     * 自适应降并发：连续 8 秒速度都低于峰值的 40%，且并发 ≥ 16 时，降到 8。
     *
     * 大量下载站的 CDN 会对单 IP 的高并发做惩罚，高连接数反而越下越慢。
     * 只在用户本次任务没手动调过滑杆时才自动降，避免跟用户抢控制权。
     */
    private fun adaptConcurrency(
        progress: DownloadProgress,
        gate: ResumableDownloader.ConnectionGovernor,
        rt: TaskRuntime
    ) {
        if (rt.autoAdjusted || rt.userAdjusted || !progress.segmented) return
        val speed = progress.bytesPerSecond
        if (speed <= 0) return
        val now = System.currentTimeMillis()
        if (speed > rt.peakSpeed) {
            rt.peakSpeed = speed
            rt.lowSince = 0L
            return
        }
        // 峰值还不够高时不判断，避免小文件/慢网络误触发
        if (rt.peakSpeed < 800 * 1024) return
        if (speed < rt.peakSpeed * 0.4) {
            if (rt.lowSince == 0L) {
                rt.lowSince = now
            } else if (now - rt.lowSince >= 8_000L && gate.wanted >= 16) {
                rt.autoAdjusted = true
                rt.lowSince = 0L
                val from = gate.wanted
                gate.request(8)
                _events.tryEmit("检测到服务器对高并发限速，已自动从 $from 降到 8 连接")
            }
        } else {
            rt.lowSince = 0L
        }
    }

    // ------------------------------------------------------------ 任务操作

    /**
     * 热切换并发：下载中拖滑杆走这里。
     *
     * 只改该任务的闸门值，[ResumableDownloader] 的调度协程会在 250ms 内增删 worker，
     * 已有连接继续跑、进度一分不丢；同时写回设置，下次下载沿用。
     */
    fun setTaskConnections(id: Long, connections: Int) {
        val rt = runtimes[id] ?: return
        rt.userAdjusted = true
        val n = connections.coerceIn(1, ResumableDownloader.MAX_CONNECTIONS)
        rt.governor?.request(n)
        updateTask(id) { t -> if (t.phase == TaskPhase.RUNNING) t.copy(connections = n) else t }
        scope.launch {
            runCatching { settingsStore.setDownloadThreads(n) }
        }
    }

    /** 暂停：取消协程，`.part` 与 `.part.meta` 都保留，可继续。 */
    fun pauseTask(id: Long) {
        val rt = runtimes[id] ?: return
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.phase != TaskPhase.RUNNING && task.phase != TaskPhase.QUEUED) return
        rt.job?.cancel(CancellationException("用户暂停下载"))
        updateTask(id) { it.copy(phase = TaskPhase.PAUSED, speed = 0, connections = 0) }
        _events.tryEmit("已暂停：${task.fileName}，点「继续」从断点接着下")
    }

    /** 继续（暂停的任务）/ 重试（失败的任务）：回到队列，有槽位就立刻开跑，从断点续传。 */
    fun resumeTask(id: Long) = synchronized(lock) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.phase != TaskPhase.PAUSED && task.phase != TaskPhase.FAILED) return
        updateTask(id) { it.copy(phase = TaskPhase.QUEUED, message = null) }
        tryStartNext()
    }

    /** 删除任务：正在下载就取消（分片保留，重加同链接会自动续传），并从列表移除。 */
    fun removeTask(id: Long) = synchronized(lock) {
        runtimes.remove(id)?.job?.cancel(CancellationException("用户删除任务"))
        _tasks.update { list -> list.filterNot { it.id == id } }
    }

    /** 一键清掉已完成与已失败的任务（暂停的不动，还有用）。 */
    fun clearFinished() = synchronized(lock) {
        _tasks.value.filter { it.phase == TaskPhase.DONE || it.phase == TaskPhase.FAILED }
            .forEach { runtimes.remove(it.id) }
        _tasks.update { list -> list.filterNot { it.phase == TaskPhase.DONE || it.phase == TaskPhase.FAILED } }
    }

    /** 设置同时下载的任务数（1~5）。调大立刻补位，调小不打断正在跑的任务。 */
    fun setMaxParallel(value: Int) {
        val n = value.coerceIn(1, 5)
        if (n == maxParallel) return
        maxParallel = n
        scope.launch { runCatching { settingsStore.setMaxParallelTasks(n) } }
        tryStartNext()
    }

    // ------------------------------------------------------------ 工具

    private fun updateTask(id: Long, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
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
    fun lastFile(): File? =
        _tasks.value.lastOrNull { it.phase == TaskPhase.DONE }?.filePath?.let { File(it) }
}

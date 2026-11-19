package com.dlguard.download

import android.content.Context
import com.dlguard.data.GuardRepository
import com.dlguard.data.ResolvedSource
import com.dlguard.data.SettingsStore
import com.dlguard.service.GuardState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    /** 嗅探 / 分享来源页，站点规则据此决定「页面域 Cookie」取哪个域。 */
    val pageUrl: String? = null,
    /** 用户手动填写的高级请求参数（优先级高于站点规则）。 */
    val profile: com.dlguard.site.RequestProfile = com.dlguard.site.RequestProfile(),
    /** 命中的站点规则名（仅用于界面展示，不参与下载）。 */
    val ruleName: String? = null,
    /** 用户选定的官方校验基准，格式 `SHA-256:abc…`；空 = 不比对。 */
    val expectedHash: String? = null,
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
    /** FAILED 且文件被隔离时的隔离路径，供「去隔离区」定位。 */
    val quarantinedPath: String? = null,
    /** 失败时的 HTTP 状态码（0 = 网络层错误）。 */
    val httpCode: Int = 0,
    /** 收尾阶段：校验 / 落盘时 UI 要显示具体动作，而不是干巴巴的 100%。 */
    val stage: DownloadStage = DownloadStage.DOWNLOADING,
    /** 这个任务是否被全局熔断压过并发（UI 给出解释，避免用户以为滑杆坏了）。 */
    val throttledByHost: Boolean = false,
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
    private val policyProvider: () -> DownloadPolicy = { DownloadPolicy() },
    /**
     * 插件脚本的 URL 重写入口（沙箱执行）；返回 null 表示不改写。
     * 用函数注入而不是直接依赖 PluginManager，避免下载层反向依赖插件层。
     */
    private val urlRewriter: (String) -> String? = { null },
    /** HLS（m3u8）点播下载器：与普通下载共用目录解析、并发闸门与收尾校验。 */
    private val hls: com.dlguard.download.hls.HlsDownloader,
    /**
     * 站点规则解析入口。用函数注入而不是直接依赖 SiteRuleResolver，
     * 保持下载层与配置层解耦，也方便测试时替换成固定 profile。
     */
    private val profileResolver: suspend (com.dlguard.site.RequestContext) -> com.dlguard.site.RequestProfile =
        { com.dlguard.site.RequestProfile() }
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        /** ETA 冻结窗口 3 秒：刷新间隔小于它的更新一律沿用旧值。 */
        private const val ETA_FREEZE_MS = 3_000L

        /** 接力模式下的并发上限（见 runTask 注释）。 */
        private const val RELAY_MAX_CONNECTIONS = 32
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
        /** 是否被全局熔断压过并发（只用于 UI 解释，不参与调度）。 */
        var capLimited = false
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
        cookie: String? = null,
        expectedHash: String? = null,
        pageUrl: String? = null,
        profile: com.dlguard.site.RequestProfile = com.dlguard.site.RequestProfile()
    ) = addTask(url, preferredName, referer, cookie, expectedHash, pageUrl, profile)

    /** 加一个下载任务。并行槽位有空就立刻开跑，没有就排队（顺序 = 加入顺序）。 */
    fun addTask(
        url: String,
        preferredName: String? = null,
        referer: String? = null,
        cookie: String? = null,
        expectedHash: String? = null,
        pageUrl: String? = null,
        profile: com.dlguard.site.RequestProfile = com.dlguard.site.RequestProfile()
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
            pageUrl = pageUrl,
            profile = profile,
            expectedHash = expectedHash,
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
        var url = task.url
        try {
            val settings = runCatching { settingsStore.settings.first() }.getOrNull()
            val basePolicy = runCatching { policyProvider() }.getOrDefault(DownloadPolicy())

            // 插件脚本重写（沙箱执行，只做字符串变换）：必须在探测/分段之前完成，
            // 否则所有 Range 请求都会打到旧地址上。
            val rewritten = runCatching { urlRewriter(url) }.getOrNull()
            if (!rewritten.isNullOrBlank() && rewritten != url) {
                url = rewritten
                updateTask(id) {
                    it.copy(
                        url = rewritten,
                        // 重写可能换掉文件名（/small/x.jpg → /large/x.jpg），
                        // 用户没指定名字时跟着新地址重新猜，避免存成旧名
                        fileName = if (it.preferredName == null) guessName(rewritten) else it.fileName
                    )
                }
                _events.tryEmit("已按插件脚本重写下载地址：${HostCircuitBreaker.hostOf(rewritten) ?: "未知站点"}")
            }

            // 站点规则解析放在 URL 重写之后：规则要匹配最终的下载地址，而不是插件改写前的地址。
            // 解析失败一律降级为「只用用户显式填的参数」，规则配错不能挡下载。
            val resolved = runCatching {
                profileResolver(
                    com.dlguard.site.RequestContext(
                        url = url,
                        pageUrl = task.pageUrl ?: task.referer,
                        pageCookie = task.cookie,
                        explicit = task.profile
                    )
                )
            }.getOrDefault(task.profile)

            // 规则里的并发数 / 文件名模板 / 旁挂校验 / 完成后打开，逐项覆盖全局策略
            val policy = basePolicy.copy(
                userAgent = resolved.userAgent ?: basePolicy.userAgent,
                extraHeaders = resolved.headers,
                fileNameTemplate = resolved.fileNameTemplate,
                sidecarHash = resolved.sidecarHash ?: basePolicy.sidecarHash,
                autoOpen = resolved.openWhenDone ?: basePolicy.autoOpen
            )
            val reqReferer = resolved.referer
            val reqCookie = resolved.cookie
            if (resolved.ruleName != null) {
                updateTask(id) { it.copy(ruleName = resolved.ruleName) }
            }

            val host = HostCircuitBreaker.hostOf(url)
            val breakerOn = policy.globalBreaker && host != null

            // 全局熔断：同站点任一任务撞过 403/429，新任务进来先把并发压到安全值
            // 不再无脑 256：低内存设备按堆大小降到 32/64，否则光 worker 缓冲就能把进程顶到 LMK
            val deviceMax = DeviceBudget.maxConnections()
            val cap = if (breakerOn) HostCircuitBreaker.capFor(host!!) else deviceMax
            // 接力模式下所有流量都挤在一条热点链路上，256 连接只会让网关排队到卡死。
            // 32 条已经能把 2.4G/5G 热点跑满，再高是纯内耗。
            val ceiling = if (com.dlguard.relay.RelayState.active) RELAY_MAX_CONNECTIONS
            else deviceMax
            val preferredThreads = resolved.concurrency.takeIf { it > 0 }
                ?: (settings?.downloadThreads ?: 16)
            val initial = preferredThreads.coerceIn(1, ceiling)
                .coerceAtMost(cap)
            val gate = ResumableDownloader.ConnectionGovernor(initial)
            rt.governor = gate
            rt.frozenEta = -1
            rt.etaLastChangeAt = 0L
            rt.peakSpeed = 0L
            rt.lowSince = 0L
            rt.autoAdjusted = false
            if (breakerOn && cap < preferredThreads) {
                rt.capLimited = true
                updateTask(id) {
                    it.copy(
                        connections = initial,
                        throttledByHost = true,
                        message = "该站点正在限流，并发已全局降到 $cap"
                    )
                }
            }

            // 熔断冷却：与其硬冲把封禁时间拉长，不如等几秒。最多等 30 秒，避免界面像卡死。
            if (breakerOn) {
                val cool = HostCircuitBreaker.cooldownMs(host!!)
                if (cool > 0) {
                    val seconds = ((cool + 999) / 1000).coerceAtLeast(1)
                    updateTask(id) { it.copy(message = "站点限流冷却中，约 $seconds 秒后开始", speed = 0) }
                    delay(cool.coerceAtMost(30_000L))
                    updateTask(id) { it.copy(message = null) }
                }
            }

            // 旁挂哈希：用户没填校验值时才去旁边找 .sha256/.md5，找到就当官方基准
            var expectedHash = task.expectedHash
            if (expectedHash.isNullOrBlank() && policy.sidecarHash) {
                val found = runCatching { com.dlguard.integrity.SidecarHash.discover(url, task.fileName) }.getOrNull()
                if (found != null) {
                    expectedHash = "${found.algorithm}:${found.hash}"
                    updateTask(id) {
                        it.copy(
                            expectedHash = expectedHash,
                            message = "已自动获取旁挂 ${found.algorithm} 校验值"
                        )
                    }
                    _events.tryEmit("发现官方校验文件，下载完成后自动比对 ${found.algorithm}")
                }
            }

            // HLS 点播走独立下载器：分片拼接与断点续传的语义完全不同，
            // 但进度、熔断、收尾校验与普通任务共用同一条链路。
            val isHls = com.dlguard.download.hls.HlsUrls.isPlaylist(url)

            val progressSink: (DownloadProgress) -> Unit = sink@{ progress ->
                // 任务已被暂停/删除时（job 取消，isActive 立即变 false）丢弃回调，
                // 防止收尾阶段的旧进度把 PAUSED/已删除状态又写回 RUNNING。
                if (rt.job?.isActive != true) return@sink
                // 跨任务联动降级：别的任务把这个 host 打到 429 了，本任务也要跟着收
                if (breakerOn) enforceHostCap(host!!, gate, rt)
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
                        etaSeconds = if (progress.stage == DownloadStage.DOWNLOADING) rt.frozenEta else -1,
                        hotSwitchable = progress.segmented && progress.stage == DownloadStage.DOWNLOADING,
                        stage = progress.stage,
                        throttledByHost = t.throttledByHost || rt.capLimited
                    )
                }
            }

            var outcome = if (isHls) {
                hls.download(
                    url = url,
                    preferredName = task.preferredName,
                    requireWifi = settings?.wifiOnlyDownload ?: false,
                    referer = reqReferer,
                    cookie = reqCookie,
                    onProgress = progressSink
                )
            } else {
                downloader.download(
                    url = url,
                    preferredName = task.preferredName,
                    requireWifi = settings?.wifiOnlyDownload ?: false,
                    connections = initial,
                    referer = reqReferer,
                    cookie = reqCookie,
                    governor = gate,
                    policy = policy,
                    onProgress = progressSink
                )
            }

            // 遇阻自保：403/429 大概率是服务器在限流，此时并发越高越糟。
            // 整轮失败后自动把并发减半再来一次——分片都在，续传不重下。
            var throttleRound = 0
            while (!outcome.success) {
                val code = outcome.httpCode ?: 0
                // 先记账到全局熔断器：同站点的其它任务下一轮就会看到新的上限
                if (breakerOn && (code == 429 || code == 403 || code == 503)) {
                    val hostCap = HostCircuitBreaker.onThrottle(host!!, code)
                    if (gate.wanted > hostCap) {
                        gate.request(hostCap)
                        rt.capLimited = true
                    }
                }
                val maxRounds = if (code == 429) 3 else 1
                val canThrottle = (code == 429 || code == 403) &&
                    gate.wanted > 1 &&
                    throttleRound < maxRounds &&
                    rt.job?.isActive == true
                if (!canThrottle) break
                throttleRound++
                val next = (gate.wanted / 2).coerceAtLeast(1)
                gate.request(next)
                updateTask(id) { it.copy(connections = next, speed = 0) }
                _events.tryEmit(
                    if (code == 429) {
                        "服务器限流（HTTP 429），已降到 $next 连接并等待后重试"
                    } else {
                        "服务器拒绝并发请求（HTTP 403），已降到 $next 连接重试"
                    }
                )
                // 429 是明确的「你太快了」，等久一点；403 更像权限问题，只轻退避
                delay(if (code == 429) 5_000L * throttleRound else 2_000L * throttleRound)
                outcome = if (isHls) {
                    hls.download(
                        url = url,
                        preferredName = task.preferredName,
                        requireWifi = settings?.wifiOnlyDownload ?: false,
                        referer = reqReferer,
                        cookie = reqCookie,
                        onProgress = progressSink
                    )
                } else {
                    downloader.download(
                        url = url,
                        preferredName = task.preferredName,
                        requireWifi = settings?.wifiOnlyDownload ?: false,
                        connections = next,
                        referer = reqReferer,
                        cookie = reqCookie,
                        governor = gate,
                        policy = policy,
                        onProgress = progressSink
                    )
                }
            }

            if (outcome.success) {
                if (breakerOn) HostCircuitBreaker.onSuccess(host!!)
                finishSuccess(task, outcome, policy, expectedHash)
            } else {
                updateTask(id) { t ->
                    t.copy(
                        phase = TaskPhase.FAILED,
                        speed = 0,
                        connections = 0,
                        message = outcome.error ?: "未知错误",
                        httpCode = outcome.httpCode ?: 0,
                        quarantinedPath = outcome.quarantinedPath
                    )
                }
                // 校验失败本身就是一条需要留档的结论：不写库的话，
                // 「校验记录」里永远看不到这次失败，异常统计还会是 0。
                recordIntegrityFailure(task, outcome)
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

    /**
     * 校验失败的留档。只有 [DownloadOutcome.integrity] 非空才写——网络中断、403 之类
     * 还没走到校验环节的失败不是「文件有问题」，不该污染校验记录。
     */
    private suspend fun recordIntegrityFailure(task: DownloadTask, outcome: DownloadOutcome) {
        val integrity = outcome.integrity ?: return
        val targetPath = outcome.targetPath ?: run {
            val dir = runCatching { downloader.targetDirectory() }.getOrNull()
            if (dir != null) File(dir, task.fileName).absolutePath else task.fileName
        }
        withContext(Dispatchers.IO) {
            runCatching {
                repository.saveResult(
                    result = integrity,
                    file = File(targetPath),
                    source = ResolvedSource(
                        downloadUrl = task.url,
                        declaredSize = integrity.declaredSize,
                        origin = "内置加固下载器（${outcome.connections} 连接）"
                    ),
                    quarantinedPath = outcome.quarantinedPath,
                    displayPath = targetPath,
                    displayName = task.fileName
                )
            }
        }
    }

    private suspend fun finishSuccess(
        task: DownloadTask,
        outcome: DownloadOutcome,
        policy: DownloadPolicy,
        expectedHash: String?
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
                        origin = if (outcome.contentType?.contains("mpegurl", true) == true) {
                            "内置 HLS 下载器（分片顺序拼接）"
                        } else {
                            "内置加固下载器（${outcome.connections} 连接）"
                        }
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
        val hashCheck = verifyExpectedHash(file, expectedHash)
        val summary = integrity.summary() +
            "\n平均 ${speedText(outcome.averageSpeed)} · 用时 ${outcome.elapsedMs / 1000} 秒" +
            extra + hashCheck.summary
        // 官方哈希不符 = 文件内容与发布方给出的值不是同一个东西，
        // 无论传输是否完整都不能报「完成」，否则用户会以为拿到了正品。
        updateTask(task.id) { t ->
            t.copy(
                phase = if (hashCheck.mismatch) TaskPhase.FAILED else TaskPhase.DONE,
                progress = if (hashCheck.mismatch) t.progress else 1f,
                downloaded = outcome.bytes,
                total = outcome.bytes,
                speed = 0,
                connections = 0,
                filePath = file.absolutePath,
                message = summary
            )
        }
        when {
            hashCheck.mismatch -> _events.tryEmit("${file.name} 与官方校验值不符，文件已保留但请勿使用")
            outcome.replacedExisting -> _events.tryEmit("下载完成：${file.name}（已替换旧文件）")
            else -> _events.tryEmit("下载完成：${file.name}")
        }
        // 插件「完成即打开」：只在真校验通过后触发，失败的文件绝不自动拉起。
        // BLOCKED 的包系统本来就会拒绝安装，自动拉安装器只会让用户看到一次「应用未安装」。
        if (policy.autoOpen && integrity.verdict != com.dlguard.integrity.Verdict.BLOCKED) {
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

    /** 官方哈希比对结论。 */
    private data class HashCheck(val summary: String, val mismatch: Boolean)

    /**
     * 与用户选定的官方校验值比对整文件哈希。
     *
     * 注意这不是 APK 签名校验：签名只能证明「包是自己签的、没被改」，
     * 官方公布的 SHA-256 才能证明「这个字节序列就是发布方发出的那一份」。
     * 两者互补，所以这里单独算一次整文件摘要。
     */
    private suspend fun verifyExpectedHash(file: File, expected: String?): HashCheck {
        if (expected.isNullOrBlank()) return HashCheck("", false)
        val parsed = runCatching { com.dlguard.integrity.FileHasher.parseBaseline(expected) }.getOrNull()
            ?: return HashCheck("\n⚠ 自定义校验值格式无法识别，已跳过比对", false)
        val (algorithm, want) = parsed
        val actual = runCatching {
            com.dlguard.integrity.FileHasher.byAlgorithm(file, algorithm)
        }.getOrNull()
            ?: return HashCheck("\n⚠ 计算 $algorithm 失败，无法比对", false)
        val shortWant = com.dlguard.integrity.FileHasher.shorten(want)
        val shortActual = com.dlguard.integrity.FileHasher.shorten(actual)
        return if (com.dlguard.integrity.FileHasher.matches(actual, want)) {
            HashCheck("\n$algorithm 与官方值一致 ✓（$shortActual）", false)
        } else {
            HashCheck(
                "\n⚠ $algorithm 与官方值不符\n官方 $shortWant\n实际 $shortActual" +
                    "\n文件已保留在原位置，但内容与发布方公布的值不一致，请从官方渠道重新获取",
                true
            )
        }
    }

    /**
     * 全局熔断的跨任务联动。
     *
     * 这是 1.9.0 的核心改动：退让不再是「各任务自己算自己的」。
     * 同 host 的任一任务撞上 429/403 后，其余任务的进度回调（≤250ms 一次）会在这里读到
     * 新的上限并把 gate 压下去，连接数在下个调度周期内退役到安全值。
     */
    private fun enforceHostCap(
        host: String,
        gate: ResumableDownloader.ConnectionGovernor,
        rt: TaskRuntime
    ) {
        val cap = HostCircuitBreaker.capFor(host)
        if (gate.wanted > cap) {
            gate.request(cap)
            rt.capLimited = true
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
        val ceiling = if (com.dlguard.relay.RelayState.active) RELAY_MAX_CONNECTIONS
        else DeviceBudget.maxConnections()
        val n = connections.coerceIn(1, ceiling)
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

    /**
     * 全部暂停：正在跑的和排队的一起停。
     *
     * 多任务队列下逐个点太慢，尤其在地铁里信号变差、想先把带宽让给别的应用时。
     * 语义与单个暂停一致：只取消协程，`.part` 与 `.part.meta` 全部保留，随时能继续。
     * 失败和已完成的任务不动——它们本来就不占带宽。
     */
    fun pauseAll() {
        val targets = _tasks.value.filter {
            it.phase == TaskPhase.RUNNING || it.phase == TaskPhase.QUEUED
        }
        if (targets.isEmpty()) return
        targets.forEach { pauseTask(it.id) }
    }

    /** 全部继续：所有暂停中的任务回到队列，按「同时下载任务数」依次补位。 */
    fun resumeAll() = synchronized(lock) {
        val paused = _tasks.value.filter { it.phase == TaskPhase.PAUSED }
        if (paused.isEmpty()) return
        paused.forEach { task ->
            updateTask(task.id) { it.copy(phase = TaskPhase.QUEUED, message = null) }
        }
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

package com.dlguard.service

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.dlguard.data.DownloadHistoryResolver
import com.dlguard.data.GuardRepository
import com.dlguard.data.QuarantineManager
import com.dlguard.download.RecentWrites
import com.dlguard.integrity.ApkIntegrityChecker
import com.dlguard.integrity.IntegrityResult
import com.dlguard.integrity.Verdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 校验流水线：防抖 → 来源反查 → 多级校验 → 隔离 → 落库 → 通知。
 * 所有步骤都在 IO 线程串行执行单个文件的处理，不同文件之间并行。
 */
class CheckPipeline(
    private val context: Context,
    private val scope: CoroutineScope,
    private val checker: ApkIntegrityChecker,
    private val repository: GuardRepository,
    private val quarantine: QuarantineManager,
    private val resolver: DownloadHistoryResolver,
    private val notifier: NotificationHelper,
    private val shouldNotify: () -> Boolean = { true },
    private val onRecordSaved: (IntegrityResult) -> Unit
) {

    companion object {
        private const val TAG = "CheckPipeline"
        private const val DEBOUNCE_MS = 3_000L
        private const val POLL_STEP_MS = 400L
        private const val MAX_SETTLE_WAIT_MS = 180_000L
        private const val MAX_EMPTY_WAIT_MS = 20_000L
        private val TARGET_EXTENSIONS = setOf("apk", "apks", "xapk", "zip")
        private val IGNORED_SUFFIX = listOf(".part", ".tmp", ".temp", ".crdownload", ".download")
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    /** 同时最多校验 2 个文件，避免批量拷贝后几十个大文件同时跑 CRC 拖垮 IO。 */
    private val checkSemaphore = Semaphore(permits = 2)
    private val active = ConcurrentHashMap<String, Boolean>()
    /** 已处理指纹。v1 用非线程安全的 LinkedHashMap，多个校验协程并发 put 会破坏桶结构，已改为 CHM + 容量阈值清理。 */
    private val processed = ConcurrentHashMap<String, Long>()
    private val maxProcessed = 512

    fun submit(file: File) {
        if (!isCandidate(file)) return
        val path = file.absolutePath
        if (jobs[path]?.isActive == true) return
        jobs[path] = scope.launch(Dispatchers.IO) {
            try {
                if (!awaitStable(file)) return@launch
                process(file)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                Log.e(TAG, "处理 ${file.name} 失败", e)
            } finally {
                jobs.remove(path)
            }
        }
    }

    /** 连续 3 秒大小不变才认为写入结束，避免校验半截文件。 */
    private suspend fun awaitStable(file: File): Boolean {
        val start = SystemClock.elapsedRealtime()
        var lastSize = -1L
        var stableSince = start
        while (scope.isActive) {
            if (!file.exists() || !file.isFile) return false
            val size = file.length()
            val now = SystemClock.elapsedRealtime()
            if (size != lastSize) {
                lastSize = size
                stableSince = now
            } else if (size > 0 && now - stableSince >= DEBOUNCE_MS) {
                return true
            }
            // 0 字节文件同样要给出结论（截断），否则浏览器下载失败留下的空文件会被静默忽略
            if (size == 0L && now - start >= MAX_EMPTY_WAIT_MS) return true
            if (now - start >= MAX_SETTLE_WAIT_MS) return false
            delay(POLL_STEP_MS)
        }
        return false
    }

    private suspend fun process(file: File) {
        checkSemaphore.withPermit {
            processLocked(file)
        }
    }

    private suspend fun processLocked(file: File) {
        val path = file.absolutePath
        if (RecentWrites.consume(path)) {
            Log.i(TAG, "${file.name} 由内置下载器写入并已校验，跳过重复处理")
            return
        }
        val fingerprint = "${file.length()}@${file.lastModified()}"
        val key = "$path#$fingerprint"
        if (processed.containsKey(key)) return
        if (active.putIfAbsent(key, true) != null) return

        try {
            // 服务重启后对最近文件补扫时，避免重复写入同一份文件的结论。
            // 只对「仍在原地且结论健康」的记录去重：损坏文件已被移入隔离区，同名同大小再次出现必须重新校验。
            val previous = runCatching { repository.findByPathAndSize(path, file.length()) }.getOrNull()
            if (previous != null && previous.quarantinedPath == null &&
                previous.verdict == Verdict.OK.name &&
                System.currentTimeMillis() - previous.checkedAt < 10 * 60 * 1000L
            ) {
                Log.i(TAG, "${file.name} 10 分钟内已有相同大小的健康记录，跳过")
                return
            }
            val source = resolver.resolve(file)
            val result = checker.check(file, source.declaredSize) { stage ->
                Log.d(TAG, "${file.name}: $stage")
            }

            var quarantinedPath: String? = null
            var quarantineFailed = false
            if (!result.healthy) {
                val moved = quarantine.quarantine(file)
                if (moved != null) {
                    quarantinedPath = moved.absolutePath
                } else {
                    quarantineFailed = true
                }
            }

            val recordId = repository.saveResult(result, file, source, quarantinedPath)
            if (processed.size > maxProcessed) processed.clear()
            processed[key] = System.currentTimeMillis()

            GuardState.update {
                it.copy(
                    lastCheckedAt = System.currentTimeMillis(),
                    lastFileName = file.name,
                    lastVerdict = result.verdict.name
                )
            }
            onRecordSaved(result)

            if (!result.healthy) {
                val record = repository.findRecord(recordId)
                if (record != null && shouldNotify()) {
                    notifier.notifyCorrupt(record)
                }
                if (quarantineFailed) {
                    Log.w(TAG, "隔离失败，文件保留原地：$path")
                }
            }
            Log.i(TAG, "${file.name} => ${result.verdict} (${result.detail})")
        } finally {
            active.remove(key)
            // 校验期间文件又被写入（浏览器续写、用户覆盖）：fingerprint 变了就再排一次，别漏掉最终版本
            val changed = file.exists() &&
                "${file.length()}@${file.lastModified()}" != fingerprint
            if (changed) {
                scope.launch {
                    delay(600)
                    submit(file)
                }
            }
        }
    }

    private fun isCandidate(file: File): Boolean {
        if (!file.isFile) return false
        val name = file.name
        if (name.startsWith(".")) return false
        val lower = name.lowercase()
        if (IGNORED_SUFFIX.any { lower.endsWith(it) }) return false
        return lower.substringAfterLast('.', "") in TARGET_EXTENSIONS
    }

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }
}

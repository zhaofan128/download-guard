package com.dlguard.service

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 下载目录监听器，三条通道互为补充：
 *  1. FileObserver：CREATE / MODIFY / ATTRIB / CLOSE_WRITE / MOVED_TO / DELETE，递归 3 层、最多 200 个目录；
 *     部分 ROM 会吞掉 CLOSE_WRITE，因此把 MODIFY/ATTRIB 也算作候选，交给防抖流水线判定结束；
 *  2. MediaStore ContentObserver：任何 App 向公共存储插入/更新文件都会触发，补上 ROM 裁剪事件的洞；
 *  3. 4 秒轮询兜底：覆盖批量拷贝、mtime 被篡改、SD 卡挂载等极端情况。
 */
class DownloadWatcher(
    private val context: Context?,
    private val scope: CoroutineScope,
    private val directoryProvider: () -> List<File>,
    private val onCandidate: (File) -> Unit
) {

    companion object {
        private const val TAG = "DownloadWatcher"
        private const val MAX_DEPTH = 3
        private const val MAX_DIRS = 200
        private const val MAX_ENTRIES_PER_DIR = 3000
        private const val POLL_INTERVAL_MS = 4_000L
        private const val MEDIA_DEBOUNCE_MS = 1_200L
        private const val RECENT_WINDOW_MS = 30 * 60 * 1000L
        private const val MAX_KNOWN = 8_000
        private val TARGET_EXTENSIONS = setOf("apk", "apks", "xapk", "zip")
        private val IGNORED_SUFFIX = listOf(".part", ".tmp", ".temp", ".crdownload", ".download", ".!ut")
    }

    private val observers = ConcurrentHashMap<String, FileObserver>()
    private val known = ConcurrentHashMap<String, String>()
    private val bootstrapped = ConcurrentHashMap.newKeySet<String>()
    private var pollJob: Job? = null
    private var mediaObserver: ContentObserver? = null
    private var mediaDebounceJob: Job? = null
    private var running = false

    @Suppress("DEPRECATION")
    private val observerMask = FileObserver.CREATE or FileObserver.MODIFY or FileObserver.ATTRIB or
        FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.DELETE

    fun start() {
        if (running) return
        running = true
        refresh()
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { scanOnce() }
                    .onFailure { Log.w(TAG, "轮询扫描失败: ${it.message}") }
                delay(POLL_INTERVAL_MS)
            }
        }
        registerMediaObserver()
        Log.i(TAG, "watcher started, dirs=${observers.size}")
    }

    fun stop() {
        running = false
        pollJob?.cancel()
        pollJob = null
        mediaDebounceJob?.cancel()
        mediaDebounceJob = null
        runCatching { mediaObserver?.let { context?.contentResolver?.unregisterContentObserver(it) } }
        mediaObserver = null
        observers.values.forEach { runCatching { it.stopWatching() } }
        observers.clear()
        known.clear()
        bootstrapped.clear()
        Log.i(TAG, "watcher stopped")
    }

    /** 目录列表变化（新增/删除自定义目录）后调用：补监听、清理失效监听、重新扫一遍。 */
    fun refresh() {
        if (!running) return
        val roots = directoryProvider().filter { it.exists() && it.isDirectory }
        roots.forEach { root ->
            watchTree(root, 0)
            if (bootstrapped.add(root.absolutePath)) bootstrap(root)
        }
        pruneObservers(roots.map { it.absolutePath })
    }

    private fun pruneObservers(rootPaths: List<String>) {
        val stale = observers.keys.filter { path ->
            rootPaths.none { root -> path == root || path.startsWith("$root/") }
        }
        stale.forEach { path ->
            observers.remove(path)?.let { runCatching { it.stopWatching() } }
        }
        if (stale.isNotEmpty()) Log.i(TAG, "清理失效监听 ${stale.size} 个")
    }

    @Suppress("DEPRECATION")
    private fun watchTree(dir: File, depth: Int) {
        if (depth > MAX_DEPTH || observers.size >= MAX_DIRS) return
        val path = dir.absolutePath
        if (observers.containsKey(path)) {
            dir.listFiles()?.filter { it.isDirectory }?.forEach { watchTree(it, depth + 1) }
            return
        }
        val observer = object : FileObserver(path, observerMask) {
            override fun onEvent(event: Int, name: String?) {
                val childName = name ?: return
                val child = File(dir, childName)
                if (event and FileObserver.DELETE != 0) {
                    known.remove(child.absolutePath)
                    return
                }
                if (event and (FileObserver.CREATE or FileObserver.MOVED_TO) != 0 && child.isDirectory) {
                    watchTree(child, depth + 1)
                }
                if (isTarget(child.name)) callback(child)
            }
        }
        runCatching {
            observer.startWatching()
            observers[path] = observer
            Log.d(TAG, "watching $path")
        }.onFailure { Log.w(TAG, "无法监听 $path: ${it.message}") }
        dir.listFiles()?.filter { it.isDirectory }?.forEach { watchTree(it, depth + 1) }
    }

    /** 首次启动时把已存在的旧文件记入 known，避免把历史文件全部重新校验。 */
    private fun bootstrap(root: File) {
        val now = System.currentTimeMillis()
        walkFiles(root, 0) { file ->
            if (!isTarget(file.name)) return@walkFiles
            val recent = now - file.lastModified() <= RECENT_WINDOW_MS
            if (recent && file.length() > 0) {
                callback(file)
            } else {
                remember(file)
            }
        }
    }

    private fun registerMediaObserver() {
        val ctx = context ?: return
        if (mediaObserver != null) return
        val uri: Uri = runCatching { MediaStore.Files.getContentUri("external") }.getOrNull() ?: return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                scheduleMediaScan()
            }
        }
        runCatching {
            ctx.contentResolver.registerContentObserver(uri, true, observer)
            mediaObserver = observer
            Log.i(TAG, "MediaStore 观察器已注册")
        }.onFailure { Log.w(TAG, "注册 MediaStore 观察器失败: ${it.message}") }
    }

    private fun scheduleMediaScan() {
        if (!running) return
        mediaDebounceJob?.cancel()
        mediaDebounceJob = scope.launch(Dispatchers.IO) {
            delay(MEDIA_DEBOUNCE_MS)
            runCatching { scanOnce() }.onFailure { Log.w(TAG, "MediaStore 触发扫描失败: ${it.message}") }
        }
    }

    private fun scanOnce() {
        val now = System.currentTimeMillis()
        directoryProvider().forEach { root ->
            if (!root.exists() || !root.isDirectory) return@forEach
            if (!observers.containsKey(root.absolutePath)) watchTree(root, 0)
            walkFiles(root, 0) { file ->
                if (!isTarget(file.name)) return@walkFiles
                if (file.length() <= 0L) return@walkFiles
                if (now - file.lastModified() > RECENT_WINDOW_MS) return@walkFiles
                val fingerprint = fingerprint(file)
                if (known[file.absolutePath] != fingerprint) {
                    remember(file)
                    callback(file)
                }
            }
        }
    }

    private fun callback(file: File) {
        runCatching { onCandidate(file) }
            .onFailure { Log.w(TAG, "候选回调失败 ${file.name}: ${it.message}") }
    }

    private fun remember(file: File) {
        if (known.size > MAX_KNOWN) known.clear()
        known[file.absolutePath] = fingerprint(file)
    }

    private fun fingerprint(file: File): String = "${file.length()}@${file.lastModified()}"

    private fun isTarget(name: String): Boolean {
        if (name.startsWith(".")) return false
        val lower = name.lowercase()
        if (IGNORED_SUFFIX.any { lower.endsWith(it) }) return false
        return lower.substringAfterLast('.', "") in TARGET_EXTENSIONS
    }

    private fun walkFiles(dir: File, depth: Int, visitor: (File) -> Unit) {
        if (depth > MAX_DEPTH) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        if (children.size > MAX_ENTRIES_PER_DIR) {
            children.take(MAX_ENTRIES_PER_DIR).forEach { child ->
                if (child.isFile) visitor(child) else walkFiles(child, depth + 1, visitor)
            }
            return
        }
        children.forEach { child ->
            if (child.isDirectory) walkFiles(child, depth + 1, visitor) else visitor(child)
        }
    }

    val watchedDirectoryCount: Int get() = observers.size
}

package com.dlguard.bt

import com.dlguard.download.Magnet
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import java.io.File
import kotlin.coroutines.resume

/**
 * BT/磁力引擎。
 *
 * libtorrent 的 native 对象不是线程安全的：所有 SessionManager / TorrentHandle 调用
 * 必须发生在同一线程。这里用一条专用 HandlerThread 承载，外部通过方法投递任务，
 * 状态以不可变 [BtTask] 快照通过 [tasks] 流发出，UI 不接触任何 native 类型。
 *
 * 这个 libtorrent4j 版本没有公开的「遍历所有 torrent」API，所以引擎自己维护
 * [handles]（id -> TorrentHandle）：添加时同步 find + AddTorrentAlert 双保险捕获。
 */
class BtEngine(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = BtStore(context)
    private val btDir = File(context.filesDir, "bt").apply { mkdirs() }
    private val saveDir = File(context.getExternalFilesDir("Download"), "BT").apply { mkdirs() }
    private val sessionStateFile = File(btDir, "session.state")
    private val thread = HandlerThread("dlguard-bt", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler = Handler(thread.looper)

    private var session: SessionManager? = null
    /** id(infoHash hex) -> native handle。仅在工作线程访问。 */
    private val handles = HashMap<String, TorrentHandle>()
    /** id -> 用户是否暂停。 */
    private val pausedSet = java.util.Collections.synchronizedSet(HashSet<String>())
    /** id -> 原始来源（magnet / .torrent 路径）。 */
    private val sources = java.util.Collections.synchronizedMap(HashMap<String, String>())
    /** id -> 名称，元数据未到时用 dn 参数兜底。 */
    private val names = java.util.Collections.synchronizedMap(HashMap<String, String>())
    /** id -> 错误原因。 */
    private val errors = java.util.Collections.synchronizedMap(HashMap<String, String>())

    private val _tasks = MutableStateFlow<List<BtTask>>(emptyList())
    val tasks: StateFlow<List<BtTask>> = _tasks.asStateFlow()

    private val _dhtNodes = MutableStateFlow(0)
    val dhtNodes: StateFlow<Int> = _dhtNodes.asStateFlow()

    private val _limits = MutableStateFlow(Limits(0, 0))
    val limits: StateFlow<Limits> = _limits.asStateFlow()

    data class Limits(val downloadKb: Int, val uploadKb: Int)

    private val alertListener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(
            AlertType.ADD_TORRENT.swig(),
            AlertType.METADATA_RECEIVED.swig(),
            AlertType.TORRENT_ERROR.swig(),
            AlertType.TORRENT_FINISHED.swig()
        )
        override fun alert(a: Alert<*>) {
            when (a) {
                is AddTorrentAlert -> {
                    val th = a.handle()
                    runCatching {
                        val id = th.infoHash().toHex()
                        handles[id] = th
                        errors.remove(id)
                        refreshLocked()
                    }
                }
                is MetadataReceivedAlert -> runCatching { refreshLocked() }
                is TorrentErrorAlert -> {
                    runCatching {
                        val id = a.handle().infoHash().toHex()
                        errors[id] = a.message().ifBlank { "种子错误" }
                        refreshLocked()
                    }
                }
            }
        }
    }

    init {
        // 先从 SharedPreferences 恢复任务清单（在 native 线程启动前，纯内存操作）
        store.all().forEach { s ->
            sources[s.id] = s.source
            names[s.id] = s.name
            if (s.paused) pausedSet.add(s.id)
        }
        post {
            val sp = SettingsPack().apply {
                setBoolean(SETT_ENABLE_DHT, true)
                setBoolean(SETT_ENABLE_PEX, true)
                setBoolean(SETT_ENABLE_LSD, true)
                setBoolean(SETT_ENABLE_ROUTERS, true)
                setBoolean(SETT_ENABLE_INCOMING, true)
                setBoolean(SETT_ENABLE_UPNP, true)
                setBoolean(SETT_ENABLE_NATPMP, true)
                connectionsLimit(200)
                activeDownloads(5)
                activeSeeds(10)
            }
            val params = if (sessionStateFile.exists()) {
                runCatching { SessionParams(sessionStateFile.readBytes()) }.getOrElse { SessionParams(sp) }
            } else SessionParams(sp)
            val sm = SessionManager()
            sm.addListener(alertListener)
            sm.start(params)
            session = sm
            applyLimitsLocked(_limits.value)
            restoreLocked()
        }
        scope.launch {
            while (true) {
                delay(1000)
                post { refreshLocked() }
            }
        }
        scope.launch {
            while (true) {
                delay(30_000)
                post { session?.let { runCatching { sessionStateFile.writeBytes(it.saveState()) } } }
            }
        }
    }

    private fun post(block: () -> Unit) {
        handler.post { runCatching(block) }
    }

    // ---------------------------------------------------------------- 添加任务

    /** 添加磁力。返回 infoHash（40 位 hex），非法磁力返回 null。 */
    suspend fun addMagnet(uri: String): String? {
        val parsed = Magnet.parse(uri) ?: return null
        return suspendCancellableCoroutine { cont ->
            post {
                val sm = session
                var result: String? = null
                if (sm != null) {
                    runCatching {
                        sm.download(uri.trim(), saveDir, null)
                        result = parsed.infoHash
                    }
                }
                if (result != null) {
                    val id = result!!
                    val name = parsed.displayName ?: parsed.infoHash.take(12)
                    sources[id] = uri.trim()
                    names[id] = name
                    store.save(id, uri.trim(), name, false)
                    // download() 同步注册，find 兜底拿到 handle（alert 也可能已先到）
                    runCatching { sm?.find(Sha1Hash.parseHex(id))?.let { handles[id] = it } }
                    refreshLocked()
                }
                cont.resume(result)
            }
        }
    }

    /** 添加本地 .torrent。种子损坏返回 null。 */
    suspend fun addTorrentFile(path: String): String? = suspendCancellableCoroutine { cont ->
        post {
            val sm = session
            var result: String? = null
            if (sm != null) {
                runCatching {
                    val ti = TorrentInfo(File(path).readBytes())
                    if (ti.isValid) {
                        val hash = ti.infoHash().toHex()
                        if (sm.find(Sha1Hash.parseHex(hash)) == null) {
                            sm.download(ti, saveDir)
                        }
                        sources[hash] = path
                        names[hash] = ti.files().name()
                        result = hash
                    }
                }
            }
            if (result != null) {
                val id = result!!
                runCatching { sm?.find(Sha1Hash.parseHex(id))?.let { handles[id] = it } }
                val name = names[id] ?: id.take(12)
                store.save(id, path, name, false)
                refreshLocked()
            }
            cont.resume(result)
        }
    }

    // ---------------------------------------------------------------- 任务操作

    fun pause(id: String) {
        post {
            handles[id]?.pause()
            pausedSet.add(id)
            store.setPaused(id, true)
            refreshLocked()
        }
    }

    fun resume(id: String) {
        post {
            handles[id]?.resume()
            pausedSet.remove(id)
            store.setPaused(id, false)
            errors.remove(id)
            refreshLocked()
        }
    }

    /** 删除任务。[deleteFiles] 为 true 时连下载目录里的文件一起删。 */
    fun remove(id: String, deleteFiles: Boolean) {
        post {
            if (deleteFiles) runCatching { deleteTaskFiles(id) }
            session?.let { sm ->
                handles.remove(id)?.let { th -> runCatching { sm.remove(th) } }
            }
            pausedSet.remove(id)
            sources.remove(id)
            names.remove(id)
            errors.remove(id)
            store.delete(id)
            refreshLocked()
        }
    }

    private fun deleteTaskFiles(id: String) {
        val th = handles[id]
        val ti = th?.torrentFile()
        if (ti != null && ti.isValid) {
            val fs = ti.files()
            for (i in 0 until fs.numFiles()) {
                runCatching { File(saveDir, fs.filePath(i)).delete() }
            }
        }
    }

    fun forceRecheck(id: String) {
        post { handles[id]?.forceRecheck() }
    }

    /** 文件选择：下标与 fileList() 一致。IGNORE 的文件不下载。 */
    fun setFilePriorities(id: String, priorities: List<Priority>) {
        post {
            handles[id]?.let { th ->
                priorities.forEachIndexed { index, p -> runCatching { th.filePriority(index, p) } }
                refreshLocked()
            }
        }
    }

    /** 读取种子里的文件列表，供文件选择 UI 用。 */
    suspend fun fileList(id: String): List<BtFile> = suspendCancellableCoroutine { cont ->
        post {
            val list = mutableListOf<BtFile>()
            runCatching {
                val ti = handles[id]?.torrentFile()
                if (ti != null && ti.isValid) {
                    val fs = ti.files()
                    for (i in 0 until fs.numFiles()) {
                        list += BtFile(i, fs.filePath(i), fs.fileSize(i))
                    }
                }
            }
            cont.resume(list)
        }
    }

    data class BtFile(val index: Int, val path: String, val size: Long)

    // ---------------------------------------------------------------- 设置

    fun setLimits(downloadKb: Int, uploadKb: Int) {
        _limits.value = Limits(downloadKb.coerceAtLeast(0), uploadKb.coerceAtLeast(0))
        post { applyLimitsLocked(_limits.value) }
    }

    private fun applyLimitsLocked(value: Limits) {
        session?.let {
            it.downloadRateLimit(value.downloadKb * 1024)
            it.uploadRateLimit(value.uploadKb * 1024)
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun restoreLocked() {
        for ((id, source) in sources) {
            runCatching {
                val sm = session ?: return
                if (sm.find(Sha1Hash.parseHex(id)) != null) return@runCatching
                if (source.startsWith("magnet:", true)) {
                    sm.download(source, saveDir, null)
                } else if (File(source).exists()) {
                    val ti = TorrentInfo(File(source).readBytes())
                    if (ti.isValid) sm.download(ti, saveDir)
                }
                sm.find(Sha1Hash.parseHex(id))?.let { handles[id] = it }
            }
        }
        refreshLocked()
    }

    private fun refreshLocked() {
        val sm = session ?: return
        sm.postTorrentUpdates()
        _dhtNodes.value = sm.dhtNodes().toInt()
        val dht = _dhtNodes.value
        val out = mutableListOf<BtTask>()
        for ((id, th) in handles) {
            runCatching {
                val st = th.status()
                val paused = pausedSet.contains(id)
                val state = when {
                    paused -> BtState.PAUSED
                    st.isSeeding || st.state() == TorrentStatus.State.SEEDING ||
                        st.state() == TorrentStatus.State.FINISHED -> BtState.SEEDING
                    st.state() == TorrentStatus.State.DOWNLOADING_METADATA -> BtState.FETCHING_METADATA
                    st.state() == TorrentStatus.State.CHECKING_FILES ||
                        st.state() == TorrentStatus.State.CHECKING_RESUME_DATA -> BtState.CHECKING
                    st.state() == TorrentStatus.State.DOWNLOADING -> BtState.DOWNLOADING
                    else -> BtState.UNKNOWN
                }
                out += BtTask(
                    id = id,
                    name = if (st.name().isNullOrBlank()) names[id] ?: id.take(12) else st.name(),
                    source = sources[id] ?: "",
                    isMagnet = sources[id]?.startsWith("magnet:", true) == true,
                    state = state,
                    progress = st.progress().coerceIn(0f, 1f),
                    downloaded = st.totalDone(),
                    total = st.total().coerceAtLeast(st.totalDone()),
                    downloadRate = st.downloadRate().toLong(),
                    uploadRate = st.uploadRate().toLong(),
                    totalUploaded = st.totalUpload(),
                    seeds = st.listPeers(),
                    peers = 0,
                    dhtNodes = dht,
                    shareRatio = shareRatio(st.totalUpload(), st.totalDone()),
                    message = errors[id],
                    paused = paused
                )
            }
        }
        _tasks.value = out
    }

    fun shutdown() {
        post {
            session?.let { sm ->
                runCatching { sessionStateFile.writeBytes(sm.saveState()) }
                sm.removeListener(alertListener)
                sm.stop()
            }
            session = null
        }
        thread.quitSafely()
    }

    // libtorrent settings_pack 枚举序号（2.1.0）。用数字是因为该版本封装未导出常量。
    private companion object {
        const val SETT_ENABLE_UPNP = 4
        const val SETT_ENABLE_NATPMP = 5
        const val SETT_ENABLE_DHT = 7
        const val SETT_ENABLE_PEX = 24
        const val SETT_ENABLE_LSD = 25
        const val SETT_ENABLE_ROUTERS = 27
        const val SETT_ENABLE_INCOMING = 34
    }
}

package com.dlguard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dlguard.DownloadGuardApp
import com.dlguard.bt.BtEngine
import com.dlguard.bt.BtTask
import com.dlguard.bt.formatRate
import com.dlguard.download.Magnet
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.libtorrent4j.Priority

/**
 * BT 页 ViewModel：只做「意图转发 + 提示文案」，状态直接来自 [BtEngine.tasks]。
 *
 * 引擎是应用级的（Activity 转圈/重建不影响下载），因此这里不持有任何任务状态。
 */
class BtViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = (application as DownloadGuardApp).container.btEngine

    val tasks = engine.tasks
    val dhtNodes = engine.dhtNodes
    val limits = engine.limits

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 待确认删除的任务 id（null = 不显示对话框）。 */
    val pendingDelete = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 4)

    /** 文件选择对话框当前任务 id。 */
    val filePicker = MutableSharedFlow<String>(extraBufferCapacity = 4)

    fun add(input: String) = viewModelScope.launch {
        val text = input.trim()
        when {
            Magnet.isMagnet(text) -> {
                val id = engine.addMagnet(text)
                _messages.tryEmit(
                    if (id != null) "磁力已添加，正在通过 DHT 获取元数据…"
                    else "磁力链接无效（只支持 btih v1）"
                )
            }
            text.endsWith(".torrent", ignoreCase = true) -> {
                val id = engine.addTorrentFile(text)
                _messages.tryEmit(if (id != null) "种子已添加" else "种子文件无效或读不到")
            }
            else -> _messages.tryEmit("请粘贴 magnet: 链接，或选择本地 .torrent 文件")
        }
    }

    fun addTorrentPath(path: String) = viewModelScope.launch {
        val id = engine.addTorrentFile(path)
        _messages.tryEmit(if (id != null) "种子已添加" else "种子文件无效或读不到")
    }

    fun pause(id: String) = engine.pause(id)
    fun resume(id: String) = engine.resume(id)
    fun recheck(id: String) {
        engine.forceRecheck(id)
        _messages.tryEmit("已开始重新校验文件")
    }

    fun askDelete(id: String, deleteFiles: Boolean) {
        pendingDelete.tryEmit(id to deleteFiles)
    }

    fun confirmDelete(id: String, deleteFiles: Boolean) {
        engine.remove(id, deleteFiles)
        _messages.tryEmit(if (deleteFiles) "任务与文件已删除" else "任务已移除（文件保留）")
    }

    fun setLimits(downloadKb: Int, uploadKb: Int) {
        engine.setLimits(downloadKb, uploadKb)
        val d = if (downloadKb <= 0) "不限" else formatRate(downloadKb * 1024L)
        val u = if (uploadKb <= 0) "不限" else formatRate(uploadKb * 1024L)
        _messages.tryEmit("限速已更新：下载 $d · 上传 $u")
    }

    fun openFilePicker(id: String) = filePicker.tryEmit(id)

    suspend fun fileList(id: String) = engine.fileList(id)
    fun applyFilePriorities(id: String, priorities: List<Priority>) {
        engine.setFilePriorities(id, priorities)
        _messages.tryEmit("文件选择已更新")
    }

    fun summarize(task: BtTask): String =
        "${task.name} · ${(task.progress * 100).toInt()}% · ↓${formatRate(task.downloadRate)} ↑${formatRate(task.uploadRate)}"
}

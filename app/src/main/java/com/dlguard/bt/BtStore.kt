package com.dlguard.bt

import android.content.Context

/**
 * BT 任务的轻量持久化。
 *
 * 只存「重建任务所需的最小信息」（source + 名称 + 暂停状态），不存进度——
 * 进度由 libtorrent 对磁盘上已存在的文件做 hash check 重新得出，这才可靠。
 * Session state（DHT 节点等）由 BtEngine 单独存成二进制文件。
 */
class BtStore(context: Context) {
    private val prefs = context.getSharedPreferences("bt_tasks", Context.MODE_PRIVATE)

    data class Saved(val id: String, val source: String, val name: String, val paused: Boolean)

    fun all(): List<Saved> {
        val ids = prefs.getStringSet("ids", emptySet()) ?: emptySet()
        return ids.mapNotNull { id ->
            val source = prefs.getString("src_$id", null) ?: return@mapNotNull null
            Saved(
                id = id,
                source = source,
                name = prefs.getString("name_$id", "") ?: "",
                paused = prefs.getBoolean("paused_$id", false)
            )
        }
    }

    fun save(id: String, source: String, name: String, paused: Boolean) {
        prefs.edit()
            .putStringSet("ids", (prefs.getStringSet("ids", emptySet()) ?: emptySet()) + id)
            .putString("src_$id", source)
            .putString("name_$id", name)
            .putBoolean("paused_$id", paused)
            .apply()
    }

    fun setPaused(id: String, paused: Boolean) {
        prefs.edit().putBoolean("paused_$id", paused).apply()
    }

    fun delete(id: String) {
        prefs.edit()
            .putStringSet("ids", (prefs.getStringSet("ids", emptySet()) ?: emptySet()) - id)
            .remove("src_$id").remove("name_$id").remove("paused_$id")
            .apply()
    }
}

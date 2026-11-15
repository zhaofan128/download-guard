package com.dlguard.plugin

import android.content.Context
import org.json.JSONObject

/**
 * 插件配置存储：每个插件一份 JSON（SharedPreferences 内按插件 id 存字符串）。
 *
 * 读取时总是把 manifest 里声明的默认值合并进来，因此插件升级新增字段、
 * 或用户清空某项配置，都不会出现「读不到值」的空洞。
 */
class PluginConfigStore(context: Context) {

    companion object {
        private const val PREF = "plugin_config"
    }

    private val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun raw(id: String): JSONObject = runCatching {
        val text = prefs.getString(id, null) ?: return JSONObject()
        if (text.isBlank()) JSONObject() else JSONObject(text)
    }.getOrDefault(JSONObject())

    /** 合并默认值后的完整配置表。 */
    fun values(manifest: PluginManifest): Map<String, String> {
        val saved = raw(manifest.id)
        return manifest.config.associate { field ->
            val stored = saved.optString(field.key, "")
            field.key to stored.ifBlank { field.default }
        }
    }

    fun value(manifest: PluginManifest, key: String): String? {
        val field = manifest.config.firstOrNull { it.key == key }
        val saved = raw(manifest.id).optString(key, "")
        return saved.ifBlank { field?.default }
    }

    fun set(id: String, key: String, value: String) {
        val obj = raw(id)
        obj.put(key, value)
        prefs.edit().putString(id, obj.toString()).apply()
    }

    fun reset(id: String) {
        prefs.edit().remove(id).apply()
    }

    /** 是否有用户改过的配置（详情页用来决定是否显示「恢复默认」）。 */
    fun isCustomized(id: String): Boolean = raw(id).length() > 0
}

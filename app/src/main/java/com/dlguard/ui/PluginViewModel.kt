package com.dlguard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.dlguard.DownloadGuardApp
import com.dlguard.plugin.Plugin
import com.dlguard.plugin.PluginManifest
import com.dlguard.plugin.PluginManager
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 插件页 ViewModel：市场 / 收藏 / 我的 三种视图共用一份过滤状态。
 *
 * 数据来源全部来自 [PluginManager]（DataStore 之外的轻量 SharedPreferences 状态），
 * 页面自身不持有插件数据，保证首页、设置页、插件页看到的是同一份事实。
 */
class PluginViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as DownloadGuardApp).container
    private val manager: PluginManager = container.pluginManager

    val plugins = manager.plugins
    val market = manager.marketPlugins
    val favorites = manager.favoriteIds
    val refreshing = manager.refreshing
    val marketUpdatedAt = manager.marketUpdatedAt

    /** 当前视图：全部 / 收藏 / 我的 / 某个分类。 */
    val filter = MutableStateFlow(FILTER_ALL)
    val query = MutableStateFlow("")

    val marketUrl = MutableStateFlow(manager.marketUrl)

    companion object {
        const val FILTER_ALL = "全部"
        const val FILTER_FAVORITE = "收藏"
        const val FILTER_MINE = "我的"
    }

    fun toggleEnabled(id: String, value: Boolean) = manager.setEnabled(id, value)

    fun toggleFavorite(id: String) = manager.toggleFavorite(id)

    // ------------------------------------------------------------ 详情页 / 配置

    fun manifestOf(id: String): PluginManifest? = manager.manifestOf(id)

    fun configOf(id: String): Map<String, String> = manager.config(id)

    fun setConfig(id: String, key: String, value: String) = manager.setConfig(id, key, value)

    fun resetConfig(id: String) = manager.resetConfig(id)

    fun isConfigCustomized(id: String): Boolean = manager.isConfigCustomized(id)

    /** 浏览器下载桥的实时监听状态（详情页用于暴露端口冲突）。 */
    fun bridgeRunning(): Boolean = manager.lanServer.isRunning

    fun bridgeAddress(): String? = manager.lanServer.localAddress()

    // ------------------------------------------------------------ 安装 / 卸载

    fun uninstall(id: String, onMessage: (String) -> Unit) {
        val plugin = manager.plugins.value.firstOrNull { it.manifest.id == id }
        if (plugin?.builtin == true) {
            onMessage("官方插件不可卸载，可以关闭开关")
            return
        }
        manager.uninstall(id)
        onMessage("已卸载插件")
    }

    fun install(manifest: PluginManifest, onMessage: (String) -> Unit) {
        if (manifest.downloadUrl.isBlank()) {
            // 市场条目没给 zip 直链时，退化为「从仓库整包安装」
            if (manifest.repo.isNotBlank()) {
                installFromGithub(manifest.repo, onMessage)
            } else {
                onMessage("该插件暂无下载地址（市场索引缺少 downloadUrl / repo）")
            }
            return
        }
        onMessage("正在下载插件包…")
        manager.installFromUrl(manifest.downloadUrl) { result ->
            onMessage(result.ifBlank { "安装成功：${manifest.name}" })
        }
    }

    /** 从 GitHub 仓库安装：`owner/repo`、仓库地址或 zip 直链。 */
    fun installFromGithub(input: String, onMessage: (String) -> Unit) {
        val value = input.trim()
        if (value.isBlank()) {
            onMessage("请填写 GitHub 仓库，例如 dlguard/plugins")
            return
        }
        onMessage("正在从 GitHub 拉取插件包…")
        manager.installFromGithub(value) { onMessage(it.ifBlank { "安装成功" }) }
    }

    /** 粘贴 manifest JSON 直接创建插件（高级自定义）。 */
    fun createCustom(json: String, onMessage: (String) -> Unit) {
        val result = manager.createFromJson(json)
        onMessage(result.ifBlank { "插件已创建，可在「我的」里查看" })
    }

    /** 本地 zip 安装（需在 IO 线程调用）。返回错误信息，空串表示成功。 */
    fun installLocal(bytes: ByteArray): String =
        manager.installFromBytes(bytes, source = "本地文件")

    /** 插件的安装来源与包摘要，用于事后核对。 */
    fun provenance(id: String): Map<String, String> = manager.provenance(id)

    fun refreshMarket(onMessage: (String) -> Unit) {
        manager.refreshMarket { onMessage(it) }
    }

    fun saveMarketUrl(url: String, onMessage: (String) -> Unit) {
        val trimmed = url.trim()
        manager.marketUrl = trimmed
        marketUrl.value = trimmed
        if (trimmed.isBlank()) {
            onMessage("已清空市场源，仅显示官方插件")
        } else {
            onMessage("已保存市场源，正在刷新…")
            manager.refreshMarket { onMessage(it) }
        }
    }

    /** 合并出当前视图的插件行。 */
    fun rows(
        plugins: List<Plugin>,
        market: List<PluginManifest>,
        favorites: Set<String>,
        filter: String,
        query: String
    ): List<PluginRow> {
        val installed = plugins.associateBy { it.manifest.id }
        val keyword = query.trim().lowercase()
        val source: List<PluginRow> = when (filter) {
            FILTER_MINE -> plugins.map { plugin ->
                PluginRow(plugin.manifest, installed = true, enabled = plugin.enabled, builtin = plugin.builtin, favorite = plugin.manifest.id in favorites)
            }
            FILTER_FAVORITE -> (market + plugins.map { it.manifest })
                .distinctBy { it.id }
                .filter { it.id in favorites }
                .map { manifest ->
                    val local = installed[manifest.id]
                    PluginRow(manifest, local != null, local?.enabled ?: false, local?.builtin ?: manifest.pinned, manifest.id in favorites)
                }
            else -> market
                .filter { filter == FILTER_ALL || it.category == filter }
                .map { manifest ->
                    val local = installed[manifest.id]
                    PluginRow(manifest, local != null, local?.enabled ?: false, local?.builtin ?: manifest.pinned, manifest.id in favorites)
                }
        }
        return source
            .filter { row ->
                keyword.isEmpty() ||
                    row.manifest.name.lowercase().contains(keyword) ||
                    row.manifest.description.lowercase().contains(keyword) ||
                    row.manifest.author.lowercase().contains(keyword)
            }
            .sortedWith(compareByDescending<PluginRow> { it.manifest.pinned }.thenByDescending { it.favorite })
    }
}

data class PluginRow(
    val manifest: PluginManifest,
    val installed: Boolean,
    val enabled: Boolean,
    val builtin: Boolean,
    val favorite: Boolean
)

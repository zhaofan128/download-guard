package com.dlguard.plugin

/**
 * 插件可配置项：声明式的参数 schema。
 *
 * App 不认识任何插件的业务逻辑，只按 schema 渲染控件并把用户填的值存下来，
 * 插件（内置能力 / 第三方）自己按键名读取。这样「高度可自定义」是通用能力，
 * 而不是给每个插件硬编码一套设置界面。
 */
data class PluginConfigField(
    val key: String,
    val label: String,
    /** toggle / slider / text / choice */
    val type: String = TYPE_TOGGLE,
    val description: String = "",
    val default: String = "",
    val min: Int = 0,
    val max: Int = 48,
    val step: Int = 1,
    val options: List<String> = emptyList()
) {
    companion object {
        const val TYPE_TOGGLE = "toggle"
        const val TYPE_SLIDER = "slider"
        const val TYPE_TEXT = "text"
        const val TYPE_CHOICE = "choice"
    }
}

/**
 * 插件清单：manifest.json 的结构，同时用于市场索引条目。
 *
 * 设计原则：插件是**声明式**的——App 本体只提供通用能力（下载、嗅探、桥接），
 * 具体站点的解析规则、增强逻辑由插件承载。这样 App 本体保持中立合规，
 * 插件内容由使用者自行选择与负责。
 */
data class PluginManifest(
    val id: String = "",
    val name: String = "",
    val version: String = "1.0.0",
    val author: String = "",
    val description: String = "",
    val category: String = CATEGORY_TOOL,
    /** 展示用图标（emoji，避免额外图片资源）。 */
    val icon: String = "🧩",
    /** 插件主页 / 详情页。 */
    val homepage: String = "",
    /** 源码仓库（GitHub owner/repo 或完整地址），详情页可直接跳转。 */
    val repo: String = "",
    /** 更新日志，详情页原样展示。 */
    val changelog: String = "",
    /** 市场安装地址（zip 包直链）；内置插件为空。 */
    val downloadUrl: String = "",
    val permissions: List<String> = emptyList(),
    /** 配置项 schema：详情页据此生成表单。 */
    val config: List<PluginConfigField> = emptyList(),
    /**
     * URL 重写脚本（JavaScript，沙箱执行，200ms 超时，无网络/文件/反射权限）。
     * 入参 `url`，返回新 URL 或 null。空串 = 纯配置型插件。
     */
    val script: String = "",
    /** 脚本生效的 host 列表（后缀匹配，如 `pan.example.com`）；空 = 所有 host。 */
    val matchHosts: List<String> = emptyList(),
    /** 内置官方插件：常驻列表、默认置顶、不可卸载。 */
    val pinned: Boolean = false,
    val enabledByDefault: Boolean = false
) {
    companion object {
        const val CATEGORY_CLOUD = "网盘加速"
        const val CATEGORY_RESOLVER = "链接解析"
        const val CATEGORY_ACCEL = "下载加速"
        const val CATEGORY_TOOL = "工具"

        val CATEGORIES = listOf(CATEGORY_ACCEL, CATEGORY_RESOLVER, CATEGORY_CLOUD, CATEGORY_TOOL)
    }
}

/** 运行时插件（清单 + 本地安装/启用状态）。 */
data class Plugin(
    val manifest: PluginManifest,
    val builtin: Boolean,
    val enabled: Boolean,
    val installedAt: Long = 0L,
    val sizeBytes: Long = 0L
)

package com.dlguard.plugin

import android.content.Context
import android.util.Log
import com.dlguard.download.DownloadCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 插件管理器：内置官方插件 + 用户安装的第三方插件 + 远程市场索引。
 *
 * 存储布局：
 *  - 插件解包目录：`files/plugins/<pluginId>/`（内含 manifest.json 与插件文件）；
 *  - 启用/收藏状态、市场索引地址：SharedPreferences（`plugin_state`）；
 *  - 市场索引缓存：`files/plugins/.market.json`（拉取失败时离线可用）。
 *
 * 官方内置插件全部是「真实功能开关」，不存在装饰性条目：
 *  1. 浏览器下载桥（局域网服务，默认关闭、置顶）
 *  2. 直链嗅探（剪贴板/分享文本提取直链，默认开启）
 *  3. 智能并发测速（首页自动测速入口，默认开启）
 *  4. 网盘直链加速（合规声明型：仅对你合法持有的直链生效，默认关闭、置顶）
 */
class PluginManager(
    private val context: Context,
    private val downloadCenterProvider: () -> DownloadCenter?,
    /** 插件启用时联动全局并发设置（网盘加速插件使用）。 */
    private val onApplyConcurrency: (Int) -> Unit = {}
) {

    companion object {
        private const val TAG = "PluginManager"
        const val PREF = "plugin_state"

        const val ID_BROWSER_BRIDGE = "official.browser-bridge"
        const val ID_DIRECT_SNIFF = "official.direct-sniff"
        const val ID_AUTO_TUNE = "official.auto-tune"
        const val ID_CLOUD_DIRECT = "official.cloud-direct"
        const val ID_SPEED_LIMIT = "official.speed-limit"
        const val ID_SMART_RETRY = "official.smart-retry"
        const val ID_DEDUPE = "official.dedupe"
        const val ID_NAME_CLEAN = "official.name-clean"
        const val ID_AUTO_FOLDER = "official.auto-folder"
        const val ID_AUTO_OPEN = "official.auto-open"
        const val ID_CUSTOM_UA = "official.custom-ua"
        const val ID_GLOBAL_BREAKER = "official.global-breaker"
        const val ID_DUAL_STACK = "official.dual-stack"
        const val ID_SIDECAR_HASH = "official.sidecar-hash"
        const val ID_URL_REWRITE = "official.url-rewrite"

        private const val UA = "DownloadGuard/1.4 (+https://github.com/dlguard/plugins)"
    }

    private val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val pluginDir = File(context.filesDir, "plugins").apply { mkdirs() }
    private val marketCache = File(pluginDir, ".market.json")

    /** 插件参数存储（详情页的动态表单写入这里）。 */
    val configStore = PluginConfigStore(context)

    /** 局域网下载服务：浏览器扩展把任务转发到本机下载器的通道。 */
    val lanServer = LanDownloadServer(
        context = context,
        portProvider = { bridgePort() }
    ) { url, name ->
        val center = downloadCenterProvider()
        if (center == null) {
            "下载中心尚未初始化"
        } else if (center.isBusy) {
            "已有下载任务进行中，请等当前任务完成（暂不支持排队）"
        } else {
            center.start(url, name)
            ""
        }
    }

    /** 官方内置插件定义。 */
    private val builtins: List<PluginManifest> = listOf(
        PluginManifest(
            id = ID_BROWSER_BRIDGE,
            name = "浏览器下载桥",
            version = "1.1.0",
            author = "DownloadGuard 官方",
            description = "开启后本机变成局域网下载服务器：电脑/手机的浏览器扩展把下载任务转发过来，" +
                "由内置 48 线程引擎加速，并在完成后自动做安装包完整性校验。插件页可一键导出扩展包。",
            category = PluginManifest.CATEGORY_ACCEL,
            icon = "🌉",
            repo = "dlguard/plugins",
            changelog = "1.1.0\n· 端口、访问令牌、开机自启全部可自定义\n· 配置改动即时重启监听，无需重装插件\n\n" +
                "1.0.0\n· 局域网接收 + Token 鉴权 + 单任务排队",
            pinned = true,
            enabledByDefault = false,
            config = listOf(
                PluginConfigField(
                    key = "port",
                    label = "监听端口",
                    type = PluginConfigField.TYPE_TEXT,
                    default = LanDownloadServer.PORT.toString(),
                    description = "1024~65535。改动后监听服务会自动重启；扩展里的服务器地址端口要同步改。"
                ),
                PluginConfigField(
                    key = "token",
                    label = "访问令牌",
                    type = PluginConfigField.TYPE_TEXT,
                    default = "",
                    description = "留空则自动生成 24 位随机令牌。改成一串自己记得住的字符，方便多台电脑共用。"
                ),
                PluginConfigField(
                    key = "autoStart",
                    label = "随 App 自动启动",
                    type = PluginConfigField.TYPE_TOGGLE,
                    default = "true",
                    description = "App 启动时按开关状态自动拉起监听；关闭后需手动在插件页开启一次。"
                )
            )
        ),
        PluginManifest(
            id = ID_DIRECT_SNIFF,
            name = "直链嗅探",
            version = "1.1.0",
            author = "DownloadGuard 官方",
            description = "从剪贴板、系统分享、浏览器复制文本中自动提取 http/https 直链并预填下载框，" +
                "支持 apk/apks/xapk/zip 与带 download 关键字的直链。",
            category = PluginManifest.CATEGORY_RESOLVER,
            icon = "🧲",
            repo = "dlguard/plugins",
            changelog = "1.1.0\n· 识别规则可自定义：扩展名与关键词都能改\n· 支持逗号分隔多条规则\n\n" +
                "1.0.0\n· 剪贴板 / 分享文本自动提取",
            pinned = true,
            enabledByDefault = true,
            config = listOf(
                PluginConfigField(
                    key = "extensions",
                    label = "识别扩展名",
                    type = PluginConfigField.TYPE_TEXT,
                    default = "apk,apks,xapk,zip",
                    description = "逗号分隔。链接路径以这些后缀结尾时判定为下载链接。"
                ),
                PluginConfigField(
                    key = "keywords",
                    label = "路径关键词",
                    type = PluginConfigField.TYPE_TEXT,
                    default = "download,apk,file,soft,xiazai",
                    description = "逗号分隔。形如 /download?id=… 没有扩展名的直链靠关键词命中；soft/xiazai 用于识别下载站页面。"
                )
            )
        ),
        PluginManifest(
            id = ID_AUTO_TUNE,
            name = "智能并发测速",
            version = "1.1.0",
            author = "DownloadGuard 官方",
            description = "首页亮出「自动测速」入口：从起始并发开始逐档实测吞吐，" +
                "收益到顶自动锁定最优并发数并写回设置。",
            category = PluginManifest.CATEGORY_ACCEL,
            icon = "📈",
            repo = "dlguard/plugins",
            changelog = "1.1.0\n· 起始并发、上限、判停阈值可调\n· 阶梯自动跳过超出上限的档位\n\n" +
                "1.0.0\n· 1→48 逐档实测，提速不足 10% 判停",
            pinned = true,
            enabledByDefault = true,
            config = listOf(
                PluginConfigField(
                    key = "start",
                    label = "起始并发",
                    type = PluginConfigField.TYPE_SLIDER,
                    default = "1",
                    min = 1,
                    max = 16,
                    step = 1,
                    description = "从几连接开始探。小带宽网络从 1 起步最准，大带宽可以直接从 4/8 起步省时间。"
                ),
                PluginConfigField(
                    key = "max",
                    label = "测速上限",
                    type = PluginConfigField.TYPE_SLIDER,
                    default = "48",
                    min = 2,
                    max = 48,
                    step = 2,
                    description = "最高探到几连接。超过 32 后多数服务器已无收益，但可以试。"
                ),
                PluginConfigField(
                    key = "gain",
                    label = "判停增益阈值",
                    type = PluginConfigField.TYPE_SLIDER,
                    default = "10",
                    min = 5,
                    max = 30,
                    step = 1,
                    description = "相对上一档提速不足该百分比即判定到顶。调大更省流量，调小更激进。"
                )
            )
        ),
        PluginManifest(
            id = ID_CLOUD_DIRECT,
            name = "网盘直链加速",
            version = "1.1.0",
            author = "DownloadGuard 官方",
            description = "对你自己合法持有的网盘直链（公开分享的 http 直链、自建网盘等）启用高并发加速。" +
                "本插件不含任何绕过登录、付费或服务方限速的解析逻辑；请在遵守服务条款与法律的前提下使用。",
            category = PluginManifest.CATEGORY_CLOUD,
            icon = "☁️",
            repo = "dlguard/plugins",
            changelog = "1.1.0\n· 加速并发可调，启用插件时自动应用到全局设置\n· 明确合规边界：只处理你有权访问的直链\n\n" +
                "1.0.0\n· 直链 48 线程加速开关",
            pinned = true,
            enabledByDefault = false,
            config = listOf(
                PluginConfigField(
                    key = "concurrency",
                    label = "加速并发数",
                    type = PluginConfigField.TYPE_SLIDER,
                    default = "32",
                    min = 1,
                    max = 48,
                    step = 1,
                    description = "启用本插件时自动把「设置 → 下载并发连接数」设为该值；关闭插件不会改回去。"
                )
            )
        ),
        PluginManifest(
            id = ID_SPEED_LIMIT,
            name = "限速卫士",
            version = "1.0.0",
            author = "DownloadGuard 官方",
            description = "给下载器加一个总带宽上限：48 条连接合计不超过设定值。" +
                "开着视频通话、打游戏、或者热点共享给别人时，下载不再把整条宽带吃干净。",
            category = PluginManifest.CATEGORY_ACCEL,
            icon = "🚦",
            repo = "dlguard/plugins",
            changelog = "1.0.0\n· 全局令牌桶限速，48 条连接共享一个额度\n· 限速值实时生效，无需重启下载",
            pinned = true,
            enabledByDefault = false,
            config = listOf(
                PluginConfigField(
                    key = "limit",
                    label = "总限速（KB/s）",
                    type = PluginConfigField.TYPE_SLIDER,
                    default = "1024",
                    min = 128,
                    max = 8192,
                    step = 128,
                    description = "所有连接加起来不超过这个速度。1024 KB/s 大约能同时看 4K 视频；" +
                        "设太小会明显拖慢下载，建议先设 2048 试。"
                )
            )
        ),
        PluginManifest(
            id = ID_SMART_RETRY,
            name = "智能重试",
            version = "1.0.0",
            author = "DownloadGuard 官方",
            description = "网络抖动、服务器偶发 5xx、连接被中途掐断时，按指数退避自动重试，" +
                "并且只重下失败的那一小块，不会从头再来。",
            category = PluginManifest.CATEGORY_TOOL,
            icon = "🔁",
            repo = "dlguard/plugins",
            changelog = "1.0.0\n· 重试次数可调（1~8 次）\n· 每个分块独立重试，块内最多 3 次\n· 退避间隔逐次翻倍",
            pinned = true,
            enabledByDefault = true,
            config = listOf(
                PluginConfigField(
                    key = "attempts",
                    label = "最多重试次数",
                    type = PluginConfigField.TYPE_SLIDER,
                    default = "4",
                    min = 1,
                    max = 8,
                    step = 1,
                    description = "整轮下载最多尝试几次。网络差的场景调到 6~8，代价是失败时等待更久。"
                )
            )
        ),
        PluginManifest(
            id = ID_DEDUPE,
            name = "重复文件跳过",
            version = "1.0.0",
            author = "DownloadGuard 官方",
            description = "下载前先看一眼目标目录：已有同名且大小完全一致、校验也通过的文件，就直接复用，" +
                "不再浪费流量重下一遍。适合经常回头补同一个安装包的场景。",
            category = PluginManifest.CATEGORY_TOOL,
            icon = "♻️",
            repo = "dlguard/plugins",
            changelog = "1.0.0\n· 同名 + 同大小 + 校验通过 → 直接复用\n· 大小不一致时仍然正常重下",
            pinned = true,
            enabledByDefault = false,
            config = emptyList()
        ),
        PluginManifest(
            id = ID_NAME_CLEAN,
            name = "文件名净化",
            version = "1.0.0",
            author = "DownloadGuard 官方",
            description = "把下载站塞进文件名里的广告去掉：「[www.xxx.com]App_v1.2.apk」变成「App_v1.2.apk」，" +
                "「软件名_绿色版_下载之家.apk」变成「软件名.apk」。",
            category = PluginManifest.CATEGORY_TOOL,
            icon = "🧼",
            repo = "dlguard/plugins",
            changelog = "1.0.0\n· 去掉方括号/圆括号包裹的站点广告\n· 去掉尾部 _www.xxx.com 与「下载之家」之类后缀",
            pinned = true,
            enabledByDefault = true,
            config = emptyList()
        ),
        PluginManifest(
            id = ID_AUTO_FOLDER,
            name = "自动归档",
            version = "1.0.0",
            author = "DownloadGuard 官方",
            description = "按文件类型自动放进子目录：安装包进 Apps、压缩包进 Archives、视频进 Videos、" +
                "图片进 Pictures、文档进 Documents，其余进 Others。下载目录不再是一锅粥。",
            category = PluginManifest.CATEGORY_TOOL,
            icon = "🗂️",
            repo = "dlguard/plugins",
            changelog = "1.0.0\n· 六类归档：Apps / Archives / Videos / Audio / Pictures / Documents / Others\n· 目录不存在自动创建，创建失败回退到下载根目录",
            pinned = true,
            enabledByDefault = false,
            config = emptyList()
        ),
        PluginManifest(
            id = ID_AUTO_OPEN,
            name = "完成即打开",
            version = "1.0.0",
            author = "DownloadGuard 官方",
            description = "校验通过后立刻拉起对应的应用：安装包直接进安装界面，视频用播放器打开，" +
                "PDF 用阅读器打开。省掉「下载完 → 回桌面 → 找文件 → 点开」四步。",
            category = PluginManifest.CATEGORY_TOOL,
            icon = "🚀",
            repo = "dlguard/plugins",
            changelog = "1.0.0\n· 仅在校验通过后触发\n· 没有能打开该类型的应用时静默跳过",
            pinned = true,
            enabledByDefault = false,
            config = emptyList()
        ),
        PluginManifest(
            id = ID_CUSTOM_UA,
            name = "UA 伪装",
            version = "1.0.0",
            author = "DownloadGuard 官方",
            description = "自定义请求的 User-Agent。有些站点对非浏览器 UA 直接限速或返回错误页，" +
                "换成桌面版 Chrome 的 UA 往往就能拿到正常速度。",
            category = PluginManifest.CATEGORY_RESOLVER,
            icon = "🎭",
            repo = "dlguard/plugins",
            changelog = "1.0.0\n· 探测与分块请求统一使用自定义 UA\n· 留空则回退到内置移动端 Chrome UA",
            pinned = true,
            enabledByDefault = false,
            config = listOf(
                PluginConfigField(
                    key = "ua",
                    label = "User-Agent",
                    type = PluginConfigField.TYPE_TEXT,
                    default = "",
                    description = "留空 = 内置移动端 Chrome UA。想伪装成电脑版可填：" +
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
            )
        ),
        PluginManifest(
            id = ID_GLOBAL_BREAKER,
            name = "全局限流熔断",
            version = "1.0.0",
            author = "DownloadGuard 官方",
            description = "同一个站点开多个任务时，任何一个任务撞上 403/429，所有任务一起降并发并短暂冷却。" +
                "避免「任务 A 已经老实退让、任务 B 还在 256 并发猛冲」把自己这个 IP 彻底送走。",
            category = PluginManifest.CATEGORY_ACCEL,
            icon = "🧯",
            repo = "dlguard/plugins",
            changelog = "1.0.0\n· 按 host 全局记账，跨任务共享并发上限\n· 429 阶梯减半 + 冷却，成功若干次后自动恢复",
            pinned = true,
            enabledByDefault = true,
            config = emptyList()
        ),
        PluginManifest(
            id = ID_DUAL_STACK,
            name = "IPv4/IPv6 双栈竞速",
            version = "1.0.0",
            author = "DownloadGuard 官方",
            description = "解析出多个地址后并发做一次 TCP 探测，按实测延迟排序再连接。" +
                "专门治移动网络下「IPv6 有地址没路由」导致首包卡十几秒的问题。",
            category = PluginManifest.CATEGORY_ACCEL,
            icon = "🛰",
            repo = "dlguard/plugins",
            changelog = "1.0.0\n· 解析结果按 RTT 排序，黑洞地址自动排到最后\n· 结果缓存 60 秒，切网自动失效",
            pinned = true,
            enabledByDefault = true,
            config = emptyList()
        ),
        PluginManifest(
            id = ID_SIDECAR_HASH,
            name = "旁挂哈希自动校验",
            version = "1.0.0",
            author = "DownloadGuard 官方",
            description = "下载地址旁边通常躺着同名的 .sha256 / .md5 小文件。开启后自动拉取并解析，" +
                "把它当作本次下载的官方基准，完成后直接告诉你「一致」还是「不符」。",
            category = PluginManifest.CATEGORY_TOOL,
            icon = "🔖",
            repo = "dlguard/plugins",
            changelog = "1.0.0\n· 支持 .sha256/.sha256sum/.md5/.sha1 四种命名\n· 校验文件里写的是别的文件名时自动放弃",
            pinned = true,
            enabledByDefault = true,
            config = emptyList()
        ),
        PluginManifest(
            id = ID_URL_REWRITE,
            name = "URL 重写脚本",
            version = "1.0.0",
            author = "DownloadGuard 官方",
            description = "用一段沙箱 JS 改写下载地址，例如把缩略图换成原图、去掉跟踪参数。" +
                "脚本只有字符串运算能力：无网络、无文件、无反射，200ms 超时。默认关闭，可自行编辑。",
            category = PluginManifest.CATEGORY_RESOLVER,
            icon = "✂️",
            repo = "dlguard/plugins",
            changelog = "1.0.0\n· Rhino 沙箱 + 指令计数超时 + ClassShutter\n· 内置示例：清理 utm_/spm/from 等跟踪参数",
            pinned = true,
            enabledByDefault = false,
            script = "out = url.replace(/([?&])(utm_[a-z_]+|spm|from|share_token)=[^&]*/gi, '\$1')" +
                ".replace(/[?&]\$/g, '').replace(/\\?&/g, '?');",
            config = listOf(
                PluginConfigField(
                    key = "script",
                    label = "重写脚本",
                    type = PluginConfigField.TYPE_TEXT,
                    default = "",
                    description = "可用变量 url，赋值 out 或返回最后表达式即新地址。留空使用内置示例。" +
                        "脚本拿不到网络/文件/Java 对象，只做字符串变换。"
                )
            )
        )
    ).let { list ->
        // 商店版把两类高风险能力从清单里彻底移除：
        //  - URL 重写脚本：执行用户可编辑的 JS（动态代码）
        //  - 浏览器下载桥：监听局域网端口接收外部任务（本地服务）
        if (com.dlguard.BuildConfig.STORE_BUILD) {
            list.filter { it.id != ID_BROWSER_BRIDGE && it.id != ID_URL_REWRITE }
        } else {
            list
        }
    }

    private val _plugins = MutableStateFlow<List<Plugin>>(emptyList())
    val plugins = _plugins.asStateFlow()

    /** 市场列表：官方插件置顶 + 远程索引。 */
    private val _market = MutableStateFlow<List<PluginManifest>>(builtins)
    val marketPlugins = _market.asStateFlow()

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds = _favorites.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    init {
        _favorites.value = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        reloadInstalled()
        loadCachedMarket()
    }

    // ------------------------------------------------------------ 状态查询

    fun isEnabled(id: String): Boolean {
        val builtin = builtins.firstOrNull { it.id == id }
        if (builtin != null) return prefs.getBoolean("enabled_$id", builtin.enabledByDefault)
        return _plugins.value.firstOrNull { it.manifest.id == id }?.enabled == true
    }

    /** 按 id 找到清单（官方插件或已安装的第三方插件）。 */
    fun manifestOf(id: String): PluginManifest? =
        builtins.firstOrNull { it.id == id }
            ?: _plugins.value.firstOrNull { it.manifest.id == id }?.manifest

    // ------------------------------------------------------------ 插件配置

    /** 合并默认值后的配置表（详情页动态表单的数据源）。 */
    fun config(id: String): Map<String, String> {
        val manifest = manifestOf(id) ?: return emptyMap()
        return configStore.values(manifest)
    }

    fun configValue(id: String, key: String, fallback: String = ""): String =
        config(id)[key]?.takeIf { it.isNotBlank() } ?: fallback

    /**
     * 把已启用插件的配置聚合成下载策略。
     *
     * 这里是「插件真的有用」的关键：插件不是一段说明文字，而是直接改写下载器的
     * 限速、重试、去重、改名、归档行为。关掉插件即恢复默认，不需要重启 App。
     */
    fun downloadPolicy(): com.dlguard.download.DownloadPolicy {
        val limitKb = if (isEnabled(ID_SPEED_LIMIT)) {
            configValue(ID_SPEED_LIMIT, "limit", "1024").toIntOrNull()?.coerceIn(128, 8192) ?: 1024
        } else {
            0
        }
        val attempts = if (isEnabled(ID_SMART_RETRY)) {
            configValue(ID_SMART_RETRY, "attempts", "4").toIntOrNull()?.coerceIn(1, 8) ?: 4
        } else {
            4
        }
        val ua = if (isEnabled(ID_CUSTOM_UA)) {
            configValue(ID_CUSTOM_UA, "ua", "").trim()
        } else {
            ""
        }
        return com.dlguard.download.DownloadPolicy(
            speedLimit = limitKb.toLong() * 1024L,
            maxAttempts = attempts,
            skipDuplicate = isEnabled(ID_DEDUPE),
            cleanName = isEnabled(ID_NAME_CLEAN),
            autoFolder = isEnabled(ID_AUTO_FOLDER),
            autoOpen = isEnabled(ID_AUTO_OPEN),
            userAgent = ua,
            globalBreaker = isEnabled(ID_GLOBAL_BREAKER),
            sidecarHash = isEnabled(ID_SIDECAR_HASH)
        )
    }

    /**
     * 按当前启用插件把 URL 交给沙箱脚本重写；没有匹配的插件时返回 null。
     *
     * 只跑第一个「匹配且改出了不同结果」的脚本，避免多个插件互相覆盖导致行为不可预期。
     */
    fun rewriteUrl(url: String): String? {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return null
        val manifests = builtins + _plugins.value.map { it.manifest }
        for (manifest in manifests) {
            if (!isEnabled(manifest.id)) continue
            if (!manifest.matchesHost(host)) continue
            val script = configValue(manifest.id, "script").takeIf { it.isNotBlank() }
                ?: manifest.script.takeIf { it.isNotBlank() }
                ?: continue
            val result = ScriptSandbox.rewrite(script, url)
            val rewritten = result.url
            if (rewritten != null && rewritten != url) {
                Log.i(TAG, "URL 重写：${manifest.id} → ${scriptHost(rewritten)}")
                return rewritten
            }
            if (result.error != null) Log.w(TAG, "脚本插件 ${manifest.id} 执行失败：${result.error}")
        }
        return null
    }

    private fun PluginManifest.matchesHost(host: String): Boolean {
        if (matchHosts.isEmpty()) return true
        return matchHosts.any { rule ->
            val normalized = rule.lowercase().removePrefix(".").removePrefix("*.")
            host == normalized || host.endsWith(".$normalized")
        }
    }

    private fun scriptHost(url: String): String =
        runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

    /** 写入一项配置；桥插件的端口/令牌变化会即时重启监听。 */
    fun setConfig(id: String, key: String, value: String) {
        configStore.set(id, key, value)
        onConfigChanged(id, key)
    }

    fun resetConfig(id: String) {
        configStore.reset(id)
        onConfigChanged(id, null)
    }

    fun isConfigCustomized(id: String): Boolean = configStore.isCustomized(id)

    /**
     * 导出市场索引模板：开源时把它放进仓库根目录并改名为 `index.json`，
     * 市场源填 `owner/repo` 即可被 App 直接读取。
     */
    fun exportMarketTemplate(): File? = runCatching {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "market")
        dir.mkdirs()
        val out = File(dir, "index.json")
        context.assets.open("market-template.json").use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        out
    }.getOrNull()

    private fun onConfigChanged(id: String, key: String?) {
        if (id == ID_BROWSER_BRIDGE && (key == null || key == "port" || key == "token")) {
            // 端口/令牌变更必须重新 bind，否则扩展还连着旧端口
            if (isEnabled(ID_BROWSER_BRIDGE) && lanServer.isRunning) {
                runCatching { lanServer.restart() }
            }
        }
        reloadInstalled()
    }

    /** 浏览器下载桥端口（来自插件配置，越界回退默认值）。 */
    fun bridgePort(): Int = configValue(ID_BROWSER_BRIDGE, "port", LanDownloadServer.PORT.toString())
        .toIntOrNull()
        ?.coerceIn(1024, 65535)
        ?: LanDownloadServer.PORT

    /** 属性变化时刷新：桥插件开关联动局域网服务。 */
    private fun applyBridge() {
        val enabled = isEnabled(ID_BROWSER_BRIDGE)
        if (com.dlguard.BuildConfig.STORE_BUILD) return
        if (enabled && !lanServer.isRunning) {
            if (!lanServer.start()) {
                prefs.edit().putBoolean("enabled_$ID_BROWSER_BRIDGE", false).apply()
            }
        } else if (!enabled && lanServer.isRunning) {
            lanServer.stop()
        }
        reloadInstalled()
    }

    fun setEnabled(id: String, value: Boolean) {
        prefs.edit().putBoolean("enabled_$id", value).apply()
        if (id == ID_CLOUD_DIRECT && value) {
            // 网盘加速插件启用时把配置的并发数真正写进下载设置
            configValue(id, "concurrency", "32")
                .toIntOrNull()
                ?.coerceIn(1, 48)
                ?.let(onApplyConcurrency)
        }
        applyBridge()
    }

    fun toggleFavorite(id: String) {
        val next = _favorites.value.toMutableSet().apply {
            if (!add(id)) remove(id)
        }
        _favorites.value = next
        prefs.edit().putStringSet("favorites", next).apply()
    }

    // ------------------------------------------------------------ 安装 / 卸载

    private fun installedDir(id: String) = File(pluginDir, id)

    fun reloadInstalled() {
        val list = builtins.map { manifest ->
            Plugin(
                manifest = manifest,
                builtin = true,
                enabled = prefs.getBoolean("enabled_${manifest.id}", manifest.enabledByDefault)
            )
        } + pluginDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { dir ->
                val manifest = readManifest(File(dir, "manifest.json")) ?: return@mapNotNull null
                Plugin(
                    manifest = manifest,
                    builtin = false,
                    enabled = prefs.getBoolean("enabled_${manifest.id}", manifest.enabledByDefault),
                    installedAt = ManifestClock.installedAt(dir, prefs),
                    sizeBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                )
            }
            .sortedByDescending { it.installedAt }
        // 商店版不接受第三方插件：即使从完整版覆盖安装、磁盘上还留着旧插件，
        // 也不加载、不执行，避免动态代码绕过商店审核
        if (com.dlguard.BuildConfig.STORE_BUILD) {
            _plugins.value = emptyList()
            return
        }
        _plugins.value = list
        // 桥插件状态以本地开关为准做一次对齐（进程重启后服务需要按开关恢复）
        if (!com.dlguard.BuildConfig.STORE_BUILD &&
            isEnabled(ID_BROWSER_BRIDGE) &&
            configValue(ID_BROWSER_BRIDGE, "autoStart", "true").toBoolean() &&
            !lanServer.isRunning
        ) {
            runCatching { lanServer.start() }
        }
    }

    fun uninstall(id: String) {
        if (builtins.any { it.id == id }) return
        installedDir(id).deleteRecursively()
        prefs.edit().remove("enabled_$id").apply()
        reloadInstalled()
    }

    /** 从 zip 字节安装插件（市场下载与本地文件共用）。返回错误信息，空串表示成功。 */
    fun installFromBytes(bytes: ByteArray, source: String = "本地文件"): String {
        if (com.dlguard.BuildConfig.STORE_BUILD) {
            return "商店版不支持安装第三方插件"
        }
        return try {
            val entries = LinkedHashMap<String, ByteArray>()
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                var total = 0L
                while (entry != null) {
                    val name = entry.name.replace('\\', '/')
                    if (name.contains("..")) return "插件包包含非法路径：$name"
                    if (!entry.isDirectory && !name.startsWith("__MACOSX/") && !name.endsWith(".DS_Store")) {
                        // 注意：ZipInputStream 不能用 readBytes()（会吞掉后续所有 entry），
                        // 必须逐个 entry 读到当前流末尾为止。
                        val buffer = ByteArray(8192)
                        val out = java.io.ByteArrayOutputStream()
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            if (out.size() > 8 * 1024 * 1024) return "单个文件超过 8MB 上限：$name"
                        }
                        val data = out.toByteArray()
                        total += data.size
                        if (total > 32L * 1024 * 1024) return "插件包超过 32MB 上限"
                        entries[name] = data
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            // 兼容 GitHub 仓库 zip：manifest.json 可能位于 `<repo>-main/` 这一层
            val manifestEntry = when {
                entries.containsKey("manifest.json") -> "manifest.json"
                else -> entries.keys.firstOrNull {
                    it.endsWith("/manifest.json") && it.count { c -> c == '/' } == 1
                }
            } ?: return "插件包缺少 manifest.json（需位于根目录或单层子目录）"
            val prefix = manifestEntry.removeSuffix("manifest.json")
            val raw = String(entries.getValue(manifestEntry), Charsets.UTF_8)
            val manifest = parseManifest(JSONObject(raw)) ?: return "manifest.json 格式不合法（至少需要 id / name）"
            if (manifest.id.isBlank()) return "manifest.json 缺少 id"
            if (builtins.any { it.id == manifest.id }) return "插件 id 与官方插件冲突：${manifest.id}"

            val dir = installedDir(manifest.id)
            dir.deleteRecursively()
            dir.mkdirs()
            entries.forEach { (name, data) ->
                val relative = name.removePrefix(prefix)
                if (relative.isBlank()) return@forEach
                val target = File(dir, relative)
                if (!target.canonicalPath.startsWith(dir.canonicalPath)) return "插件包路径越界"
                target.parentFile?.mkdirs()
                target.writeBytes(data)
            }
            if (!File(dir, "manifest.json").exists()) File(dir, "manifest.json").writeText(raw)
            prefs.edit()
                .putBoolean("enabled_${manifest.id}", manifest.enabledByDefault)
                .putLong("installed_at_${manifest.id}", System.currentTimeMillis())
                .apply()
            writeProvenance(dir, source, bytes)
            reloadInstalled()
            ""
        } catch (e: Exception) {
            Log.w(TAG, "install failed", e)
            "安装失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * 记录插件包的来源与内容摘要。
     *
     * 插件本身是**声明式配置**（manifest.json + 资源），不能执行代码，所以没有
     * 「插件拿到本应用权限」这条攻击面。真正需要防的是「中途被换包」：
     * 把安装时的来源 URL 与 zip 的 SHA-256 落盘，用户事后可以核对
     * 「我现在跑的插件，是不是当初从那个地址装的那一份」。
     */
    private fun writeProvenance(dir: File, source: String, bytes: ByteArray) {
        runCatching {
            val sha = java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            File(dir, "provenance.txt").writeText(
                buildString {
                    appendLine("source=$source")
                    appendLine("sha256=$sha")
                    appendLine("bytes=${bytes.size}")
                    appendLine("installedAt=${System.currentTimeMillis()}")
                }
            )
        }
    }

    /** 读取插件的安装来源（source / sha256 / bytes / installedAt），无记录返回空。 */
    fun provenance(id: String): Map<String, String> {
        val file = File(installedDir(id), "provenance.txt")
        if (!file.exists()) return emptyMap()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val at = line.indexOf('=')
                if (at <= 0) null else line.substring(0, at) to line.substring(at + 1)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun installFromUrl(url: String, onDone: (String) -> Unit) {
        if (com.dlguard.BuildConfig.STORE_BUILD) {
            onDone("商店版不支持在线安装插件")
            return
        }
        scope.launch {
            onDone(downloadAndInstall(url))
        }
    }

    /** 从 GitHub 仓库整包安装：支持 `owner/repo`、仓库页地址或 zip 直链。 */
    fun installFromGithub(input: String, onDone: (String) -> Unit) {
        if (com.dlguard.BuildConfig.STORE_BUILD) {
            onDone("商店版不支持在线安装插件")
            return
        }
        val zip = MarketSource.zipUrl(input)
        if (zip == null) {
            onDone("无法识别仓库地址，请填 owner/repo（例如 dlguard/plugins）")
            return
        }
        scope.launch {
            var message = downloadAndInstall(zip)
            if (message.contains("HTTP 404")) {
                val fallback = MarketSource.fallbackZipUrl(zip)
                if (fallback != null) message = downloadAndInstall(fallback)
            }
            if (message.isBlank()) {
                val repo = MarketSource.repoOf(input) ?: input
                message = "安装成功：$repo"
            }
            onDone(message)
        }
    }

    private fun downloadAndInstall(url: String): String = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching "下载插件包失败：HTTP ${response.code}"
            val body = response.body?.bytes() ?: return@runCatching "插件包为空"
            if (body.size > 32 * 1024 * 1024) return@runCatching "插件包超过 32MB 上限"
            installFromBytes(body, source = url)
        }
    }.getOrElse { "下载插件包失败：${it.message ?: it.javaClass.simpleName}" }

    /**
     * 用一段 manifest JSON 直接创建插件（高度自定义入口）。
     *
     * 适合两类场景：自己写一个只含规则/配置的轻量插件；把别人分享的清单粘进来快速安装。
     */
    fun createFromJson(json: String): String {
        val text = json.trim()
        if (text.isBlank()) return "请粘贴 manifest JSON"
        return try {
            val obj = JSONObject(text)
            val manifest = parseManifest(obj) ?: return "JSON 不合法：至少需要 id 与 name"
            if (builtins.any { it.id == manifest.id }) return "插件 id 与官方插件冲突：${manifest.id}"
            if (!manifest.id.matches(Regex("[A-Za-z0-9_.-]{2,64}"))) {
                return "id 只能包含字母/数字/下划线/点/横线（2~64 位）"
            }
            val dir = installedDir(manifest.id)
            dir.mkdirs()
            File(dir, "manifest.json").writeText(obj.toString(2))
            prefs.edit()
                .putBoolean("enabled_${manifest.id}", manifest.enabledByDefault)
                .putLong("installed_at_${manifest.id}", System.currentTimeMillis())
                .apply()
            // 手写 JSON 的插件没有 zip 包，摘要按清单文本本身算
            writeProvenance(dir, source = "手动创建（JSON）", bytes = text.toByteArray())
            reloadInstalled()
            ""
        } catch (e: Exception) {
            "JSON 解析失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ------------------------------------------------------------ 市场索引

    /**
     * 市场源：可填 `owner/repo`、GitHub 仓库地址或任意 index.json 直链。
     * 默认指向开源仓库内的索引，`main` 不存在时自动回退 `master`。
     */
    var marketUrl: String
        get() = if (prefs.contains("market_url")) {
            prefs.getString("market_url", "").orEmpty()
        } else {
            MarketSource.DEFAULT_INDEX
        }
        set(value) {
            prefs.edit().putString("market_url", value.trim()).apply()
        }

    private val _marketUpdatedAt = MutableStateFlow(prefs.getLong("market_updated_at", 0L))
    val marketUpdatedAt = _marketUpdatedAt.asStateFlow()

    /** 是否已经配置过市场源（空串表示用户主动只看官方插件）。 */
    fun hasMarketSource(): Boolean = marketUrl.isNotBlank()

    /**
     * 市场索引格式（随 GitHub 仓库分发，可指向任意静态 JSON）：
     * ```json
     * { "plugins": [ { "id": "...", "name": "...", "version": "1.0",
     *   "author": "...", "description": "...", "category": "下载加速",
     *   "icon": "🔌", "downloadUrl": "https://.../x.zip",
     *   "homepage": "https://...", "repo": "owner/repo",
     *   "changelog": "1.0 首个版本",
     *   "permissions": ["network"],
     *   "config": [ { "key": "threads", "label": "线程数", "type": "slider",
     *                 "default": "16", "min": 1, "max": 48 } ] } ] }
     * ```
     */
    fun refreshMarket(onDone: (String) -> Unit = {}) {
        if (com.dlguard.BuildConfig.STORE_BUILD) {
            _market.value = builtins
            onDone("商店版仅包含官方内置能力，不提供在线插件市场")
            return
        }
        val input = marketUrl.trim()
        if (input.isBlank()) {
            _market.value = builtins
            onDone("未配置市场源，仅显示官方插件")
            return
        }
        val url = MarketSource.indexUrl(input)
        if (url == null) {
            onDone("无法识别市场源，请填 owner/repo 或 index.json 地址")
            return
        }
        if (_refreshing.value) return
        _refreshing.value = true
        scope.launch {
            val message = runCatching { fetchMarket(url) }
                .getOrElse { "市场索引拉取失败：${it.message ?: it.javaClass.simpleName}" }
            _refreshing.value = false
            onDone(message)
        }
    }

    private fun fetchMarket(url: String): String {
        fun open(target: String): Response = client.newCall(
            Request.Builder().url(target).header("User-Agent", UA).build()
        ).execute()

        open(url).use { response ->
            // main 分支不存在是开源仓库最常见的首次配置问题，自动换 master 再试一次
            if (response.code == 404) {
                val fallback = MarketSource.fallbackIndexUrl(url)
                if (fallback != null) return open(fallback).use { consumeMarket(it, fallback) }
            }
            return consumeMarket(response, url)
        }
    }

    private fun consumeMarket(response: Response, url: String): String {
        if (response.code == 404 && url.contains(MarketSource.DEFAULT_REPO)) {
            return "默认示例市场还没发布（HTTP 404）。点右上角齿轮，把市场源换成你自己的 GitHub 仓库，格式 owner/repo"
        }
        if (!response.isSuccessful) return "市场索引拉取失败：HTTP ${response.code}"
        val text = response.body?.string() ?: return "市场索引为空"
        marketCache.writeText(text)
        val now = System.currentTimeMillis()
        prefs.edit().putLong("market_updated_at", now).apply()
        _marketUpdatedAt.value = now
        applyMarketJson(text)
        val count = _market.value.count { !it.pinned }
        val source = MarketSource.pretty(url)
        return "市场已刷新：$count 个第三方插件（$source）"
    }

    private fun loadCachedMarket() {
        runCatching {
            if (marketCache.exists()) applyMarketJson(marketCache.readText())
        }
    }

    private fun applyMarketJson(text: String) {
        val root = JSONObject(text)
        val array = root.optJSONArray("plugins") ?: JSONArray()
        val remote = (0 until array.length()).mapNotNull { i ->
            runCatching { parseManifest(array.getJSONObject(i)) }.getOrNull()
        }.filter { m -> m.id.isNotBlank() && builtins.none { it.id == m.id } }
        _market.value = builtins + remote
    }

    private fun parseManifest(obj: JSONObject): PluginManifest? {
        val id = obj.optString("id").trim()
        val name = obj.optString("name").trim()
        if (id.isBlank() || name.isBlank()) return null
        val permissions = obj.optJSONArray("permissions")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
        } ?: emptyList()
        return PluginManifest(
            id = id,
            name = name,
            version = obj.optString("version", "1.0.0"),
            author = obj.optString("author", "未知作者"),
            description = obj.optString("description", ""),
            category = obj.optString("category", PluginManifest.CATEGORY_TOOL)
                .takeIf { it in PluginManifest.CATEGORIES } ?: PluginManifest.CATEGORY_TOOL,
            icon = obj.optString("icon", "🧩").take(4),
            homepage = obj.optString("homepage", ""),
            repo = obj.optString("repo", ""),
            changelog = obj.optString("changelog", ""),
            downloadUrl = obj.optString("downloadUrl", ""),
            permissions = permissions,
            config = parseConfig(obj.optJSONArray("config")),
            script = obj.optString("script", "").takeIf { it.isNotBlank() } ?: "",
            matchHosts = obj.optJSONArray("matchHosts")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).trim().takeIf { it.isNotBlank() } }
            } ?: listOfNotNull(obj.optString("matchHost").trim().takeIf { it.isNotBlank() }),
            pinned = obj.optBoolean("pinned", false),
            enabledByDefault = obj.optBoolean("enabledByDefault", false)
        )
    }

    /** 解析配置 schema；非法条目直接跳过，不影响插件本身安装。 */
    private fun parseConfig(array: JSONArray?): List<PluginConfigField> {
        if (array == null) return emptyList()
        val allowed = setOf(
            PluginConfigField.TYPE_TOGGLE,
            PluginConfigField.TYPE_SLIDER,
            PluginConfigField.TYPE_TEXT,
            PluginConfigField.TYPE_CHOICE
        )
        return (0 until array.length()).mapNotNull { i ->
            val obj = runCatching { array.getJSONObject(i) }.getOrNull() ?: return@mapNotNull null
            val key = obj.optString("key").trim()
            val label = obj.optString("label").trim()
            if (key.isBlank() || label.isBlank()) return@mapNotNull null
            val type = obj.optString("type", PluginConfigField.TYPE_TOGGLE)
                .takeIf { it in allowed } ?: PluginConfigField.TYPE_TOGGLE
            PluginConfigField(
                key = key,
                label = label,
                type = type,
                description = obj.optString("description", ""),
                default = obj.optString("default", ""),
                min = obj.optInt("min", 0),
                max = obj.optInt("max", 48),
                step = obj.optInt("step", 1).coerceAtLeast(1),
                options = obj.optJSONArray("options")?.let { arr ->
                    (0 until arr.length()).mapNotNull { idx ->
                        arr.optString(idx).takeIf { it.isNotBlank() }
                    }
                } ?: emptyList()
            )
        }
    }

    private fun readManifest(file: File): PluginManifest? = runCatching {
        if (!file.exists()) return null
        parseManifest(JSONObject(file.readText()))
    }.getOrNull()

    /** 安装时间读取的小工具（避免在插件类里散落 prefs 细节）。 */
    private object ManifestClock {
        fun installedAt(dir: File, prefs: android.content.SharedPreferences): Long =
            prefs.getLong("installed_at_${dir.name}", dir.lastModified())
    }
}

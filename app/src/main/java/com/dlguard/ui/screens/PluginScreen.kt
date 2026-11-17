package com.dlguard.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dlguard.plugin.BrowserExtensionHelper
import com.dlguard.plugin.MarketSource
import com.dlguard.plugin.Plugin
import com.dlguard.plugin.PluginConfigField
import com.dlguard.plugin.PluginManager
import com.dlguard.plugin.PluginManifest
import com.dlguard.ui.PluginRow
import com.dlguard.ui.PluginViewModel
import com.dlguard.ui.components.GlassCard
import com.dlguard.ui.components.GuardButton
import com.dlguard.ui.components.GuardButtonStyle
import com.dlguard.ui.components.JellySwitch
import com.dlguard.ui.theme.Danger
import com.dlguard.ui.theme.Success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 插件页：市场 / 收藏 / 我的 三视图 + 分类筛选 + 搜索 + 详情页 + 浏览器扩展入口。
 *
 * 市场内容与 App 本体解耦：官方插件（置顶）提供真实通用能力，
 * 第三方插件通过 GitHub 上的索引 JSON 分发，App 只负责安装、启停、配置与列表展示。
 */
@Composable
fun PluginScreen(
    viewModel: PluginViewModel,
    onMessage: (String) -> Unit,
    bottomInset: Dp
) {
    val context = LocalContext.current
    val plugins by viewModel.plugins.collectAsStateWithLifecycle()
    val market by viewModel.market.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val marketUrl by viewModel.marketUrl.collectAsStateWithLifecycle()
    val updatedAt by viewModel.marketUpdatedAt.collectAsStateWithLifecycle()

    var detailId by remember { mutableStateOf<String?>(null) }
    var showExtension by remember { mutableStateOf(false) }
    var showMarketConfig by remember { mutableStateOf(false) }
    var showGithubInstall by remember { mutableStateOf(false) }
    var showCustom by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            when {
                bytes == null -> onMessage("读取插件包失败")
                bytes.size > 32 * 1024 * 1024 -> onMessage("插件包超过 32MB 上限")
                else -> {
                    val result = withContext(Dispatchers.IO) { viewModel.installLocal(bytes) }
                    onMessage(result.ifBlank { "插件安装成功" })
                }
            }
        }
    }

    val rows = remember(plugins, market, favorites, filter, query) {
        viewModel.rows(plugins, market, favorites, filter, query)
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "插件",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "能力以插件形式扩展，本体保持轻与中立",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
                IconButton(onClick = { showMarketConfig = true }) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "市场设置",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                IconButton(onClick = { showExtension = true }) {
                    Text(text = "🌐", fontSize = 20.sp)
                }
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.query.value = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = "搜索插件名称、作者、关键词",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                textStyle = MaterialTheme.typography.bodySmall,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )
        }

        item {
            val chips = listOf(
                PluginViewModel.FILTER_ALL,
                PluginViewModel.FILTER_FAVORITE,
                PluginViewModel.FILTER_MINE
            ) + PluginManifest.CATEGORIES
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                chips.forEach { label ->
                    val selected = filter == label
                    Box(
                        Modifier
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                RoundedCornerShape(50)
                            )
                            .clickable { viewModel.filter.value = label }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        // 市场源状态：一眼看出接的是不是 GitHub、上次什么时候拉的
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = MarketSource.pretty(marketUrl),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    modifier = Modifier.weight(1f)
                )
                if (updatedAt > 0) {
                    Text(
                        text = "更新于 " + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(updatedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                }
            }
        }

        if (rows.isEmpty()) {
            item {
                GlassCard(contentPadding = PaddingValues(18.dp)) {
                    Text(
                        text = when (filter) {
                            PluginViewModel.FILTER_MINE -> "还没有安装第三方插件。去「全部」里看看吧。"
                            PluginViewModel.FILTER_FAVORITE -> "还没有收藏插件。点插件卡片右上角的星标即可收藏。"
                            else -> "没有匹配的插件。可点右上角齿轮配置 GitHub 市场源，或调整搜索词。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
            }
        }

        items(rows, key = { it.manifest.id }) { row ->
            PluginCard(
                row = row,
                onOpen = { detailId = row.manifest.id },
                onToggle = { viewModel.toggleEnabled(row.manifest.id, it) },
                onInstall = { viewModel.install(row.manifest, onMessage) },
                onFavorite = { viewModel.toggleFavorite(row.manifest.id) }
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { viewModel.refreshMarket(onMessage) }) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (refreshing) "拉取中…" else "刷新市场",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        item {
            Spacer(
                Modifier
                    .height(bottomInset + 8.dp)
                    .navigationBarsPadding()
            )
        }
    }

    detailId?.let { id ->
        PluginDetailDialog(
            viewModel = viewModel,
            pluginId = id,
            plugins = plugins,
            market = market,
            favorites = favorites,
            onDismiss = { detailId = null },
            onMessage = onMessage
        )
    }

    if (showExtension) {
        val bridgeEnabled = plugins.firstOrNull { it.manifest.id == PluginManager.ID_BROWSER_BRIDGE }?.enabled == true
        val manager = (context.applicationContext as? com.dlguard.DownloadGuardApp)?.container?.pluginManager
        BrowserExtensionDialog(
            context = context,
            bridgeEnabled = bridgeEnabled,
            address = manager?.lanServer?.localAddress(),
            port = manager?.bridgePort() ?: 8787,
            token = manager?.lanServer?.token.orEmpty(),
            onEnableBridge = { viewModel.toggleEnabled(PluginManager.ID_BROWSER_BRIDGE, true) },
            onDismiss = { showExtension = false },
            onMessage = onMessage
        )
    }

    if (showMarketConfig) {
        MarketConfigDialog(
            current = marketUrl,
            updatedAt = updatedAt,
            onDismiss = { showMarketConfig = false },
            onSave = {
                viewModel.saveMarketUrl(it, onMessage)
                showMarketConfig = false
            },
            onGithubInstall = {
                showMarketConfig = false
                showGithubInstall = true
            },
            onCustom = {
                showMarketConfig = false
                showCustom = true
            },
            onExportTemplate = {
                val file = (context.applicationContext as? com.dlguard.DownloadGuardApp)
                    ?.container?.pluginManager?.exportMarketTemplate()
                if (file == null) {
                    onMessage("导出失败：模板资源缺失")
                } else {
                    runCatching {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "DownloadGuard 插件市场 index.json 模板")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "把市场模板发到电脑"))
                    }.onFailure { onMessage("分享失败：${it.message}") }
                }
            },
            onPickZip = { zipPicker.launch("*/*") }
        )
    }

    if (showGithubInstall) {
        GithubInstallDialog(
            onDismiss = { showGithubInstall = false },
            onConfirm = { input ->
                showGithubInstall = false
                viewModel.installFromGithub(input, onMessage)
            }
        )
    }

    if (showCustom) {
        CustomPluginDialog(
            onDismiss = { showCustom = false },
            onConfirm = { json ->
                showCustom = false
                viewModel.createCustom(json, onMessage)
            }
        )
    }
}

// ================================================================ 卡片

@Composable
private fun PluginCard(
    row: PluginRow,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onFavorite: () -> Unit
) {
    GlassCard(contentPadding = PaddingValues(14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(text = row.manifest.icon, fontSize = 19.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.manifest.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(6.dp))
                    Tag(text = row.manifest.category, color = MaterialTheme.colorScheme.primary)
                    if (row.builtin) {
                        Spacer(Modifier.width(4.dp))
                        Tag(text = "官方", color = Success)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${row.manifest.author} · v${row.manifest.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = row.manifest.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "查看详情 ›",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                IconButton(onClick = onFavorite, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = if (row.favorite) Icons.Filled.Star else Icons.Filled.FavoriteBorder,
                        contentDescription = if (row.favorite) "取消收藏" else "收藏",
                        tint = if (row.favorite) Color(0xFFFBBF24) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (row.installed) {
                    JellySwitch(
                        checked = row.enabled,
                        onCheckedChange = onToggle,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                } else {
                    GuardButton(
                        text = "安装",
                        onClick = onInstall,
                        enabled = row.manifest.downloadUrl.isNotBlank() || row.manifest.repo.isNotBlank(),
                        style = GuardButtonStyle.Glass
                    )
                }
            }
        }
    }
}

// ================================================================ 详情页

@Composable
private fun PluginDetailDialog(
    viewModel: PluginViewModel,
    pluginId: String,
    plugins: List<Plugin>,
    market: List<PluginManifest>,
    favorites: Set<String>,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val local = plugins.firstOrNull { it.manifest.id == pluginId }
    val manifest = local?.manifest ?: market.firstOrNull { it.id == pluginId } ?: return
    val installed = local != null
    val enabled = local?.enabled == true
    val favorite = pluginId in favorites
    // 来源与包摘要只读一次，别在重组里反复读文件
    val provenance = remember(pluginId, installed) {
        if (installed && !manifest.pinned) viewModel.provenance(pluginId) else emptyMap()
    }
    var values by remember(pluginId, manifest.config) {
        val stored = viewModel.configOf(pluginId)
        mutableStateOf(
            manifest.config.associate { field ->
                field.key to (stored[field.key]?.takeIf { it.isNotBlank() } ?: field.default)
            }
        )
    }

    fun update(key: String, value: String) {
        values = values + (key to value)
        viewModel.setConfig(pluginId, key, value)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) { Text("‹ 返回") }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { viewModel.toggleFavorite(pluginId) }) {
                            Icon(
                                imageVector = if (favorite) Icons.Filled.Star else Icons.Filled.FavoriteBorder,
                                contentDescription = "收藏",
                                tint = if (favorite) Color(0xFFFBBF24) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        }
                    }
                }

                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(62.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(18.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = manifest.icon, fontSize = 30.sp)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = manifest.name,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Tag(text = manifest.category, color = MaterialTheme.colorScheme.primary)
                                if (manifest.pinned) {
                                    Spacer(Modifier.width(6.dp))
                                    Tag(text = "官方", color = Success)
                                }
                                if (!installed) {
                                    Spacer(Modifier.width(6.dp))
                                    Tag(text = "未安装", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "${manifest.author} · v${manifest.version}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (installed) {
                            GuardButton(
                                text = if (enabled) "关闭插件" else "启用插件",
                                onClick = { viewModel.toggleEnabled(pluginId, !enabled) },
                                modifier = Modifier.weight(1f),
                                style = if (enabled) GuardButtonStyle.Primary else GuardButtonStyle.Glass
                            )
                        } else {
                            GuardButton(
                                text = "安装插件",
                                onClick = { viewModel.install(manifest, onMessage) },
                                modifier = Modifier.weight(1f),
                                enabled = manifest.downloadUrl.isNotBlank() || manifest.repo.isNotBlank()
                            )
                        }
                        if (installed && !manifest.pinned) {
                            GuardButton(
                                text = "卸载",
                                onClick = {
                                    viewModel.uninstall(pluginId, onMessage)
                                    onDismiss()
                                },
                                style = GuardButtonStyle.Danger
                            )
                        }
                    }
                }

                // 安装信息
                item {
                    GlassCard(contentPadding = PaddingValues(14.dp)) {
                        DetailLine("版本", "v${manifest.version}")
                        DetailLine(
                            "大小",
                            if (manifest.pinned) "内置" else formatSize(local?.sizeBytes ?: 0L)
                        )
                        DetailLine(
                            "安装时间",
                            if (installed && (local?.installedAt ?: 0L) > 0) {
                                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(local!!.installedAt))
                            } else if (manifest.pinned) {
                                "内置官方插件"
                            } else {
                                "未安装"
                            }
                        )
                        DetailLine("插件 ID", manifest.id)
                        // 信任链：说明能力边界 + 留一条可核对的安装来源
                        DetailLine("能力边界", "声明式配置，不含可执行代码")
                        provenance["source"]?.let { DetailLine("安装来源", it) }
                        provenance["sha256"]?.let {
                            DetailLine(
                                "包摘要",
                                com.dlguard.integrity.FileHasher.shorten(it, 16, 8)
                            )
                        }
                        if (manifest.id == PluginManager.ID_BROWSER_BRIDGE) {
                            DetailLine(
                                "监听",
                                if (viewModel.bridgeRunning()) {
                                    "运行中 · ${viewModel.bridgeAddress() ?: "未连接局域网"}"
                                } else {
                                    "未运行（端口可能被占用）"
                                }
                            )
                        }
                    }
                }

                item {
                    SectionTitle("插件介绍")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = manifest.description.ifBlank { "作者没有填写介绍。" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                    )
                }

                if (manifest.changelog.isNotBlank()) {
                    item {
                        SectionTitle("更新日志")
                        Spacer(Modifier.height(6.dp))
                        GlassCard(contentPadding = PaddingValues(14.dp)) {
                            Text(
                                text = manifest.changelog,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                if (manifest.config.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle("自定义配置")
                            Spacer(Modifier.weight(1f))
                            if (viewModel.isConfigCustomized(pluginId)) {
                                Text(
                                    text = "恢复默认",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        viewModel.resetConfig(pluginId)
                                        values = viewModel.configOf(pluginId)
                                        onMessage("已恢复默认配置")
                                    }
                                )
                            }
                        }
                        Text(
                            text = "这些参数由插件声明，改完立即生效",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                    items(manifest.config, key = { it.key }) { field ->
                        ConfigFieldRow(
                            field = field,
                            value = values[field.key] ?: field.default,
                            installed = installed,
                            onToggle = { update(field.key, it.toString()) },
                            onChange = { update(field.key, it) }
                        )
                    }
                }

                if (manifest.permissions.isNotEmpty()) {
                    item {
                        SectionTitle("权限")
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            manifest.permissions.forEach { permission ->
                                Tag(text = permission, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }
                    }
                }

                if (manifest.homepage.isNotBlank() || manifest.repo.isNotBlank()) {
                    item {
                        SectionTitle("链接")
                        Spacer(Modifier.height(6.dp))
                        if (manifest.repo.isNotBlank()) {
                            LinkRow(
                                label = "源码仓库",
                                value = manifest.repo,
                                onClick = {
                                    val url = if (manifest.repo.startsWith("http")) manifest.repo
                                    else "https://github.com/${manifest.repo}"
                                    openUrl(context, url, onMessage)
                                }
                            )
                        }
                        if (manifest.homepage.isNotBlank()) {
                            LinkRow(
                                label = "插件主页",
                                value = manifest.homepage,
                                onClick = { openUrl(context, manifest.homepage, onMessage) }
                            )
                        }
                    }
                }

                if (!installed) {
                    item {
                        GuardButton(
                            text = "从 GitHub 安装此插件",
                            onClick = {
                                val source = manifest.repo.ifBlank { manifest.id }
                                viewModel.installFromGithub(source, onMessage)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = manifest.repo.isNotBlank(),
                            style = GuardButtonStyle.Glass
                        )
                    }
                }

                item {
                    Text(
                        text = if (manifest.pinned) {
                            "官方插件随 App 内置，不支持卸载；关闭开关即可停用其能力。"
                        } else {
                            "第三方插件由作者提供，App 只按清单声明加载其配置与能力入口，请自行判断来源可信度。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigFieldRow(
    field: PluginConfigField,
    value: String,
    installed: Boolean,
    onToggle: (Boolean) -> Unit,
    onChange: (String) -> Unit
) {
    var draft by remember(field.key, value) { mutableStateOf(value) }

    GlassCard(contentPadding = PaddingValues(14.dp)) {
        when (field.type) {
            PluginConfigField.TYPE_TOGGLE -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = field.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (field.description.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = field.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    JellySwitch(
                        checked = value == "true",
                        onCheckedChange = onToggle,
                        enabled = installed
                    )
                }
            }

            PluginConfigField.TYPE_SLIDER -> {
                val min = field.min.coerceAtLeast(0)
                val max = field.max.coerceAtLeast(min + 1)
                val initial = (value.toIntOrNull() ?: field.default.toIntOrNull() ?: min)
                    .coerceIn(min, max)
                var sliderValue by remember(field.key, value) { mutableStateOf(initial) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = field.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "$sliderValue",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Slider(
                    value = sliderValue.toFloat(),
                    onValueChange = { raw ->
                        // 按 step 吸附，松手才写存储，避免拖动时高频落盘
                        val snapped = field.min + ((raw.roundToInt() - field.min) / field.step) * field.step
                        sliderValue = snapped.coerceIn(min, max)
                    },
                    onValueChangeFinished = { onChange(sliderValue.toString()) },
                    valueRange = min.toFloat()..max.toFloat(),
                    enabled = installed
                )
                if (field.description.isNotBlank()) {
                    Text(
                        text = field.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            PluginConfigField.TYPE_CHOICE -> {
                Text(
                    text = field.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    field.options.forEach { option ->
                        val selected = value == option
                        Box(
                            Modifier
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                    RoundedCornerShape(50)
                                )
                                .clickable(enabled = installed) { onChange(option) }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = option,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                            )
                        }
                    }
                }
                if (field.description.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = field.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            else -> {
                Text(
                    text = field.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = installed,
                    shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (field.description.isNotBlank()) {
                        Text(
                            text = field.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (draft != value) {
                        Text(
                            text = "应用",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { onChange(draft) }
                                .padding(start = 8.dp, top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun LinkRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(text = "↗", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

// ================================================================ 小工具

@Composable
private fun Tag(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.CHINA, "%.1f MB", bytes / 1024.0 / 1024.0)
}

private fun openUrl(context: Context, url: String, onMessage: (String) -> Unit) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { onMessage("打不开链接：没有可用的浏览器") }
}

private fun copyToClipboard(context: Context, text: String, onMessage: (String) -> Unit, hint: String) {
    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clip?.setPrimaryClip(ClipData.newPlainText("DownloadGuard", text))
    onMessage(hint)
}

// ================================================================ 浏览器扩展

@Composable
private fun BrowserExtensionDialog(
    context: Context,
    bridgeEnabled: Boolean,
    address: String?,
    port: Int,
    token: String,
    onEnableBridge: () -> Unit,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit
) {
    val browsers = remember { BrowserExtensionHelper.installedBrowsers(context) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("浏览器扩展") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "装好扩展后，浏览器里的下载会自动改走手机的多线程引擎（默认 16 连接、按网络质量自动增减，上限 256），落盘前做完整性校验——电脑端同样适用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(if (bridgeEnabled) Success else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f), CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (bridgeEnabled) "桥服务运行中 · 端口 $port" else "桥服务未开启",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )
                    Spacer(Modifier.weight(1f))
                    if (!bridgeEnabled) {
                        Text(
                            text = "开启",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(onClick = onEnableBridge)
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "手机服务地址",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
                Text(
                    text = address ?: "未连接局域网（请连 Wi-Fi 或开热点）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Token",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        )
                        Text(
                            text = token,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "复制",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            copyToClipboard(context, "$address\n$token", onMessage, "地址与 Token 已复制")
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "已检测到的浏览器",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
                if (browsers.isEmpty()) {
                    Text(
                        text = "未检测到常见浏览器，可手动在任意 Chromium 浏览器安装",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                browsers.take(4).forEach { browser ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = browser.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            modifier = Modifier.weight(1f)
                        )
                        if (!browser.supportsExtensions) {
                            Tag(
                                text = if (browser.hasExtensionStore) "有扩展商店" else "不支持扩展",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = "扩展页",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                if (!BrowserExtensionHelper.openExtensionPage(context, browser)) {
                                    onMessage("${browser.label} 打不开，可能已被禁用")
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GuardButton(
                        text = "导出扩展包",
                        onClick = {
                            val file = BrowserExtensionHelper.exportZip(context)
                            if (file == null) {
                                onMessage("导出失败：扩展资源缺失")
                                return@GuardButton
                            }
                            runCatching {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_SUBJECT, "DownloadGuard 浏览器扩展")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "发送扩展包到电脑/浏览器"))
                            }.onFailure { onMessage("分享失败：${it.message}") }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    GuardButton(
                        text = "复制说明",
                        onClick = {
                            copyToClipboard(
                                context,
                                BrowserExtensionHelper.installGuide(context),
                                onMessage,
                                "安装说明已复制（含目录、地址、Token）"
                            )
                        },
                        modifier = Modifier.weight(1f),
                        style = GuardButtonStyle.Glass
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "扩展目录：${BrowserExtensionHelper.extensionDir().absolutePath}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (expanded) {
                        "Android 沙箱不允许本 App 直接写进其他浏览器的扩展数据（那是浏览器私有目录）。" +
                            "扩展已经解压到上面的公共目录：在浏览器扩展页选「从本地/文件夹安装」并选中它即可；" +
                            "电脑端把 zip 解压后走 chrome://extensions → 开发者模式 → 加载已解压的扩展程序。"
                    } else {
                        "点此查看为什么不能「一键写入浏览器扩展页」▾"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable { expanded = !expanded }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

// ================================================================ 市场设置

@Composable
private fun MarketConfigDialog(
    current: String,
    updatedAt: Long,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onGithubInstall: () -> Unit,
    onCustom: () -> Unit,
    onExportTemplate: () -> Unit,
    onPickZip: () -> Unit
) {
    var value by remember { mutableStateOf(current) }
    val resolved = MarketSource.indexUrl(value)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("插件市场源") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "填 GitHub 仓库（owner/repo）即可，App 自动读取仓库里的 index.json；" +
                        "也可以填任意静态 JSON 地址，或留空只看官方插件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("dlguard/plugins", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "解析为：" + (resolved ?: "无法识别"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (updatedAt > 0) {
                    Text(
                        text = "上次更新：" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(updatedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onGithubInstall) { Text("从 GitHub 安装插件") }
                    TextButton(onClick = onCustom) { Text("自定义插件") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onPickZip) { Text("安装本地 zip") }
                    TextButton(onClick = onExportTemplate) {
                        Text("导出市场模板", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }) { Text("保存并刷新") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun GithubInstallDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从 GitHub 安装") },
        text = {
            Column {
                Text(
                    text = "填插件仓库，仓库根目录（或单层子目录）需要有 manifest.json。" +
                        "例如 dlguard/plugins-demo；也可以填仓库页面地址或 zip 直链。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("owner/repo", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text("安装") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun CustomPluginDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(SAMPLE_MANIFEST) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义插件") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "粘贴 manifest JSON 直接创建插件。支持 config 数组声明参数，" +
                        "详情页会自动生成开关/滑杆/输入框。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp),
                    textStyle = MaterialTheme.typography.labelSmall,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private const val SAMPLE_MANIFEST = """{
  "id": "my.rule-demo",
  "name": "我的规则插件",
  "version": "1.0.0",
  "author": "我",
  "description": "示例：声明两个参数，详情页会自动生成控件。",
  "category": "工具",
  "icon": "🧪",
  "enabledByDefault": false,
  "permissions": ["network"],
  "config": [
    { "key": "enabled", "label": "启用规则", "type": "toggle", "default": "true" },
    { "key": "threads", "label": "线程数", "type": "slider", "default": "8", "min": 1, "max": 48 },
    { "key": "pattern", "label": "匹配正则", "type": "text", "default": "" }
  ]
}"""

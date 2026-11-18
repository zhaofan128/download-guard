package com.dlguard.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dlguard.BuildConfig
import android.content.Context
import com.dlguard.core.CrashGuard
import com.dlguard.download.ResumableDownloader
import com.dlguard.core.Formatters
import com.dlguard.core.PermissionHelper
import com.dlguard.service.DownloadDirs
import com.dlguard.service.NotificationHelper
import com.dlguard.shizuku.ShizukuHelper
import com.dlguard.ui.MainViewModel
import com.dlguard.ui.SiteRulesActivity
import com.dlguard.ui.SettingViewModel
import com.dlguard.ui.components.GlassCard
import com.dlguard.ui.components.GuardButton
import com.dlguard.ui.components.GuardButtonStyle
import com.dlguard.ui.components.JellySwitch
import com.dlguard.ui.components.liquidGlass
import com.dlguard.ui.theme.Danger
import com.dlguard.ui.theme.LocalGlassConfig
import com.dlguard.ui.theme.Spacing
import com.dlguard.ui.theme.Success
import com.dlguard.ui.theme.Warning
import java.io.File
import kotlin.math.roundToInt

/**
 * 设置页。所有开关走 [SettingViewModel] 的热生效引擎：
 * 视觉即时响应 + 300ms 防抖 + 异步副作用 + 失败回滚，Activity 全程不重建。
 */
@Composable
fun SettingScreen(
    viewModel: MainViewModel,
    settingViewModel: SettingViewModel,
    bottomInset: Dp
) {
    val context = LocalContext.current
    val settings by settingViewModel.settings.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val shizukuStatus by settingViewModel.shizukuStatus.collectAsStateWithLifecycle()
    val customDirs by viewModel.customDirs.collectAsStateWithLifecycle()
    val quarantineFiles by viewModel.quarantineFiles.collectAsStateWithLifecycle()
    val quarantineBytes by viewModel.quarantineBytes.collectAsStateWithLifecycle()
    val guard by viewModel.guard.collectAsStateWithLifecycle()
    val siteRules by viewModel.siteRules.collectAsStateWithLifecycle()

    var showAddDir by rememberSaveable { mutableStateOf(false) }
    var showClearQuarantine by rememberSaveable { mutableStateOf(false) }
    var showClearRecords by rememberSaveable { mutableStateOf(false) }
    var addDirText by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = Spacing.Page,
            end = Spacing.Page,
            top = 10.dp,
            // 底部内边距强制 ≥140dp：悬浮胶囊导航栏 + 手势条都不遮挡最后几行
            bottom = maxOf(bottomInset + 40.dp, 140.dp)
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Column {
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "守护 ${guard.directories} 个目录 · ${if (guard.running) "服务运行中" else "服务未运行"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        item {
            Section("守护行为") {
                SettingSwitch(
                    title = "开机自启动",
                    subtitle = "收到 BOOT_COMPLETED / 覆盖安装后自动拉起前台服务",
                    checked = settings.autoStart,
                    hapticEnabled = settings.haptics,
                    onCheckedChange = settingViewModel::setAutoStart
                )
                SettingSwitch(
                    title = "常驻通知",
                    subtitle = "低优先级、不可划掉，展示守护状态与统计",
                    checked = settings.notifications,
                    hapticEnabled = settings.haptics,
                    onCheckedChange = settingViewModel::setNotifications
                )
                SettingSwitch(
                    title = "监控总开关",
                    subtitle = "关闭后停止前台服务与目录监听（热生效，不重启 App）",
                    checked = settings.monitorEnabled,
                    hapticEnabled = settings.haptics,
                    onCheckedChange = settingViewModel::setMonitorEnabled
                )
                SettingSwitch(
                    title = "接管剪贴板链接",
                    subtitle = "发现 apk/apks/xapk 直链时提醒一键极速下载（Shizuku 授权后后台静默监听）",
                    checked = settings.clipboardWatch,
                    hapticEnabled = settings.haptics,
                    onCheckedChange = settingViewModel::setClipboardWatch
                )
                SettingSwitch(
                    title = "浏览器链接爬取（无障碍）",
                    subtitle = "监听浏览器节点树，点击/长按安装包直链时自动抓取；Shizuku 可一键静默开启",
                    checked = settings.accessibilityCapture,
                    hapticEnabled = settings.haptics,
                    onCheckedChange = settingViewModel::setAccessibilityCapture
                )
                SettingSwitch(
                    title = "跨应用抓取（微信/QQ 内嵌页）",
                    subtitle = "把抓取范围扩到社交/网盘 App 的内嵌 WebView：只识别 .apk/.zip/.pdf/.mp4 等文件直链，" +
                        "不读取、不记录聊天与浏览内容；默认关闭",
                    checked = settings.crossAppCapture,
                    hapticEnabled = settings.haptics,
                    onCheckedChange = settingViewModel::setCrossAppCapture
                )
                SettingSwitch(
                    title = "自动接收网盘下载",
                    subtitle = "网盘 App 走系统下载器时直接建任务，接管成功后自动删掉系统那份（需要 LSPosed 模块）；" +
                        "浏览器内置下载器无法从外部取消，只进候选列表不自动下载，用候选列表手动确认",
                    checked = settings.autoIntake,
                    hapticEnabled = settings.haptics,
                    onCheckedChange = settingViewModel::setAutoIntake
                )
                SettingSwitch(
                    title = "无障碍心跳保活",
                    subtitle = "每 20 秒检测无障碍状态，被系统杀掉后自动写回开启（需 Shizuku）",
                    checked = settings.autoRepairAccessibility,
                    hapticEnabled = settings.haptics,
                    onCheckedChange = settingViewModel::setAutoRepairAccessibility
                )
                SettingSwitch(
                    title = "允许安装应用",
                    subtitle = "点击校验完成的 APK 时调用系统安装器，标准安装方式，不需要 Shizuku 或 root",
                    checked = settings.allowInstaller,
                    hapticEnabled = settings.haptics,
                    onCheckedChange = settingViewModel::setAllowInstaller
                )
                SettingSwitch(
                    title = "推荐用本 App 下载安装包",
                    subtitle = "在下载引导中优先展示内置加固下载器",
                    checked = settings.preferInAppDownload,
                    hapticEnabled = settings.haptics,
                    onCheckedChange = settingViewModel::setPreferInApp
                )
                SettingSwitch(
                    title = "仅 Wi-Fi 下载",
                    subtitle = "蜂窝网络下拒绝开始下载，避免流量损耗",
                    checked = settings.wifiOnlyDownload,
                    hapticEnabled = settings.haptics,
                    onCheckedChange = settingViewModel::setWifiOnly
                )
                SettingSwitch(
                    title = "从下载通知抓取链接",
                    subtitle = "需额外授予「通知使用权」；抓到链接后，损坏文件可直接一键重下",
                    checked = settings.captureNotificationLinks,
                    hapticEnabled = settings.haptics,
                    onCheckedChange = settingViewModel::setCaptureNotificationLinks
                )
                ThreadSelector(
                    current = settings.downloadThreads,
                    onSelect = settingViewModel::setDownloadThreads
                )
                ParallelTaskSelector(
                    current = settings.maxParallelTasks,
                    onSelect = settingViewModel::setMaxParallelTasks
                )
                TitleFontSelector(
                    current = settings.titleFont,
                    onSelect = settingViewModel::setTitleFont
                )
                SettingSwitch(
                    title = "触觉反馈",
                    subtitle = "果冻开关切换时的 TextHandleMove 触感",
                    checked = settings.haptics,
                    hapticEnabled = settings.haptics,
                    onCheckedChange = settingViewModel::setHaptics
                )
                SettingSwitch(
                    title = "动态取色（Material You）",
                    subtitle = "Android 12+ 跟随壁纸取色，关闭后使用青紫品牌色",
                    checked = settings.dynamicColor,
                    hapticEnabled = settings.haptics,
                    onCheckedChange = settingViewModel::setDynamicColor
                )
            }
        }

        item {
            Section("站点规则") {
                SettingAction(
                    title = "按域名管理下载规则",
                    subtitle = if (siteRules.isEmpty()) {
                        "网盘直链的 Referer / Cookie 来源 / UA / 并发 / 文件名模板，可导入导出、可测试"
                    } else {
                        "${siteRules.size} 条规则 · 命中域名自动套用，不必每次手填请求头"
                    },
                    icon = Icons.Filled.List,
                    onClick = {
                        context.startActivity(Intent(context, SiteRulesActivity::class.java))
                    }
                )
            }
        }

        item {
            RelaySection(
                settings = settings,
                onClientEnabled = settingViewModel::setRelayClientEnabled,
                onSaveTarget = settingViewModel::saveRelayTarget,
                onGatewayStart = settingViewModel::startRelayGateway,
                onGatewayStop = settingViewModel::stopRelayGateway,
                onTest = settingViewModel::testRelay
            )
        }

        item {
            CollapsibleSection(
            title = "Shizuku 特权中枢",
            subtitle = "shell 安装、静默授权等高级能力，不装也能正常用所有核心功能"
        ) {
                ShizukuStatusRow(
                    status = shizukuStatus,
                    onRequest = { settingViewModel.requestShizukuPermission() }
                )
                SettingAction(
                    title = "免手点授予所有文件访问",
                    subtitle = "appops set MANAGE_EXTERNAL_STORAGE allow（等效手动到系统设置打开开关）",
                    icon = Icons.Filled.Lock,
                    onClick = { settingViewModel.grantAllFilesAccessViaShizuku() }
                )
                SettingAction(
                    title = "加入电池优化白名单",
                    subtitle = "dumpsys deviceidle whitelist +本应用，配合心跳保活服务常驻",
                    icon = Icons.Filled.Refresh,
                    onClick = { settingViewModel.joinBatteryWhitelistViaShizuku() }
                )
                SettingAction(
                    title = "静默开启无障碍服务",
                    subtitle = "settings put secure enabled_accessibility_services 写回本服务，免手动勾选",
                    icon = Icons.Filled.Add,
                    onClick = { settingViewModel.enableAccessibilityViaShizuku() }
                )
            }
        }

        item {
            Section("权限与保活") {
                PermissionRow(
                    title = "所有文件访问权限",
                    subtitle = if (permissions.storage) {
                        "已授权，可监听公共下载目录"
                    } else {
                        PermissionHelper.allFilesAccessHint()
                    },
                    granted = permissions.storage,
                    onClick = { viewModel.openPermissionGuide() }
                )
                PermissionRow(
                    title = "通知权限",
                    subtitle = if (permissions.notifications) "已开启，可收到损坏告警" else "未开启，损坏告警无法展示",
                    granted = permissions.notifications,
                    onClick = { PermissionHelper.openNotificationSettings(context) }
                )
                PermissionRow(
                    title = "电池优化白名单",
                    subtitle = if (permissions.ignoringBattery) "已忽略电池优化，服务更稳" else "未加入白名单，可能被系统冻结",
                    granted = permissions.ignoringBattery,
                    onClick = { PermissionHelper.requestIgnoreBatteryOptimizations(context) }
                )
                PermissionRow(
                    title = "通知使用权",
                    subtitle = if (NotificationHelper.isNotificationListenerEnabled(context)) {
                        "已授权，可从浏览器/网盘通知中提取下载链接"
                    } else {
                        "未授权，损坏文件只能手动补链接（不影响其他功能）"
                    },
                    granted = NotificationHelper.isNotificationListenerEnabled(context),
                    onClick = { NotificationHelper.openNotificationListenerSettings(context) }
                )
                PermissionRow(
                    title = "厂商自启动白名单",
                    subtitle = PermissionHelper.autoStartGuideText(),
                    granted = guard.running,
                    valueLabel = PermissionHelper.vendorLabel(),
                    onClick = { PermissionHelper.openAutoStartSettings(context) }
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GuardButton(
                        text = "模拟重启",
                        onClick = viewModel::simulateBoot,
                        modifier = Modifier.weight(1f),
                        style = GuardButtonStyle.Glass,
                        icon = Icons.Filled.Refresh
                    )
                    GuardButton(
                        text = "立即扫描",
                        onClick = viewModel::rescan,
                        modifier = Modifier.weight(1f),
                        style = GuardButtonStyle.Primary
                    )
                }
            }
        }

        item {
            Section("监听目录") {
                Text(
                    text = "内置目录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
                )
                BuiltInDirChips()
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "自定义目录",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "添加",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                addDirText = ""
                                showAddDir = true
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                if (customDirs.isEmpty()) {
                    Text(
                        text = "尚未添加。可添加网盘自定义缓存目录的绝对路径。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                } else {
                    customDirs.forEach { dir ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = dir.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "添加于 ${Formatters.shortTime(dir.addedAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                            }
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "移除目录",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                modifier = Modifier
                                    .size(26.dp)
                                    .clickable { viewModel.removeCustomDir(dir.path) }
                                    .padding(4.dp)
                            )
                        }
                    }
                }
            }
        }

        item {
            Section("隔离区") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "已隔离 ${quarantineFiles.size} 个文件",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${Formatters.bytes(quarantineBytes)} · 位于应用私有目录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                quarantineFiles.take(8).forEach { file ->
                    QuarantineRow(file = file, onDelete = { viewModel.deleteQuarantine(file) })
                }
                if (quarantineFiles.size > 8) {
                    Text(
                        text = "仅显示最近 8 个，完整列表可导出日志查看",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                GuardButton(
                    text = "清空隔离区",
                    onClick = { showClearQuarantine = true },
                    modifier = Modifier.fillMaxWidth(),
                    style = GuardButtonStyle.Danger,
                    enabled = quarantineFiles.isNotEmpty()
                )
            }
        }

        item {
            CollapsibleSection(
            title = "日志与数据",
            subtitle = "导出校验日志、清空记录、复制隔离区路径"
        ) {
                SettingAction(
                    title = "导出校验日志",
                    subtitle = "生成 CSV 并调用系统分享；链接凭证与本地绝对路径已脱敏（文件带 _redacted 标记）",
                    icon = Icons.Filled.Share,
                    onClick = {
                        viewModel.exportLogs { file ->
                            if (file != null) {
                                shareCsv(context, file)
                            }
                        }
                    }
                )
                SettingAction(
                    title = "清空校验记录",
                    subtitle = "仅删除数据库记录，隔离区文件不受影响",
                    icon = Icons.Filled.Delete,
                    danger = true,
                    onClick = { showClearRecords = true }
                )
                SettingAction(
                    title = "清空捕获的下载链接",
                    subtitle = "共 ${viewModel.capturedLinks.recent(200).size} 条，来自通知/分享/LSPosed 回传，仅存于应用私有目录",
                    icon = Icons.Filled.Delete,
                    danger = true,
                    onClick = viewModel::clearCapturedLinks
                )
                SettingAction(
                    title = "复制隔离区路径",
                    subtitle = viewModel.quarantinePath,
                    icon = Icons.Filled.Lock,
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager
                        clipboard?.setPrimaryClip(
                            android.content.ClipData.newPlainText("quarantine", viewModel.quarantinePath)
                        )
                        android.widget.Toast.makeText(context, "隔离区路径已复制", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        item {
            GlassCard(contentPadding = PaddingValues(16.dp)) {
                Text(
                    text = "关于浏览器下载接管",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "版本 ${BuildConfig.VERSION_NAME} · minSdk 26 / targetSdk 34\n" +
                        "最高 256 连接块级调度 + 多任务队列 + 本地真实校验：EOCD → 全 entry CRC32 → APK 解析 → v2 签名验签 → 可选官方哈希比对。不上传任何文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }

        item {
            CrashReportCard()
        }
    }

    if (showAddDir) {
        AlertDialog(
            onDismissRequest = { showAddDir = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("添加监听目录") },
            text = {
                Column {
                    Text(
                        text = "填写绝对路径，例如 /storage/emulated/0/Quark/Download",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = addDirText,
                        onValueChange = { addDirText = it },
                        singleLine = true,
                        placeholder = { Text("/storage/emulated/0/…") },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.addCustomDir(addDirText)
                    showAddDir = false
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDir = false }) { Text("取消") }
            }
        )
    }

    if (showClearQuarantine) {
        ConfirmDialog(
            title = "清空隔离区？",
            message = "将永久删除 ${quarantineFiles.size} 个已隔离的损坏安装包（${Formatters.bytes(quarantineBytes)}），此操作不可恢复。",
            confirmText = "确认清空",
            onConfirm = {
                viewModel.clearQuarantine()
                showClearQuarantine = false
            },
            onDismiss = { showClearQuarantine = false }
        )
    }

    if (showClearRecords) {
        ConfirmDialog(
            title = "清空校验记录？",
            message = "将删除全部历史校验记录，统计数据会归零。隔离区文件不会被删除。",
            confirmText = "确认清空",
            onConfirm = {
                viewModel.clearRecords()
                showClearRecords = false
            },
            onDismiss = { showClearRecords = false }
        )
    }
}

@Composable
private fun ThreadSelector(current: Int, onSelect: (Int) -> Unit) {
    // 拖动过程中的本地示数：跟手实时刷新；松手才提交写入（DataStore 不被高频打）。
    // remember(current) 的 key：自动测速在别处写回设置后，示数会跟着新值重置。
    var display by remember(current) { mutableStateOf(current) }
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(
            text = "下载并发连接数",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "拖动选择 1~256 连接；块级调度 + 工作窃取防木桶效应，服务器不支持 Range 时自动退化为单连接。到首页点「自动测速」可实测最快并发并自动写回",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(14.dp))
        // 示数行：大号数字随拖动实时变化，右侧提示当前已生效的值
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "$display",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "线程并发",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 7.dp)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "当前生效 $current",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.padding(bottom = 5.dp)
            )
        }
        Slider(
            value = display.toFloat(),
            onValueChange = { display = it.roundToInt().coerceIn(1, ResumableDownloader.MAX_CONNECTIONS) },
            onValueChangeFinished = { if (display != current) onSelect(display) },
            valueRange = 1f..ResumableDownloader.MAX_CONNECTIONS.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "1",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
            Text(
                text = "${ResumableDownloader.MAX_CONNECTIONS}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }
    }
}

/** 同时下载的任务数：多任务队列的并行度，超出的任务自动排队。 */
@Composable
private fun ParallelTaskSelector(current: Int, onSelect: (Int) -> Unit) {
    var display by remember(current) { mutableStateOf(current) }
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(
            text = "同时下载任务数",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "最多同时下载 5 个文件；超出的任务排队等待，前面的完成后自动开始。每个任务都能独立暂停/继续/删除",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "$display",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "个并行任务",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 7.dp)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "当前生效 $current",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.padding(bottom = 5.dp)
            )
        }
        Slider(
            value = display.toFloat(),
            onValueChange = { display = it.roundToInt().coerceIn(1, 5) },
            onValueChangeFinished = { if (display != current) onSelect(display) },
            valueRange = 1f..5f,
            steps = 3,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "1",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
            Text(
                text = "5",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }
    }
}

/** 首页标题字体：sans 现代无衬线（默认）/ system 跟随系统 / serif / mono。 */
@Composable
private fun TitleFontSelector(current: String, onSelect: (String) -> Unit) {
    val options = listOf(
        "sans" to "现代无衬线",
        "system" to "跟随系统",
        "serif" to "衬线",
        "mono" to "等宽"
    )
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(
            text = "首页标题字体",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "只影响首页顶部「浏览器下载接管」标题，立即生效。若系统主题字体本身是手写体，选「现代无衬线」可覆盖",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                val selected = value == current
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                        )
                        .clickable { if (!selected) onSelect(value) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ShizukuStatusRow(status: ShizukuHelper.Status, onRequest: () -> Unit) {
    val (label, granted) = when (status) {
        ShizukuHelper.Status.READY -> "已授权，特权功能可用" to true
        ShizukuHelper.Status.NEED_PERMISSION -> "服务运行中，等待授权" to false
        ShizukuHelper.Status.BINDER_PENDING ->
            "已安装，连接未送达（点「连接」自动索取，服务在跑的话几秒内就绪）" to false
        ShizukuHelper.Status.NOT_RUNNING -> "已安装但服务未运行（在 Shizuku 管理器中启动服务）" to false
        ShizukuHelper.Status.NOT_INSTALLED -> "未安装 Shizuku 管理器（可选增强，不装也能用）" to false
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRequest)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(if (granted) Success else Warning, CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "Shizuku 授权状态",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        GuardButton(
            text = if (granted) "已就绪" else "连接 Shizuku",
            onClick = onRequest,
            style = if (granted) GuardButtonStyle.Glass else GuardButtonStyle.Primary
        )
    }
}

/**
 * 可折叠分组：高级功能默认收起。
 *
 * 设置页一屏塞十几个开关时，新用户很难判断哪些是必须的。把「Shizuku」「日志与数据」
 * 这类只在特定场景用得到的分组收起来，首屏只留守护行为、权限、目录这些必看项。
 */
@Composable
private fun CollapsibleSection(
    title: String,
    subtitle: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(start = 6.dp, end = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
                if (!expanded) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                }
            }
            Text(
                text = if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 10.dp)
            )
        }
        if (expanded) {
            GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
        )
        GlassCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            content()
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    hapticEnabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        Spacer(Modifier.width(10.dp))
        JellySwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            hapticEnabled = hapticEnabled,
            accentColor = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * 接力下载：把「有网设备」的连接借给「没网设备」。
 *
 * 这里刻意不自动开热点——ColorOS/MIUI 对 LocalOnlyHotspot 的限制各不相同，
 * 与其做成时灵时不灵，不如让用户手动开系统热点（所有 ROM 都有），
 * App 只负责代理和展示要输入的地址。
 */
@Composable
private fun RelaySection(
    settings: com.dlguard.data.GuardSettings,
    onClientEnabled: (Boolean) -> Unit,
    onSaveTarget: (String, String, String) -> Unit,
    onGatewayStart: () -> Unit,
    onGatewayStop: () -> Unit,
    onTest: () -> Unit
) {
    var host by remember { mutableStateOf(settings.relayHost) }
    var port by remember { mutableStateOf(settings.relayPort.toString()) }
    var token by remember { mutableStateOf(settings.relayToken) }
    val gatewayOn by produceState(initialValue = com.dlguard.relay.RelayState.gatewayRunning) {
        while (true) {
            value = com.dlguard.relay.RelayState.gatewayRunning
            kotlinx.coroutines.delay(1_200)
        }
    }
    val addresses = remember { com.dlguard.relay.RelayServer.companionAddresses() }

    Section("接力下载") {
        Text(
            text = "一台有网、一台没网时：没网设备连上有网设备的热点，下载请求经它转发。" +
                "HTTPS 走隧道，网关看不到内容、不落盘。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(vertical = 8.dp)
        )
        SettingSwitch(
            title = "使用附近设备的网络",
            subtitle = "开启后所有下载走网关；关闭立即恢复本机直连",
            checked = settings.relayClientEnabled,
            hapticEnabled = settings.haptics,
            onCheckedChange = onClientEnabled
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("网关地址") },
                placeholder = { Text("192.168.43.1") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors()
            )
            OutlinedTextField(
                value = port,
                onValueChange = { input -> port = input.filter { it.isDigit() }.take(5) },
                label = { Text("端口") },
                singleLine = true,
                modifier = Modifier.width(92.dp),
                colors = OutlinedTextFieldDefaults.colors()
            )
        }
        OutlinedTextField(
            value = token,
            onValueChange = { input -> token = input.filter { it.isDigit() }.take(6) },
            label = { Text("接入码") },
            placeholder = { Text("网关设备上显示的 6 位数字") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors()
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(onClick = { onSaveTarget(host, port, token) }) { Text("保存") }
            TextButton(onClick = {
                onSaveTarget(host, port, token)
                onTest()
            }) { Text("测试连接") }
        }
        Spacer(Modifier.height(6.dp))
        SettingSwitch(
            title = "共享本机网络（网关）",
            subtitle = "先在系统里打开「个人热点」让对方连上；本页会显示要输入的地址与接入码",
            checked = gatewayOn,
            hapticEnabled = settings.haptics,
            onCheckedChange = { if (it) onGatewayStart() else onGatewayStop() }
        )
        if (gatewayOn) {
            val ip = addresses.firstOrNull() ?: "打开热点后显示"
            Text(
                text = "本机地址  $ip:${com.dlguard.relay.RelayState.gatewayPort}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "接入码  ${com.dlguard.relay.RelayState.gatewayToken.ifBlank { token }}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    onClick: () -> Unit,
    valueLabel: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(if (granted) Success else Warning, CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        if (!valueLabel.isNullOrBlank()) {
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SettingAction(
    title: String,
    subtitle: String,
    icon: ImageVector,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (danger) Danger else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(19.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (danger) Danger else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun BuiltInDirChips() {
    val config = LocalGlassConfig.current
    val names = DownloadDirs.builtIn().map { dir ->
        when {
            dir.name.equals("Download", true) -> "Download"
            dir.absolutePath.contains("MicroMsg") -> "微信"
            dir.absolutePath.contains("Quark", true) -> "夸克"
            dir.absolutePath.contains("Baidu", true) || dir.absolutePath.contains("baidu", true) -> "百度网盘"
            dir.absolutePath.contains("uc", true) -> "UC"
            dir.absolutePath.contains("Browser", true) -> "浏览器"
            else -> dir.name
        }
    }.distinct()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        names.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { name ->
                    Box(
                        Modifier
                            .liquidGlass(
                                cornerRadius = 999.dp,
                                config = config,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                elevation = 1.dp
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuarantineRow(file: File, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(26.dp)
                .background(Danger.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = Danger,
                modifier = Modifier.size(14.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${Formatters.bytes(file.length())} · ${Formatters.shortTime(file.lastModified())}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = "删除",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier
                .size(26.dp)
                .clickable(onClick = onDelete)
                .padding(4.dp)
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title) },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = Danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun shareCsv(context: android.content.Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "[已脱敏] 浏览器下载接管校验日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出校验日志"))
    }
}

/**
 * 崩溃诊断卡：上次异常退出的现场（时间/异常/堆栈摘要）。
 * 没有崩溃记录时整卡隐藏；有记录时支持复制与分享（发给开发者可直接定位根因）。
 */
@Composable
private fun CrashReportCard() {
    if (!CrashGuard.hasLastReport()) return
    val context = LocalContext.current
    val summary = CrashGuard.lastReportSummary()

    GlassCard(
        tint = Danger.copy(alpha = 0.10f),
        contentPadding = PaddingValues(16.dp)
    ) {
        Text(
            text = "上次异常退出（诊断）",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = summary ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GuardButton(
                text = "复制",
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText("crash", CrashGuard.lastReportText() ?: "")
                    )
                    android.widget.Toast.makeText(context, "崩溃日志已复制", android.widget.Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                style = GuardButtonStyle.Glass
            )
            GuardButton(
                text = "分享给开发者",
                onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, CrashGuard.lastReportText() ?: "")
                        putExtra(Intent.EXTRA_SUBJECT, "DownloadGuard 崩溃报告")
                    }
                    context.startActivity(Intent.createChooser(intent, "分享崩溃报告"))
                },
                modifier = Modifier.weight(1f),
                style = GuardButtonStyle.Primary
            )
            GuardButton(
                text = "清除",
                onClick = {
                    CrashGuard.clear()
                    android.widget.Toast.makeText(context, "已清除崩溃记录", android.widget.Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                style = GuardButtonStyle.Glass
            )
        }
    }
}

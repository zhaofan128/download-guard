package com.dlguard.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dlguard.core.Formatters
import com.dlguard.core.LinkSniffer
import com.dlguard.core.PermissionHelper
import com.dlguard.data.GuardStats
import com.dlguard.data.db.CheckRecord
import com.dlguard.integrity.Verdict
import com.dlguard.service.GuardSnapshot
import com.dlguard.download.DownloadUiState
import com.dlguard.shizuku.ShizukuHelper
import com.dlguard.ui.MainViewModel
import com.dlguard.ui.SettingViewModel
import com.dlguard.ui.components.AnimatedCounter
import com.dlguard.ui.components.EmptyState
import com.dlguard.ui.components.GlassCard
import com.dlguard.ui.components.GuardButton
import com.dlguard.ui.components.GuardButtonStyle
import com.dlguard.ui.components.JellyProgressBar as JellyProgressBarComponent
import com.dlguard.ui.components.liquidGlass
import com.dlguard.ui.components.rememberFileOpenController
import com.dlguard.ui.theme.Danger
import com.dlguard.ui.theme.LocalGlassConfig
import com.dlguard.ui.theme.LocalMotionEnabled
import com.dlguard.ui.theme.MonoSmallStyle
import com.dlguard.ui.theme.Success
import com.dlguard.ui.theme.Teal300
import com.dlguard.ui.theme.Violet300
import android.widget.Toast
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    settingViewModel: SettingViewModel,
    onOpenRecords: () -> Unit,
    onOpenSettings: () -> Unit,
    bottomInset: Dp
) {
    val context = LocalContext.current
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val guard by viewModel.guard.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val downloadState by viewModel.download.collectAsStateWithLifecycle()
    val clipboard by viewModel.clipboardSuggestion.collectAsStateWithLifecycle()
    val pendingName by viewModel.pendingFileName.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val tuneState by viewModel.tuneState.collectAsStateWithLifecycle()
    val resolving by viewModel.resolving.collectAsStateWithLifecycle()
    val plugins by viewModel.plugins.collectAsStateWithLifecycle()
    val autoTuneEnabled = plugins.firstOrNull {
        it.manifest.id == com.dlguard.plugin.PluginManager.ID_AUTO_TUNE
    }?.enabled ?: true
    val shizukuStatus by ShizukuHelper.status.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }
    val clipboardValue = clipboard
    val fileOpen = rememberFileOpenController { settings.allowInstaller }

    LaunchedEffect(clipboardValue) {
        if (!clipboardValue.isNullOrBlank()) url = clipboardValue
    }

    // 首次进入且没给「所有文件访问」时主动解释一次，避免用户在应用信息页开个「存储空间」就以为完事
    LaunchedEffect(Unit) {
        viewModel.maybeAutoShowPermissionGuide()
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 10.dp,
            // 底部内边距强制 ≥120dp：悬浮胶囊导航栏不再遮挡最后几行内容
            bottom = maxOf(bottomInset + 24.dp, 120.dp)
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Header(
                permissionsOk = permissions.storage && permissions.notifications,
                onPillClick = {
                    if (permissions.storage) onOpenSettings() else viewModel.openPermissionGuide()
                }
            )
        }
        if (shizukuStatus != ShizukuHelper.Status.READY) {
            item {
                ShizukuCard(
                    status = shizukuStatus,
                    onConnect = { settingViewModel.requestShizukuPermission() },
                    onOpenSettings = onOpenSettings
                )
            }
        }
        item {
            StatusCard(
                guard = guard,
                stats = stats,
                storageGranted = permissions.storage,
                onScan = viewModel::rescan,
                onStart = viewModel::startGuard,
                onPause = viewModel::pauseGuard,
                onResume = viewModel::resumeGuard
            )
        }
        if (!permissions.storage) {
            item {
                PermissionBanner(
                    legacyStorage = permissions.legacyStorage,
                    onClick = viewModel::openPermissionGuide
                )
            }
        }
        if (!permissions.notifications) {
            item {
                NotificationBanner(onClick = {
                    PermissionHelper.openNotificationSettings(context)
                })
            }
        }
        if (pendingName != null) {
            item {
                RedownloadHint(
                    fileName = pendingName!!,
                    onDismiss = viewModel::clearPendingFileName,
                    onPaste = {
                        val text = runCatching {
                            val manager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                            manager?.primaryClip?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)?.text?.toString()
                        }.getOrNull()
                        if (!text.isNullOrBlank()) url = text.trim()
                    }
                )
            }
        }
        item {
            DownloadCard(
                url = url,
                onUrlChange = { url = it },
                onStart = { viewModel.smartDownload(url) },
                resolving = resolving,
                onCancel = viewModel::cancelDownload,
                connections = settings.downloadThreads,
                onUseClipboard = {
                    val manager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    val text = runCatching {
                        manager?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                    }.getOrNull()
                    if (!text.isNullOrBlank()) url = text.trim()
                },
                state = downloadState,
                tuneState = tuneState,
                autoTuneEnabled = autoTuneEnabled,
                onTune = { viewModel.startConcurrencyTune(url) },
                onDismiss = viewModel::dismissDownload,
                onMessage = viewModel::notifyMessage,
                onOpenInPage = viewModel::openInSniffer,
                onOpenFile = fileOpen::open,
                onPause = viewModel::pauseDownload,
                onResume = viewModel::resumeDownload,
                onConnectionsChange = viewModel::setDownloadConnections
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "最近校验",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "查看全部",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpenRecords() }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }
        if (recent.isEmpty()) {
            item {
                EmptyState(
                    title = "还没有校验记录",
                    subtitle = "在 Download 目录放入一个安装包，或使用上方加固下载器",
                    icon = Icons.Filled.Info
                )
            }
        } else {
            itemsIndexed(recent) { index, record ->
                RecordRow(
                    record = record,
                    index = index,
                    modifier = Modifier.staggered(index),
                    onOpenFile = fileOpen::open
                )
            }
        }
        item {
            Text(
                text = "监听中目录：${guard.directories} 个 · 服务${if (guard.running) "运行中" else "未运行"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun Header(permissionsOk: Boolean, onPillClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "浏览器下载接管",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "安装包下载完整性守护",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClick = onPillClick)
                .liquidGlass(
                    cornerRadius = 999.dp,
                    config = LocalGlassConfig.current,
                    tint = if (permissionsOk) Success.copy(alpha = 0.10f) else Danger.copy(alpha = 0.16f),
                    elevation = 3.dp
                )
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(if (permissionsOk) Success else Danger, CircleShape)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (permissionsOk) "权限就绪" else "待授权",
                style = MaterialTheme.typography.labelSmall,
                color = if (permissionsOk) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    if (LocalGlassConfig.current.surfaceAlpha < 0.3f) Color(0xFFFCA5A5) else Color(0xFFB91C1C)
                }
            )
        }
    }
}

@Composable
private fun ShizukuCard(
    status: ShizukuHelper.Status,
    onConnect: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val message = when (status) {
        ShizukuHelper.Status.NOT_INSTALLED ->
            "未安装 Shizuku：可选增强，用于免手点授权、后台剪贴板监听与无障碍自启动。不装也能正常使用"
        ShizukuHelper.Status.BINDER_PENDING ->
            "Shizuku 已安装且服务可能在运行，但连接未送达本应用（覆盖安装或进程被杀会错过推送）。点击「连接」自动索取"
        ShizukuHelper.Status.NOT_RUNNING ->
            "Shizuku 已安装但服务未运行：打开 Shizuku 管理器启动服务后回来连接"
        ShizukuHelper.Status.NEED_PERMISSION ->
            "Shizuku 服务已就绪，点击授权后可启用全部特权功能"
        ShizukuHelper.Status.READY -> return
    }
    GlassCard(
        tint = Color(0xFF38BDF8).copy(alpha = 0.12f),
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = Color(0xFF38BDF8),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Shizuku 授权中枢",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
            GuardButton(
                text = if (status == ShizukuHelper.Status.NOT_INSTALLED) "说明" else "连接",
                onClick = onConnect,
                style = GuardButtonStyle.Glass
            )
        }
    }
}

@Composable
private fun StatusCard(
    guard: GuardSnapshot,
    stats: GuardStats,
    storageGranted: Boolean,
    onScan: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit
) {
    val statusColor = when {
        !storageGranted -> Color(0xFFF59E0B)
        guard.running -> Success
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    }
    GlassCard(
        cornerRadius = 24.dp,
        refraction = true,
        contentPadding = PaddingValues(20.dp)
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .shadow(
                                6.dp,
                                CircleShape,
                                clip = false,
                                ambientColor = if (guard.running) Success.copy(alpha = 0.5f) else Color.Transparent
                            )
                            .background(statusColor, CircleShape)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = when {
                            !storageGranted -> "等待授权"
                            guard.running -> "正在守护"
                            guard.paused -> "已暂停"
                            else -> "未运行"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when {
                        !storageGranted -> "授予「所有文件访问权限」后才能监听下载目录"
                        guard.running -> "持续监听 ${guard.directories} 个目录，下载结束自动校验"
                        guard.paused -> "监控已暂停，点击下方按钮恢复"
                        else -> "尚未启动监控，点击下方按钮启动"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "已校验",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                        AnimatedCounter(
                            value = stats.total,
                            style = MonoSmallStyle.copy(
                                fontSize = MaterialTheme.typography.headlineMedium.fontSize,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(38.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                    )
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "损坏",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                        AnimatedCounter(
                            value = stats.broken,
                            style = MonoSmallStyle.copy(
                                fontSize = MaterialTheme.typography.headlineMedium.fontSize,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = if (stats.broken > 0) Danger else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GuardButton(
                        text = "立即扫描",
                        onClick = onScan,
                        modifier = Modifier.weight(1f),
                        style = GuardButtonStyle.Primary
                    )
                    GuardButton(
                        text = when {
                            !storageGranted -> "授权并启动"
                            guard.running -> "暂停守护"
                            guard.paused -> "恢复守护"
                            else -> "启动守护"
                        },
                        onClick = when {
                            !storageGranted -> onStart
                            guard.running -> onPause
                            guard.paused -> onResume
                            else -> onStart
                        },
                        modifier = Modifier.weight(1f),
                        style = GuardButtonStyle.Glass
                    )
                }
            }
        }
        if (guard.running) {
            Spacer(Modifier.height(16.dp))
            BreathingLine()
        }
    }
}

/** 2s 周期呼吸光晕，只在运行中出现。 */
@Composable
private fun BreathingLine() {
    val motionEnabled = LocalMotionEnabled.current
    val transition = rememberInfiniteTransition(label = "breath")
    val alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathAlpha"
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(2.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Teal300.copy(alpha = if (motionEnabled) alpha else 0.35f),
                        Violet300.copy(alpha = if (motionEnabled) alpha else 0.35f),
                        Color.Transparent
                    )
                )
            )
    )
}

@Composable
private fun PermissionBanner(legacyStorage: Boolean, onClick: () -> Unit) {
    GlassCard(
        tint = Danger.copy(alpha = 0.16f),
        onClick = onClick,
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = if (LocalGlassConfig.current.surfaceAlpha < 0.3f) Color(0xFFFCA5A5) else Color(0xFFB91C1C),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "缺少「所有文件访问权限」",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (legacyStorage) {
                        "「存储空间」权限不够用，点此查看正确的授权路径"
                    } else {
                        "无法监听 Download / 微信 / 网盘目录，点此前往授权"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun NotificationBanner(onClick: () -> Unit) {
    GlassCard(
        tint = Color(0xFFFBBF24).copy(alpha = 0.14f),
        onClick = onClick,
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = Color(0xFFF59E0B),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "通知权限未开启",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "损坏告警与常驻通知将无法展示，点击开启",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
        }
    }
}

@Composable
private fun RedownloadHint(fileName: String, onDismiss: () -> Unit, onPaste: () -> Unit) {
    GlassCard(
        tint = Color(0xFFFBBF24).copy(alpha = 0.14f),
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = Color(0xFFF59E0B),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "缺少原始链接",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "目标：$fileName。可从浏览器分享该链接到本 App，或直接粘贴到下方输入框。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GuardButton(text = "从剪贴板填入", onClick = onPaste, modifier = Modifier.weight(1f), style = GuardButtonStyle.Glass)
            GuardButton(text = "忽略", onClick = onDismiss, modifier = Modifier.weight(1f), style = GuardButtonStyle.Glass)
        }
    }
}

@Composable
private fun DownloadCard(
    url: String,
    onUrlChange: (String) -> Unit,
    onStart: () -> Unit,
    resolving: Boolean,
    onCancel: () -> Unit,
    onUseClipboard: () -> Unit,
    state: DownloadUiState,
    tuneState: MainViewModel.TuneState,
    autoTuneEnabled: Boolean,
    onTune: () -> Unit,
    connections: Int,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenInPage: (String) -> Unit,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onConnectionsChange: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    GlassCard(contentPadding = PaddingValues(18.dp)) {
        Text(
            text = "内置加固下载器",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "最高 48 线程块级调度 · 自动测速找最快并发 · 断点续传 · 每块落盘即 fsync · 完成校验后才落地",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = "粘贴直链，或下载站网页地址（自动抓取真实链接）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            textStyle = MaterialTheme.typography.bodySmall,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
                cursorColor = MaterialTheme.colorScheme.primary
            )
        )
        Spacer(Modifier.height(10.dp))
        // 主操作独占一行，次级操作放第二行：按钮文字不再被挤成两行
        if (state is DownloadUiState.Running) {
            // 暂停与取消分开：暂停保留分片（可续传），取消才是真的丢掉这个任务
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GuardButton(
                    text = "暂停",
                    onClick = onPause,
                    modifier = Modifier.weight(1f),
                    style = GuardButtonStyle.Glass
                )
                GuardButton(
                    text = "取消下载",
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    style = GuardButtonStyle.Danger
                )
            }
        } else if (state is DownloadUiState.Paused) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GuardButton(
                    text = "继续下载",
                    onClick = onResume,
                    modifier = Modifier.weight(1f)
                )
                GuardButton(
                    text = "取消",
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    style = GuardButtonStyle.Danger
                )
            }
        } else {
            GuardButton(
                text = when {
                    resolving -> "解析链接中…"
                    LinkSniffer.looksLikeWebPage(url) -> "抓取链接并下载"
                    else -> "开始下载"
                },
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                enabled = url.isNotBlank() && !resolving
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (autoTuneEnabled) {
                    val tuning = tuneState is MainViewModel.TuneState.Probing
                    GuardButton(
                        text = if (tuning) "测速中…" else "自动测速",
                        onClick = onTune,
                        modifier = Modifier.weight(1f),
                        enabled = url.isNotBlank() && !tuning,
                        style = GuardButtonStyle.Glass
                    )
                    GuardButton(
                        text = "读取剪贴板",
                        onClick = onUseClipboard,
                        modifier = Modifier.weight(1f),
                        style = GuardButtonStyle.Glass
                    )
                } else {
                    GuardButton(
                        text = "读取剪贴板",
                        onClick = onUseClipboard,
                        modifier = Modifier.weight(1f),
                        style = GuardButtonStyle.Glass
                    )
                }
            }
        }
        // 自动测速实时进度：探测到第几档、当前档吞吐、结论与失败原因
        val tuneHint = when (val t = tuneState) {
            is MainViewModel.TuneState.Probing ->
                "探测 ${t.concurrency} 连接 · " +
                    (if (t.speed > 0) com.dlguard.download.ConcurrencyTuner.formatSpeed(t.speed) else "握手中") +
                    "（第 ${t.step}/${t.totalSteps} 档）"
            is MainViewModel.TuneState.Done -> t.note
            is MainViewModel.TuneState.Failed -> "测速失败：${t.message}"
            MainViewModel.TuneState.Idle -> ""
        }
        // 完整曲线：把每档实测值都摊开，结论可自查，不再是黑箱
        val tuneCurve = (tuneState as? MainViewModel.TuneState.Done)
            ?.probes
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("  ·  ") { "${it.concurrency}→${curveSpeed(it.bytesPerSecond)}" }
            ?.let { "$it　（MB/s）" }
            .orEmpty()
        // 下载一开始就收起测速结论：那条绿字是针对「上一次测速的那条链接」的，
        // 留在正在下载的卡片上方会让人以为「保持单连接」是本次下载的状态。
        val showTuneHint = tuneHint.isNotEmpty() &&
            state !is DownloadUiState.Running &&
            state !is DownloadUiState.Paused
        AnimatedVisibility(
            visible = showTuneHint,
            enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 6 },
            exit = fadeOut(tween(160))
        ) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    text = tuneHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (tuneState) {
                        is MainViewModel.TuneState.Done -> Success
                        is MainViewModel.TuneState.Failed -> Danger
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    }
                )
                if (tuneCurve.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = tuneCurve,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = state !is DownloadUiState.Idle,
            enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 6 },
            exit = fadeOut(tween(160))
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                when (state) {
                    is DownloadUiState.Running -> DownloadProgressCard(state, onConnectionsChange)
                    is DownloadUiState.Paused -> PausedCard(
                        state = state,
                        onResume = onResume,
                        onCancel = onCancel
                    )
                    is DownloadUiState.Success -> ResultBanner(
                        success = true,
                        title = "下载完成 · 校验通过",
                        message = "${state.fileName}\n${state.summary}",
                        onDismiss = onDismiss,
                        onOpen = { onOpenFile(state.path) }
                    )
                    is DownloadUiState.Failed -> {
                        val canRetryInPage = state.httpCode in listOf(403, 404, 410) &&
                            state.sourceUrl.isNotBlank()
                        ResultBanner(
                            success = false,
                            title = "下载失败",
                            message = state.message,
                            onDismiss = onDismiss,
                            actionText = if (canRetryInPage) "用网页模式打开" else null,
                            onAction = if (canRetryInPage) {
                                ({ onOpenInPage(state.sourceUrl) })
                            } else {
                                null
                            }
                        )
                    }
                    DownloadUiState.Idle -> Unit
                }
            }
        }
    }
}

@Composable
private fun DownloadProgressCard(
    state: DownloadUiState.Running,
    onConnectionsChange: (Int) -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .liquidGlass(
                cornerRadius = 18.dp,
                config = LocalGlassConfig.current,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                elevation = 2.dp
            )
            .padding(14.dp)
    ) {
        Column {
            // 第一行：文件名 + 百分比
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (state.total > 0) "${(state.progress * 100).toInt()}%" else "总长未知",
                    style = MonoSmallStyle,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
            // 第二行：果冻进度条（液态拖尾）
            JellyProgressBarComponent(progress = state.progress, active = state.speed > 0)
            Spacer(Modifier.height(8.dp))
            // 第三行：小字均分对齐（已下载 / 速度 / 连接数 / 剩余时间）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = buildString {
                        append(Formatters.bytes(state.downloaded))
                        if (state.total > 0) append("/${Formatters.bytes(state.total)}")
                    },
                    style = MonoSmallStyle,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                Text(
                    text = if (state.speed > 0) Formatters.speed(state.speed) else "计算中...",
                    style = MonoSmallStyle,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                Text(
                    text = when {
                        state.connections > 1 -> "${state.connections} 连接"
                        state.connections == 1 -> "单连接"
                        else -> "准备中"
                    },
                    style = MonoSmallStyle,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                Text(
                    text = when {
                        state.total <= 0 -> "流式写入"
                        state.etaSeconds > 0 -> "剩 ${Formatters.durationSeconds(state.etaSeconds)}"
                        state.progress >= 0.995f -> "即将完成"
                        else -> "剩余 计算中..."
                    },
                    style = MonoSmallStyle,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            if (state.attempt > 1) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "第 ${state.attempt} 次尝试（自动退避重试）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
            }
            if (state.hotSwitchable) {
                Spacer(Modifier.height(10.dp))
                HotSwitchSlider(
                    current = state.connections,
                    onChange = onConnectionsChange
                )
            }
        }
    }
}

/**
 * 下载中的并发热切换滑杆。
 *
 * 关键点：拖动时用本地值渲染，不被后台 250ms 一次的进度回调「拉回去」；
 * 松手后交还给真实状态，所以既跟手又不会抖。
 */
@Composable
private fun HotSwitchSlider(current: Int, onChange: (Int) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableIntStateOf(current.coerceIn(1, 48)) }
    LaunchedEffect(current) {
        if (!dragging) local = current.coerceIn(1, 48)
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "并发连接",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "热切换 · 不中断下载",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "$local",
                style = MonoSmallStyle,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = local.toFloat(),
            onValueChange = {
                dragging = true
                val next = it.roundToInt().coerceIn(1, 48)
                if (next != local) {
                    local = next
                    onChange(next)
                }
            },
            onValueChangeFinished = { dragging = false },
            valueRange = 1f..48f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 暂停态卡片：进度已落盘，给一条明确的「继续」路径。 */
@Composable
private fun PausedCard(
    state: DownloadUiState.Paused,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val fraction = if (state.total > 0) {
        (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f)
    } else {
        0f
    }
    Box(
        Modifier
            .fillMaxWidth()
            .liquidGlass(
                cornerRadius = 18.dp,
                config = LocalGlassConfig.current,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                elevation = 2.dp
            )
            .padding(14.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "已暂停",
                    style = MonoSmallStyle,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                )
            }
            Spacer(Modifier.height(8.dp))
            JellyProgressBarComponent(progress = fraction, active = false)
            Spacer(Modifier.height(8.dp))
            Text(
                text = buildString {
                    append(Formatters.bytes(state.downloaded))
                    if (state.total > 0) append(" / ${Formatters.bytes(state.total)}")
                    append("　·　分片已保留，继续时从断点接着下")
                },
                style = MonoSmallStyle,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
    }
}

@Composable
private fun ResultBanner(
    success: Boolean,
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onOpen: (() -> Unit)? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    GlassCard(
        tint = if (success) Success.copy(alpha = 0.12f) else Danger.copy(alpha = 0.14f),
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .background(
                        if (success) Success.copy(alpha = 0.2f) else Danger.copy(alpha = 0.2f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (success) Icons.Filled.Check else Icons.Filled.Close,
                    contentDescription = null,
                    tint = if (success) Success else Danger,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "关闭",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss)
                    .padding(2.dp)
            )
        }
        if (onOpen != null) {
            Spacer(Modifier.height(10.dp))
            GuardButton(
                text = "打开文件",
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth(),
                style = GuardButtonStyle.Glass
            )
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(10.dp))
            GuardButton(
                text = actionText,
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(),
                style = GuardButtonStyle.Glass
            )
        }
    }
}

@Composable
private fun RecordRow(
    record: CheckRecord,
    index: Int,
    modifier: Modifier = Modifier,
    onOpenFile: (String) -> Unit = {}
) {
    val healthy = record.healthy
    val verdict = runCatching { Verdict.valueOf(record.verdict) }.getOrNull()
    val canOpen = record.quarantinedPath.isNullOrBlank() && java.io.File(record.filePath).exists()
    GlassCard(
        modifier = modifier.clickable(enabled = canOpen) {
            if (canOpen) onOpenFile(record.filePath)
        },
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .background(
                        if (healthy) Success.copy(alpha = 0.16f) else Danger.copy(alpha = 0.16f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (healthy) Icons.Filled.Check else Icons.Filled.Close,
                    contentDescription = null,
                    tint = if (healthy) Success else Danger,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = record.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${verdict?.label ?: record.verdict} · ${Formatters.bytes(record.sizeBytes)} · ${Formatters.relative(record.checkedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (healthy) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    } else {
                        if (LocalGlassConfig.current.surfaceAlpha < 0.3f) Color(0xFFFCA5A5) else Color(0xFFB91C1C)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!record.quarantinedPath.isNullOrBlank()) {
                Text(
                    text = "已隔离",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier
                        .liquidGlass(cornerRadius = 999.dp, config = LocalGlassConfig.current, elevation = 1.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            } else if (canOpen) {
                Text(
                    text = "打开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
    }
}

/** 列表项错峰入场：40ms 逐个淡入上浮 16dp，尊重系统移除动画。 */
@Composable
private fun Modifier.staggered(index: Int): Modifier {
    val motionEnabled = LocalMotionEnabled.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(index) {
        delay(index.coerceAtMost(12) * 40L)
        visible = true
    }
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(300),
        label = "staggerAlpha$index"
    )
    val offsetY by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (visible) 0f else 16f,
        animationSpec = tween(300),
        label = "staggerOffset$index"
    )
    return this.graphicsLayer {
        this.alpha = if (motionEnabled) alpha else 1f
        translationY = if (motionEnabled) offsetY.dp.toPx() else 0f
    }
}

/** 测速曲线里的紧凑速度写法：统一换算成 MB/s 数值，单位标注在行尾。 */
private fun curveSpeed(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0) "—"
    else String.format(java.util.Locale.CHINA, "%.2f", bytesPerSecond / 1024.0 / 1024.0)

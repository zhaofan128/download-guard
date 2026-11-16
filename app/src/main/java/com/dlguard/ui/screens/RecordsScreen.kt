package com.dlguard.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dlguard.integrity.FileHasher
import kotlinx.coroutines.launch
import com.dlguard.core.Formatters
import com.dlguard.data.db.CheckRecord
import com.dlguard.integrity.Verdict
import com.dlguard.ui.MainViewModel
import com.dlguard.ui.components.EmptyState
import com.dlguard.ui.components.GlassCard
import com.dlguard.ui.components.GuardButton
import com.dlguard.ui.components.GuardButtonStyle
import com.dlguard.ui.components.liquidGlass
import com.dlguard.ui.components.rememberFileOpenController
import com.dlguard.ui.theme.Danger
import com.dlguard.ui.theme.LocalGlassConfig
import com.dlguard.ui.theme.LocalMotionEnabled
import com.dlguard.ui.theme.Spacing
import com.dlguard.ui.theme.Success
import com.dlguard.ui.theme.Teal300

@Composable
fun RecordsScreen(viewModel: MainViewModel, bottomInset: Dp) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val fileOpen = rememberFileOpenController { settings.allowInstaller }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val filtered = if (filter == 0) records else records.filter { !it.healthy }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = Spacing.Page,
            end = Spacing.Page,
            top = 10.dp,
            bottom = bottomInset + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text(
                    text = "校验记录",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "共 ${records.size} 条 · 异常 ${records.count { !it.healthy }} 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill(
                        text = "全部 ${records.size}",
                        selected = filter == 0,
                        onClick = { filter = 0 }
                    )
                    FilterPill(
                        text = "异常 ${records.count { !it.healthy }}",
                        selected = filter == 1,
                        onClick = { filter = 1 }
                    )
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                EmptyState(
                    title = if (filter == 0) "暂无校验记录" else "没有异常记录",
                    subtitle = if (filter == 0) "守护服务会在安装包下载完成后自动校验并记录结果" else "所有已校验的安装包结构完整",
                    icon = if (filter == 0) Icons.Filled.List else Icons.Filled.Check
                )
            }
        } else {
            itemsIndexed(filtered, key = { _, item -> item.id }) { index, record ->
                RecordDetailCard(
                    record = record,
                    index = index,
                    expanded = expandedId == record.id,
                    onToggle = { expandedId = if (expandedId == record.id) null else record.id },
                    onDelete = { viewModel.deleteRecord(record) },
                    onOpen = { fileOpen.open(record.filePath) },
                    onTrustRestore = { viewModel.trustAndRestore(record) }
                )
            }
        }
        item {
            if (records.isNotEmpty()) {
                GuardButton(
                    text = "清空全部记录",
                    onClick = viewModel::clearRecords,
                    modifier = Modifier.fillMaxWidth(),
                    style = GuardButtonStyle.Danger
                )
            }
        }
    }
}

@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val config = LocalGlassConfig.current
    Box(
        modifier = Modifier
            .liquidGlass(
                cornerRadius = 999.dp,
                config = config,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                } else {
                    Color.Transparent
                },
                elevation = 2.dp
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
    }
}

@Composable
private fun RecordDetailCard(
    record: CheckRecord,
    index: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onTrustRestore: () -> Unit
) {
    val motionEnabled = LocalMotionEnabled.current
    val verdict = runCatching { Verdict.valueOf(record.verdict) }.getOrNull()
    var visible by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(record.id) {
        kotlinx.coroutines.delay(index.coerceAtMost(12) * 40L)
        visible = true
    }
    val alpha by animateFloatAsState(if (visible || !motionEnabled) 1f else 0f, tween(300), label = "recordAlpha")
    val offsetY by animateFloatAsState(if (visible || !motionEnabled) 0f else 16f, tween(300), label = "recordOffset")

    GlassCard(
        modifier = Modifier.graphicsLayer {
            this.alpha = if (motionEnabled) alpha else 1f
            translationY = if (motionEnabled) offsetY.dp.toPx() else 0f
        },
        onClick = onToggle,
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(
                        if (record.healthy) Success.copy(alpha = 0.16f) else Danger.copy(alpha = 0.16f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (record.healthy) Icons.Filled.Check else Icons.Filled.Close,
                    contentDescription = null,
                    tint = if (record.healthy) Success else Danger,
                    modifier = Modifier.size(19.dp)
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
                    text = "${verdict?.label ?: record.verdict} · ${Formatters.bytes(record.sizeBytes)} · ${Formatters.shortTime(record.checkedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (record.healthy) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    } else {
                        if (LocalGlassConfig.current.surfaceAlpha < 0.3f) Color(0xFFFCA5A5) else Color(0xFFB91C1C)
                    }
                )
            }
            Text(
                text = if (expanded) "收起" else "详情",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { -it / 8 },
            exit = fadeOut(tween(140))
        ) {
            Column(Modifier.padding(top = 12.dp)) {
                DetailLine("完整路径", record.filePath)
                DetailLine("校验详情", record.detail)
                DetailLine("条目数", "${record.entriesChecked} · 耗时 ${record.elapsedMs}ms")
                if (!record.sourcePackage.isNullOrBlank()) DetailLine("来源应用", record.sourcePackage)
                if (!record.downloadUrl.isNullOrBlank()) DetailLine("下载链接", record.downloadUrl)
                if (!record.quarantinedPath.isNullOrBlank()) DetailLine("隔离位置", record.quarantinedPath)
                if (record.ignored) DetailLine("状态", "已忽略")
                Spacer(Modifier.height(6.dp))
                Row {
                    if (record.quarantinedPath.isNullOrBlank() &&
                        java.io.File(record.filePath).exists()
                    ) {
                        TextButton(onClick = onOpen) {
                            Text("打开文件", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    // 加壳包、企业定制包这类被结构校验误判的，给一条明确的放行通道
                    if (!record.quarantinedPath.isNullOrBlank()) {
                        TextButton(onClick = onTrustRestore) {
                            Text(
                                "信任并恢复",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                    TextButton(onClick = onDelete) {
                        Text("删除记录", color = Danger, style = MaterialTheme.typography.labelLarge)
                    }
                }
                HashCompareSection(
                    filePath = if (record.quarantinedPath.isNullOrBlank()) {
                        record.filePath
                    } else {
                        record.quarantinedPath
                    },
                    originPath = record.filePath
                )
            }
        }
    }
}

/**
 * 外部哈希比对：把官网公布的 MD5 / SHA-256 拿来对照本地文件。
 *
 * 内置校验证明的是「传输没坏、签名没被改」，但证明不了「这就是官方发布的那个文件」——
 * 只有发布方自己公布的摘要能做到这一点。哈希默认不显示，点一下才算，
 * 避免每次展开记录都去读一遍大文件。
 */
@Composable
private fun HashCompareSection(filePath: String, originPath: String) {
    var sha256 by remember(filePath) { mutableStateOf<String?>(null) }
    var md5 by remember(filePath) { mutableStateOf<String?>(null) }
    var busy by remember(filePath) { mutableStateOf(false) }
    var input by remember(filePath) { mutableStateOf("") }
    var verdictText by remember(filePath) { mutableStateOf<String?>(null) }
    var verdictOk by remember(filePath) { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    val target = java.io.File(filePath)
    val usable = target.exists()

    Column(Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                enabled = usable && !busy,
                onClick = {
                    busy = true
                    verdictText = null
                    verdictOk = null
                    scope.launch {
                        sha256 = FileHasher.sha256(target)
                        md5 = FileHasher.md5(target)
                        busy = false
                    }
                }
            ) {
                Text(
                    text = when {
                        !usable -> "文件已不在原位"
                        busy -> "计算中…"
                        sha256 == null -> "计算文件哈希"
                        else -> "重新计算"
                    },
                    style = MaterialTheme.typography.labelLarge
                )
            }
            if (sha256 != null) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = "SHA-256 · MD5",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
        sha256?.let { DetailLine("SHA-256", it) }
        md5?.let { DetailLine("MD5", it) }
        if (usable && sha256 != null) {
            Spacer(Modifier.height(6.dp))
            androidx.compose.material3.OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("粘贴官网公布的 MD5 / SHA-256") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = input.isNotBlank(),
                    onClick = {
                        val parsed = FileHasher.parseBaseline(input)
                        if (parsed == null) {
                            verdictOk = false
                            verdictText = "识别不出校验值：支持 32 位 MD5、40 位 SHA-1、64 位 SHA-256"
                            return@TextButton
                        }
                        val (algorithm, want) = parsed
                        val actual = if (algorithm == "MD5") md5 else sha256
                        if (actual == null) {
                            verdictOk = false
                            verdictText = "还没算出本地 $algorithm，先点上面的「计算文件哈希」"
                            return@TextButton
                        }
                        verdictOk = FileHasher.matches(actual, want)
                        verdictText = if (verdictOk == true) {
                            "与官方值一致：这就是发布方发出的那一份"
                        } else {
                            "与官方值不符。文件可能被替换过，建议从官方渠道重新下载"
                        }
                    }
                ) {
                    Text("比对", style = MaterialTheme.typography.labelLarge)
                }
                verdictText?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (verdictOk == true) Success else Danger
                    )
                }
            }
        }
        if (!usable) {
            Text(
                text = "原位置：$originPath",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.width(74.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            modifier = Modifier.weight(1f)
        )
    }
}

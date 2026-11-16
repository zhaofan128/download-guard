package com.dlguard.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dlguard.bt.BtState
import com.dlguard.bt.BtTask
import com.dlguard.bt.formatBytes
import com.dlguard.bt.formatRate
import com.dlguard.ui.BtViewModel
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import org.libtorrent4j.Priority

/**
 * BT/磁力页。
 *
 * 独立的第 5 个标签页，与 HTTP 下载列表分开：BT 有自己做种/节点/分享率的概念，
 * 塞进 HTTP 卡片会混淆两类任务。状态全部来自 [BtViewModel.tasks]（应用级引擎）。
 */
@Composable
fun BtScreen(viewModel: BtViewModel, bottomInset: Dp = 86.dp) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val dhtNodes by viewModel.dhtNodes.collectAsStateWithLifecycle()
    val limits by viewModel.limits.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var showLimitDialog by remember { mutableStateOf(false) }
    var pickerTask by remember { mutableStateOf<BtTask?>(null) }
    var deleteTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // id -> name

    val torrentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) scope.launch { viewModel.addTorrentPath(uri.toString()) }
    }

    LaunchedEffect(Unit) {
        viewModel.pendingDelete.collect { (id, _) ->
            deleteTarget = id to (tasks.firstOrNull { it.id == id }?.name ?: "这个任务")
        }
    }
    LaunchedEffect(Unit) {
        viewModel.filePicker.collect { id ->
            pickerTask = tasks.firstOrNull { it.id == id }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(bottom = bottomInset)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("BT / 磁力下载", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "DHT 节点 $dhtNodes · 完成后的任务持续做种，直到你手动暂停或删除",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("magnet: 链接 或 .torrent 路径") },
                placeholder = { Text("magnet:?xt=urn:btih:…") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors()
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { torrentPicker.launch("application/x-bittorrent") }) {
                Icon(Icons.Filled.Add, contentDescription = "选择种子文件")
            }
            Button(onClick = {
                if (input.isNotBlank()) {
                    viewModel.add(input)
                    input = ""
                }
            }) { Icon(Icons.Filled.Add, contentDescription = null) }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "限速：↓ ${if (limits.downloadKb <= 0) "不限" else formatRate(limits.downloadKb * 1024L)}" +
                    "  ↑ ${if (limits.uploadKb <= 0) "不限" else formatRate(limits.uploadKb * 1024L)}",
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = { showLimitDialog = true }) { Text("设置") }
        }

        Spacer(Modifier.height(8.dp))
        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有 BT 任务", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "粘贴磁力链接，或点文件夹图标选 .torrent。\n元数据通过 DHT 获取，可能需要几十秒。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(tasks, key = { it.id }) { task ->
                    BtTaskCard(
                        task = task,
                        onPauseResume = { if (task.paused) viewModel.resume(task.id) else viewModel.pause(task.id) },
                        onRecheck = { viewModel.recheck(task.id) },
                        onFiles = { viewModel.openFilePicker(task.id) },
                        onDelete = { viewModel.askDelete(task.id, false) },
                        onDeleteFiles = { viewModel.askDelete(task.id, true) }
                    )
                }
            }
        }
    }

    if (showLimitDialog) {
        LimitDialog(
            current = limits,
            onDismiss = { showLimitDialog = false },
            onConfirm = { d, u -> viewModel.setLimits(d, u); showLimitDialog = false }
        )
    }
    pickerTask?.let { task ->
        FilePickerDialog(
            task = task,
            onDismiss = { pickerTask = null },
            onConfirm = { priorities ->
                viewModel.applyFilePriorities(task.id, priorities)
                pickerTask = null
            },
            loadFiles = { viewModel.fileList(task.id) }
        )
    }
    deleteTarget?.let { (id, name) ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除任务") },
            text = { Text("「$name」要怎么删？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.confirmDelete(id, false)
                    deleteTarget = null
                }) { Text("仅移除任务") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.confirmDelete(id, true)
                        deleteTarget = null
                    }) { Text("连文件一起删") }
                    TextButton(onClick = { deleteTarget = null }) { Text("取消") }
                }
            }
        )
    }
}

@Composable
private fun BtTaskCard(
    task: BtTask,
    onPauseResume: () -> Unit,
    onRecheck: () -> Unit,
    onFiles: () -> Unit,
    onDelete: () -> Unit,
    onDeleteFiles: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        task.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${task.state.label} · ${(task.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (task.state == BtState.ERROR) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                IconButton(onClick = onPauseResume) {
                    Icon(
                        if (task.paused) Icons.Filled.PlayArrow else Icons.Filled.Clear,
                        contentDescription = if (task.paused) "继续" else "暂停"
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = task.progress,
                Modifier.fillMaxWidth().height(6.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "↓ ${formatRate(task.downloadRate)}   ↑ ${formatRate(task.uploadRate)}" +
                    "   ${formatBytes(task.downloaded)} / ${formatBytes(task.total)}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "种子 ${task.seeds} · 节点 ${task.dhtNodes} · 已上传 ${formatBytes(task.totalUploaded)}" +
                    " · 分享率 ${String.format("%.2f", task.shareRatio)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            if (task.message != null) {
                Text(task.message!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onFiles) { Text("文件") }
                TextButton(onClick = onRecheck) { Text("校验") }
                TextButton(onClick = onDeleteFiles) { Text("删文件") }
                TextButton(onClick = onDelete) { Text("移除") }
            }
        }
    }
}

@Composable
private fun LimitDialog(
    current: com.dlguard.bt.BtEngine.Limits,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    var d by remember { mutableStateOf(if (current.downloadKb <= 0) "" else current.downloadKb.toString()) }
    var u by remember { mutableStateOf(if (current.uploadKb <= 0) "" else current.uploadKb.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("速度限制（KB/s）") },
        text = {
            Column {
                Text("留空 = 不限速", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = d, onValueChange = { d = it.filter { c -> c.isDigit() } },
                    label = { Text("下载上限") }, singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = u, onValueChange = { u = it.filter { c -> c.isDigit() } },
                    label = { Text("上传上限") }, singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(d.toIntOrNull() ?: 0, u.toIntOrNull() ?: 0)
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun FilePickerDialog(
    task: BtTask,
    onDismiss: () -> Unit,
    onConfirm: (List<Priority>) -> Unit,
    loadFiles: suspend () -> List<com.dlguard.bt.BtEngine.BtFile>
) {
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<com.dlguard.bt.BtEngine.BtFile>>(emptyList()) }
    val selected = remember { mutableStateOf(setOf<Int>()) }
    LaunchedEffect(task.id) {
        files = loadFiles()
        selected.value = files.indices.toSet()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择要下载的文件") },
        text = {
            LazyColumn(Modifier.heightInMax(320.dp)) {
                items(files, key = { it.index }) { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = f.index in selected.value,
                            onCheckedChange = { checked ->
                                selected.value = if (checked) selected.value + f.index else selected.value - f.index
                            }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(f.path, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            Text(formatBytes(f.size), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val priorities = files.map { if (it.index in selected.value) Priority.DEFAULT else Priority.IGNORE }
                onConfirm(priorities)
            }) { Text("应用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun Modifier.heightInMax(max: Dp): Modifier = this.then(Modifier.heightIn(max = max))

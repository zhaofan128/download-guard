package com.dlguard.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dlguard.DownloadGuardApp
import com.dlguard.site.CookieMode
import com.dlguard.site.RefererMode
import com.dlguard.site.SiteRule
import com.dlguard.site.SiteRules
import com.dlguard.ui.components.GuardButton
import com.dlguard.ui.components.GuardButtonStyle
import com.dlguard.ui.components.GlassCard
import com.dlguard.ui.theme.DownloadGuardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 站点规则管理页。
 *
 * 定位：把「不同网盘的差异」变成可导入导出的声明式配置，而不是给每个站点写死代码。
 * 安全边界（页面顶部也写给用户看）：
 *  - 规则不含任何可执行脚本，只是「请求头 / 并发 / 文件名」的取值说明；
 *  - 导出的文件会**清空自定义 Cookie 的值**，分享规则不会顺带分享登录态；
 *  - Cookie 只在本机参与请求，不进日志、不上传。
 */
class SiteRulesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            DownloadGuardTheme(
                darkTheme = isSystemInDarkTheme(),
                dynamicColor = false,
                lowRamDevice = false
            ) {
                SiteRulesScreen(onClose = { finish() })
            }
        }
    }
}

/** 编辑中的草稿：全部用字符串保存，避免输入过程中被类型转换打断。 */
private data class RuleDraft(
    val id: String = "",
    val name: String = "",
    val hosts: String = "",
    val priority: String = "100",
    val enabled: Boolean = true,
    val refererMode: String = RefererMode.PAGE,
    val refererValue: String = "",
    val cookieMode: String = CookieMode.MERGE,
    val cookieValue: String = "",
    val userAgent: String = "",
    val headers: String = "",
    val concurrency: String = "0",
    val fileNameTemplate: String = "",
    val note: String = ""
) {
    fun toRule(): SiteRule? {
        val hostList = hosts.split(',', '，', '\n', ' ', '\t')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (hostList.isEmpty() || id.isBlank()) return null
        return SiteRule(
            id = id,
            name = name.trim().ifBlank { hostList.first() }.take(80),
            enabled = enabled,
            priority = priority.toIntOrNull() ?: 100,
            hosts = hostList,
            refererMode = refererMode,
            refererValue = if (refererMode == RefererMode.CUSTOM) refererValue.trim() else "",
            cookieMode = cookieMode,
            cookieValue = if (cookieMode == CookieMode.CUSTOM) cookieValue.trim() else "",
            userAgent = userAgent.trim(),
            headers = SiteRules.parseHeaderLines(headers),
            concurrency = (concurrency.toIntOrNull() ?: 0).coerceIn(0, 64),
            fileNameTemplate = fileNameTemplate.trim(),
            note = note.trim()
        )
    }

    companion object {
        fun from(rule: SiteRule): RuleDraft = RuleDraft(
            id = rule.id,
            name = rule.name,
            hosts = rule.hosts.joinToString(", "),
            priority = rule.priority.toString(),
            enabled = rule.enabled,
            refererMode = rule.refererMode,
            refererValue = rule.refererValue,
            cookieMode = rule.cookieMode,
            cookieValue = rule.cookieValue,
            userAgent = rule.userAgent,
            headers = rule.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" },
            concurrency = rule.concurrency.toString(),
            fileNameTemplate = rule.fileNameTemplate,
            note = rule.note
        )

        fun blank(): RuleDraft = RuleDraft(
            id = "rule-${System.currentTimeMillis().toString(36)}"
        )
    }
}

@Composable
private fun SiteRulesScreen(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = (context.applicationContext as DownloadGuardApp).container
    val rules by container.siteRuleStore.rules.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<RuleDraft?>(null) }
    var testing by remember { mutableStateOf<SiteRule?>(null) }
    var deleting by remember { mutableStateOf<SiteRule?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(message) {
        val text = message
        if (text != null) {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            message = null
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                message = "读取文件失败，或文件为空"
                return@launch
            }
            val result = container.siteRuleStore.import(text, merge = true)
            message = when {
                result.error != null -> result.error
                result.skipped > 0 -> "导入 ${result.rules.size} 条规则，跳过 ${result.skipped} 条不合法条目"
                else -> "已导入 ${result.rules.size} 条规则"
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = container.siteRuleStore.export()
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    }
                }.isSuccess
            }
            message = if (ok) "已导出 ${rules.size} 条规则（自定义 Cookie 的值已清空）" else "导出失败"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "站点规则",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "按域名决定 Referer / Cookie 来源 / UA / 并发 / 文件名",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GlassCard(contentPadding = PaddingValues(16.dp)) {
                    Text(
                        text = "纯声明式，不含可执行代码",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "规则只描述「请求怎么发、文件怎么命名」，不能读写文件、不能执行脚本。" +
                            "导出的 JSON 会清空自定义 Cookie 的值——分享规则不会顺带分享登录态。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GuardButton(
                            text = "新建",
                            onClick = { editing = RuleDraft.blank() },
                            modifier = Modifier.weight(1f),
                            icon = Icons.Filled.Add
                        )
                        GuardButton(
                            text = "导入",
                            onClick = {
                                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                            },
                            modifier = Modifier.weight(1f),
                            style = GuardButtonStyle.Glass
                        )
                        GuardButton(
                            text = "导出",
                            onClick = { exportLauncher.launch("dlguard-site-rules.json") },
                            modifier = Modifier.weight(1f),
                            style = GuardButtonStyle.Glass,
                            enabled = rules.isNotEmpty()
                        )
                    }
                }
            }

            if (rules.isEmpty()) {
                item {
                    GlassCard(contentPadding = PaddingValues(20.dp)) {
                        Text(
                            text = "还没有规则",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "示例：pan.example.com 用「页面 Referer + 页面/直链 Cookie 合并」，" +
                                "dl.example.net 用另一套 UA。规则按 host 自动命中，下载时无需再填任何东西。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                    }
                }
            }

            items(rules, key = { it.id }) { rule ->
                RuleCard(
                    rule = rule,
                    onToggle = { enabled ->
                        scope.launch { container.siteRuleStore.setEnabled(rule.id, enabled) }
                    },
                    onEdit = { editing = RuleDraft.from(rule) },
                    onTest = { testing = rule },
                    onDelete = { deleting = rule }
                )
            }
        }
    }

    editing?.let { draft ->
        RuleEditorDialog(
            draft = draft,
            onDismiss = { editing = null },
            onSave = { updated ->
                val rule = updated.toRule()
                if (rule == null) {
                    message = "至少填一个域名"
                } else {
                    scope.launch {
                        container.siteRuleStore.upsert(rule)
                        editing = null
                        message = "已保存「${rule.name}」"
                    }
                }
            }
        )
    }

    testing?.let { rule ->
        RuleTestDialog(
            rule = rule,
            resolver = container.siteRuleResolver,
            onDismiss = { testing = null }
        )
    }

    deleting?.let { rule ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除规则") },
            text = { Text("确定删除「${rule.name}」？已下载的任务不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.siteRuleStore.delete(rule.id)
                        deleting = null
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun RuleCard(
    rule: SiteRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit
) {
    GlassCard(contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = rule.hosts.joinToString("、"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = rule.summary(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        if (rule.note.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = rule.note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onTest) {
                Icon(
                    Icons.Filled.PlayArrow, "测试",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Filled.Edit, "编辑",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete, "删除",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.75f)
                )
            }
        }
    }
}

@Composable
private fun RuleEditorDialog(
    draft: RuleDraft,
    onDismiss: () -> Unit,
    onSave: (RuleDraft) -> Unit
) {
    var d by remember { mutableStateOf(draft) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.hosts.isBlank() && draft.name.isBlank()) "新建规则" else "编辑规则") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DraftField("名称", d.name, { d = d.copy(name = it) }, "例如：示例网盘")
                DraftField(
                    "域名（逗号分隔）", d.hosts, { d = d.copy(hosts = it) },
                    "pan.example.com, *.example.net"
                )
                Text(
                    text = "example.com 精确匹配；*.example.com 匹配自身与所有子域；* 匹配全部。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
                DraftField("优先级", d.priority, { d = d.copy(priority = it) }, "100", number = true)

                Text("Referer 来源", style = MaterialTheme.typography.labelSmall)
                ModeChips(
                    options = RefererMode.ALL,
                    label = { RefererMode.label(it) },
                    selected = d.refererMode,
                    onSelect = { d = d.copy(refererMode = it) }
                )
                if (d.refererMode == RefererMode.CUSTOM) {
                    DraftField(
                        "Referer 值", d.refererValue, { d = d.copy(refererValue = it) },
                        "https://pan.example.com/"
                    )
                }

                Text("Cookie 来源", style = MaterialTheme.typography.labelSmall)
                ModeChips(
                    options = CookieMode.ALL,
                    label = { CookieMode.label(it) },
                    selected = d.cookieMode,
                    onSelect = { d = d.copy(cookieMode = it) }
                )
                if (d.cookieMode == CookieMode.CUSTOM) {
                    DraftField(
                        "Cookie 值（仅存本机，导出时清空）",
                        d.cookieValue,
                        { d = d.copy(cookieValue = it) },
                        "name=value; name2=value2"
                    )
                }

                DraftField("User-Agent（留空用内置）", d.userAgent, { d = d.copy(userAgent = it) }, "")
                DraftField(
                    "额外 Header（每行 Key: Value）", d.headers,
                    { d = d.copy(headers = it) }, "Accept-Language: zh-CN", minLines = 3
                )
                DraftField(
                    "初始并发（0 = 跟随全局）", d.concurrency,
                    { d = d.copy(concurrency = it) }, "0", number = true
                )
                DraftField(
                    "文件名模板", d.fileNameTemplate, { d = d.copy(fileNameTemplate = it) },
                    "{name}-{date}.{ext}"
                )
                Text(
                    text = "占位符：{name} {ext} {host} {date} {rule}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
                DraftField("备注", d.note, { d = d.copy(note = it) }, "可选")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = d.enabled, onCheckedChange = { d = d.copy(enabled = it) })
                    Spacer(Modifier.width(10.dp))
                    Text("启用", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(d) }) {
                Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun RuleTestDialog(
    rule: SiteRule,
    resolver: com.dlguard.site.SiteRuleResolver,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf("") }
    var pageUrl by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("测试「${rule.name}」") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "只做规则匹配与请求头计算，不发起任何网络请求。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                DraftField("直链 URL", url, { url = it }, "https://dl.example.net/file.zip")
                DraftField("页面 URL（可选）", pageUrl, { pageUrl = it }, "https://pan.example.com/s/abc")
                GuardButton(
                    text = if (running) "计算中…" else "计算请求头",
                    onClick = {
                        if (url.isBlank()) {
                            result = "请先填直链 URL"
                        } else {
                            running = true
                            scope.launch {
                                result = runCatching {
                                    resolver.preview(
                                        rule = rule,
                                        url = url.trim(),
                                        pageUrl = pageUrl.trim().takeIf { it.isNotEmpty() }
                                    ).describe()
                                }.getOrElse { "计算失败：${it.message ?: "未知错误"}" }
                                running = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !running
                )
                result?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun DraftField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    number: Boolean = false,
    minLines: Int = 1
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        placeholder = {
            if (placeholder.isNotEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            }
        },
        singleLine = minLines == 1,
        minLines = minLines,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number)
        else KeyboardOptions.Default,
        textStyle = MaterialTheme.typography.bodySmall,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        )
    )
}

@Composable
private fun ModeChips(
    options: List<String>,
    label: (String) -> String,
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { option ->
            val active = option == selected
            Box(
                modifier = Modifier
                    .background(
                        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

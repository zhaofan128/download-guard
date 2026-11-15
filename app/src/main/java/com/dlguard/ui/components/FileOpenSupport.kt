package com.dlguard.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dlguard.core.FileOpener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * 「打开文件」的 UI 侧胶水。
 *
 * 安装包走标准路径：FileProvider + ACTION_VIEW 交给系统安装器，任何设备、任何 ROM
 * 都能用，不依赖 Shizuku 或 root。只有两种情况会打断流程：
 * 1. 没授权「安装未知应用」→ 弹窗引导去开关，返回后自动续装；
 * 2. 系统安装器确实被 ROM 拦死 → 弹窗说明，若手机上恰好有 Shizuku，可一键改走
 *    `pm install` 兜底（纯可选，没有也不影响）。
 */
@Stable
class FileOpenController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val allowInstall: () -> Boolean
) {

    /** 未授权「安装未知应用」的引导弹窗。 */
    var permissionPrompt by mutableStateOf(false)
        private set

    /** 系统安装器被拦截时的兜底弹窗。 */
    var blockedPrompt by mutableStateOf(false)
        private set

    var busy by mutableStateOf(false)
        private set

    private var pendingPath: String? = null
    private var awaitingGrant = false

    /** 待安装文件名，用于弹窗里给个明确目标。 */
    val pendingName: String?
        get() = pendingPath?.let { runCatching { File(it).name }.getOrNull() }

    /** 手机上是否装了可用的 Shizuku（决定兜底弹窗是「一键安装」还是纯说明）。 */
    val shizukuAvailable: Boolean
        get() = FileOpener.shizukuReady()

    fun open(path: String) {
        if (busy) return
        scope.launch {
            busy = true
            val result = runCatching { FileOpener.open(context, path, allowInstall()) }
                .getOrElse { FileOpener.Result.Failed(it.message ?: "打开失败") }
            busy = false
            handle(path, result)
        }
    }

    private fun handle(path: String, result: FileOpener.Result) {
        when (result) {
            FileOpener.Result.Opened -> Unit
            FileOpener.Result.InstallerDisabled -> toast("「允许安装应用」已关闭，可在设置里打开")
            FileOpener.Result.NeedInstallPermission -> {
                pendingPath = path
                awaitingGrant = true
                permissionPrompt = true
            }
            is FileOpener.Result.Failed -> {
                if (path.endsWith(".apk", ignoreCase = true) && shizukuAvailable) {
                    pendingPath = path
                    blockedPrompt = true
                } else {
                    toast(result.reason)
                }
            }
        }
    }

    fun grantPermission() {
        val path = pendingPath ?: return
        scope.launch {
            busy = true
            val retried = runCatching { FileOpener.open(context, path, allowInstall()) }
                .getOrElse { FileOpener.Result.Failed(it.message ?: "打开失败") }
            busy = false
            if (retried == FileOpener.Result.Opened) {
                permissionPrompt = false
                pendingPath = null
                awaitingGrant = false
            } else {
                // 用户还没打开开关，把他送到设置页；返回 App 时自动再试一次
                FileOpener.openInstallSettings(context)
            }
        }
    }

    fun dismissPermissionPrompt() {
        permissionPrompt = false
        awaitingGrant = false
        pendingPath = null
    }

    fun installWithShizuku() {
        val path = pendingPath ?: return
        scope.launch {
            busy = true
            toast("正在通过 Shizuku 安装…")
            val result = runCatching { FileOpener.installWithShizuku(context, path) }
                .getOrElse { FileOpener.Result.Failed(it.message ?: "安装失败") }
            busy = false
            blockedPrompt = false
            pendingPath = null
            when (result) {
                FileOpener.Result.Opened -> toast("安装完成")
                is FileOpener.Result.Failed -> toast(result.reason)
                else -> Unit
            }
        }
    }

    fun dismissBlockedPrompt() {
        blockedPrompt = false
        pendingPath = null
    }

    internal fun onResume() {
        if (!awaitingGrant) return
        val path = pendingPath ?: return
        if (!FileOpener.canInstall(context)) return
        awaitingGrant = false
        permissionPrompt = false
        open(path)
    }

    private fun toast(text: String) {
        runCatching { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }
}

@Composable
fun rememberFileOpenController(allowInstall: () -> Boolean): FileOpenController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentAllow = rememberUpdatedState(allowInstall)
    val controller = remember(context, scope) {
        FileOpenController(context, scope) { currentAllow.value() }
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) controller.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (controller.permissionPrompt) {
        AlertDialog(
            onDismissRequest = controller::dismissPermissionPrompt,
            title = { Text("还差一步：允许安装应用") },
            text = {
                Text(
                    buildString {
                        append("Android 要求应用先获得「安装未知应用」授权，才能调起系统安装器。\n\n")
                        append("点「去开启」，把「允许来自此来源的应用」打开，再按返回键，会自动继续安装。")
                        append(pendingHint(controller))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 8
                )
            },
            confirmButton = {
                TextButton(onClick = controller::grantPermission) { Text("去开启") }
            },
            dismissButton = {
                TextButton(onClick = controller::dismissPermissionPrompt) { Text("取消") }
            }
        )
    }

    if (controller.blockedPrompt) {
        AlertDialog(
            onDismissRequest = controller::dismissBlockedPrompt,
            title = { Text("系统安装器没有响应") },
            text = {
                Text(
                    buildString {
                        append("安装包已经交给系统，但手机的安装器没有启动，")
                        append("常见于 ROM 的「安装前安全检测」拦截。\n\n")
                        append("可以到文件管理器里手动打开这个安装包；")
                        append("也可以让 Shizuku 直接调用系统包管理器安装，绕开安装器界面。")
                        append(pendingHint(controller))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 8
                )
            },
            confirmButton = {
                TextButton(onClick = controller::installWithShizuku) { Text("用 Shizuku 安装") }
            },
            dismissButton = {
                TextButton(onClick = controller::dismissBlockedPrompt) { Text("关闭") }
            }
        )
    }
    return controller
}

private fun pendingHint(controller: FileOpenController): String {
    val name = controller.pendingName ?: return ""
    return "\n\n待安装：$name"
}

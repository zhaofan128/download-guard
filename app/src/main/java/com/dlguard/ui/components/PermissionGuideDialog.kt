package com.dlguard.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dlguard.core.PermissionHelper
import com.dlguard.ui.theme.Success

/**
 * 权限向导：解释「存储空间」和「所有文件访问」的区别，并给出本机 ROM 的具体路径。
 *
 * 之所以做成对话框而不是直接跳系统设置：国产 ROM 有相当比例的机型打不开
 * `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`，直接跳会毫无反应，
 * 用户只会以为应用坏了。这里保证即使三级跳转全失败，页面上仍有可照抄的文字路径。
 *
 * 挂载在 AppShell 层级，首页和设置页共用一个实例。
 */
@Composable
fun PermissionGuideDialog(
    legacyStorage: Boolean,
    granted: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    val context = LocalContext.current
    // 第一次点跳「本应用」页，没找到开关再点自动切到应用列表页
    var jumpAttempts by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(
            cornerRadius = 28.dp,
            contentPadding = PaddingValues(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (granted) "权限已就绪" else "需要「所有文件访问权限」",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (granted) {
                    "检测到已授权，可以开始守护了。"
                } else if (legacyStorage) {
                    "你在应用信息里开的「存储空间」只是普通读写权限，只能读图片和媒体；" +
                        "监听 Download、微信、网盘目录必须授予「所有文件访问权限」。"
                } else {
                    "本应用需要读取 Download、浏览器、微信、网盘等公共目录才能发现下载完成的安装包，" +
                        "这需要「所有文件访问权限」，普通「存储空间」权限不够。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(if (granted) Success else Color(0xFFF59E0B), CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (granted) "当前状态：已授予" else "当前状态：未授予",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            if (!granted) {
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                        .padding(12.dp)
                ) {
                    Text(
                        text = PermissionHelper.allFilesAccessHint(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "部分 ROM 的开关叫「允许管理所有文件」或「所有文件访问」，打开后回到本页点下方按钮。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
            }
            Spacer(Modifier.height(18.dp))
            if (granted) {
                GuardButton(
                    text = "开始守护",
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    style = GuardButtonStyle.Primary
                )
            } else {
                GuardButton(
                    text = "去系统设置授权",
                    onClick = {
                        jumpAttempts += 1
                        val result = if (jumpAttempts >= 2) {
                            if (PermissionHelper.openAllFilesAccessList(context)) {
                                PermissionHelper.JumpResult.LIST_PAGE
                            } else {
                                PermissionHelper.openAllFilesAccessDetailed(context)
                            }
                        } else {
                            PermissionHelper.openAllFilesAccessDetailed(context)
                        }
                        val tip = when (result) {
                            PermissionHelper.JumpResult.APP_PAGE -> "请打开「允许管理所有文件」开关"
                            PermissionHelper.JumpResult.LIST_PAGE ->
                                "已打开「所有文件访问」列表，请找到「浏览器下载接管」并打开开关"
                            PermissionHelper.JumpResult.APP_DETAILS -> "请点「权限」→「所有文件访问」打开开关"
                            PermissionHelper.JumpResult.FAILED -> "系统设置打不开，请按上面路径手动操作"
                        }
                        Toast.makeText(context, tip, Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    style = GuardButtonStyle.Primary
                )
                Spacer(Modifier.height(10.dp))
                GuardButton(
                    text = "我已授权，重新检测",
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    style = GuardButtonStyle.Glass
                )
            }
            Spacer(Modifier.height(6.dp))
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = "稍后",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 10.dp)
                )
            }
        }
    }
}

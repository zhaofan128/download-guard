package com.dlguard.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.dlguard.shizuku.ShizukuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 用系统默认应用打开下载完成的文件（安装包交给系统安装器、压缩包交给解压器、
 * 视频交给播放器、PDF 交给阅读器）。
 *
 * **安装包走标准路径**：`ACTION_VIEW` + `content://`（FileProvider）交给系统安装器，
 * 这是所有 Android 应用通用的做法，不依赖 Shizuku、不需要 root，任何人装上就能用。
 *
 * Shizuku 只作为**可选兜底**：[installWithShizuku] 由用户在失败对话框里主动触发，
 * 用于个别 ROM 把系统安装器拦死（报「安装包已损坏」）的场景。没有 Shizuku 完全不影响
 * 正常安装。
 */
object FileOpener {

    sealed interface Result {
        /** 已交给系统安装器或目标应用。 */
        data object Opened : Result

        /** 设置里的「允许安装应用」被关掉了。 */
        data object InstallerDisabled : Result

        /** 尚未授权「安装未知应用」，需要引导用户去设置页打开。 */
        data object NeedInstallPermission : Result

        /** 打不开，附带给用户看的原因。 */
        data class Failed(val reason: String) : Result
    }

    /** Shizuku 是否可用于兜底安装（已装、在运行、已授权本应用）。 */
    fun shizukuReady(): Boolean = runCatching { ShizukuHelper.hasPermission() }.getOrDefault(false)

    /**
     * @param allowInstall 设置里的「允许安装应用」，只对 APK 生效。
     */
    suspend fun open(context: Context, path: String, allowInstall: Boolean = true): Result {
        val file = File(path)
        if (!file.exists() || !file.isFile) return Result.Failed("文件不存在，可能已被移动或删除")
        if (file.length() <= 0L) return Result.Failed("文件为空，无法打开")

        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (t: Throwable) {
            return Result.Failed("无法生成文件访问地址：${t.message ?: "文件路径不在可共享范围内"}")
        }

        val isApk = file.extension.equals("apk", ignoreCase = true)
        val mime = if (isApk) APK_MIME else mimeOf(file)

        if (isApk) {
            // 商店版没有 REQUEST_INSTALL_PACKAGES：调用安装器会被系统静默丢弃，
            // 与其让用户以为「点了没反应」，不如明确告知走系统文件管理器。
            if (com.dlguard.BuildConfig.STORE_BUILD) {
                return Result.Failed("商店版不提供应用安装：文件已保存，请用系统「文件」应用打开")
            }
            if (!allowInstall) return Result.InstallerDisabled
            // Android 8+ 未授权「安装未知应用」时，安装器会静默结束，这里提前拦下来给出引导
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !canInstall(context)) {
                return Result.NeedInstallPermission
            }
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isApk) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        grantToResolvers(context, intent, uri)

        if (start(context, intent)) return Result.Opened

        return if (isApk) {
            Result.Failed("系统安装器没能启动，可以到文件管理器里手动点开这个安装包")
        } else {
            Result.Failed("没有能打开 .${file.extension} 的应用，可以到文件管理器里手动选择")
        }
    }

    /** 是否已获得「安装未知应用」授权（Android 8 以下恒为 true）。 */
    fun canInstall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(true)
    }

    /** 跳转「安装未知应用」授权页；不同 ROM 页面名不同，逐级降级。 */
    fun openInstallSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val pkgUri = Uri.parse("package:${context.packageName}")
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkgUri),
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (start(context, intent)) return
        }
    }

    /** 跳转 Shizuku 应用本体，便于用户授权。 */
    fun openShizuku(context: Context): Boolean {
        val candidates = listOf(
            Intent().setClassName("moe.shizuku.privileged.api", "moe.shizuku.manager.MainActivity"),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (start(context, intent)) return true
        }
        return false
    }

    fun mimeOf(file: File): String {
        val ext = file.extension.lowercase()
        return when (ext) {
            "apk" -> APK_MIME
            "apks", "xapk", "zip" -> "application/zip"
            "7z" -> "application/x-7z-compressed"
            "rar" -> "application/vnd.rar"
            "pdf" -> "application/pdf"
            "txt", "log", "md" -> "text/plain"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        }
    }

    // ---------------------------------------------------------------- 可选兜底

    /**
     * 用 Shizuku 执行 `pm install` 直接装包，仅在系统安装器被 ROM 拦死时由用户主动选择。
     * `-r` 覆盖安装、`-t` 允许 testOnly 包、`-d` 允许降级覆盖。
     */
    suspend fun installWithShizuku(context: Context, path: String): Result = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists() || !file.isFile) return@withContext Result.Failed("文件不存在，可能已被移动或删除")
        if (!shizukuReady()) return@withContext Result.Failed("Shizuku 未连接或未授权本应用")
        val cmd = "pm install -r -t -d ${shellQuote(file.absolutePath)}"
        val output = ShizukuHelper.execShell(cmd).getOrElse { t ->
            val detail = (t.message ?: t.javaClass.simpleName).trim().take(200)
            return@withContext Result.Failed("Shizuku 安装失败：$detail")
        }
        if (output.contains("Success", ignoreCase = true)) {
            Result.Opened
        } else {
            Result.Failed("Shizuku 安装失败：${output.trim().take(200).ifBlank { "无输出" }}")
        }
    }

    private const val APK_MIME = "application/vnd.android.package-archive"

    private fun shellQuote(raw: String): String {
        val escaped = raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("`", "\\`")
        return "\"$escaped\""
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        false
    }

    /** 给能接住这个 Intent 的应用显式授予读权限（部分 ROM 不认 Intent 上的 grant 标记）。 */
    private fun grantToResolvers(context: Context, intent: Intent, uri: Uri) {
        runCatching {
            val pm = context.packageManager
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            resolved.forEach { info ->
                val pkg = info.activityInfo?.packageName ?: return@forEach
                runCatching {
                    context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
    }
}

package com.dlguard.plugin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 浏览器扩展辅助：检测手机里可装的浏览器、导出 MV3 扩展包、把用户送到安装入口。
 *
 * 关于「直接写进浏览器扩展管理页」：Android 的沙箱不允许一个 App 修改另一个 App 的
 * 私有数据目录（扩展列表就存在浏览器私有目录里），所以任何声称"一键写入"的做法
 * 要么需要 root，要么是假的。这里采取能真正跑通的路径：
 *  1. 把扩展目录解压到公共 `Download/DownloadGuard-Extension`（浏览器选择目录时可见）；
 *  2. 同时导出一份 zip，可发到电脑用「加载已解压的扩展程序」；
 *  3. 一键拉起已安装的浏览器，并提供路径复制 / 安装步骤，减少手工找目录的成本。
 */
object BrowserExtensionHelper {

    data class Browser(
        val label: String,
        val packageName: String,
        /** 是否支持 Chromium 扩展（MV3）。 */
        val supportsExtensions: Boolean,
        /** 是否自带扩展商店/扩展管理页（用于提示用户去哪里安装）。 */
        val hasExtensionStore: Boolean = false
    )

    /** 常见浏览器包名白名单（AndroidManifest 的 queries 需同步声明才可见）。 */
    private val KNOWN = listOf(
        Browser("Chrome", "com.android.chrome", true),
        Browser("Chrome Beta", "com.chrome.beta", true),
        Browser("Microsoft Edge", "com.microsoft.emmx", true),
        Browser("Kiwi Browser", "com.kiwibrowser.browser", true, true),
        Browser("Mises Browser", "com.mises.browser", true, true),
        Browser("Lemur Browser", "com.lemurbrowser.browser", true, true),
        Browser("Quetta Browser", "com.quetta.browser", true, true),
        Browser("X 浏览器", "com.x.browser", false, true),
        Browser("Alook", "alook.browser", false, true),
        Browser("Firefox", "org.mozilla.firefox", false),
        Browser("三星浏览器", "com.sec.android.app.sbrowser", false),
        Browser("QQ 浏览器", "com.tencent.mtt", false, true),
        Browser("UC 浏览器", "com.UCMobile", false, true),
        Browser("Via", "mark.via.gp", false)
    )

    fun installedBrowsers(context: Context): List<Browser> =
        KNOWN.filter { isInstalled(context, it.packageName) }

    private fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    /** 递归列出 assets 下某目录的全部文件相对路径。 */
    private fun assetPaths(context: Context, root: String): List<String> {
        val assets = context.assets
        val result = mutableListOf<String>()

        fun walk(path: String) {
            val children = runCatching { assets.list(path) }.getOrNull() ?: return
            if (children.isEmpty()) return
            for (child in children) {
                val full = "$path/$child"
                val grand = runCatching { assets.list(full) }.getOrNull().orEmpty()
                if (grand.isEmpty()) result += full else walk(full)
            }
        }
        walk(root)
        return result
    }

    /** 公共 Download 下的扩展目录（浏览器「选择目录」时能直接看到）。 */
    fun extensionDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "DownloadGuard-Extension"
    )

    /**
     * 导出扩展：zip 写到应用外部目录（FileProvider 可直接分享），
     * 并尽力解压一份到公共 Download（有「所有文件访问」权限时成功）。
     */
    fun exportZip(context: Context): File? = runCatching {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "browser-extension")
        dir.mkdirs()
        val out = File(dir, "DownloadGuard-BrowserExtension.zip")
        val paths = assetPaths(context, "browser-extension")
        if (paths.isEmpty()) return null
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (path in paths) {
                val relative = path.removePrefix("browser-extension/")
                zip.putNextEntry(ZipEntry(relative))
                context.assets.open(path).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        // 尽力而为：公共 Download 目录放一份解压版（无权限时静默失败，zip 仍然可用）
        runCatching {
            val publicDir = extensionDir()
            publicDir.mkdirs()
            paths.forEach { path ->
                val target = File(publicDir, path.removePrefix("browser-extension/"))
                target.parentFile?.mkdirs()
                context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
        out
    }.getOrNull()

    /** 拉起指定浏览器（没有启动入口时返回 false）。 */
    fun openBrowser(context: Context, packageName: String): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /**
     * 尝试直达浏览器的扩展管理页：部分 Chromium 分支（Kiwi/Mises 等）接受自定义 scheme，
     * 不接受时退化为打开浏览器主界面。
     */
    fun openExtensionPage(context: Context, browser: Browser): Boolean {
        val schemes = when {
            browser.packageName.contains("kiwi") -> listOf("kiwi://extensions", "chrome://extensions")
            browser.packageName.contains("mises") -> listOf("mises://extensions", "chrome://extensions")
            browser.packageName.contains("lemur") -> listOf("lemur://extensions", "chrome://extensions")
            browser.packageName.contains("quetta") -> listOf("quetta://extensions", "chrome://extensions")
            browser.packageName.contains("emmx") -> listOf("edge://extensions")
            else -> listOf("chrome://extensions")
        }
        for (scheme in schemes) {
            val ok = runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(scheme)).apply {
                    setPackage(browser.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return openBrowser(context, browser.packageName)
    }

    /** 用系统文件管理器打开扩展目录；部分 ROM 没有 resource/folder 处理器会失败。 */
    fun openExtensionFolder(context: Context): Boolean = runCatching {
        val dir = extensionDir().apply { mkdirs() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dir)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "resource/folder")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** 安装说明（可一键复制，发到电脑或在手机上照做）。 */
    fun installGuide(context: Context): String {
        val dir = extensionDir().absolutePath
        val address = runCatching {
            (context.applicationContext as? com.dlguard.DownloadGuardApp)
                ?.container?.pluginManager?.lanServer?.localAddress()
        }.getOrNull() ?: "http://手机IP:8787"
        val token = runCatching {
            (context.applicationContext as? com.dlguard.DownloadGuardApp)
                ?.container?.pluginManager?.lanServer?.token
        }.getOrNull().orEmpty()
        return """
            DownloadGuard 浏览器扩展安装说明
            ─────────────────────────────
            手机端扩展目录：$dir
            手机服务地址：$address
            访问令牌：$token

            电脑 Chrome / Edge：
              1. 把 DownloadGuard-BrowserExtension.zip 传到电脑并解压
              2. 打开 chrome://extensions（Edge 为 edge://extensions）
              3. 右上角开启「开发者模式」→「加载已解压的扩展程序」→ 选解压目录
              4. 点扩展图标 → 设置服务器地址与 Token（须与手机同一 Wi-Fi）

            手机 Kiwi / Mises / Lemur：
              1. 浏览器地址栏进入扩展管理页
              2. 开启开发者模式 → 从本地/文件夹安装
              3. 选择目录：$dir

            说明：Android 沙箱不允许本 App 直接写进其他浏览器的扩展数据，
            因此采用「导出目录 + 一键拉起浏览器」的方式，扩展文件已备好，只需在浏览器里选目录。
        """.trimIndent()
    }
}

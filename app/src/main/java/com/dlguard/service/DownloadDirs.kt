package com.dlguard.service

import android.os.Environment
import java.io.File

/** 内置监听目录（不存在会自动跳过），覆盖浏览器、微信、主流网盘缓存位置。 */
object DownloadDirs {

    @Suppress("DEPRECATION")
    fun builtIn(): List<File> {
        val root = Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
        val dirs = mutableListOf<File>()
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let { dirs += it }
        dirs += File(root, "Download")
        dirs += File(root, "Download/Browser")
        dirs += File(root, "Browser")
        dirs += File(root, "browser")
        dirs += File(root, "tencent/MicroMsg/Download")
        dirs += File(root, "tencent/QQfile_recv")
        dirs += File(root, "Android/data/com.tencent.mm/MicroMsg/Download")
        dirs += File(root, "uc/download")
        dirs += File(root, "UCDownloads")
        dirs += File(root, "Quark/Download")
        dirs += File(root, "BaiduNetdisk/download")
        dirs += File(root, "baidu/netdisk/download")
        dirs += File(root, "Android/data/com.baidu.netdisk/files/download")
        dirs += File(root, "MIUI/download")
        dirs += File(root, "Download/Quark")
        // 微信「保存到手机」/「其他应用打开」实际落到这些公共目录，可绕过 Android/data 不可读的限制
        dirs += File(root, "Download/WeiXin")
        dirs += File(root, "Download/Weixin")
        dirs += File(root, "Download/微信")
        dirs += File(root, "Download/QQ")
        dirs += File(root, "Download/qq")
        dirs += File(root, "Download/Telegram")
        dirs += File(root, "Telegram")
        dirs += File(root, "Download/uc")
        dirs += File(root, "Download/baidu")
        dirs += File(root, "Downloads")
        return dirs.distinctBy { it.absolutePath }
    }
}

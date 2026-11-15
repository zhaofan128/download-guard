package com.dlguard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dlguard.DownloadGuardApp

/**
 * 接收 LSPosed 模块回传的下载链接。
 *
 * 模块在浏览器进程里 hook 到 WebView `DownloadListener`（以及 DownloadManager 的落库点）后，
 * 把「原始 URL + 文件名」通过显式广播发给本应用，从而在主流程无 root 的前提下补上来源信息。
 *
 * 安全取舍：广播只做「记录候选链接」，不会直接触发下载；真正下载仍需用户确认或命中已有记录的
 * 重新下载动作，因此即使被第三方应用滥发，也只会污染候选列表，不会静默下载任意文件。
 */
class CapturedUrlReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CAPTURED_URL = "com.dlguard.action.CAPTURED_URL"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_SOURCE = "source"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_CAPTURED_URL) return
        val url = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()
        if (url.isEmpty() || url.length > 4096) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val name = intent.getStringExtra(EXTRA_NAME)
        val source = intent.getStringExtra(EXTRA_SOURCE)?.take(48) ?: "Xposed 模块"
        val app = context.applicationContext as? DownloadGuardApp ?: return
        app.container.capturedLinks.record(url, name, source)
        Log.i("CapturedUrlReceiver", "收到模块回传链接：${url.take(80)}")
    }
}

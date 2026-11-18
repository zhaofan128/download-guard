package com.dlguard.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.dlguard.DownloadGuardApp
import com.dlguard.core.LinkSniffer
import com.dlguard.relay.RelayUrls

/**
 * 「分享到 DownloadGuard」入口。
 *
 * 两种来源：
 *  - 浏览器 / 网盘 App 点分享，选本 App（ACTION_SEND，text/plain）；
 *  - 其它应用用 ACTION_VIEW 打开 http(s) 链接（系统会把它列进候选）。
 *
 * 分流逻辑刻意保守：
 *  - 明确的文件直链（.apk/.zip/.mp4…）→ 直接建任务，并提示「没带登录态，403 就用网页模式」；
 *  - 其余一律进嗅探页，让用户先登录再点下载。分享页链接直接当直链下，
 *    只会得到一个几 KB 的 HTML 空壳。
 *
 * 不在这里做网络探测：分享应该是「秒进」，探一次 HEAD 可能要好几秒，
 * 而且失败率高（很多站点拒绝 HEAD），体验反而更差。
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = extractUrl(intent)
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "没有收到链接", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val real = RelayUrls.extractOriginal(url) ?: url
        if (looksLikeFileLink(real)) {
            val started = (application as? DownloadGuardApp)?.container?.downloadCenter
                ?.start(real) ?: false
            if (started) {
                Toast.makeText(
                    this,
                    "已加入下载队列。若提示 403，请在任务卡片点「网页模式」登录后再下",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this, "链接无效，无法加入下载队列", Toast.LENGTH_SHORT).show()
            }
            finish()
        } else {
            startActivity(
                Intent(this, SniffActivity::class.java)
                    .putExtra(SniffActivity.EXTRA_PAGE_URL, real)
            )
            finish()
        }
    }

    private fun extractUrl(intent: Intent?): String? {
        if (intent == null) return null
        val raw = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return null
        // 分享文本常常是「标题 + 链接 + 推广语」，抓第一个 http(s) 即可
        val match = Regex("https?://[^\\s\"'<>]+").find(raw) ?: return null
        return match.value.trim().trimEnd('.', ',', '。', '，', ')', '）', ']', '】')
    }

    private fun looksLikeFileLink(url: String): Boolean {
        if (LinkSniffer.looksLikeFile(url)) return true
        val lower = url.lowercase()
        // 没有扩展名但语义明确的下载端点：/download/xxx、?filename=、getfile
        return lower.contains("/download/") ||
            lower.contains("filename=") ||
            lower.contains("getfile") ||
            lower.contains("/dl/")
    }
}

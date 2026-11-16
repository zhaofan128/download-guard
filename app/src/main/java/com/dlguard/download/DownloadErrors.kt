package com.dlguard.download

/**
 * 下载失败文案。
 *
 * 以前直接把 `e.javaClass.simpleName` 抛给用户，结果界面上只有一句
 * 「无法连接下载地址：HttpStatusException」——HTTP/2 没有 reason phrase，message 是空的，
 * 用户既不知道错在哪，也不知道下一步该干什么。这里统一把状态码和网络异常翻成人话。
 *
 * 放在独立 object 里是为了能在 JVM 单测里直接断言文案，不用拉起 Android 环境。
 */
object DownloadErrors {

    fun http(code: Int, reason: String?): String = when (code) {
        401 -> "需要登录后才能下载（HTTP 401）。这类链接通常绑定账号，请先在浏览器登录，" +
            "或用首页的「抓取链接并下载」让 App 带上来源页与 Cookie"
        403 -> "服务器拒绝访问（HTTP 403）。这类链接常是一次性的，或需要浏览器验证 / 登录态；" +
            "请回原网页重新点下载，或用首页的「抓取链接并下载」让 App 带上来源页与 Cookie"
        404 -> "文件不存在（HTTP 404），链接可能已失效，回原网页重新获取"
        410 -> "文件已被服务器删除（HTTP 410）"
        429 -> "请求太频繁（HTTP 429），等一分钟再试"
        503 -> "服务器暂时不可用（HTTP 503），稍后再试"
        else -> "HTTP $code：${reason?.takeIf { it.isNotBlank() } ?: "服务器拒绝了请求"}"
    }

    fun network(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "域名解析失败，检查网络或代理（${e.message ?: "未知主机"}）"
        is java.net.SocketTimeoutException -> "连接超时，服务器没有响应"
        is javax.net.ssl.SSLException -> "TLS 握手失败，可能是证书问题或代理拦截"
        is java.net.ConnectException -> "连接被拒绝，可能被网络屏蔽"
        else -> "${e.javaClass.simpleName} ${e.message ?: ""}".trim()
    }
}

package com.dlguard.desktop;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * 把底层异常翻译成用户能照做的话。
 * "Connection reset" 这种信息对用户没有意义，只会让人以为软件坏了。
 */
public final class DownloadErrors {

    private DownloadErrors() {}

    public static String forHttp(int code, String url) {
        String host = hostOf(url);
        switch (code) {
            case 400: return "服务器认为请求不合法（400），链接可能被改动过";
            case 401: return "需要登录（401）。请用浏览器打开一次拿到直链，或在设置里补充 Cookie/Referer";
            case 403: return "服务器拒绝访问（403）。常见原因是链接过期、防盗链或需要登录，可在设置里填写 Referer 后重试";
            case 404: return "地址不存在（404），文件可能已被删除或改名";
            case 405: return "服务器不允许这种请求方式（405）";
            case 407: return "代理要求认证（407），请检查设置里的代理";
            case 410: return "文件已下架（410）";
            case 416: return "请求的字节范围超出文件大小（416），续传记录可能已失效，删除任务重新下载";
            case 429: return "请求太频繁被限流（429），等一会儿再试，或在设置里降低连接数";
            case 500:
            case 502:
            case 503:
            case 504: return "服务器暂时不可用（" + code + "），稍后重试";
            default: return "服务器返回 HTTP " + code + (host.isEmpty() ? "" : "（" + host + "）");
        }
    }

    /**
     * 传输中途断开的异常判断：JDK 17 读到 -1（EOFException 或静默返回），
     * JDK 21/25 抛 "Premature EOF"。Engine 的读取循环用它把「提前断开」统一
     * 视同 EOF，走同一套重试与错误文案，跨 JDK 行为一致。
     */
    public static boolean isEofLike(Throwable e) {
        if (e instanceof java.io.EOFException) return true;
        String m = e.getMessage();
        if (m == null) return false;
        String lower = m.toLowerCase(Locale.ROOT);
        return lower.contains("premature eof")
                || lower.contains("unexpected end of")
                || lower.contains("connection reset")
                || lower.contains("broken pipe");
    }

    public static String humanize(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getMessage() == null) t = t.getCause();
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        // 上游把异常再包一层（如 Future 里的 RuntimeException）时，
        // getMessage 会带上 "java.io.IOException: " 这类前缀，先剥掉再判断
        msg = msg.replaceFirst("^(?:[\\w.]+\\.)?[\\w$]+(?:Exception|Error):\\s*", "");
        String lower = msg.toLowerCase(Locale.ROOT);
        if (t instanceof UnknownHostException || lower.contains("unknownhost")) {
            return "域名解析失败，检查网络或 DNS 设置";
        }
        if (t instanceof ConnectException || lower.contains("connection refused")) {
            return "连接被拒绝，目标服务器没有响应";
        }
        if (t instanceof SocketTimeoutException || lower.contains("timed out")) {
            return "连接超时，网络不稳定或对方服务器较慢，可稍后重试";
        }
        // 传输中途断开：不同 JDK 的文案不一样（JDK 17 是 "unexpected end of file"，
        // JDK 25 是 "Premature EOF"），必须统一映射，否则用户看到的是听不懂的异常名。
        if (lower.contains("premature eof") || lower.contains("unexpected end of")
                || lower.contains("connection reset") || lower.contains("broken pipe")
                || lower.contains("connect reset") || t instanceof java.io.EOFException) {
            return "连接中断，下载不完整，请重试";
        }
        if (lower.contains("ssl") || lower.contains("certificate") || lower.contains("pkix")) {
            return "HTTPS 证书校验失败，站点证书可能已过期或不被系统信任";
        }
        if (lower.contains("no space left")) {
            return "磁盘空间不足，清理后重试";
        }
        if (lower.contains("permission denied") || lower.contains("access denied")) {
            return "没有写入权限，换一个保存目录";
        }
        if (msg.startsWith("HTTP ") || msg.contains("服务器") || msg.contains("链接")) return msg;
        return msg.isEmpty() ? "下载失败，请重试" : msg;
    }

    private static String hostOf(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    public static IOException wrap(String msg) {
        return new IOException(msg);
    }
}

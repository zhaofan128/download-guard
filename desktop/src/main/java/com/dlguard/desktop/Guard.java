package com.dlguard.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 本地 HTTP 服务的请求来源判定。
 *
 * 桌面版只监听 127.0.0.1，但「只有本机能连」不等于「只有本应用能连」：
 * 浏览器里任何一个页面都能向 {@code http://127.0.0.1:8765/api/tasks} 发一个
 * {@code text/plain} 的 POST（简单请求，不触发 CORS 预检），把任意链接下到任意目录；
 * 再配合 DNS rebinding（evil.com 解析到 127.0.0.1），连 Host 都是攻击者的域名。
 *
 * 这里按「浏览器一定会怎么填头」来判断，不依赖客户端自觉：
 * <ol>
 *   <li>Host 必须是本机地址（localhost / 127.0.0.1 / ::1，或 --lan 下的本机网卡地址）；</li>
 *   <li>带了 Origin 就必须与 Host 同源；{@code Origin: null}（沙箱 iframe、file://）直接拒绝；</li>
 *   <li>{@code Sec-Fetch-Site: cross-site} 直接拒绝，老浏览器靠前两条兜底。</li>
 * </ol>
 *
 * 判定全部是纯函数，{@link SelfTest} 直接覆盖，不依赖起服务。
 */
final class Guard {

    private Guard() { }

    /** 解析 Host 头里的主机名，支持 {@code [::1]:8765} 这种 IPv6 写法。 */
    static String hostName(String hostHeader) {
        if (hostHeader == null) return "";
        String h = hostHeader.trim();
        if (h.isEmpty()) return "";
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 0 ? h.substring(1, end).toLowerCase(Locale.ROOT) : "";
        }
        int firstColon = h.indexOf(':');
        int lastColon = h.lastIndexOf(':');
        // 只有一个冒号才是 host:port；多个冒号是裸 IPv6（没有方括号，不合规范，按整串处理）
        if (firstColon > 0 && firstColon == lastColon) {
            return h.substring(0, firstColon).toLowerCase(Locale.ROOT);
        }
        return h.toLowerCase(Locale.ROOT);
    }

    /** 解析 Host 头里的端口；没有写端口时返回 {@code fallback}。 */
    static int hostPort(String hostHeader, int fallback) {
        if (hostHeader == null) return fallback;
        String h = hostHeader.trim();
        int colon;
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            if (end < 0) return fallback;
            colon = h.indexOf(':', end);
        } else {
            int first = h.indexOf(':');
            colon = (first > 0 && first == h.lastIndexOf(':')) ? first : -1;
        }
        if (colon < 0 || colon + 1 >= h.length()) return fallback;
        try {
            return Integer.parseInt(h.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 允许的 Host 主机名：本机回环地址，加上 --lan 时的本机网卡地址。 */
    static List<String> allowedHosts(boolean lan) {
        List<String> list = new ArrayList<>();
        list.add("127.0.0.1");
        list.add("localhost");
        list.add("::1");
        list.add("0:0:0:0:0:0:0:1");
        if (lan) {
            for (String ip : Util.localIPv4()) {
                if (!list.contains(ip)) list.add(ip);
            }
        }
        return list;
    }

    static boolean hostAllowed(String hostHeader, int port, boolean lan, List<String> extraHosts) {
        String name = hostName(hostHeader);
        if (name.isEmpty()) {
            // HTTP/1.0 客户端可以不带 Host；只对本机监听放行，--lan 时一律要求
            return !lan;
        }
        if (extraHosts != null) {
            for (String h : extraHosts) {
                if (h != null && h.trim().toLowerCase(Locale.ROOT).equals(name)) return true;
            }
        }
        boolean known = false;
        for (String h : allowedHosts(lan)) {
            if (h.equals(name)) { known = true; break; }
        }
        if (!known) return false;
        // 浏览器访问非 80 端口一定带端口；带了就必须和服务端口一致
        int p = hostPort(hostHeader, port);
        return p == port;
    }

    /** Origin 必须与请求的 Host 严格同源（scheme 只接受 http）。 */
    static boolean originAllowed(String origin, String hostHeader, int port, boolean lan,
                                 List<String> extraHosts) {
        if (origin == null || origin.trim().isEmpty()) return true;   // 同源导航 / curl 不带
        String o = origin.trim();
        if ("null".equalsIgnoreCase(o)) return false;                 // 沙箱 iframe / file://
        if (!o.regionMatches(true, 0, "http://", 0, 7)) return false; // https 页面发到 http 服务也是跨站
        String rest = o.substring(7);
        int slash = rest.indexOf('/');
        if (slash >= 0) rest = rest.substring(0, slash);
        if (rest.isEmpty()) return false;
        String originHost = hostName(rest);
        int originPort = hostPort(rest, 80);
        String reqHost = hostName(hostHeader);
        if (reqHost.isEmpty()) return false;
        if (!originHost.equals(reqHost)) return false;
        return originPort == hostPort(hostHeader, port);
    }

    /**
     * 一次请求是否放行。
     *
     * @param hostHeader  请求的 Host 头
     * @param origin      请求的 Origin 头（可空）
     * @param fetchSite   Sec-Fetch-Site 头（可空）
     * @param extraHosts  用户用 --allow-host 显式放行的域名（可空）
     */
    static boolean allowed(String hostHeader, String origin, String fetchSite, int port, boolean lan,
                           List<String> extraHosts) {
        if (!hostAllowed(hostHeader, port, lan, extraHosts)) return false;
        if (fetchSite != null && "cross-site".equalsIgnoreCase(fetchSite.trim())) return false;
        return originAllowed(origin, hostHeader, port, lan, extraHosts);
    }

    /** 给错误提示用的简短原因，避免用户只看到 403 不知道哪一步没过。 */
    static String rejectReason(String hostHeader, String origin, String fetchSite, int port, boolean lan,
                               List<String> extraHosts) {
        if (!hostAllowed(hostHeader, port, lan, extraHosts)) {
            return "Host 不在允许列表（" + hostHeader + "），可能是 DNS 重绑定攻击；"
                    + "如果确实要用别的域名访问，请加 --allow-host";
        }
        if (fetchSite != null && "cross-site".equalsIgnoreCase(fetchSite.trim())) {
            return "拒绝跨站请求（Sec-Fetch-Site: cross-site）";
        }
        return "Origin 与服务地址不同源（" + origin + "）";
    }

    // ---------------------------------------------------------------- 浏览器扩展

    /**
     * 浏览器扩展的 Origin 形如 {@code chrome-extension://<id>}。
     *
     * 网页脚本改不了 Origin，所以「Origin 是扩展协议」本身就是一条可信信号；
     * 但 curl 能伪造，因此 hook 端点还要求配对令牌，不能只靠这一段判定。
     */
    static boolean isExtensionOrigin(String origin) {
        if (origin == null) return false;
        String o = origin.trim().toLowerCase(Locale.ROOT);
        return o.startsWith("chrome-extension://") || o.startsWith("moz-extension://")
                || o.startsWith("safari-web-extension://") || o.startsWith("ms-browser-extension://");
    }

    /**
     * hook 端点的来源判定：Host 仍必须是本机，Origin 只能是扩展协议或空。
     *
     * 与普通 API 的区别只有一条：扩展发起的请求带 {@code Sec-Fetch-Site: cross-site}
     * （从扩展进程到 127.0.0.1 在浏览器眼里就是跨站），所以这里不看 Sec-Fetch-Site；
     * 代价是必须靠「扩展 Origin + 配对令牌」两道门，缺一不可。
     */
    static boolean hookOriginOk(String hostHeader, String origin, int port, boolean lan,
                                List<String> extraHosts) {
        if (!hostAllowed(hostHeader, port, lan, extraHosts)) return false;
        if (origin == null || origin.trim().isEmpty()) return true;   // curl / 自检
        return isExtensionOrigin(origin);
    }
}

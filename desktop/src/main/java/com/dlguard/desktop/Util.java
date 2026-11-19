package com.dlguard.desktop;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 桌面版公共小工具：文件名、哈希、人类可读单位、系统集成。 */
public final class Util {

    private Util() {}

    /** 去掉 Windows/Linux/macOS 都不接受或容易出问题的字符。 */
    private static final Pattern ILLEGAL = Pattern.compile("[\\\\/:*?\"<>|\\x00-\\x1f]");
    private static final Pattern RESERVED =
            Pattern.compile("^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?$", Pattern.CASE_INSENSITIVE);

    /**
     * 当前进程 PID。
     *
     * 不用 ProcessHandle：那是 Java 9 才有的 API，用了整个 jar 就只能跑在 11+ 上，
     * 而 Windows 上大量机器装的还是 JRE 8。RuntimeMXBean 的 "pid@host" 在 HotSpot 上
     * 从 Java 6 就有，取值失败时返回 -1，调用方按「拿不到」处理即可。
     */
    public static long currentPid() {
        try {
            String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            int at = name.indexOf('@');
            if (at > 0) return Long.parseLong(name.substring(0, at));
        } catch (Throwable ignored) {
            // 被裁剪过的 JRE 可能没有 java.management，退回 -1
        }
        return -1L;
    }

    public static String sanitizeName(String raw, String fallback) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) name = fallback;
        name = name.replace('\u0000', ' ');
        name = ILLEGAL.matcher(name).replaceAll("_");
        name = name.replaceAll("\\s+", " ").trim();
        // Windows 不允许文件名以点或空格结尾，会导致保存失败
        while (name.endsWith(".") || name.endsWith(" ")) name = name.substring(0, name.length() - 1);
        if (name.isEmpty()) name = fallback;
        if (RESERVED.matcher(name).matches()) name = "_" + name;
        if (name.length() > 180) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 && name.length() - dot <= 12 ? name.substring(dot) : "";
            name = name.substring(0, 180 - ext.length()) + ext;
        }
        return name;
    }

    /** 从 URL 推断文件名，去掉查询串与常见的签名参数。 */
    public static String nameFromUrl(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            String path = u.getPath();
            if (path != null && !path.isEmpty()) {
                String last = path.substring(path.lastIndexOf('/') + 1);
                if (!last.isEmpty()) {
                    return java.net.URLDecoder.decode(last, "UTF-8");
                }
            }
            return u.getHost();
        } catch (Exception e) {
            return "download";
        }
    }

    /** 解析 Content-Disposition，优先 RFC 5987 的 filename*。 */
    public static String parseContentDisposition(String header) {
        if (header == null || header.isEmpty()) return null;
        Matcher star = Pattern.compile("filename\\*\\s*=\\s*([^']*)''([^;]+)", Pattern.CASE_INSENSITIVE).matcher(header);
        if (star.find()) {
            try {
                return java.net.URLDecoder.decode(star.group(2).trim(), "UTF-8");
            } catch (Exception ignored) { }
        }
        Matcher plain = Pattern.compile("filename\\s*=\\s*\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE).matcher(header);
        if (plain.find()) {
            String v = plain.group(1).trim();
            try {
                // 有些服务器直接塞 UTF-8 原始字节，按 ISO-8859-1 解出来再转一次
                byte[] bytes = v.getBytes(StandardCharsets.ISO_8859_1);
                String utf8 = new String(bytes, StandardCharsets.UTF_8);
                if (!utf8.contains("\uFFFD")) return utf8;
            } catch (Exception ignored) { }
            return v;
        }
        return null;
    }

    public static String fmtSize(long bytes) {
        if (bytes < 0) return "—";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    public static String fmtSpeed(long bytesPerSec) {
        if (bytesPerSec <= 0) return "—";
        return fmtSize(bytesPerSec) + "/s";
    }

    public static String fmtDuration(long seconds) {
        if (seconds <= 0 || seconds > 86400L * 30) return "—";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) return String.format(Locale.US, "%d 小时 %02d 分", h, m);
        if (m > 0) return String.format(Locale.US, "%d 分 %02d 秒", m, s);
        return s + " 秒";
    }

    public static String sha256(Path file, Progress p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[256 * 1024];
        long total = Files.size(file);
        long read = 0;
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
                read += n;
                if (p != null) p.onProgress(read, total);
            }
        }
        return hex(md.digest());
    }

    public static String md5(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] buf = new byte[256 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public interface Progress {
        void onProgress(long done, long total);
    }

    /** 同名文件自动加 (1)(2)，绝不在用户不知情时覆盖已有文件。 */
    public static Path uniquePath(Path dir, String name) {
        Path target = dir.resolve(name);
        if (!Files.exists(target)) return target;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            target = dir.resolve(base + " (" + i + ")" + ext);
            if (!Files.exists(target)) return target;
        }
        return dir.resolve(System.currentTimeMillis() + "-" + name);
    }

    /** 原子写：先写 .tmp 再替换，断电不会留下半个 JSON。 */
    public static void atomicWrite(Path file, byte[] data) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static String readText(Path file, String def) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return def;
        }
    }

    /** 本机局域网 IPv4，多个网卡时优先 192.168/10./172.16-31。 */
    public static List<String> localIPv4() {
        List<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) continue;
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        result.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) { }
        Collections.sort(result, (a, b) -> rank(a) - rank(b));
        return result;
    }

    private static int rank(String ip) {
        if (ip.startsWith("192.168.")) return 0;
        if (ip.startsWith("10.")) return 1;
        if (ip.startsWith("172.")) return 2;
        return 3;
    }

    public static void openInOS(Path path) {
        try {
            if (!Desktop.isDesktopSupported()) return;
            Desktop d = Desktop.getDesktop();
            File f = path.toFile();
            if (f.isDirectory()) d.open(f);
            else if (d.isSupported(Desktop.Action.OPEN)) d.open(f);
        } catch (Exception ignored) { }
    }

    public static void browse(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new java.net.URI(url));
                return;
            }
        } catch (Exception ignored) { }
        // 无桌面环境（服务器/精简版 Windows）时退回命令行浏览器
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            else if (os.contains("mac")) new ProcessBuilder("open", url).start();
            else new ProcessBuilder("xdg-open", url).start();
        } catch (Exception ignored) { }
    }

    public static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}

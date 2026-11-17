package com.dlguard.desktop;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令行投递：把链接交给正在运行的桌面版实例。
 *
 * 桌面版是「本地服务 + 浏览器界面」，这也意味着它天然带一个本机 API。
 * 把 API 暴露成命令，脚本、cron、CI 就不用去模拟浏览器点击：
 *
 * <pre>
 *   java -jar DownloadGuard-Desktop.jar --add https://example.com/a.bin --wait
 *   java -jar DownloadGuard-Desktop.jar --status
 * </pre>
 *
 * 只连 127.0.0.1，并且沿用实例写在数据目录里的令牌（0600），
 * 不额外开一个能被局域网访问的端口。
 */
final class Cli {

    private Cli() { }

    /** 投递链接；wait 为 true 时等到任务进入终态再返回。 */
    static int add(Store store, List<String> urls, String dir, int conn, String sha,
                   boolean wait, boolean quiet) {
        int port = store.readInstancePort();
        String token = store.readInstanceToken();
        if (port <= 0) {
            System.err.println("没有找到正在运行的 DownloadGuard 实例。");
            System.err.println("先启动桌面版（java -jar DownloadGuard-Desktop.jar），再用 --add 投递。");
            return 2;
        }
        Reply alive = call(port, token, "GET", "/api/state", null);
        if (!alive.ok) {
            System.err.println("连不上运行中的实例（127.0.0.1:" + port + "）：" + alive.error);
            return 2;
        }

        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String u : urls) {
            Map<String, Object> body = Json.obj(
                    "url", u,
                    "dir", dir == null ? "" : dir,
                    "connections", conn,
                    "sha256", sha == null ? "" : sha);
            Reply r = call(port, token, "POST", "/api/tasks", Json.write(body));
            if (!r.ok) {
                System.err.println("投递失败：" + r.error);
                return 1;
            }
            for (Object o : Json.list(r.data.get("ids"))) ids.add(String.valueOf(o));
            names.add(Util.nameFromUrl(u));
        }
        if (!quiet) {
            for (int i = 0; i < ids.size(); i++) {
                System.out.println("已投递 " + shortId(ids.get(i)) + "  " + names.get(i));
            }
        }
        if (!wait) return 0;
        return await(port, token, ids, quiet);
    }

    /** 打印当前任务列表。 */
    static int status(Store store) {
        int port = store.readInstancePort();
        String token = store.readInstanceToken();
        if (port <= 0) {
            System.err.println("没有找到正在运行的 DownloadGuard 实例。");
            return 2;
        }
        Reply r = call(port, token, "GET", "/api/state", null);
        if (!r.ok) {
            System.err.println("连不上运行中的实例（127.0.0.1:" + port + "）：" + r.error);
            return 2;
        }
        List<Object> tasks = Json.list(r.data.get("tasks"));
        if (tasks.isEmpty()) {
            System.out.println("队列是空的。");
            return 0;
        }
        System.out.printf("%-10s %8s %12s  %s%n", "状态", "进度", "速度", "文件");
        for (Object o : tasks) {
            Map<String, Object> t = Json.map(o);
            System.out.printf("%-10s %8s %12s  %s%n",
                    statusText(Json.str(t, "status", "")),
                    percent(t),
                    speedText(Json.num(t, "speed", 0)),
                    Json.str(t, "name", Json.str(t, "url", "")));
            String err = Json.str(t, "error", "");
            if (!err.isEmpty()) System.out.println("           └ " + err);
        }
        return 0;
    }

    /** 轮询到指定任务全部结束。退出码：0 全成功，1 有失败/取消。 */
    private static int await(int port, String token, List<String> ids, boolean quiet) {
        boolean anyFailed = false;
        Map<String, String> lastLine = new LinkedHashMap<>();
        while (true) {
            Reply r = call(port, token, "GET", "/api/state", null);
            if (!r.ok) {
                System.err.println("与实例的连接断了：" + r.error);
                return 1;
            }
            Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
            for (Object o : Json.list(r.data.get("tasks"))) {
                Map<String, Object> t = Json.map(o);
                byId.put(Json.str(t, "id", ""), t);
            }
            int remaining = 0;
            for (String id : ids) {
                Map<String, Object> t = byId.get(id);
                if (t == null) {
                    // 任务被删掉了
                    if (!quiet) System.out.println("\n任务 " + shortId(id) + " 已从队列移除");
                    anyFailed = true;
                    continue;
                }
                String st = Json.str(t, "status", "");
                if (isTerminal(st)) {
                    if (!quiet && !lastLine.containsKey(id)) {
                        printFinal(t);
                        lastLine.put(id, "");
                    }
                    if (!"completed".equals(st)) anyFailed = true;
                } else {
                    remaining++;
                    if (!quiet) {
                        String line = "  " + padRight(Json.str(t, "name", ""), 28)
                                + " " + padLeft(percent(t), 6)
                                + " " + padLeft(speedText(Json.num(t, "speed", 0)), 12);
                        if (!line.equals(lastLine.get(id))) {
                            System.out.print("\r" + line + "   ");
                            System.out.flush();
                            lastLine.put(id, line);
                        }
                    }
                }
            }
            if (remaining == 0) {
                if (!quiet) System.out.println();
                return anyFailed ? 1 : 0;
            }
            sleep(700);
        }
    }

    private static void printFinal(Map<String, Object> t) {
        String st = Json.str(t, "status", "");
        String name = Json.str(t, "name", "");
        if ("completed".equals(st)) {
            String path = Json.str(t, "finalPath", "");
            System.out.println("\n完成：" + (path.isEmpty() ? name : path));
            String sha = Json.str(t, "sha256", "");
            if (!sha.isEmpty()) System.out.println("SHA-256：" + sha);
        } else if ("cancelled".equals(st)) {
            System.out.println("\n已取消：" + name);
        } else {
            System.out.println("\n失败：" + name + " — " + Json.str(t, "error", st));
        }
    }

    private static boolean isTerminal(String status) {
        return "completed".equals(status) || "error".equals(status) || "cancelled".equals(status);
    }

    // ------------------------------------------------------------------ HTTP

    private static final class Reply {
        final int code;
        final Map<String, Object> data;
        final String error;
        final boolean ok;

        Reply(int code, Map<String, Object> data, String error) {
            this.code = code;
            this.data = data;
            this.error = error;
            this.ok = code >= 200 && code < 300;
        }
    }

    private static Reply call(int port, String token, String method, String path, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(3000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Accept", "application/json");
            if (token != null && !token.isEmpty()) c.setRequestProperty("X-DG-Token", token);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = readAll(in);
            Map<String, Object> data = null;
            try {
                data = Json.map(Json.parse(text));
            } catch (Exception ignored) { }
            String err = data != null ? Json.str(data, "error", "HTTP " + code) : "HTTP " + code;
            return new Reply(code, data, err);
        } catch (Exception e) {
            return new Reply(0, null, e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ 输出

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private static String percent(Map<String, Object> t) {
        long size = Json.num(t, "size", 0);
        long done = Json.num(t, "done", 0);
        if ("completed".equals(Json.str(t, "status", ""))) return "100%";
        if (size <= 0) return done > 0 ? Util.fmtSize(done) : "—";
        return String.format("%.1f%%", Math.min(100.0, done * 100.0 / size));
    }

    private static String speedText(long bytesPerSec) {
        return bytesPerSec > 0 ? Util.fmtSpeed(bytesPerSec) : "—";
    }

    private static String statusText(String s) {
        switch (s) {
            case "queued": return "排队中";
            case "downloading": return "下载中";
            case "verifying": return "校验中";
            case "paused": return "已暂停";
            case "completed": return "已完成";
            case "cancelled": return "已取消";
            case "error": return "失败";
            default: return s;
        }
    }

    private static String padRight(String s, int width) {
        StringBuilder sb = new StringBuilder(s == null ? "" : s);
        while (sb.length() < width) sb.append(' ');
        return sb.length() > width ? sb.substring(0, width - 1) + "…" : sb.toString();
    }

    private static String padLeft(String s, int width) {
        StringBuilder sb = new StringBuilder();
        for (int i = (s == null ? 0 : s.length()); i < width; i++) sb.append(' ');
        sb.append(s == null ? "" : s);
        return sb.toString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

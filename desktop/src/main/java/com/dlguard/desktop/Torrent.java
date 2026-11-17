package com.dlguard.desktop;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BT / 磁力：走 aria2 的 JSON-RPC。
 *
 * 为什么不是自己写一个 BT 客户端：DHT、uTP、PEX、加密握手这些在桌面上要写几万行，
 * 而 aria2 是现成的、跨平台的、命令行就能装。这里做的是"检测 + 拉起 + 托管"：
 * 用户装了 aria2 就能用磁力，没装时给一句能照做的提示，而不是假装支持。
 */
public final class Torrent {

    private Torrent() {}

    private static Daemon daemon;

    // ------------------------------------------------------------------ 探测

    public static String aria2Path(Settings s) {
        if (s != null && s.aria2Path != null && !s.aria2Path.trim().isEmpty()) {
            Path p = Paths.get(s.aria2Path.trim());
            if (Files.isExecutable(p)) return p.toString();
        }
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String exe = win ? "aria2c.exe" : "aria2c";
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                try {
                    Path p = Paths.get(dir, exe);
                    if (Files.isExecutable(p)) return p.toString();
                } catch (Exception ignored) { }
            }
        }
        List<String> common = new ArrayList<>();
        if (win) {
            common.add("C:\\Program Files\\aria2\\aria2c.exe");
            common.add("C:\\aria2\\aria2c.exe");
        } else {
            common.add("/usr/bin/aria2c");
            common.add("/usr/local/bin/aria2c");
            common.add("/opt/homebrew/bin/aria2c");
        }
        for (String c : common) {
            if (Files.isExecutable(Paths.get(c))) return c;
        }
        return "";
    }

    public static String displayName(String magnet) {
        try {
            int q = magnet.indexOf('?');
            if (q < 0) return "磁力任务";
            for (String kv : magnet.substring(q + 1).split("&")) {
                if (kv.startsWith("dn=")) {
                    String name = URLDecoder.decode(kv.substring(3), "UTF-8");
                    if (!name.trim().isEmpty()) return Util.sanitizeName(name, "磁力任务");
                }
            }
        } catch (Exception ignored) { }
        return "磁力任务";
    }

    /** 从 .torrent 的 bencode 里抠出 info.name，纯展示用，不建索引。 */
    public static String nameOfTorrent(byte[] data, String fileName) {
        try {
            byte[] key = "4:name".getBytes(StandardCharsets.ISO_8859_1);
            for (int i = 0; i + key.length < data.length; i++) {
                boolean match = true;
                for (int j = 0; j < key.length; j++) {
                    if (data[i + j] != key[j]) { match = false; break; }
                }
                if (!match) continue;
                int p = i + key.length;
                int len = 0;
                while (p < data.length && data[p] >= '0' && data[p] <= '9') {
                    len = len * 10 + (data[p] - '0');
                    p++;
                }
                if (p < data.length && data[p] == ':' && len > 0 && p + 1 + len <= data.length) {
                    String name = new String(data, p + 1, len, StandardCharsets.UTF_8);
                    if (!name.trim().isEmpty()) return Util.sanitizeName(name, "种子任务");
                }
            }
        } catch (Exception ignored) { }
        String n = fileName == null ? "种子任务" : fileName.replaceAll("(?i)\\.torrent$", "");
        return Util.sanitizeName(n, "种子任务");
    }

    // ------------------------------------------------------------------ 运行

    public static void run(Engine engine, Task t) throws Exception {
        String aria2 = aria2Path(engine.settings);
        if (aria2.isEmpty()) {
            throw new IOException("未检测到 aria2c。BT/磁力需要它做底层传输："
                    + "Windows 可下载 aria2 解压后把目录加进 PATH，macOS 执行 brew install aria2，"
                    + "Ubuntu/Debian 执行 sudo apt install aria2，然后在设置里点「重新检测」");
        }
        Daemon d = ensureDaemon(engine, aria2);
        String gid = t.gid;
        if (gid == null || gid.isEmpty()) {
            gid = addTask(d, t);
            t.gid = gid;
        } else {
            // 续传：任务可能是被暂停/程序重启前留下的
            try { d.call("aria2.unpause", Json.arr(gid)); } catch (Exception ignored) { }
        }
        t.status = "downloading";

        long lastDone = -1;
        int idle = 0;
        while (true) {
            if (t.cancelRequested) {
                try { d.call("aria2.remove", Json.arr(gid)); } catch (Exception ignored) { }
                t.gid = "";
                return;
            }
            if (t.pauseRequested) {
                try { d.call("aria2.pause", Json.arr(gid)); } catch (Exception ignored) { }
                throw new Engine.PauseSignal();
            }
            Map<String, Object> st;
            try {
                st = d.status(gid);
            } catch (Exception e) {
                // 之前程序退出时 aria2 会话已丢：把旧 gid 丢掉，用同一个链接重新添加
                t.gid = "";
                gid = addTask(d, t);
                t.gid = gid;
                st = d.status(gid);
            }
            String status = Json.str(st, "status", "active");
            long total = Json.num(st, "totalLength", 0);
            long completed = Json.num(st, "completedLength", 0);
            t.size = total;
            t.done = completed;
            t.speed = Json.num(st, "downloadSpeed", 0);
            if (total > 0 && t.speed > 0) t.eta = (total - completed) / t.speed;

            if ("complete".equals(status)) {
                t.done = total > 0 ? total : completed;
                t.size = Math.max(total, completed);
                String path = firstFilePath(st);
                if (path != null && new java.io.File(path).exists()) {
                    t.finalPath = path;
                    t.name = Paths.get(path).getFileName().toString();
                    if (t.size < 2L * 1024 * 1024 * 1024) {
                        t.stage = "计算 SHA-256";
                        engine.fireNow();
                        t.sha256 = Util.sha256(Paths.get(path), null);
                    }
                }
                t.status = "completed";
                t.finishedAt = System.currentTimeMillis();
                t.verify = t.sha256.isEmpty() ? "" : "ok";
                t.stage = "";
                try { d.call("aria2.removeDownloadResult", Json.arr(gid)); } catch (Exception ignored) { }
                engine.persistNow();
                engine.fireNow();
                return;
            }
            if ("error".equals(status)) {
                String msg = Json.str(st, "errorMessage", "BT 传输失败");
                throw new IOException("BT 下载失败：" + msg);
            }
            if ("removed".equals(status)) {
                throw new IOException("BT 任务已被移除");
            }

            // 连续 6 分钟一个字节都不动，提示可能没有做种，而不是让用户干等
            if (completed == lastDone) {
                idle++;
                if (idle > 300) throw new IOException("长时间没有收到数据，可能没有做种或磁力链接已失效");
            } else {
                idle = 0;
            }
            lastDone = completed;
            t.stage = "BT 传输中";
            engine.fireNow();
            Thread.sleep(1000);
        }
    }

    private static String firstFilePath(Map<String, Object> st) {
        List<Object> files = Json.list(st.get("files"));
        if (!files.isEmpty()) {
            Map<String, Object> f = Json.map(files.get(0));
            String p = Json.str(f, "path", "");
            if (!p.isEmpty()) return p;
        }
        Map<String, Object> bt = Json.map(st.get("bittorrent"));
        Map<String, Object> info = Json.map(bt.get("info"));
        return Json.str(info, "name", null);
    }

    private static String addTask(Daemon d, Task t) throws Exception {
        boolean seedFile = t.torrentData != null && t.torrentData.length > 0;
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("dir", t.dir);
        opts.put("continue", "true");
        List<Object> params = new ArrayList<>();
        if (seedFile) {
            // aria2.addTorrent(torrent, uris[], options)
            params.add(Base64.getEncoder().encodeToString(t.torrentData));
            params.add(new ArrayList<>());
        } else {
            // aria2.addUri(uris[], options) —— 第一个参数必须是数组，不能是裸字符串
            params.add(Json.arr(t.url));
        }
        params.add(opts);
        Object r = d.call(seedFile ? "aria2.addTorrent" : "aria2.addUri", params);
        return String.valueOf(r);
    }

    private static synchronized Daemon ensureDaemon(Engine engine, String aria2) throws Exception {
        if (daemon != null && daemon.alive()) return daemon;
        daemon = new Daemon(aria2, engine.settings.saveDir);
        daemon.start();
        return daemon;
    }

    // ------------------------------------------------------------------ RPC

    static final class Daemon {
        private final String exe;
        private final String dir;
        private final int port;
        private final String token;
        private Process process;

        Daemon(String exe, String dir) throws Exception {
            this.exe = exe;
            this.dir = dir;
            this.port = 16800 + (int) (Math.random() * 500);
            this.token = Util.hex(java.security.MessageDigest.getInstance("SHA-1")
                    .digest(String.valueOf(System.nanoTime()).getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        }

        void start() throws Exception {
            Files.createDirectories(Paths.get(dir));
            ProcessBuilder pb = new ProcessBuilder(exe,
                    "--enable-rpc",
                    "--rpc-listen-all=false",
                    "--rpc-listen-port=" + port,
                    "--rpc-secret=" + token,
                    "--dir=" + dir,
                    "--continue=true",
                    "--seed-time=0",
                    "--bt-save-metadata=true",
                    "--summary-interval=0",
                    "--console-log-level=warn",
                    "--stop-with-process=" + ProcessHandle.current().pid()
            );
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            process = pb.start();
            // 等 RPC 起来，最多 8 秒
            for (int i = 0; i < 40; i++) {
                try {
                    call("aria2.getVersion", Json.arr());
                    return;
                } catch (Exception e) {
                    Thread.sleep(200);
                }
            }
            throw new IOException("aria2 启动失败，请确认可执行文件有运行权限");
        }

        boolean alive() {
            if (process == null || !process.isAlive()) return false;
            try {
                call("aria2.getVersion", Json.arr());
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        Object call(String method, List<Object> params) throws Exception {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jsonrpc", "2.0");
            body.put("id", "dg");
            body.put("method", method);
            List<Object> p = new ArrayList<>();
            p.add("token:" + token);
            p.addAll(params);
            body.put("params", p);

            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/jsonrpc")
                    .openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(8000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(Json.write(body).getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = read(in);
            c.disconnect();
            if (code >= 400) throw new IOException("aria2 RPC HTTP " + code + " " + text);
            Map<String, Object> resp = Json.map(Json.parse(text));
            if (resp.containsKey("error")) {
                Map<String, Object> err = Json.map(resp.get("error"));
                throw new IOException("aria2: " + Json.str(err, "message", "unknown"));
            }
            return resp.get("result");
        }

        Map<String, Object> status(String gid) throws Exception {
            return Json.map(call("aria2.tellStatus", Json.arr(gid)));
        }

        private String read(InputStream in) throws IOException {
            if (in == null) return "";
            try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }
}

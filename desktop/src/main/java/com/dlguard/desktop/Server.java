package com.dlguard.desktop;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 内嵌 HTTP 服务：既是 Web UI 的静态服务器，也是桌面端的 REST 后端。
 *
 * 只监听 127.0.0.1（除非显式 --lan）。开了局域网访问就必须带 token，
 * 否则同一 WiFi 下的任何人都能把你的电脑当成下载器用。
 */
public final class Server {

    private final Engine engine;
    private final boolean lan;
    private final String token;
    private HttpServer server;
    private int port;

    public Server(Engine engine, int port, boolean lan) throws IOException {
        this.engine = engine;
        this.lan = lan;
        this.token = lan ? newToken() : "";
        IOException last = null;
        for (int i = 0; i < 30; i++) {
            try {
                this.port = port + i;
                server = HttpServer.create(new InetSocketAddress(lan ? "0.0.0.0" : "127.0.0.1", this.port), 0);
                last = null;
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (server == null) throw last == null ? new IOException("端口被占用") : last;
        ThreadPoolExecutor pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(12);
        server.setExecutor(pool);
        server.createContext("/", this::handle);
    }

    private static String newToken() {
        try {
            return Util.hex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(System.nanoTime()).getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (Exception e) {
            return Long.toHexString(System.nanoTime());
        }
    }

    public int port() { return port; }

    public String localUrl() { return "http://127.0.0.1:" + port + "/"; }

    public String lanUrl() {
        List<String> ips = Util.localIPv4();
        String ip = ips.isEmpty() ? "127.0.0.1" : ips.get(0);
        return "http://" + ip + ":" + port + "/?token=" + token;
    }

    public String token() { return token; }

    public void start() { server.start(); }

    public void stop() { server.stop(1); }

    // ------------------------------------------------------------------ 路由

    private void handle(HttpExchange ex) {
        try {
            ex.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().add("Referrer-Policy", "no-referrer");
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/") || path.equals("/events")) {
                if (!authorized(ex)) {
                    json(ex, 403, Json.obj("ok", false, "error", "访问令牌无效，请使用启动时打印的完整链接打开"));
                    return;
                }
                routeApi(ex, path);
            } else {
                serveStatic(ex, path);
            }
        } catch (Exception e) {
            try {
                json(ex, 500, Json.obj("ok", false, "error", String.valueOf(e.getMessage())));
            } catch (Exception ignored) { }
        } finally {
            ex.close();
        }
    }

    private boolean authorized(HttpExchange ex) {
        if (!lan) return true;
        Map<String, String> q = query(ex.getRequestURI());
        if (token.equals(q.get("token"))) return true;
        String header = ex.getRequestHeaders().getFirst("X-DG-Token");
        if (token.equals(header)) return true;
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie != null) {
            for (String part : cookie.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && "dg_token".equals(kv[0]) && token.equals(kv[1])) return true;
            }
        }
        return false;
    }

    private void routeApi(HttpExchange ex, String path) throws Exception {
        String method = ex.getRequestMethod();
        if (path.equals("/events")) {
            streamEvents(ex);
            return;
        }
        if (path.equals("/api/state")) {
            json(ex, 200, decorate(engine.state()));
            return;
        }
        if (path.equals("/api/version")) {
            json(ex, 200, Json.obj("version", Engine.VERSION));
            return;
        }
        if (path.equals("/api/tasks") && "POST".equals(method)) {
            Map<String, Object> body = body(ex);
            String dir = Json.str(body, "dir", "");
            int conn = Json.i(body, "connections", 0);
            String sha = Json.str(body, "sha256", "");
            List<String> urls = new ArrayList<>();
            if (body.get("urls") instanceof List) {
                for (Object o : Json.list(body.get("urls"))) {
                    String u = String.valueOf(o).trim();
                    if (!u.isEmpty()) urls.add(u);
                }
            }
            String single = Json.str(body, "url", "").trim();
            if (!single.isEmpty()) urls.add(single);
            if (urls.isEmpty()) {
                json(ex, 400, Json.obj("ok", false, "error", "没有可下载的链接"));
                return;
            }
            List<String> ids = new ArrayList<>();
            for (String u : urls) ids.add(engine.add(u, dir, conn, sha).id);
            json(ex, 200, Json.obj("ok", true, "ids", ids));
            return;
        }
        if (path.equals("/api/settings") && "POST".equals(method)) {
            Map<String, Object> body = body(ex);
            Settings s = Settings.fromMap(body);
            engine.applySettings(s);
            json(ex, 200, Json.obj("ok", true, "settings", s.toMap()));
            return;
        }
        if (path.equals("/api/browse")) {
            Map<String, String> q = query(ex.getRequestURI());
            json(ex, 200, browse(q.getOrDefault("path", engine.settings.saveDir)));
            return;
        }
        if (path.equals("/api/torrent") && "POST".equals(method)) {
            Map<String, String> q = query(ex.getRequestURI());
            byte[] data = readAll(ex.getRequestBody(), 64 * 1024 * 1024);
            if (data.length == 0) {
                json(ex, 400, Json.obj("ok", false, "error", "种子文件是空的"));
                return;
            }
            Task t = engine.addTorrent(data, q.getOrDefault("name", "upload.torrent"), null);
            json(ex, 200, Json.obj("ok", true, "id", t.id));
            return;
        }
        if (path.startsWith("/api/tasks/")) {
            String rest = path.substring("/api/tasks/".length());
            String[] parts = rest.split("/");
            String id = parts[0];
            String action = parts.length > 1 ? parts[1] : "";
            Task t = engine.get(id);
            if (t == null) {
                json(ex, 404, Json.obj("ok", false, "error", "任务不存在"));
                return;
            }
            if ("DELETE".equals(method)) {
                Map<String, String> q = query(ex.getRequestURI());
                boolean withFile = "1".equals(q.get("file"));
                engine.remove(id, withFile);
                json(ex, 200, Json.obj("ok", true));
                return;
            }
            switch (action) {
                case "pause": engine.pause(id); break;
                case "resume": engine.resume(id); break;
                case "cancel": engine.cancel(id); break;
                case "retry": engine.retry(id); break;
                case "open":
                    if (!t.finalPath.isEmpty()) Util.openInOS(Paths.get(t.finalPath));
                    break;
                case "reveal": {
                    String target = t.finalPath.isEmpty() ? t.dir : t.finalPath;
                    Path p = Paths.get(target);
                    Util.openInOS(Files.isDirectory(p) ? p : p.getParent());
                    break;
                }
                default:
                    json(ex, 400, Json.obj("ok", false, "error", "未知操作"));
                    return;
            }
            json(ex, 200, Json.obj("ok", true));
            return;
        }
        if (path.equals("/api/clear") && "POST".equals(method)) {
            engine.clearCompleted();
            json(ex, 200, Json.obj("ok", true));
            return;
        }
        if (path.equals("/api/open-dir")) {
            Util.openInOS(Paths.get(engine.settings.saveDir));
            json(ex, 200, Json.obj("ok", true));
            return;
        }
        json(ex, 404, Json.obj("ok", false, "error", "接口不存在"));
    }

    // ------------------------------------------------------------------ 静态资源

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        // 开了局域网又没带令牌：给一张说明页，而不是让界面加载出来却调不通接口
        if (lan && !authorized(ex)) {
            byte[] page = ("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<title>需要访问令牌</title><body style=\"font-family:system-ui,-apple-system,'PingFang SC',sans-serif;"
                    + "background:#0f1118;color:#eef0f9;display:grid;place-items:center;height:100vh;margin:0\">"
                    + "<div style=\"max-width:420px;padding:28px;border:1px solid rgba(255,255,255,.12);"
                    + "border-radius:18px;line-height:1.7\"><h2 style=\"margin:0 0 8px\">需要访问令牌</h2>"
                    + "<p style=\"color:#a3a9c2;margin:0\">请用桌面版启动窗口里打印的完整链接打开，"
                    + "链接里带有本次启动生成的令牌。</p></div></body></html>").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(403, page.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(page); }
            return;
        }
        if ("/".equals(path) || path.isEmpty()) {
            Map<String, String> q = query(ex.getRequestURI());
            if (lan && token.equals(q.get("token"))) {
                Headers h = ex.getResponseHeaders();
                h.add("Set-Cookie", "dg_token=" + token + "; Path=/; SameSite=Lax; Max-Age=2592000");
            }
            path = "/index.html";
        }
        if (path.contains("..")) {
            json(ex, 400, Json.obj("ok", false, "error", "非法路径"));
            return;
        }
        String name = path.substring(1);
        byte[] data = readResource(name);
        if (data == null) {
            byte[] fallback = readResource("index.html");
            if (fallback == null) {
                json(ex, 404, Json.obj("ok", false, "error", "资源不存在"));
                return;
            }
            data = fallback;
            name = "index.html";
        }
        Headers h = ex.getResponseHeaders();
        h.add("Content-Type", mime(name));
        h.add("Cache-Control", name.equals("index.html") ? "no-cache" : "max-age=300");
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    private byte[] readResource(String name) throws IOException {
        // 开发时优先读源码目录，改 CSS 不用重新打包
        String dev = System.getProperty("dg.web");
        if (dev != null && !dev.isEmpty()) {
            Path p = Paths.get(dev, name);
            if (Files.isRegularFile(p)) return Files.readAllBytes(p);
        }
        Path local = Paths.get("desktop/src/main/resources/web", name);
        if (Files.isRegularFile(local)) return Files.readAllBytes(local);
        try (InputStream in = Server.class.getResourceAsStream("/web/" + name)) {
            if (in == null) return null;
            return readAll(in, 32 * 1024 * 1024);
        }
    }

    private String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (n.endsWith(".json") || n.endsWith(".webmanifest")) return "application/json; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    // ------------------------------------------------------------------ SSE

    private void streamEvents(HttpExchange ex) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.add("Content-Type", "text/event-stream; charset=utf-8");
        h.add("Cache-Control", "no-cache");
        h.add("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream os = ex.getResponseBody()) {
            while (true) {
                String payload = Json.write(decorate(engine.state()));
                os.write(("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
                Thread.sleep(900);
            }
        } catch (Exception ignored) {
            // 浏览器关页面/切后台就会断开，属于正常现象
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 把「只有服务器知道的」信息（局域网地址）补进状态里。 */
    private Map<String, Object> decorate(Map<String, Object> state) {
        Map<String, Object> env = Json.map(state.get("env"));
        env.put("lan", lan);
        env.put("lanUrl", lan ? lanUrl() : "");
        state.put("env", env);
        return state;
    }

    private void json(HttpExchange ex, int code, Object body) throws IOException {
        byte[] data = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    private Map<String, Object> body(HttpExchange ex) throws IOException {
        byte[] data = readAll(ex.getRequestBody(), 32 * 1024 * 1024);
        if (data.length == 0) return new LinkedHashMap<>();
        return Json.map(Json.parse(new String(data, StandardCharsets.UTF_8)));
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > limit) throw new IOException("请求体过大");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> map = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) return map;
        for (String kv : raw.split("&")) {
            int eq = kv.indexOf('=');
            try {
                if (eq > 0) {
                    map.put(URLDecoder.decode(kv.substring(0, eq), "UTF-8"),
                            URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
                } else if (!kv.isEmpty()) {
                    map.put(URLDecoder.decode(kv, "UTF-8"), "");
                }
            } catch (Exception ignored) { }
        }
        return map;
    }

    /** 目录选择器数据源：只回目录，文件不回，避免 UI 里出现几万个文件名。 */
    private Map<String, Object> browse(String path) {
        Path dir;
        try {
            dir = Paths.get(path == null || path.isEmpty() ? engine.settings.saveDir : path);
        } catch (Exception e) {
            dir = Paths.get(engine.settings.saveDir);
        }
        if (!Files.isDirectory(dir)) dir = Paths.get(System.getProperty("user.home", "."));
        List<Object> dirs = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                    .sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
                    .limit(500)
                    .forEach(p -> dirs.add(Json.obj("name", p.getFileName().toString(), "path", p.toString())));
        } catch (Exception ignored) { }
        List<Object> roots = new ArrayList<>();
        java.io.File[] rootFiles = java.io.File.listRoots();
        if (rootFiles != null) {
            for (java.io.File rf : rootFiles) {
                roots.add(Json.obj("name", rf.getAbsolutePath(), "path", rf.getAbsolutePath()));
            }
        }
        Path parent = dir.getParent();
        return Json.obj(
                "path", dir.toString(),
                "parent", parent == null ? "" : parent.toString(),
                "separator", java.io.File.separator,
                "dirs", dirs,
                "roots", roots,
                "home", System.getProperty("user.home", "")
        );
    }
}

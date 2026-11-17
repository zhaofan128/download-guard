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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 内嵌 HTTP 服务：既是 Web UI 的静态服务器，也是桌面端的 REST 后端。
 *
 * 只监听 127.0.0.1（除非显式 --lan）。开了局域网访问就必须带 token，
 * 否则同一 WiFi 下的任何人都能把你的电脑当成下载器用。
 */
public final class Server {

    private static final String COOKIE = "dg_token";
    private static final int AUTH_FAIL_LIMIT = 8;
    private static final long AUTH_FAIL_WINDOW_MS = 60_000L;

    private final Engine engine;
    private final boolean lan;
    private final String token;
    private final List<String> extraHosts;
    private final Map<String, Deque<Long>> authFails = new ConcurrentHashMap<>();
    /** SSE 每个客户端会长期占用一个线程，必须和普通 API 分开，且限制总数。 */
    private final ExecutorService ssePool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sse-client");
        t.setDaemon(true);
        return t;
    });
    private final Semaphore sseSlots = new Semaphore(12);
    private final Set<OutputStream> sseOutputs = ConcurrentHashMap.newKeySet();
    /** 扩展重复投递同一链接时的短期去重：key → 任务 id，5 秒内不重复建任务。 */
    private final Map<String, String> hookRecent = new ConcurrentHashMap<>();
    private final Map<String, Long> hookRecentAt = new ConcurrentHashMap<>();
    private volatile boolean running = true;
    private HttpServer server;
    private ThreadPoolExecutor httpPool;
    private int port;

    public Server(Engine engine, int port, boolean lan) throws IOException {
        this(engine, port, lan, null);
    }

    public Server(Engine engine, int port, boolean lan, List<String> extraHosts) throws IOException {
        this.engine = engine;
        this.lan = lan;
        this.extraHosts = extraHosts;
        // 本机模式同样要鉴权：127.0.0.1 对浏览器里的任何页面都是可达的
        this.token = newToken();
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
        httpPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(24);
        server.setExecutor(httpPool);
        server.createContext("/", this::handle);
        // 首次启动生成配对码；扩展要在弹窗里填这 8 位数字才能拿到投递令牌
        if (engine.settings.hookPairCode == null || engine.settings.hookPairCode.isEmpty()) {
            engine.settings.hookPairCode = newPairCode();
            engine.applySettings(engine.settings);
        }
    }

    /** 32 字节 SecureRandom，Base64URL 无填充；不再是 nanoTime 的可猜摘要。 */
    private static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public int port() { return port; }

    /** 首次打开用：带令牌，服务端会 Set-Cookie 后 302 到干净地址。 */
    public String localUrl() { return "http://127.0.0.1:" + port + "/?token=" + token; }

    /** 不带令牌的地址，只用于展示。 */
    public String cleanUrl() { return "http://127.0.0.1:" + port + "/"; }

    public String lanUrl() {
        List<String> ips = Util.localIPv4();
        String ip = ips.isEmpty() ? "127.0.0.1" : ips.get(0);
        return "http://" + ip + ":" + port + "/?token=" + token;
    }

    public String token() { return token; }

    public void start() { server.start(); }

    public void stop() {
        running = false;
        // 先关掉所有 SSE 输出，阻塞在 write/sleep 的处理线程会立刻退出
        for (OutputStream os : sseOutputs) {
            try { os.close(); } catch (IOException ignored) { }
        }
        sseOutputs.clear();
        ssePool.shutdownNow();
        if (httpPool != null) httpPool.shutdownNow();
        server.stop(1);
    }

    // ------------------------------------------------------------------ 浏览器接管

    /** 这三个路径是扩展唯一能碰到的入口，别的路径仍然按同源规则办。 */
    private static boolean isHookPath(String path) {
        return path.equals("/api/hook") || path.equals("/api/hook/ping") || path.equals("/api/hook/pair");
    }

    /** 8 位数字配对码；用 SecureRandom，不用 Math.random。 */
    private static String newPairCode() {
        byte[] b = new byte[4];
        new SecureRandom().nextBytes(b);
        int v = ((b[0] & 0x7f) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
        return String.format(Locale.ROOT, "%08d", v % 100_000_000);
    }

    /** 预检：只回放行扩展需要的那几个头，不做通配。 */
    private void preflight(HttpExchange ex, String origin) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.add("Access-Control-Allow-Origin", origin.trim());
        h.add("Access-Control-Allow-Methods", "GET, POST");
        h.add("Access-Control-Allow-Headers", "Content-Type, X-DG-Hook-Token");
        h.add("Access-Control-Max-Age", "600");
        h.add("Vary", "Origin");
        ex.sendResponseHeaders(204, -1);
    }

    private void hookCors(HttpExchange ex, String origin) {
        if (!Guard.isExtensionOrigin(origin)) return;
        Headers h = ex.getResponseHeaders();
        h.add("Access-Control-Allow-Origin", origin.trim());
        h.add("Vary", "Origin");
    }

    private void hookJson(HttpExchange ex, int code, Object body, String origin) throws IOException {
        hookCors(ex, origin);
        json(ex, code, body);
    }

    private void routeHook(HttpExchange ex, String path, String method, String origin) throws IOException {
        hookCors(ex, origin);
        if (!engine.settings.hookEnabled) {
            hookJson(ex, 403, Json.obj("ok", false, "error", "浏览器接管已在设置里关闭"), origin);
            return;
        }
        if (path.equals("/api/hook/ping")) {
            if (!"GET".equalsIgnoreCase(method)) {
                hookJson(ex, 405, Json.obj("ok", false, "error", "只支持 GET"), origin);
                return;
            }
            boolean paired = engine.settings.hookToken != null && !engine.settings.hookToken.isEmpty();
            hookJson(ex, 200, Json.obj("app", "dlguard", "api", 1,
                    "paired", paired, "needPair", !paired), origin);
            return;
        }
        if (path.equals("/api/hook/pair")) {
            if (!"POST".equalsIgnoreCase(method)) {
                hookJson(ex, 405, Json.obj("ok", false, "error", "只支持 POST"), origin);
                return;
            }
            Map<String, Object> b = body(ex, 8 * 1024);
            String code = Json.str(b, "code", "").trim();
            String expect = engine.settings.hookPairCode == null ? "" : engine.settings.hookPairCode;
            // 配对码只有 8 位，必须和令牌失败共用同一套窗口限流，否则可以慢慢猜
            if (!constEq(expect, code)) {
                denyAuth(ex);
                return;
            }
            clearAuthFails(ex);
            if (engine.settings.hookToken == null || engine.settings.hookToken.isEmpty()) {
                engine.settings.hookToken = newToken();
                engine.applySettings(engine.settings);
            }
            hookJson(ex, 200, Json.obj("ok", true, "token", engine.settings.hookToken), origin);
            return;
        }
        // /api/hook：真正投递
        if (!"POST".equalsIgnoreCase(method)) {
            hookJson(ex, 405, Json.obj("ok", false, "error", "只支持 POST"), origin);
            return;
        }
        String got = ex.getRequestHeaders().getFirst("X-DG-Hook-Token");
        String want = engine.settings.hookToken == null ? "" : engine.settings.hookToken;
        if (!constEq(want, got)) {
            denyAuth(ex);
            return;
        }
        clearAuthFails(ex);
        Map<String, Object> b = body(ex, 64 * 1024);
        String url = Json.str(b, "url", "").trim();
        if (url.isEmpty()) {
            hookJson(ex, 400, Json.obj("ok", false, "error", "缺少 url"), origin);
            return;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
            hookJson(ex, 400, Json.obj("ok", false, "error", "只接受 http/https 链接"), origin);
            return;
        }
        long size = Json.num(b, "size", Json.num(b, "fileSize", 0));
        String key = url + "|" + size;
        String dup = hookRecent.get(key);
        Long at = hookRecentAt.get(key);
        long now = System.currentTimeMillis();
        if (dup != null && at != null && now - at < 5_000 && engine.get(dup) != null) {
            hookJson(ex, 200, Json.obj("ok", true, "id", dup, "duplicate", true), origin);
            return;
        }
        Engine.Intake in = new Engine.Intake();
        in.referer = Json.str(b, "referer", Json.str(b, "pageUrl", ""));
        in.cookie = Json.str(b, "cookie", "");
        in.userAgent = Json.str(b, "userAgent", "");
        in.fileName = Json.str(b, "fileName", "");
        in.size = size;
        in.source = "browser";
        Task t;
        try {
            t = engine.add(url, Json.str(b, "dir", ""), Json.i(b, "connections", 0), "", in);
        } catch (IllegalArgumentException e) {
            hookJson(ex, 400, Json.obj("ok", false, "error", e.getMessage()), origin);
            return;
        }
        hookRecent.put(key, t.id);
        hookRecentAt.put(key, now);
        if (hookRecent.size() > 500) {
            hookRecent.clear();
            hookRecentAt.clear();
        }
        engine.settings.hookLastSource = "browser";
        hookJson(ex, 200, Json.obj("ok", true, "id", t.id, "name", t.name), origin);
    }

    // ------------------------------------------------------------------ 路由

    private void handle(HttpExchange ex) {
        boolean keepOpen = false;
        try {
            Headers h = ex.getResponseHeaders();
            h.add("X-Content-Type-Options", "nosniff");
            h.add("Referrer-Policy", "no-referrer");
            h.add("X-Frame-Options", "DENY");
            h.add("Cross-Origin-Resource-Policy", "same-origin");
            // 前端没有内联脚本，只有少量动态 style 属性，CSP 可以收得很紧
            h.add("Content-Security-Policy",
                    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                            + "img-src 'self' data:; connect-src 'self'; object-src 'none'; "
                            + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
            // 先判来源：本机端口对浏览器里的任何页面都是可达的，
            // 不判 Host/Origin 的话，一个 <form> 就能往这里塞下载任务。
            String host = ex.getRequestHeaders().getFirst("Host");
            String origin = ex.getRequestHeaders().getFirst("Origin");
            String fetchSite = ex.getRequestHeaders().getFirst("Sec-Fetch-Site");
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();

            // 浏览器接管端点单独走门：扩展的 Origin 是 chrome-extension://，
            // 天然过不了「同源」这一条。代价是这里必须靠「扩展 Origin + 配对令牌」两道门，
            // 且只对这三个路径开口，别的路径一律按同源规则办。
            if (isHookPath(path)) {
                if (!Guard.hookOriginOk(host, origin, port, lan, extraHosts)) {
                    json(ex, 403, Json.obj("ok", false, "error",
                            "只接受本机地址 + 浏览器扩展来源"));
                    return;
                }
                if ("OPTIONS".equalsIgnoreCase(method)) {
                    if (!Guard.isExtensionOrigin(origin)) {
                        json(ex, 403, Json.obj("ok", false, "error", "非扩展来源不发预检"));
                        return;
                    }
                    preflight(ex, origin);
                    return;
                }
                routeHook(ex, path, method, origin);
                return;
            }

            if (!Guard.allowed(host, origin, fetchSite, port, lan, extraHosts)) {
                json(ex, 403, Json.obj("ok", false,
                        "error", Guard.rejectReason(host, origin, fetchSite, port, lan, extraHosts)));
                return;
            }
            // 不响应预检：跨源页面即使想带 X-DG-Token 也过不了浏览器这一关
            if ("OPTIONS".equalsIgnoreCase(method)) {
                json(ex, 403, Json.obj("ok", false, "error", "不响应跨源预检请求"));
                return;
            }
            boolean stateChanging = !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method);
            if (path.startsWith("/api/") || path.equals("/events") || path.equals("/api/events")) {
                if (!hasSessionToken(ex)) {
                    denyAuth(ex);
                    return;
                }
                clearAuthFails(ex);
                // 双提交：状态变更必须带自定义头。浏览器跨源请求带这个头会先发预检，
                // 而服务端不响应预检，所以 CSRF 在这一步就被挡住了。
                if (stateChanging && !headerToken(ex)) {
                    json(ex, 403, Json.obj("ok", false, "error", "缺少 X-DG-Token 请求头"));
                    return;
                }
                if (!contentTypeOk(ex, path, stateChanging)) {
                    json(ex, 415, Json.obj("ok", false, "error", "该接口只接受 application/json"));
                    return;
                }
                if (path.equals("/events") || path.equals("/api/events")) {
                    if (!"GET".equalsIgnoreCase(method)) {
                        json(ex, 405, Json.obj("ok", false, "error", "事件流只支持 GET"));
                        return;
                    }
                    if (!sseSlots.tryAcquire()) {
                        json(ex, 503, Json.obj("ok", false, "error", "事件流连接数已满，请关闭多余页面"));
                        return;
                    }
                    keepOpen = true;
                    ssePool.execute(() -> {
                        try {
                            streamEvents(ex);
                        } catch (Exception ignored) {
                        } finally {
                            sseSlots.release();
                            try { ex.close(); } catch (Exception ignored) { }
                        }
                    });
                    return;
                }
                routeApi(ex, path);
            } else {
                if (stateChanging) {
                    json(ex, 405, Json.obj("ok", false, "error", "静态资源只支持 GET"));
                    return;
                }
                serveStatic(ex, path);
            }
        } catch (Exception e) {
            try {
                json(ex, 500, Json.obj("ok", false, "error", String.valueOf(e.getMessage())));
            } catch (Exception ignored) { }
        } finally {
            if (!keepOpen) ex.close();
        }
    }

    /** 状态变更只认 JSON；种子上传单独放行 octet-stream / multipart。 */
    private static boolean contentTypeOk(HttpExchange ex, String path, boolean changing) {
        if (!changing) return true;
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        String v = ct == null ? "" : ct.toLowerCase(Locale.ROOT).trim();
        if (path.equals("/api/torrent")) {
            return v.startsWith("application/octet-stream") || v.startsWith("multipart/form-data");
        }
        if (path.equals("/api/tasks") || path.equals("/api/settings")
                || path.equals("/api/settings/save-dir")) {
            return v.startsWith("application/json");
        }
        // 其余动作没有请求体（暂停/继续/打开目录…），token 头已经足够
        return true;
    }

    private boolean hasSessionToken(HttpExchange ex) {
        return headerToken(ex) || cookieToken(ex);
    }

    private boolean headerToken(HttpExchange ex) {
        return sameToken(ex.getRequestHeaders().getFirst("X-DG-Token"));
    }

    private boolean cookieToken(HttpExchange ex) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return false;
        for (String part : cookie.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && COOKIE.equals(kv[0]) && sameToken(kv[1])) return true;
        }
        return false;
    }

    private boolean queryToken(HttpExchange ex) {
        return sameToken(query(ex.getRequestURI()).get("token"));
    }

    private boolean sameToken(String candidate) {
        return constEq(token, candidate);
    }

    /** 常量时间比较两个字符串；任一侧为空都返回 false（空令牌等于没有令牌）。 */
    private static boolean constEq(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private void denyAuth(HttpExchange ex) throws IOException {
        String ip = clientIp(ex);
        if (tooManyFails(ip)) {
            ex.getResponseHeaders().add("Retry-After", "60");
            json(ex, 429, Json.obj("ok", false, "error", "鉴权失败次数过多，请 1 分钟后再试"));
            return;
        }
        noteFail(ip);
        json(ex, 403, Json.obj("ok", false,
                "error", "访问令牌无效，请使用启动时打印的完整链接打开"));
    }

    private void clearAuthFails(HttpExchange ex) {
        authFails.remove(clientIp(ex));
    }

    private boolean tooManyFails(String ip) {
        Deque<Long> q = authFails.get(ip);
        if (q == null) return false;
        synchronized (q) {
            purge(q);
            // 第 AUTH_FAIL_LIMIT 次失败时就返回 429：允许 7 次尝试，第 8 次开始限流
            return q.size() >= AUTH_FAIL_LIMIT - 1;
        }
    }

    private void noteFail(String ip) {
        Deque<Long> q = authFails.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) {
            long now = System.currentTimeMillis();
            purge(q);
            q.addLast(now);
        }
    }

    private static void purge(Deque<Long> q) {
        long cutoff = System.currentTimeMillis() - AUTH_FAIL_WINDOW_MS;
        while (!q.isEmpty() && q.peekFirst() < cutoff) q.removeFirst();
    }

    private static String clientIp(HttpExchange ex) {
        try {
            return ex.getRemoteAddress().getAddress().getHostAddress();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void routeApi(HttpExchange ex, String path) throws Exception {
        String method = ex.getRequestMethod();
        if (path.equals("/events") || path.equals("/api/events")) {
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
        if (path.equals("/api/hook/info")) {
            boolean paired = engine.settings.hookToken != null && !engine.settings.hookToken.isEmpty();
            json(ex, 200, Json.obj(
                    "enabled", engine.settings.hookEnabled,
                    "paired", paired,
                    "pairCode", engine.settings.hookPairCode == null ? "" : engine.settings.hookPairCode,
                    "lastSource", engine.settings.hookLastSource));
            return;
        }
        if (path.equals("/api/hook/reset-pair") && "POST".equals(method)) {
            engine.settings.hookPairCode = newPairCode();
            engine.settings.hookToken = "";
            engine.applySettings(engine.settings);
            json(ex, 200, Json.obj("ok", true, "pairCode", engine.settings.hookPairCode));
            return;
        }
        if (path.equals("/api/hook/extension.zip")) {
            byte[] zip;
            try (InputStream in = Server.class.getResourceAsStream("/extension.zip")) {
                if (in == null) {
                    json(ex, 404, Json.obj("ok", false,
                            "error", "这个 jar 里没有内置扩展包，请从仓库 browser-extension/ 目录加载"));
                    return;
                }
                zip = readAll(in, 8 * 1024 * 1024);
            }
            ex.getResponseHeaders().add("Content-Type", "application/zip");
            ex.getResponseHeaders().add("Content-Disposition",
                    "attachment; filename=\"DownloadGuard-extension.zip\"");
            ex.sendResponseHeaders(200, zip.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(zip);
            }
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
            // 先整体解析目录再建任务：不能出现「前两个任务加进去了，第三个目录非法」的半成功
            Path target;
            try {
                target = DirGuard.resolveWritable(dir, engine.allowedRoots(), engine.defaultDir());
            } catch (Exception e) {
                json(ex, 400, Json.obj("ok", false, "error", e.getMessage()));
                return;
            }
            List<String> ids = new ArrayList<>();
            try {
                for (String u : urls) ids.add(engine.add(u, target.toString(), conn, sha).id);
            } catch (IllegalArgumentException e) {
                json(ex, 400, Json.obj("ok", false, "error", e.getMessage()));
                return;
            }
            json(ex, 200, Json.obj("ok", true, "ids", ids));
            return;
        }
        if (path.equals("/api/settings") && "POST".equals(method)) {
            Map<String, Object> body = body(ex);
            Settings s = Settings.fromMap(body);
            // 保存目录必须走单独的接口：这样一次设置保存里不会悄悄换掉写入根目录
            String current;
            try {
                current = engine.defaultDir().toString();
            } catch (Exception e) {
                current = engine.settings.saveDir;
            }
            if (s.saveDir == null || s.saveDir.trim().isEmpty()) s.saveDir = engine.settings.saveDir;
            String incoming = s.saveDir;
            try {
                incoming = DirGuard.canonical(incoming).toString();
            } catch (Exception ignored) { }
            if (!incoming.equals(current)) {
                json(ex, 409, Json.obj("ok", false, "code", "use_save_dir_endpoint",
                        "error", "修改保存目录请调用 /api/settings/save-dir"));
                return;
            }
            s.saveDir = engine.settings.saveDir;
            // 扩展配对凭据不接受请求体覆盖：设置页能改的只是开关，不是令牌
            s.hookToken = engine.settings.hookToken;
            s.hookPairCode = engine.settings.hookPairCode;
            if (!body.containsKey("allowedDirs")) {
                s.allowedDirs = new ArrayList<>(engine.settings.allowedDirs);
            }
            engine.applySettings(s);
            json(ex, 200, Json.obj("ok", true, "settings", s.toMap()));
            return;
        }
        if (path.equals("/api/settings/save-dir") && "POST".equals(method)) {
            Map<String, Object> body = body(ex);
            Path p;
            try {
                p = DirGuard.resolveNewSaveDir(Json.str(body, "dir", ""));
            } catch (Exception e) {
                json(ex, 400, Json.obj("ok", false, "error", e.getMessage()));
                return;
            }
            Settings s = Settings.fromMap(engine.settings.toDiskMap());
            String old = s.saveDir;
            s.saveDir = p.toString();
            if (!s.allowedDirs.contains(old)) s.allowedDirs.add(old);
            if (!s.allowedDirs.contains(p.toString())) s.allowedDirs.add(p.toString());
            engine.applySettings(s);
            json(ex, 200, Json.obj("ok", true, "settings", s.toMap()));
            return;
        }
        if (path.equals("/api/browse")) {
            Map<String, String> q = query(ex.getRequestURI());
            Map<String, Object> result = browse(q.getOrDefault("path", engine.settings.saveDir));
            if (result == null) {
                json(ex, 403, Json.obj("ok", false, "error", "只能浏览允许的保存目录"));
                return;
            }
            json(ex, 200, result);
            return;
        }
        if (path.equals("/api/torrent") && "POST".equals(method)) {
            Map<String, String> q = query(ex.getRequestURI());
            byte[] data = readAll(ex.getRequestBody(), 64 * 1024 * 1024);
            if (data.length == 0) {
                json(ex, 400, Json.obj("ok", false, "error", "种子文件是空的"));
                return;
            }
            Task t;
            try {
                t = engine.addTorrent(data, q.getOrDefault("name", "upload.torrent"), null);
            } catch (IllegalArgumentException e) {
                json(ex, 400, Json.obj("ok", false, "error", e.getMessage()));
                return;
            }
            json(ex, 200, Json.obj("ok", true, "id", t.id));
            return;
        }
        if (path.equals("/api/tasks/all/pause") && "POST".equals(method)) {
            int n = engine.pauseAll();
            json(ex, 200, Json.obj("ok", true, "affected", n));
            return;
        }
        if (path.equals("/api/tasks/all/resume") && "POST".equals(method)) {
            int n = engine.resumeAll();
            json(ex, 200, Json.obj("ok", true, "affected", n));
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
            // 只接受 POST：GET 能被 <img src> 之类直接触发，等于给任意网页一个「打开文件夹」的入口
            if (!"POST".equals(method)) {
                json(ex, 405, Json.obj("ok", false, "error", "该接口只接受 POST"));
                return;
            }
            Util.openInOS(Paths.get(engine.settings.saveDir));
            json(ex, 200, Json.obj("ok", true));
            return;
        }
        json(ex, 404, Json.obj("ok", false, "error", "接口不存在"));
    }

    // ------------------------------------------------------------------ 静态资源

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        if ("/".equals(path) || path.isEmpty()) {
            // 首次带 token 打开：种 HttpOnly Cookie，然后 302 到干净地址，
            // 令牌不会留在地址栏、历史记录和 Referer 里
            if (queryToken(ex)) {
                ex.getResponseHeaders().add("Set-Cookie",
                        COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=2592000");
                ex.getResponseHeaders().add("Location", "/");
                ex.sendResponseHeaders(302, -1);
                return;
            }
            if (!cookieToken(ex)) {
                tokenPage(ex);
                return;
            }
            byte[] page = readResource("index.html");
            if (page == null) {
                json(ex, 404, Json.obj("ok", false, "error", "资源不存在"));
                return;
            }
            // 前端需要把 token 放进 X-DG-Token 请求头；HttpOnly Cookie 读不到，
            // 所以只在同源页面里用 <meta> 注入（已配 CSP + X-Frame-Options: DENY）
            String html = new String(page, StandardCharsets.UTF_8)
                    .replace("__DG_TOKEN__", token);
            byte[] data = html.getBytes(StandardCharsets.UTF_8);
            Headers h = ex.getResponseHeaders();
            h.add("Content-Type", "text/html; charset=utf-8");
            h.add("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(data); }
            return;
        }
        if (!cookieToken(ex)) {
            tokenPage(ex);
            return;
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
        h.add("Cache-Control", name.equals("index.html") ? "no-store" : "max-age=300");
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    /** 没带令牌时的说明页：不包含 token，也不加载任何静态资源。 */
    private void tokenPage(HttpExchange ex) throws IOException {
        byte[] page = ("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>需要访问令牌</title><body style=\"font-family:system-ui,-apple-system,'PingFang SC',sans-serif;"
                + "background:#0f1118;color:#eef0f9;display:grid;place-items:center;height:100vh;margin:0\">"
                + "<div style=\"max-width:440px;padding:28px;border:1px solid rgba(255,255,255,.12);"
                + "border-radius:18px;line-height:1.7\"><h2 style=\"margin:0 0 8px\">需要访问令牌</h2>"
                + "<p style=\"color:#a3a9c2;margin:0\">请用桌面版启动时打印的完整链接打开。"
                + "令牌每次启动都会更新，第一次打开后浏览器会记住 30 天。</p></div></body></html>")
                .getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(403, page.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(page); }
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
        OutputStream os = ex.getResponseBody();
        sseOutputs.add(os);
        long lastBeat = System.currentTimeMillis();
        try {
            while (running) {
                String payload = Json.write(decorate(engine.state()));
                os.write(("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
                long now = System.currentTimeMillis();
                if (now - lastBeat > 15_000) {
                    // 心跳注释行：代理/浏览器不会把它当事件，但能保住连接
                    os.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    lastBeat = now;
                }
                Thread.sleep(900);
            }
        } catch (Exception ignored) {
            // 浏览器关页面/切后台、服务停止都会走到这里，属于正常现象
        } finally {
            sseOutputs.remove(os);
            try { os.close(); } catch (IOException ignored) { }
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
        return body(ex, 32 * 1024 * 1024);
    }

    private Map<String, Object> body(HttpExchange ex, int limit) throws IOException {
        byte[] data = readAll(ex.getRequestBody(), limit);
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

    /**
     * 目录选择器数据源：只回目录，文件不回，避免 UI 里出现几万个文件名。
     *
     * <p>返回 {@code null} 表示请求的路径不在允许范围内——调用方回 403，
     * 而不是像旧实现那样悄悄退回用户主目录（那等于把整个家目录列给前端看）。
     */
    private Map<String, Object> browse(String path) {
        List<Path> allowed = engine.allowedRoots();
        Path dir;
        try {
            dir = DirGuard.resolveWritable(path, allowed, engine.defaultDir());
        } catch (Exception e) {
            return null;
        }
        // 目录还没创建时，从最近的存在祖先开始浏览
        Path probe = dir;
        while (probe != null && !Files.isDirectory(probe)) probe = probe.getParent();
        if (probe == null) return null;
        dir = probe;

        List<Object> dirs = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                    .sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
                    .limit(500)
                    .forEach(p -> {
                        // 软链到白名单之外的目录不列出来，避免「点进去就 403」的困惑
                        try {
                            Path real = p.toRealPath();
                            for (Path root : allowed) {
                                if (DirGuard.isUnder(real, root)) {
                                    dirs.add(Json.obj("name", p.getFileName().toString(), "path", p.toString()));
                                    break;
                                }
                            }
                        } catch (Exception ignored) { }
                    });
        } catch (Exception ignored) { }

        List<Object> roots = new ArrayList<>();
        for (Path root : allowed) roots.add(Json.obj("name", root.toString(), "path", root.toString()));
        Path parent = dir.getParent();
        boolean parentAllowed = false;
        if (parent != null) {
            for (Path root : allowed) if (DirGuard.isUnder(parent, root)) { parentAllowed = true; break; }
        }
        return Json.obj(
                "path", dir.toString(),
                "parent", parentAllowed ? parent.toString() : "",
                "separator", java.io.File.separator,
                "dirs", dirs,
                "roots", roots
        );
    }
}

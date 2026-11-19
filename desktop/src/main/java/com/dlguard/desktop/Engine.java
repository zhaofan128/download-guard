package com.dlguard.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 下载引擎：多连接分段 + 断点续传 + 全局限速 + 队列。
 *
 * 设计取舍：
 * - 每个任务一个普通线程，任务内每个分段一个 IO 线程（桌面场景连接数不会爆炸）；
 * - 分段进度写 .meta.json，进程被杀也能从断点接上；
 * - 写盘用 RandomAccessFile 按偏移写，分段之间绝不重叠，避免"文件大了但内容是错的"。
 */
public final class Engine {

    public interface Listener {
        void onChanged();
    }

    public static final String VERSION = "3.4.2";

    /**
     * 单个分段允许的最大重连次数。
     *
     * 正常网络抖动是个位数；设这个上限只为拦住「每次给几字节就断开」的病态服务器——
     * 没有它，只要服务器愿意给数据，这个 while 就永远转下去，用户看到的是
     * 一个永远 0.1% 的任务，而不是一条能看懂的错误。每次重连至少退避 400ms，
     * 500 次约等于 200 秒。
     */
    static final int MAX_SEGMENT_RECONNECTS = 500;

    /** 单个分段连续收不到数据的容忍时长，超过就判定这条连接已经死了。 */
    static final long STALL_LIMIT_MS = 120_000L;

    public final Store store;
    public volatile Settings settings;
    public volatile int serverPort = 8765;
    private final List<Task> tasks = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Task> index = new ConcurrentHashMap<>();
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final SpeedLimiter limiter = new SpeedLimiter();
    private final Object queueLock = new Object();
    private final List<Listener> listeners = new ArrayList<>();
    private final AtomicLong lastPersist = new AtomicLong(0);
    private volatile boolean shutdown = false;
    private Thread ticker;

    public Engine(Store store) {
        this.store = store;
        this.settings = store.loadSettings();
        limiter.setRate(settings.speedLimitKB * 1024);
        for (Task t : store.loadTasks()) {
            // 老版本的任务可能没有 dir 或目录已被删除：统一收敛到当前保存目录，
            // 不让后续 createDirectories 在进程工作目录里凭空建目录
            try {
                t.dir = DirGuard.resolveWritable(t.dir, allowedRoots(), defaultDir()).toString();
            } catch (Exception e) {
                t.dir = settings.saveDir;
            }
            tasks.add(t);
            index.put(t.id, t);
        }
        recoverFromMeta();
    }

    /**
     * 启动时对齐「任务记录」和「真实临时文件」。
     *
     * <p>旧实现只写 meta 不读，恢复完全依赖 tasks.json；如果 part 已经被移动/清理而
     * segments 还写着 done=size，下一轮会新建 RandomAccessFile 并 setLength(size)，
     * 产出一个全零文件然后当成功收尾。这里的原则：
     * <ul>
     *   <li>meta 比 tasks.json 更接近实时进度，能对上就采用 meta；</li>
     *   <li>part 不存在或大小不符时，任何 done 都不可信，分段全部清零；</li>
     *   <li>completed 任务只有在目标文件仍存在且非空时才保留状态。</li>
     * </ul>
     */
    private void recoverFromMeta() {
        Set<String> known = ConcurrentHashMap.newKeySet();
        for (Task t : snapshot()) {
            known.add(t.id);
            if ("HLS".equals(t.kind) || "BT".equals(t.kind)) continue;
            mergeMeta(t);
            repairPart(t);
        }
        // tasks.json 损坏时 Store 会把它备份后返回空列表；只要 meta 还在，
        // 至少能把任务（URL/文件名/断点）恢复成暂停状态，让用户手动继续。
        try (java.util.stream.Stream<Path> stream = Files.list(store.partsDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".meta.json"))
                    .forEach(p -> {
                        String file = p.getFileName().toString();
                        String id = file.substring(0, file.length() - ".meta.json".length());
                        if (known.contains(id)) return;
                        Task t = taskFromMeta(id, p);
                        if (t != null) {
                            tasks.add(t);
                            index.put(t.id, t);
                        }
                    });
        } catch (IOException ignored) { }
    }

    private void mergeMeta(Task t) {
        Map<String, Object> meta = readMeta(t);
        if (meta == null) return;
        long size = Json.num(meta, "size", 0);
        if (t.size <= 0 && size > 0) t.size = size;
        if (!t.name.isEmpty()) {
            // 保留 tasks.json 里的展示名
        } else {
            t.name = Json.str(meta, "name", t.name);
        }
        List<Object> list = Json.list(meta.get("segments"));
        if (list.isEmpty()) return;
        List<Task.Seg> fromMeta = new ArrayList<>();
        for (Object o : list) {
            Map<String, Object> sm = Json.map(o);
            fromMeta.add(new Task.Seg(Json.num(sm, "start", 0), Json.num(sm, "end", 0),
                    Json.num(sm, "done", 0)));
        }
        if (t.segs.isEmpty()) {
            t.segs = fromMeta;
        } else if (sameLayout(t.segs, fromMeta)) {
            // 取两者较大的 done：meta 每 700ms 写一次，通常比 tasks.json 新
            for (int i = 0; i < t.segs.size(); i++) {
                long d = Math.min(fromMeta.get(i).done, t.segs.get(i).length());
                if (d > t.segs.get(i).done) t.segs.get(i).done = d;
            }
        }
        t.done = t.totalDone();
    }

    private static boolean sameLayout(List<Task.Seg> a, List<Task.Seg> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).start != b.get(i).start || a.get(i).end != b.get(i).end) return false;
        }
        return true;
    }

    private void repairPart(Task t) {
        Path part = store.partPath(t);
        boolean partOk = Files.isRegularFile(part);
        long partSize = 0;
        if (partOk) {
            try { partSize = Files.size(part); } catch (IOException e) { partSize = 0; }
        }
        if (partOk && partSize > 0) {
            if (t.size > 0 && partSize != t.size) {
                // 临时文件与记录不一致：分段位置全部不可信，删掉重下
                t.segs.clear();
                t.done = 0;
                t.status = "paused";
                t.warning = "临时文件大小与断点记录不一致，已重置，续传会从头开始";
                try { Files.deleteIfExists(part); } catch (IOException ignored) { }
            }
            return;
        }

        // part 不存在或 0 字节：如果目标文件已经落地，保留 completed；否则断点作废
        if ("completed".equals(t.status) && !t.finalPath.isEmpty()) {
            try {
                Path done = java.nio.file.Paths.get(t.finalPath);
                if (Files.isRegularFile(done) && Files.size(done) > 0) return;
            } catch (Exception ignored) { }
        }
        boolean hadProgress = t.segs.stream().anyMatch(s -> s.done > 0);
        t.segs.clear();
        t.done = 0;
        if (hadProgress || "completed".equals(t.status) || "error".equals(t.status)) {
            t.status = "paused";
            t.resumeOnStart = false;
            t.warning = "上次的临时文件已不存在，断点已重置，请重新开始下载";
        }
        try { Files.deleteIfExists(part); } catch (IOException ignored) { }
    }

    private Map<String, Object> readMeta(Task t) {
        try {
            String text = new String(Files.readAllBytes(store.metaPath(t)), StandardCharsets.UTF_8);
            return Json.map(Json.parse(text));
        } catch (Exception e) {
            return null;
        }
    }

    private Task taskFromMeta(String id, Path metaFile) {
        try {
            Map<String, Object> m = Json.map(Json.parse(new String(Files.readAllBytes(metaFile), StandardCharsets.UTF_8)));
            String url = Json.str(m, "url", "");
            if (url.isEmpty()) return null;
            Task t = new Task();
            t.id = id;
            t.url = url;
            t.name = Json.str(m, "name", Util.nameFromUrl(url));
            t.size = Json.num(m, "size", 0);
            try {
                t.dir = DirGuard.resolveWritable(Json.str(m, "dir", ""), allowedRoots(), defaultDir()).toString();
            } catch (Exception e) {
                t.dir = settings.saveDir;
            }
            t.kind = url.startsWith("magnet:") ? "BT"
                    : (url.toLowerCase(Locale.ROOT).contains(".m3u8") ? "HLS" : "HTTP");
            t.status = "paused";
            t.resumeOnStart = false;
            t.warning = "任务列表损坏，已从断点元数据恢复；请确认后继续";
            for (Object o : Json.list(m.get("segments"))) {
                Map<String, Object> sm = Json.map(o);
                t.segs.add(new Task.Seg(Json.num(sm, "start", 0), Json.num(sm, "end", 0),
                        Json.num(sm, "done", 0)));
            }
            t.done = t.totalDone();
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    /** 当前允许写入的根目录（保存目录 + 用户显式添加的目录），已解析真实路径。 */
    public List<Path> allowedRoots() {
        return DirGuard.allowedRoots(settings);
    }

    /** 默认保存目录的 canonical 形式；配置本身损坏时退回用户主目录下的 Downloads。 */
    public Path defaultDir() throws IOException {
        try {
            return DirGuard.canonical(settings.saveDir);
        } catch (Exception e) {
            return DirGuard.canonical(Settings.defaultSaveDir());
        }
    }

    public void addListener(Listener l) {
        synchronized (listeners) { listeners.add(l); }
    }

    public void fireNow() {
        fire();
    }

    public void persistNow() {
        persist(true);
    }

    private void fire() {
        synchronized (listeners) {
            for (Listener l : listeners) {
                try { l.onChanged(); } catch (Exception ignored) { }
            }
        }
    }

    public void start() {
        ticker = new Thread(this::tickLoop, "engine-ticker");
        ticker.setDaemon(true);
        ticker.start();

        if (settings.autoResume) {
            // 只恢复"上次退出时正在下载"的任务；用户手动暂停的保持暂停，别擅自开工
            for (Task t : snapshot()) {
                if ("paused".equals(t.status) && t.resumeOnStart && hasPartial(t)) {
                    t.status = "queued";
                }
            }
        }
        startQueued();
    }

    private boolean hasPartial(Task t) {
        return t.segs.stream().anyMatch(s -> s.done > 0) || Files.exists(store.partPath(t));
    }

    public void shutdown() {
        shutdown = true;
        for (Task t : snapshot()) {
            if ("downloading".equals(t.status) || "queued".equals(t.status)) {
                t.pauseRequested = true;
                t.status = "paused";
                t.resumeOnStart = true;   // 下次启动接着下，而不是当成用户主动暂停
                saveMeta(t);
            }
        }
        persist(true);
    }

    // ------------------------------------------------------------------ 查询

    public List<Task> snapshot() {
        synchronized (tasks) { return new ArrayList<>(tasks); }
    }

    public Task get(String id) {
        return index.get(id);
    }

    public Map<String, Object> state() {
        List<Object> arr = new ArrayList<>();
        long totalSpeed = 0;
        int downloading = 0;
        int completed = 0;
        for (Task t : snapshot()) {
            arr.add(t.toMap());
            totalSpeed += Math.max(0, t.speed);
            if ("downloading".equals(t.status)) downloading++;
            if ("completed".equals(t.status)) completed++;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", VERSION);
        m.put("port", serverPort);
        m.put("tasks", arr);
        m.put("settings", settings.toMap());
        m.put("stats", Json.obj(
                "speed", totalSpeed,
                "downloading", downloading,
                "completed", completed,
                "total", arr.size(),
                "connections", active.size()
        ));
        m.put("env", Json.obj(
                "os", System.getProperty("os.name", "") + " " + System.getProperty("os.arch", ""),
                "java", System.getProperty("java.version", ""),
                "home", System.getProperty("user.home", ""),
                "baseDir", store.baseDir.toString(),
                "aria2", Torrent.aria2Path(settings),
                "ffmpeg", Hls.ffmpegPath()
        ));
        return m;
    }

    // ------------------------------------------------------------------ 任务操作

    public Task add(String url, String dir, int conn, String expectedSha) {
        return add(url, dir, conn, expectedSha, null);
    }

    /**
     * 投递来源附带的请求上下文。
     *
     * 网盘直链不是「有个 URL 就能下」：它通常校验 Referer、要求带登录 Cookie，
     * 有的还按 UA 区分客户端。缺任何一个都会拿到 403 或一个 200 的登录页 HTML。
     * Cookie 只在内存里传给请求头，不写进 tasks.json。
     */
    public static final class Intake {
        public String referer = "";
        public String cookie = "";
        public String userAgent = "";
        public String fileName = "";
        public String source = "manual";
        public long size = 0;
    }

    public Task add(String url, String dir, int conn, String expectedSha, Intake intake) {
        String u = url == null ? "" : url.trim();
        String lower = u.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("magnet:"))) {
            throw new IllegalArgumentException("只接受 http/https/magnet 链接");
        }
        Task t = new Task();
        t.url = u;
        try {
            // 目录是请求里带来的，必须按白名单解析；非法路径直接拒绝，不创建任何目录
            t.dir = DirGuard.resolveWritable(dir, allowedRoots(), defaultDir()).toString();
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        t.connections = conn > 0 ? Math.min(32, conn) : settings.connections;
        t.expectedSha256 = expectedSha == null ? "" : expectedSha.trim().toLowerCase(Locale.ROOT);
        t.kind = u.startsWith("magnet:") ? "BT" : (lower.contains(".m3u8") ? "HLS" : "HTTP");
        t.name = u.startsWith("magnet:") ? Torrent.displayName(u) : Util.nameFromUrl(u);
        t.nameAuto = !u.startsWith("magnet:");
        t.status = "queued";
        if (intake != null) {
            t.referer = safeHeader(intake.referer);
            t.cookie = safeHeader(intake.cookie);
            t.userAgent = safeHeader(intake.userAgent);
            t.source = intake.source == null || intake.source.isEmpty() ? "manual" : intake.source;
            if (intake.fileName != null && !intake.fileName.trim().isEmpty()) {
                t.name = Util.sanitizeName(intake.fileName.trim(), Util.nameFromUrl(u));
                t.nameAuto = false;
            }
            if (intake.size > 0) t.size = intake.size;
        }
        tasks.add(t);
        index.put(t.id, t);
        persist(true);
        startQueued();
        fire();
        return t;
    }

    /** 请求头里不允许出现换行：否则一个「文件名」就能注入额外的头。 */
    private static String safeHeader(String v) {
        if (v == null) return "";
        return v.replace("\r", "").replace("\n", "").trim();
    }

    /** .torrent 文件：解析出名字，交给 aria2 或内置种子引擎。 */
    public Task addTorrent(byte[] data, String fileName, String dir) {
        Task t = new Task();
        t.kind = "BT";
        t.name = Torrent.nameOfTorrent(data, fileName);
        try {
            t.dir = DirGuard.resolveWritable(dir, allowedRoots(), defaultDir()).toString();
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        t.url = "torrent:" + t.name;
        t.connections = 1;
        t.status = "queued";
        t.torrentData = data;
        tasks.add(t);
        index.put(t.id, t);
        persist(true);
        startQueued();
        fire();
        return t;
    }

    public void pause(String id) {
        Task t = index.get(id);
        if (t == null) return;
        t.pauseRequested = true;
        t.resumeOnStart = false;   // 用户主动暂停：重启后也不自动开工
        if ("queued".equals(t.status)) {
            t.status = "paused";
            persist(true);
        }
        fire();
    }

    public void resume(String id) {
        Task t = index.get(id);
        if (t == null) return;
        if ("completed".equals(t.status)) return;
        t.status = "queued";
        t.error = "";
        t.lastHttpCode = 0;
        t.pauseRequested = false;
        startQueued();
        fire();
    }

    public void retry(String id) {
        Task t = index.get(id);
        if (t == null) return;
        t.error = "";
        t.lastHttpCode = 0;
        t.status = "queued";
        persist(true);
        startQueued();
        fire();
    }

    public void cancel(String id) {
        Task t = index.get(id);
        if (t == null) return;
        t.cancelRequested = true;
        t.pauseRequested = true;
        if (!active.contains(id)) {
            finishCancel(t);
        }
        fire();
    }

    /**
     * 全部暂停：下载中的打暂停标记（线程在下一个检查点退出），排队中的直接转暂停。
     * 与单个任务的「暂停」语义一致——保留断点，重启后不自动开工。
     */
    public int pauseAll() {
        int n = 0;
        for (Task t : snapshot()) {
            if ("downloading".equals(t.status) || "queued".equals(t.status)) {
                t.pauseRequested = true;
                t.resumeOnStart = false;
                if ("queued".equals(t.status)) t.status = "paused";
                n++;
            }
        }
        persist(true);
        fire();
        return n;
    }

    /** 全部继续：暂停中的回到队列，按「同时下载任务数」的额度依次开跑。 */
    public int resumeAll() {
        int n = 0;
        for (Task t : snapshot()) {
            if ("paused".equals(t.status)) {
                t.status = "queued";
                t.error = "";
                t.pauseRequested = false;
                n++;
            }
        }
        if (n > 0) {
            persist(true);
            startQueued();
        }
        fire();
        return n;
    }

    private void finishCancel(Task t) {
        t.status = "cancelled";
        t.done = 0;
        t.segs.clear();
        try { Files.deleteIfExists(store.partPath(t)); } catch (IOException ignored) { }
        try { Files.deleteIfExists(store.metaPath(t)); } catch (IOException ignored) { }
        persist(true);
    }

    public void remove(String id, boolean withFile) {
        Task t = index.get(id);
        if (t == null) return;
        t.pauseRequested = true;
        t.cancelRequested = true;
        tasks.remove(t);
        index.remove(id);
        try { Files.deleteIfExists(store.partPath(t)); } catch (IOException ignored) { }
        try { Files.deleteIfExists(store.metaPath(t)); } catch (IOException ignored) { }
        if (withFile && !t.finalPath.isEmpty()) {
            try { Files.deleteIfExists(java.nio.file.Paths.get(t.finalPath)); } catch (Exception ignored) { }
        }
        persist(true);
        fire();
    }

    public void clearCompleted() {
        for (Task t : snapshot()) {
            if ("completed".equals(t.status) || "cancelled".equals(t.status) || "error".equals(t.status)) {
                tasks.remove(t);
                index.remove(t.id);
            }
        }
        persist(true);
        fire();
    }

    public void applySettings(Settings s) {
        this.settings = s;
        limiter.setRate(s.speedLimitKB * 1024);
        store.saveSettings(s);
        startQueued();
        fire();
    }

    // ------------------------------------------------------------------ 调度

    private void startQueued() {
        // 「统计 active + 启动」必须在同一把锁里完成：多个任务同时结束时会并发调用这里，
        // 分开判断会让 running 数量超过 maxConcurrent。
        synchronized (queueLock) {
            if (shutdown) return;
            int limit = Math.max(1, settings.maxConcurrent);
            for (Task t : snapshot()) {
                if (active.size() >= limit) break;
                if (!"queued".equals(t.status)) continue;
                if (active.contains(t.id)) continue;
                launch(t);
            }
        }
    }

    private void launch(Task t) {
        active.add(t.id);
        t.status = "downloading";
        t.error = "";
        t.resumeOnStart = true;
        t.pauseRequested = false;
        t.cancelRequested = false;
        t.stage = "";
        t.warning = "";
        t.signature = "";
        t.signatureDetail = "";
        Thread th = new Thread(() -> runTask(t), "task-" + shortId(t));
        th.setDaemon(true);
        th.start();
        fire();
    }

    private String shortId(Task t) {
        return t.id.length() > 8 ? t.id.substring(0, 8) : t.id;
    }

    private void runTask(Task t) {
        try {
            if ("BT".equals(t.kind)) {
                Torrent.run(this, t);
            } else if ("HLS".equals(t.kind)) {
                Hls.run(this, t);
            } else {
                downloadHttp(t);
            }
            if (t.cancelRequested) {
                finishCancel(t);
            } else if (t.pauseRequested) {
                t.status = "paused";
                saveMeta(t);
            }
        } catch (PauseSignal e) {
            t.status = t.cancelRequested ? "cancelled" : "paused";
            saveMeta(t);
        } catch (Exception e) {
            if (t.cancelRequested) {
                finishCancel(t);
            } else if (t.pauseRequested) {
                t.status = "paused";
                saveMeta(t);
            } else {
                t.status = "error";
                // 4xx/5xx 状态码优先按它解释：重试/短连接下最后一击常是 EOF 类异常，
                // 但根因（403/404/429…）在最后一次成功收到的响应码里，跨 JDK 跨网络都稳定
                t.error = DownloadErrors.forCode(t.lastHttpCode, e, t.url);
                t.speed = 0;
            }
        } finally {
            t.speed = 0;
            t.eta = 0;
            t.stage = "";
            active.remove(t.id);
            persist(true);
            fire();
            // 队列里还有活就接着拉，但让出一点时间给 UI 刷新
            new Thread(() -> {
                try { Thread.sleep(200); } catch (InterruptedException ignored) { }
                startQueued();
            }, "queue-advance").start();
        }
    }

    static final class PauseSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;

        PauseSignal() { super("paused"); }
    }

    /** 分段请求收到 200：服务器忽略了 Range，必须整体降级为单流。 */
    static final class RangeIgnored extends RuntimeException {
        private static final long serialVersionUID = 1L;

        RangeIgnored() { super("服务器忽略了 Range"); }
    }

    // ------------------------------------------------------------------ HTTP 下载

    private void downloadHttp(Task t) throws Exception {
        if (t.size <= 0 || t.name.isEmpty()) {
            Probe p = probe(t);
            if (p.m3u8) {
                t.kind = "HLS";
                fire();
                Hls.run(this, t);
                return;
            }
            applyProbe(t, p);
        }
        if (t.name.isEmpty()) t.name = Util.sanitizeName(Util.nameFromUrl(t.url), "download");

        // 续传前确认远端文件没被替换：大小或 ETag 变了就从头来，别把两个版本拼成一个坏文件
        if (!t.segs.isEmpty() && t.size > 0) {
            try {
                Probe p = probe(t);
                boolean sizeChanged = p.size > 0 && p.size != t.size;
                boolean etagChanged = !t.etag.isEmpty() && p.etag != null && !p.etag.isEmpty()
                        && !t.etag.equals(p.etag);
                if (sizeChanged || etagChanged) {
                    t.segs.clear();
                    t.done = 0;
                    t.etag = p.etag == null ? "" : p.etag;
                    t.size = p.size > 0 ? p.size : t.size;
                    try { Files.deleteIfExists(store.partPath(t)); } catch (IOException ignored) { }
                }
            } catch (Exception ignored) {
                // 探测失败不阻断续传，按本地记录继续；真出问题哈希那一步会暴露
            }
        }

        Path part = store.partPath(t);
        Files.createDirectories(java.nio.file.Paths.get(t.dir));
        // 兜底：part 不在（被清理/移动）而分段还写着进度时，不能直接 setLength 出一个全零文件
        if (t.segs.stream().anyMatch(s -> s.done > 0)
                && (!Files.exists(part) || Files.size(part) == 0)) {
            t.segs.clear();
            t.done = 0;
        }
        if (t.size > 0 && Files.exists(part) && Files.size(part) != t.size && t.segs.isEmpty()) {
            // 没有分段记录但残留了不同大小的临时文件：不可信，重来
            Files.delete(part);
        }

        // 未知长度（chunked / 没有 Content-Length）或不支持 Range：只能从 0 顺序读到 EOF。
        // 旧实现会规划一个长度 1 的分段，结果 1MB 的文件只写 1 字节还报 completed。
        boolean singleStream = t.size <= 0 || !t.rangeSupported;
        if (singleStream) {
            if (!t.segs.isEmpty()) {
                t.segs.clear();
                t.done = 0;
                Files.deleteIfExists(part);
            }
            downloadSingle(t, part);
        } else {
            if (t.segs.isEmpty()) {
                t.segs = planSegments(t.size, t.connections, true);
            }
            t.done = t.totalDone();
            try {
                downloadSegmented(t, part);
            } catch (RangeIgnored e) {
                // 服务器对 Range 请求回了 200 全量：立刻放弃分段，清空临时文件改走单流，
                // 绝不能让多个 worker 把全量数据写到不同偏移
                t.segs.clear();
                t.done = 0;
                t.rangeSupported = false;
                Files.deleteIfExists(part);
                downloadSingle(t, part);
            }
        }

        if (t.pauseRequested) {
            saveMeta(t);
            throw new PauseSignal();
        }
        finalizeFile(t, part);
    }

    private void downloadSegmented(Task t, Path part) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(part.toFile(), "rw")) {
            if (t.size > 0 && raf.length() != t.size) raf.setLength(t.size);
            List<Future<?>> futures = new ArrayList<>();
            int threads = Math.min(Math.max(1, t.connections), Math.max(1, t.segs.size()));
            ExecutorService io = Executors.newFixedThreadPool(threads, r -> {
                Thread th = new Thread(r, "seg-" + shortId(t));
                th.setDaemon(true);
                return th;
            });
            try {
                for (Task.Seg seg : t.segs) {
                    if (seg.done >= seg.length()) continue;
                    futures.add(io.submit(() -> {
                        try {
                            fetchSegment(t, seg, raf);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        return null;
                    }));
                }
                for (Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (java.util.concurrent.ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof RuntimeException && cause.getCause() instanceof PauseSignal) {
                            throw new PauseSignal();
                        }
                        if (cause instanceof RuntimeException && cause.getCause() instanceof RangeIgnored) {
                            throw (RangeIgnored) cause.getCause();
                        }
                        if (cause instanceof RangeIgnored) throw (RangeIgnored) cause;
                        throw new IOException(cause == null ? e.getMessage() : cause.getMessage(), cause);
                    }
                }
            } finally {
                io.shutdownNow();
                try { io.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            }
        }
    }

    /**
     * 单流下载：从 0 顺序读到 EOF，边读边写盘。
     *
     * <p>用于两种服务器：没有 Content-Length 的 chunked 响应，以及明确不支持 Range 的服务器。
     * 已知长度时如果连接提前断开，会尽量用 Range 从断点续；不支持 Range 就从头重来，
     * 重试 3 次仍不完整就报错——绝不会把半截文件交给收尾流程。
     */
    private void downloadSingle(Task t, Path part) throws Exception {
        long expected = t.size;
        long written = 0;
        int restarts = 0;
        byte[] buf = new byte[128 * 1024];
        try (RandomAccessFile raf = new RandomAccessFile(part.toFile(), "rw")) {
            raf.setLength(0);
            while (true) {
                if (t.pauseRequested || t.cancelRequested || shutdown) throw new PauseSignal();
                String range = written > 0 && t.rangeSupported ? "bytes=" + written + "-" : null;
                HttpURLConnection c = open(t, t.url, range);
                int code = c.getResponseCode();
                t.lastHttpCode = code;
                if (code == 206) {
                    // 正常续传，但要核对起点：服务器回 206 却从 0 开始，等于把前半段又写一遍
                    String cr = c.getHeaderField("Content-Range");
                    long start = parseRangeStart(cr);
                    if (start != written) {
                        if (start == 0) {
                            written = 0;
                            raf.setLength(0);
                        } else {
                            c.disconnect();
                            throw new IOException("服务器返回了错误的续传起点（请求 " + written
                                    + "，实际 " + start + "），已停止以免拼出坏文件");
                        }
                    }
                } else if (code == 200) {
                    if (written > 0) {
                        // 服务器忽略了 Range：它给的是从 0 开始的完整文件，之前写的作废
                        written = 0;
                        raf.setLength(0);
                    }
                } else if (code == 416 && expected > 0 && written >= expected) {
                    c.disconnect();
                    break;
                } else {
                    String msg = DownloadErrors.forHttp(code, t.url);
                    c.disconnect();
                    throw new IOException(msg);
                }
                try (InputStream in = c.getInputStream()) {
                    raf.seek(written);
                    while (true) {
                        if (t.pauseRequested || t.cancelRequested || shutdown) throw new PauseSignal();
                        int n;
                        try {
                            n = in.read(buf);
                        } catch (IOException e) {
                            // JDK 17 提前关闭返回 -1，JDK 25 抛 Premature EOF：
                            // 视同 EOF，走下面统一的「重试/不完整」路径，跨 JDK 文案一致
                            if (DownloadErrors.isEofLike(e)) break;
                            throw e;
                        }
                        if (n < 0) break;
                        limiter.acquire(n);
                        raf.write(buf, 0, n);
                        written += n;
                        t.done = written;
                        if (expected > 0 && written >= expected) break;
                    }
                } finally {
                    c.disconnect();
                }
                if (expected > 0) {
                    if (written == expected) break;
                    if (written > expected) {
                        throw new IOException("服务器返回的数据比声明的长度多（" + written + " > " + expected + "）");
                    }
                    if (++restarts > 3) {
                        throw new IOException("下载不完整：服务器只给了 " + written + " / " + expected
                                + " 字节，重试 " + (restarts - 1) + " 次仍失败");
                    }
                    if (!t.rangeSupported) {
                        written = 0;
                        raf.setLength(0);
                    }
                    Thread.sleep(500L * restarts);
                    continue;
                }
                // 未知长度：读到 EOF 就是完整的；但空响应必须报错，不能产出 0 字节文件
                if (written <= 0) throw new IOException("服务器返回了空文件");
                t.size = written;
                break;
            }
        }
        t.segs.clear();
        t.segs.add(new Task.Seg(0, Math.max(0, written - 1), written));
        t.done = written;
        if (t.size <= 0) t.size = written;
        saveMeta(t);
    }

    private void fetchSegment(Task t, Task.Seg seg, RandomAccessFile raf) throws Exception {
        int attempt = 0;
        int restarts = 0;
        int reconnects = 0;
        long lastProgressAt = System.currentTimeMillis();
        byte[] buf = new byte[128 * 1024];
        while (seg.done < seg.length()) {
            if (t.pauseRequested || t.cancelRequested || shutdown) throw new PauseSignal();
            long from = seg.start + seg.done;
            long to = seg.end;
            HttpURLConnection c = open(t, t.url, "bytes=" + from + "-" + to);
            int code = c.getResponseCode();
            t.lastHttpCode = code;
            if (code == 416) {
                c.disconnect();
                if (seg.done >= seg.length()) return;
                throw new IOException("服务器返回 416（请求范围越界）");
            }
            boolean partial = code == 206;
            boolean wholeOk = code == 200 && t.segs.size() == 1 && seg.start == 0;
            if (partial) {
                // 必须核对服务器真的从请求的偏移开始给数据。只认「206」不看 Content-Range，
                // 会把「每次都从 0 发 512KB」的服务器拼成「前 512KB 重复两遍、长度却正确」的坏文件。
                String cr = c.getHeaderField("Content-Range");
                if (cr == null || !cr.startsWith("bytes " + from + "-")) {
                    c.disconnect();
                    throw new RangeIgnored();
                }
            }
            if (code == 200 && t.segs.size() > 1) {
                // 多分段时收到 200 是最危险的情况：多个 worker 都会把完整文件写到自己的偏移上。
                // 立刻放弃分段，交给上层改走单流。
                c.disconnect();
                throw new RangeIgnored();
            }
            if (!partial && !wholeOk) {
                String msg = DownloadErrors.forHttp(code, t.url);
                c.disconnect();
                throw new IOException(msg);
            }
            if (code == 200 && seg.done > 0) {
                // 服务器忽略了 Range，直接给了完整文件：数据是从第 0 字节开始的。
                // 若还按 seg.done 的位置写，文件前半段是新的、后半段是旧数据，长度对但内容是错的。
                // 这种服务器本来就不支持续传，只能从头写一遍。
                if (++restarts > 3) {
                    c.disconnect();
                    throw new IOException("服务器不支持断点续传，且连接反复中断，请换个网络环境重试");
                }
                seg.done = 0;
                synchronized (raf) { raf.setLength(0); }
            }
            long written = 0;
            try (InputStream in = c.getInputStream()) {
                while (seg.done < seg.length()) {
                    if (t.pauseRequested || t.cancelRequested || shutdown) throw new PauseSignal();
                    int n;
                    try {
                        n = in.read(buf, 0, (int) Math.min(buf.length, seg.length() - seg.done));
                    } catch (IOException e) {
                        // JDK 17 提前关闭返回 -1，JDK 25 抛 Premature EOF：视同 EOF
                        if (DownloadErrors.isEofLike(e)) break;
                        throw e;
                    }
                    if (n < 0) break;
                    limiter.acquire(n);
                    long pos = seg.start + seg.done;
                    synchronized (raf) {
                        raf.seek(pos);
                        raf.write(buf, 0, n);
                    }
                    seg.done += n;
                    written += n;
                    lastProgressAt = System.currentTimeMillis();
                    t.done = t.totalDone();
                }
            } finally {
                c.disconnect();
            }
            if (seg.done < seg.length()) {
                if (t.size <= 0 && seg.start == 0) {
                    // 服务器没给长度（chunked / 0 字节），读到 EOF 就是完整的
                    seg.done = seg.length();
                    break;
                }
                // 连接提前断了：同一段重试，保留已写入的字节。
                // 两种病态服务器必须设上限，否则这个 while 永远转下去：
                //  1) 一个字节都不给就断（attempt）；
                //  2) 每次只给一点点再断，看着"有进展"实际永远下不完（reconnects / 无进展时长）。
                if (written == 0 && ++attempt > 4) {
                    throw new IOException("连接反复中断，已重试 " + attempt + " 次");
                }
                String budget = retryBudgetError(reconnects + 1,
                        System.currentTimeMillis() - lastProgressAt, seg.done, seg.length());
                reconnects++;
                if (budget != null) throw new IOException(budget);
                Thread.sleep(Math.min(4000, 400L * Math.max(1, attempt)));
            }
        }
    }

    /** 从 {@code bytes 123-456/789} 里取出起始偏移；解析不出来返回 -1。 */
    static long parseRangeStart(String contentRange) {
        if (contentRange == null) return -1;
        String s = contentRange.trim();
        if (!s.regionMatches(true, 0, "bytes ", 0, 6)) return -1;
        s = s.substring(6).trim();
        int dash = s.indexOf('-');
        if (dash <= 0) return -1;
        try {
            return Long.parseLong(s.substring(0, dash).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 分段重试预算。返回 null 表示还能继续重试，否则返回给用户看的原因。
     *
     * 独立成纯函数是为了能被自检直接覆盖：这段逻辑以前没有上限，
     * 「每次给 1 字节再断开」的服务器会让 worker 永远转下去。
     */
    static String retryBudgetError(int reconnects, long silentMs, long done, long length) {
        if (reconnects > MAX_SEGMENT_RECONNECTS) {
            int percent = (int) (done * 100 / Math.max(1, length));
            return "这个分段的连接被服务器反复断开（已重连 " + MAX_SEGMENT_RECONNECTS
                    + " 次，完成 " + percent + "%），请换个网络或稍后重试";
        }
        if (silentMs > STALL_LIMIT_MS) {
            return "这个分段连续 " + (STALL_LIMIT_MS / 1000) + " 秒没有收到任何数据，已停止重试";
        }
        return null;
    }

    /** 不带任务上下文的请求（HLS 的播放列表探测等），沿用全局请求头。 */
    HttpURLConnection open(String url, String range) throws IOException {
        return open(null, url, range);
    }

    HttpURLConnection open(Task t, String url, String range) throws IOException {
        URL u = new URL(url);
        HttpURLConnection c;
        if (!settings.proxy.isEmpty()) {
            String[] hp = settings.proxy.split(":");
            if (hp.length == 2) {
                c = (HttpURLConnection) u.openConnection(new Proxy(Proxy.Type.HTTP,
                        new InetSocketAddress(hp[0], Integer.parseInt(hp[1].trim()))));
            } else {
                c = (HttpURLConnection) u.openConnection();
            }
        } else {
            c = (HttpURLConnection) u.openConnection();
        }
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        // 任务级请求头优先：网盘直链认 Referer 和 Cookie，全局 UA 只够下公开文件。
        // 这三个字段来自「浏览器接管」的载荷，缺一个都会拿到 403 或登录页。
        String ua = t != null && t.userAgent != null && !t.userAgent.isEmpty()
                ? t.userAgent : settings.userAgent;
        String ref = t != null && t.referer != null && !t.referer.isEmpty()
                ? t.referer : settings.referer;
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Encoding", "identity");
        if (!ref.isEmpty()) c.setRequestProperty("Referer", ref);
        if (t != null && t.cookie != null && !t.cookie.isEmpty()) {
            c.setRequestProperty("Cookie", t.cookie);
        }
        if (range != null) c.setRequestProperty("Range", range);
        return c;
    }

    private Probe probe(Task t) throws IOException {
        Probe p = new Probe();
        HttpURLConnection c = open(t, t.url, "bytes=0-0");
        int code = c.getResponseCode();
        t.lastHttpCode = code;
        if (code == 200 || code == 206) {
            String cr = c.getHeaderField("Content-Range");
            if (cr != null && cr.contains("/")) {
                try {
                    p.size = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
                } catch (NumberFormatException ignored) { }
            }
            if (p.size <= 0) {
                long cl = c.getContentLengthLong();
                if (cl > 0) p.size = cl;
            }
            p.range = code == 206 || "bytes".equalsIgnoreCase(c.getHeaderField("Accept-Ranges"));
            p.name = Util.parseContentDisposition(c.getHeaderField("Content-Disposition"));
            p.mime = c.getHeaderField("Content-Type");
            p.etag = c.getHeaderField("ETag");
            c.disconnect();
            if (p.name == null) p.name = Util.nameFromUrl(t.url);
            p.m3u8 = p.mime != null && (p.mime.contains("mpegurl") || p.mime.contains("m3u8"));
            return p;
        }
        c.disconnect();
        if (code == 405 || code == 501) {
            // 少数服务器不支持 Range，退回普通 GET 探测
            HttpURLConnection g = open(t, t.url, null);
            int gc = g.getResponseCode();
            if (gc != 200) {
                g.disconnect();
                throw new IOException(DownloadErrors.forHttp(gc, t.url));
            }
            p.size = g.getContentLengthLong();
            p.range = false;
            p.name = Util.parseContentDisposition(g.getHeaderField("Content-Disposition"));
            p.mime = g.getHeaderField("Content-Type");
            g.disconnect();
            if (p.name == null) p.name = Util.nameFromUrl(t.url);
            p.m3u8 = p.mime != null && p.mime.contains("mpegurl");
            return p;
        }
        throw new IOException(DownloadErrors.forHttp(code, t.url));
    }

    private void applyProbe(Task t, Probe p) {
        if (p.size > 0) t.size = p.size;
        // Content-Disposition 的优先级高于 URL 里的猜测名：很多下载站 URL 是 file.php?id=123
        if (p.name != null && !p.name.isEmpty()
                && (t.name.isEmpty() || t.nameAuto || t.name.startsWith("download"))) {
            t.name = p.name;
            t.nameAuto = false;
        }
        t.name = Util.sanitizeName(t.name, "download");
        t.rangeSupported = p.range;
        t.mime = p.mime == null ? "" : p.mime;
        t.etag = p.etag == null ? "" : p.etag;
    }

    static List<Task.Seg> planSegments(long size, int connections, boolean range) {
        List<Task.Seg> segs = new ArrayList<>();
        if (size <= 0) {
            // 长度未知不能用「0..0」占位：1 字节的段会让单流循环只读 1 字节。
            // 单流下载不使用分段（下载完再回填一段真实区间）。
            segs.add(new Task.Seg(0, -1, 0));
            return segs;
        }
        if (!range || connections <= 1) {
            segs.add(new Task.Seg(0, size - 1, 0));
            return segs;
        }
        int n = (int) Math.min(connections, Math.max(1, size / (2L * 1024 * 1024)));
        n = Math.max(1, Math.min(n, 32));
        long chunk = size / n;
        long pos = 0;
        for (int i = 0; i < n; i++) {
            long end = (i == n - 1) ? size - 1 : pos + chunk - 1;
            segs.add(new Task.Seg(pos, end, 0));
            pos = end + 1;
        }
        return segs;
    }

    // ------------------------------------------------------------------ 收尾

    void finalizeFile(Task t, Path part) throws Exception {
        t.status = "verifying";
        t.stage = "检查文件完整性";
        fire();

        // 收尾闸门：不存在 / 0 字节 / 与声明长度不符，都不能进入哈希与 completed。
        // 旧实现直接 Files.size(part) 然后覆盖 t.size，截断文件也能显示「校验通过」。
        if (!Files.exists(part)) {
            t.status = "error";
            t.error = "下载临时文件不见了（可能被清理或移动），请重试";
            persist(true);
            fire();
            return;
        }
        long size = Files.size(part);
        if (size <= 0) {
            t.status = "error";
            t.error = "下载结果是空文件，已停止（服务器可能返回了错误页）";
            try { Files.deleteIfExists(part); } catch (IOException ignored) { }
            persist(true);
            fire();
            return;
        }
        if (t.size > 0 && size != t.size) {
            t.status = "error";
            t.error = "下载不完整：预期 " + t.size + " 字节，实际 " + size + " 字节";
            t.verify = "";
            persist(true);
            fire();
            return;
        }
        t.stage = "计算 SHA-256";
        fire();
        t.size = size;
        t.sha256 = Util.sha256(part, (done, total) -> {
            t.verifyDone = done;
            t.done = done;
        });
        t.md5 = Util.md5(part);
        t.stage = "检查文件结构";
        fire();

        boolean isApk = t.name.toLowerCase(Locale.ROOT).endsWith(".apk");
        if (isApk && settings.verifyApk) {
            Integrity.Result r = Integrity.inspectApk(part);
            t.scheme = r.schemes;
            t.signer = r.certSha256;
            t.signerSubject = r.subject;
            t.signature = r.signature;
            t.signatureDetail = r.signatureDetail;
            if (!r.zipOk) {
                t.status = "error";
                t.error = "APK 压缩包结构损坏（" + r.detail + "），文件已隔离";
                quarantine(t, part);
                persist(true);
                return;
            }
            if ("failed".equals(r.signature)) {
                // 验签失败等同于文件不可信：不落地到下载目录，也不显示 completed
                t.status = "error";
                t.verify = "mismatch";
                t.error = "APK 签名校验失败：" + r.signatureDetail + "，文件已隔离";
                quarantine(t, part);
                persist(true);
                fire();
                return;
            }
            if ("unsupported".equals(r.signature)) {
                t.warning = "APK 签名未验证：" + r.signatureDetail;
            }
        } else if (isApk) {
            t.signature = "unsupported";
            t.signatureDetail = "设置里关闭了 APK 校验";
            t.warning = "APK 签名未验证（设置里已关闭）";
        }

        if (!t.expectedSha256.isEmpty()) {
            if (!t.expectedSha256.equalsIgnoreCase(t.sha256)) {
                t.verify = "mismatch";
                t.status = "error";
                t.error = "SHA-256 校验不通过：期望 " + t.expectedSha256 + "，实际 " + t.sha256;
                if (settings.quarantineOnMismatch) quarantine(t, part);
                persist(true);
                fire();
                return;
            }
            t.verify = "ok";
        } else {
            // 没有外部基准就没有「校验通过」这回事，界面只显示已计算出的哈希
            t.verify = "";
        }

        Path dir = java.nio.file.Paths.get(t.dir);
        Files.createDirectories(dir);
        Path target = Util.uniquePath(dir, t.name);
        try {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.copy(part, target, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(part);
        }
        t.finalPath = target.toString();
        t.name = target.getFileName().toString();
        t.size = Files.size(target);
        t.done = t.size;
        t.status = "completed";
        t.finishedAt = System.currentTimeMillis();
        t.stage = "";
        try { Files.deleteIfExists(store.metaPath(t)); } catch (IOException ignored) { }
        persist(true);
        fire();
    }

    private void quarantine(Task t, Path part) {
        try {
            Path q = store.quarantineDir.resolve(t.id + "-" + Util.sanitizeName(t.name, "file"));
            Files.createDirectories(q.getParent());
            Files.move(part, q, StandardCopyOption.REPLACE_EXISTING);
            t.finalPath = q.toString();
        } catch (IOException ignored) { }
    }

    // ------------------------------------------------------------------ 持久化 / 心跳

    void saveMeta(Task t) {
        try {
            Map<String, Object> m = Json.obj(
                    "size", t.size,
                    "name", t.name,
                    "url", t.url,
                    "dir", t.dir,
                    "etag", t.etag,
                    "segments", segs(t)
            );
            Util.atomicWrite(store.metaPath(t), Json.write(m).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }

    private List<Object> segs(Task t) {
        List<Object> list = new ArrayList<>();
        for (Task.Seg s : t.segs) list.add(Json.obj("start", s.start, "end", s.end, "done", s.done));
        return list;
    }

    private void persist(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastPersist.get() < 2500) return;
        lastPersist.set(now);
        store.saveTasks(snapshot());
    }

    private void tickLoop() {
        long lastTick = System.currentTimeMillis();
        Map<String, Long> lastDone = new LinkedHashMap<>();
        while (!shutdown) {
            try {
                Thread.sleep(700);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            double dt = Math.max(0.2, (now - lastTick) / 1000.0);
            lastTick = now;
            boolean changed = false;
            for (Task t : snapshot()) {
                if ("downloading".equals(t.status)) {
                    long d = t.totalDone();
                    Long prev = lastDone.get(t.id);
                    if (prev != null) {
                        long delta = Math.max(0, d - prev);
                        long inst = (long) (delta / dt);
                        // 指数平滑，避免 UI 上的速度数字乱跳
                        t.speed = t.speed == 0 ? inst : (long) (t.speed * 0.55 + inst * 0.45);
                        if (t.size > 0 && t.speed > 0) {
                            t.eta = Math.max(0, (t.size - d) / t.speed);
                        }
                    }
                    lastDone.put(t.id, d);
                    changed = true;
                } else {
                    lastDone.remove(t.id);
                    t.speed = 0;
                    t.eta = 0;
                }
            }
            for (Task t : snapshot()) {
                if ("downloading".equals(t.status)) saveMeta(t);
            }
            persist(false);
            if (changed) fire();
        }
    }

    // ------------------------------------------------------------------ 限速

    /** 全局令牌桶：所有分段共享一个速率预算，限速才是真的限速。 */
    static final class SpeedLimiter {
        private long rate;
        private long nextFreeNanos;

        synchronized void setRate(long bytesPerSec) {
            this.rate = bytesPerSec;
            this.nextFreeNanos = 0;
        }

        void acquire(int bytes) {
            long r = rate;
            if (r <= 0 || bytes <= 0) return;
            long waitNanos;
            synchronized (this) {
                long now = System.nanoTime();
                if (nextFreeNanos < now) nextFreeNanos = now;
                long cost = (long) ((double) bytes / r * 1_000_000_000L);
                nextFreeNanos += cost;
                waitNanos = nextFreeNanos - now;
            }
            if (waitNanos > 0) {
                try {
                    Thread.sleep(Math.min(1000, waitNanos / 1_000_000L), (int) (waitNanos % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static final class Probe {
        long size;
        boolean range;
        boolean m3u8;
        String name;
        String mime;
        String etag;
    }
}

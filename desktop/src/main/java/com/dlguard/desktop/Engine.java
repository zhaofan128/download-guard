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

    public static final String VERSION = "3.0.0";

    public final Store store;
    public volatile Settings settings;
    public volatile int serverPort = 8765;
    private final List<Task> tasks = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Task> index = new ConcurrentHashMap<>();
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final SpeedLimiter limiter = new SpeedLimiter();
    private final List<Listener> listeners = new ArrayList<>();
    private final AtomicLong lastPersist = new AtomicLong(0);
    private volatile boolean shutdown = false;
    private Thread ticker;

    public Engine(Store store) {
        this.store = store;
        this.settings = store.loadSettings();
        limiter.setRate(settings.speedLimitKB * 1024);
        for (Task t : store.loadTasks()) {
            tasks.add(t);
            index.put(t.id, t);
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
        String u = url == null ? "" : url.trim();
        Task t = new Task();
        t.url = u;
        t.dir = (dir == null || dir.trim().isEmpty()) ? settings.saveDir : dir.trim();
        t.connections = conn > 0 ? Math.min(32, conn) : settings.connections;
        t.expectedSha256 = expectedSha == null ? "" : expectedSha.trim().toLowerCase(Locale.ROOT);
        t.kind = u.startsWith("magnet:") ? "BT" : (u.toLowerCase(Locale.ROOT).contains(".m3u8") ? "HLS" : "HTTP");
        t.name = u.startsWith("magnet:") ? Torrent.displayName(u) : Util.nameFromUrl(u);
        t.nameAuto = !u.startsWith("magnet:");
        t.status = "queued";
        tasks.add(t);
        index.put(t.id, t);
        persist(true);
        startQueued();
        fire();
        return t;
    }

    /** .torrent 文件：解析出名字，交给 aria2 或内置种子引擎。 */
    public Task addTorrent(byte[] data, String fileName, String dir) {
        Task t = new Task();
        t.kind = "BT";
        t.name = Torrent.nameOfTorrent(data, fileName);
        t.dir = (dir == null || dir.trim().isEmpty()) ? settings.saveDir : dir.trim();
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
        t.pauseRequested = false;
        startQueued();
        fire();
    }

    public void retry(String id) {
        Task t = index.get(id);
        if (t == null) return;
        t.error = "";
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
        if (shutdown) return;
        int limit = Math.max(1, settings.maxConcurrent);
        for (Task t : snapshot()) {
            if (active.size() >= limit) break;
            if (!"queued".equals(t.status)) continue;
            if (active.contains(t.id)) continue;
            launch(t);
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
                t.error = DownloadErrors.humanize(e);
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
        if (t.size > 0 && Files.exists(part) && Files.size(part) != t.size && t.segs.isEmpty()) {
            // 没有分段记录但残留了不同大小的临时文件：不可信，重来
            Files.delete(part);
        }

        if (t.segs.isEmpty()) {
            t.segs = planSegments(t.size, t.connections, t.rangeSupported);
        }
        t.done = t.totalDone();

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
                        throw new IOException(cause == null ? e.getMessage() : cause.getMessage(), cause);
                    }
                }
            } finally {
                io.shutdownNow();
                try { io.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            }
        }

        if (t.pauseRequested) {
            saveMeta(t);
            throw new PauseSignal();
        }
        finalizeFile(t, part);
    }

    private void fetchSegment(Task t, Task.Seg seg, RandomAccessFile raf) throws Exception {
        int attempt = 0;
        int restarts = 0;
        byte[] buf = new byte[128 * 1024];
        while (seg.done < seg.length()) {
            if (t.pauseRequested || t.cancelRequested || shutdown) throw new PauseSignal();
            long from = seg.start + seg.done;
            long to = seg.end;
            HttpURLConnection c = open(t.url, "bytes=" + from + "-" + to);
            int code = c.getResponseCode();
            if (code == 416) {
                c.disconnect();
                if (seg.done >= seg.length()) return;
                throw new IOException("服务器返回 416（请求范围越界）");
            }
            boolean partial = code == 206;
            boolean wholeOk = code == 200 && t.segs.size() == 1 && seg.start == 0;
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
            }
            long written = 0;
            try (InputStream in = c.getInputStream()) {
                while (seg.done < seg.length()) {
                    if (t.pauseRequested || t.cancelRequested || shutdown) throw new PauseSignal();
                    int n = in.read(buf, 0, (int) Math.min(buf.length, seg.length() - seg.done));
                    if (n < 0) break;
                    limiter.acquire(n);
                    long pos = seg.start + seg.done;
                    synchronized (raf) {
                        raf.seek(pos);
                        raf.write(buf, 0, n);
                    }
                    seg.done += n;
                    written += n;
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
                // 连接提前断了：同一段重试，保留已写入的字节
                if (written == 0 && ++attempt > 4) {
                    throw new IOException("连接反复中断，已重试 " + attempt + " 次");
                }
                Thread.sleep(Math.min(4000, 400L * Math.max(1, attempt)));
            }
        }
    }

    HttpURLConnection open(String url, String range) throws IOException {
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
        c.setRequestProperty("User-Agent", settings.userAgent);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Encoding", "identity");
        if (!settings.referer.isEmpty()) c.setRequestProperty("Referer", settings.referer);
        if (range != null) c.setRequestProperty("Range", range);
        return c;
    }

    private Probe probe(Task t) throws IOException {
        Probe p = new Probe();
        HttpURLConnection c = open(t.url, "bytes=0-0");
        int code = c.getResponseCode();
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
            HttpURLConnection g = open(t.url, null);
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
        if (size <= 0 || !range || connections <= 1) {
            segs.add(new Task.Seg(0, Math.max(0, size - 1), 0));
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
        t.stage = "计算 SHA-256";
        fire();

        long size = Files.size(part);
        t.size = size;
        t.sha256 = Util.sha256(part, (done, total) -> {
            t.verifyDone = done;
            t.done = done;
        });
        t.md5 = Util.md5(part);
        t.stage = "检查文件结构";
        fire();

        if (settings.verifyApk && t.name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
            Integrity.Result r = Integrity.inspectApk(part);
            t.scheme = r.schemes;
            t.signer = r.certSha256;
            t.signerSubject = r.subject;
            if (!r.zipOk) {
                t.status = "error";
                t.error = "APK 压缩包结构损坏（" + r.detail + "），文件已隔离";
                quarantine(t, part);
                persist(true);
                return;
            }
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
        }
        t.verify = "ok";

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

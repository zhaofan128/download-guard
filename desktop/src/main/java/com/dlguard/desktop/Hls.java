package com.dlguard.desktop;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * HLS（.m3u8）点播下载。
 *
 * 电脑浏览器里很多视频站只给 m3u8，直接下载得到的是一个文本清单。
 * 这里做的是：解析主清单挑最高码率 → 解析分片清单 → 并发拉分片 → 按顺序拼成一个文件，
 * 遇到 AES-128 加密的分片用清单里的 KEY 解密。拼完再尝试用 ffmpeg 无转码封装成 mp4。
 */
public final class Hls {

    private Hls() {}

    private static final class Seg {
        String url;
        double duration;
        long seq;
        String keyUri;
        byte[] iv;
    }

    public static void run(Engine engine, Task t) throws Exception {
        t.stage = "读取播放列表";
        engine.fireNow();

        String playlistUrl = t.url;
        String text = fetchText(engine, playlistUrl);
        List<String> lines = splitLines(text);

        if (isMaster(lines)) {
            Variant best = pickBest(lines, playlistUrl);
            if (best == null) throw new IOException("主播放列表里没有可用清晰度");
            playlistUrl = best.url;
            t.stage = "选定清晰度 " + best.label;
            text = fetchText(engine, playlistUrl);
            lines = splitLines(text);
        }

        List<Seg> segs = parseMedia(lines, playlistUrl);
        if (segs.isEmpty()) throw new IOException("播放列表里没有分片");
        if (!text.contains("#EXT-X-ENDLIST")) {
            // 直播清单会不断追加分片，按当前窗口拼出来的文件既不是完整节目也不是可续传的稳定对象
            throw new IOException("这是直播流（播放列表没有 #EXT-X-ENDLIST），暂不支持离线下载");
        }

        String name = t.name;
        if (name == null || name.isEmpty() || name.equals("download")) {
            name = Util.nameFromUrl(playlistUrl);
        }
        name = name.replaceAll("(?i)\\.m3u8$", "");
        t.name = Util.sanitizeName(name + ".ts", "video.ts");

        Path workDir = engine.store.partsDir.resolve(t.id + "-hls");
        Files.createDirectories(workDir);
        Path merged = engine.store.partPath(t);
        Path metaFile = workDir.resolve("playlist.meta.json");

        int total = segs.size();
        String fingerprint = sha256Text(playlistUrl + "\n" + text);
        HlsMeta meta = HlsMeta.load(metaFile);
        boolean samePlaylist = meta != null && fingerprint.equals(meta.playlistSha256)
                && meta.count == total;
        if (!samePlaylist) {
            // 清单变了（或旧版本留下的工作目录没有元数据）：旧分片可能来自另一个版本，
            // 一律清掉重下，绝不把两版分片拼进同一个文件
            clearSegments(workDir);
            meta = new HlsMeta();
            meta.playlistUrl = playlistUrl;
            meta.playlistSha256 = fingerprint;
            meta.count = total;
            meta.sizes = new long[total];
            meta.save(metaFile);
        }

        // 残留的 .part 一律删除：它们是「写到一半被杀」的产物，不能当成完整分片
        try (java.util.stream.Stream<Path> stream = Files.list(workDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".seg.part"))
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }

        long[] sizes = new long[total];
        for (int i = 0; i < total; i++) {
            Path seg = workDir.resolve(i + ".seg");
            long expected = meta.sizes != null && i < meta.sizes.length ? meta.sizes[i] : 0;
            if (Files.isRegularFile(seg) && Files.size(seg) > 0
                    && (expected <= 0 || Files.size(seg) == expected)) {
                sizes[i] = Files.size(seg);
            } else {
                // 缺失、0 字节、大小对不上：都不可信
                try { Files.deleteIfExists(seg); } catch (IOException ignored) { }
                sizes[i] = 0;
            }
        }
        meta.sizes = sizes;
        final HlsMeta metaRef = meta;

        AtomicInteger doneCount = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        for (long s : sizes) {
            if (s > 0) {
                doneCount.incrementAndGet();
                bytes.addAndGet(s);
            }
        }
        t.done = bytes.get();
        t.stage = "HLS 分片 " + doneCount.get() + "/" + total;
        engine.fireNow();

        int threads = Math.min(6, Math.max(1, total));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < total; i++) {
                final int idx = i;
                final Seg seg = segs.get(i);
                if (sizes[idx] > 0) continue;
                futures.add(pool.submit(() -> {
                    try {
                        Path target = workDir.resolve(idx + ".seg");
                        downloadSeg(engine, t, seg, target);
                        long size = Files.size(target);
                        if (size <= 0) throw new IOException("分片 " + idx + " 是空的");
                        synchronized (metaRef) {
                            metaRef.sizes[idx] = size;
                            metaRef.save(metaFile);
                        }
                        int d = doneCount.incrementAndGet();
                        t.stage = "HLS 分片 " + d + "/" + total;
                        bytes.addAndGet(size);
                        t.done = bytes.get();
                        engine.fireNow();
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
                    Throwable c = e.getCause();
                    if (c instanceof RuntimeException && c.getCause() instanceof Engine.PauseSignal) {
                        throw (Engine.PauseSignal) c.getCause();
                    }
                    throw new IOException(DownloadErrors.humanize(c == null ? e : c), c);
                }
            }
        } finally {
            pool.shutdownNow();
            try { pool.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        }

        if (t.pauseRequested || t.cancelRequested) throw new Engine.PauseSignal();

        // 拼接前逐片复核：任何一片缺失/为空/大小与记录不符，都不能进入成品
        long expectedTotal = 0;
        for (int i = 0; i < total; i++) {
            Path p = workDir.resolve(i + ".seg");
            if (!Files.isRegularFile(p)) throw new IOException("HLS 分片 " + i + " 缺失，已停止拼接");
            long actual = Files.size(p);
            if (actual <= 0) throw new IOException("HLS 分片 " + i + " 是空的，已停止拼接");
            if (sizes[i] > 0 && actual != sizes[i]) {
                throw new IOException("HLS 分片 " + i + " 大小不符（记录 " + sizes[i]
                        + "，实际 " + actual + "），已停止拼接");
            }
            expectedTotal += actual;
        }

        t.stage = "拼接分片";
        engine.fireNow();
        try (OutputStream out = Files.newOutputStream(merged)) {
            for (int i = 0; i < total; i++) {
                Files.copy(workDir.resolve(i + ".seg"), out);
            }
        }
        if (Files.size(merged) != expectedTotal) {
            throw new IOException("HLS 拼接后大小不符：预期 " + expectedTotal + "，实际 " + Files.size(merged));
        }

        // 能无转码封装就封装，失败就保留 .ts（播放器基本都认）
        if (engine.settings.hlsRemux) {
            String ff = ffmpegPath();
            if (!ff.isEmpty()) {
                t.stage = "封装为 MP4";
                engine.fireNow();
                Path mp4 = merged.resolveSibling(t.id + ".mp4");
                boolean ok = remux(ff, merged, mp4);
                if (ok) {
                    Files.move(mp4, merged, StandardCopyOption.REPLACE_EXISTING);
                    t.name = t.name.replaceAll("(?i)\\.ts$", ".mp4");
                }
            }
        }

        for (int i = 0; i < total; i++) {
            try { Files.deleteIfExists(workDir.resolve(i + ".seg")); } catch (IOException ignored) { }
        }
        try { Files.deleteIfExists(workDir); } catch (IOException ignored) { }

        t.size = Files.size(merged);
        engine.finalizeFile(t, merged);
    }

    // ------------------------------------------------------------------ 解析

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (!line.isEmpty()) lines.add(line);
        }
        return lines;
    }

    private static boolean isMaster(List<String> lines) {
        for (String l : lines) if (l.startsWith("#EXT-X-STREAM-INF")) return true;
        return false;
    }

    private static final class Variant {
        String url;
        long bandwidth;
        String label;
    }

    private static Variant pickBest(List<String> lines, String base) {
        List<Variant> variants = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue;
            Variant v = new Variant();
            Map<String, String> attrs = parseAttrs(l.substring(l.indexOf(':') + 1));
            try {
                v.bandwidth = Long.parseLong(attrs.getOrDefault("BANDWIDTH", "0"));
            } catch (NumberFormatException ignored) { }
            String res = attrs.get("RESOLUTION");
            v.label = res != null ? res : (v.bandwidth / 1000 + " kbps");
            for (int j = i + 1; j < lines.size(); j++) {
                if (!lines.get(j).startsWith("#")) {
                    v.url = resolve(base, lines.get(j));
                    break;
                }
            }
            if (v.url != null) variants.add(v);
        }
        variants.sort((a, b) -> Long.compare(b.bandwidth, a.bandwidth));
        return variants.isEmpty() ? null : variants.get(0);
    }

    private static List<Seg> parseMedia(List<String> lines, String base) {
        List<Seg> segs = new ArrayList<>();
        String keyUri = null;
        byte[] iv = null;
        long seq = 0;
        for (String l : lines) {
            if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                try { seq = Long.parseLong(l.substring(l.indexOf(':') + 1).trim()); } catch (NumberFormatException ignored) { }
            } else if (l.startsWith("#EXT-X-KEY:")) {
                Map<String, String> a = parseAttrs(l.substring(l.indexOf(':') + 1));
                String method = a.getOrDefault("METHOD", "NONE");
                if ("NONE".equalsIgnoreCase(method)) {
                    keyUri = null;
                    iv = null;
                } else if ("AES-128".equalsIgnoreCase(method)) {
                    keyUri = a.containsKey("URI") ? resolve(base, a.get("URI")) : null;
                    iv = a.containsKey("IV") ? parseIv(a.get("IV")) : null;
                } else {
                    // SAMPLE-AES 需要解密器，明确报错而不是拼出坏文件
                    throw new IllegalArgumentException("暂不支持 " + method + " 加密的 HLS");
                }
            } else if (!l.startsWith("#") && !l.isEmpty()) {
                Seg s = new Seg();
                s.url = resolve(base, l);
                s.keyUri = keyUri;
                s.seq = seq++;
                s.iv = iv;
                segs.add(s);
            }
        }
        return segs;
    }

    private static Map<String, String> parseAttrs(String s) {
        Map<String, String> map = new LinkedHashMap<>();
        boolean inQuote = false;
        StringBuilder cur = new StringBuilder();
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') inQuote = !inQuote;
            if (c == ',' && !inQuote) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) parts.add(cur.toString());
        for (String p : parts) {
            int eq = p.indexOf('=');
            if (eq > 0) {
                String k = p.substring(0, eq).trim().toUpperCase(Locale.ROOT);
                String v = p.substring(eq + 1).trim();
                if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) v = v.substring(1, v.length() - 1);
                map.put(k, v);
            }
        }
        return map;
    }

    private static byte[] parseIv(String s) {
        String hex = s.trim();
        if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
        byte[] out = new byte[16];
        int byteLen = hex.length() / 2;
        for (int i = 0; i < byteLen && i < 16; i++) {
            out[16 - byteLen + i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String resolve(String base, String rel) {
        try {
            return new URL(new URL(base), rel).toString();
        } catch (Exception e) {
            return rel;
        }
    }

    // ------------------------------------------------------------------ 下载

    private static String fetchText(Engine engine, String url) throws IOException {
        HttpURLConnection c = engine.open(url, null);
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException(DownloadErrors.forHttp(code, url));
        }
        try (InputStream in = c.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] fetchBytes(Engine engine, String url) throws IOException {
        HttpURLConnection c = engine.open(url, null);
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException(DownloadErrors.forHttp(code, url));
        }
        try (InputStream in = c.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static final Map<String, byte[]> KEY_CACHE = new LinkedHashMap<String, byte[]>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > 8;
        }
    };

    /**
     * 下载单个分片：先写 {@code <index>.seg.part}，完整写完并关闭后再原子改名为
     * {@code <index>.seg}。进程在写文件中途被杀，留下的 .part 会在下次启动时删除，
     * 不会被当成完整分片拼接。
     */
    private static void downloadSeg(Engine engine, Task t, Seg seg, Path out) throws Exception {
        if (t.pauseRequested || t.cancelRequested) throw new Engine.PauseSignal();
        byte[] data = fetchBytes(engine, seg.url);
        if (seg.keyUri != null) {
            byte[] key;
            synchronized (KEY_CACHE) {
                key = KEY_CACHE.get(seg.keyUri);
            }
            if (key == null) {
                key = fetchBytes(engine, seg.keyUri);
                synchronized (KEY_CACHE) {
                    KEY_CACHE.put(seg.keyUri, key);
                }
            }
            if (key.length != 16) throw new IOException("HLS 密钥长度不是 16 字节");
            byte[] iv = seg.iv;
            if (iv == null) {
                iv = new byte[16];
                long s = seg.seq;
                for (int i = 15; i >= 8; i--) {
                    iv[i] = (byte) (s & 0xff);
                    s >>= 8;
                }
            }
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            data = cipher.doFinal(data);
        }
        if (data.length == 0) throw new IOException("HLS 分片内容为空");
        Path part = out.resolveSibling(out.getFileName() + ".part");
        try {
            Files.deleteIfExists(part);
            try (OutputStream os = Files.newOutputStream(part)) {
                os.write(data);
                os.flush();
            }
            try {
                Files.move(part, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(part, out, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try { Files.deleteIfExists(part); } catch (IOException ignored) { }
        }
    }

    /** 播放列表指纹与分片大小，用于判断上次的工作目录还能不能复用。 */
    static final class HlsMeta {
        String playlistUrl = "";
        String playlistSha256 = "";
        int count;
        long[] sizes = new long[0];

        static HlsMeta load(Path file) {
            try {
                Map<String, Object> m = Json.map(Json.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)));
                HlsMeta meta = new HlsMeta();
                meta.playlistUrl = Json.str(m, "playlistUrl", "");
                meta.playlistSha256 = Json.str(m, "playlistSha256", "");
                meta.count = Json.i(m, "count", 0);
                List<Object> list = Json.list(m.get("sizes"));
                meta.sizes = new long[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    meta.sizes[i] = Json.num(Json.map(list.get(i)), "size", 0);
                }
                return meta;
            } catch (Exception e) {
                return null;
            }
        }

        void save(Path file) {
            List<Object> list = new ArrayList<>();
            for (int i = 0; i < sizes.length; i++) {
                list.add(Json.obj("index", i, "size", sizes[i]));
            }
            try {
                Util.atomicWrite(file, Json.write(Json.obj(
                        "playlistUrl", playlistUrl,
                        "playlistSha256", playlistSha256,
                        "count", count,
                        "sizes", list
                )).getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) { }
        }
    }

    private static String sha256Text(String s) {
        try {
            return Util.hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static void clearSegments(Path workDir) {
        try (java.util.stream.Stream<Path> stream = Files.list(workDir)) {
            stream.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".seg") || n.endsWith(".seg.part");
            }).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    // ------------------------------------------------------------------ ffmpeg

    static String ffmpegPath() {
        String path = System.getenv("PATH");
        if (path != null) {
            String exe = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                    ? "ffmpeg.exe" : "ffmpeg";
            for (String dir : path.split(java.io.File.pathSeparator)) {
                Path p = java.nio.file.Paths.get(dir, exe);
                if (Files.isExecutable(p)) return p.toString();
            }
        }
        for (String p : new String[]{"/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/opt/homebrew/bin/ffmpeg"}) {
            if (Files.isExecutable(java.nio.file.Paths.get(p))) return p;
        }
        return "";
    }

    private static boolean remux(String ffmpeg, Path in, Path out) {
        try {
            ProcessBuilder pb = new ProcessBuilder(ffmpeg, "-y", "-loglevel", "error",
                    "-i", in.toString(), "-c", "copy", "-bsf:a", "aac_adtstoasc", out.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (InputStream is = p.getInputStream()) {
                byte[] buf = new byte[4096];
                while (is.read(buf) > 0) { /* 排空输出，避免管道写满卡死 */ }
            }
            return p.waitFor() == 0 && Files.exists(out) && Files.size(out) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unused")
    private static String doc(String s) { return s; }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static ByteArrayInputStream stream(byte[] b) {
        return new ByteArrayInputStream(b);
    }

    static BufferedReader reader(InputStream in) {
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
}

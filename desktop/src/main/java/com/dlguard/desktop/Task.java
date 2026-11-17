package com.dlguard.desktop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 桌面版任务模型。字段直接对应 Web UI，序列化后就是 tasks.json 的结构。 */
public final class Task {

    public String id = UUID.randomUUID().toString();
    public String url = "";
    public String name = "";
    public String dir = "";
    public String kind = "HTTP";          // HTTP | HLS | BT
    public String status = "queued";      // queued|downloading|paused|verifying|completed|error|cancelled
    public String error = "";
    public String mime = "";
    public long size = 0;
    public long done = 0;
    public long createdAt = System.currentTimeMillis();
    public long finishedAt = 0;
    public int connections = 8;
    public boolean rangeSupported = true;
    public String etag = "";
    public String finalPath = "";
    public String sha256 = "";
    public String md5 = "";
    public String expectedSha256 = "";
    public String verify = "";             // "" | ok | mismatch
    public String signer = "";             // APK 签名证书 SHA-256
    public String scheme = "";             // v1/v2/v3
    public String signerSubject = "";
    public String signature = "";          // verified | failed | unsupported | none
    public String signatureDetail = "";
    public String warning = "";            // 未验证 / 已跳过等需要显式提示的信息
    public String gid = "";                // BT 任务在 aria2 里的 id
    public boolean resumeOnStart = false;  // 上次退出时是否正在下载（用于开机自动续传）
    public String referer = "";            // 来源页（网盘直链必须带，否则 403）
    public String userAgent = "";          // 空则用全局设置
    public String source = "manual";       // manual | browser | accessibility | download-manager | relay
    public transient String cookie = "";   // 只存内存：Cookie 落盘等于把登录态写进 tasks.json
    public transient boolean nameAuto = false;  // 名字是从 URL 猜的，允许被 Content-Disposition 覆盖
    public List<Seg> segs = new ArrayList<>();

    // 运行期状态，不落盘
    public transient volatile long speed = 0;
    public transient volatile long eta = 0;
    public transient volatile boolean pauseRequested = false;
    public transient volatile boolean cancelRequested = false;
    public transient String stage = "";
    public transient long verifyDone = 0;
    public transient byte[] torrentData = null;   // 上传的 .torrent 只存在内存里，不落盘

    public static final class Seg {
        public long start;
        public long end;   // 含端点
        public long done;

        public Seg() {}

        public Seg(long start, long end, long done) {
            this.start = start;
            this.end = end;
            this.done = done;
        }

        public long length() { return end - start + 1; }
        public long remaining() { return Math.max(0, length() - done); }
    }

    public long totalDone() {
        if (kind.equals("BT") || segs.isEmpty()) return done;
        long sum = 0;
        for (Seg s : segs) sum += Math.min(s.done, s.length());
        return sum;
    }

    public boolean isActive() {
        return "downloading".equals(status) || "verifying".equals(status) || "queued".equals(status);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("url", url);
        m.put("name", name);
        m.put("dir", dir);
        m.put("kind", kind);
        m.put("status", status);
        m.put("error", error);
        m.put("mime", mime);
        m.put("size", size);
        m.put("done", totalDone());
        m.put("createdAt", createdAt);
        m.put("finishedAt", finishedAt);
        m.put("connections", connections);
        m.put("rangeSupported", rangeSupported);
        m.put("finalPath", finalPath);
        m.put("sha256", sha256);
        m.put("md5", md5);
        m.put("expectedSha256", expectedSha256);
        m.put("verify", verify);
        m.put("signer", signer);
        m.put("scheme", scheme);
        m.put("signerSubject", signerSubject);
        m.put("signature", signature);
        m.put("signatureDetail", signatureDetail);
        m.put("warning", warning);
        m.put("speed", speed);
        m.put("eta", eta);
        m.put("stage", stage);
        m.put("gid", gid);
        m.put("resumeOnStart", resumeOnStart
                || "downloading".equals(status) || "queued".equals(status) || "verifying".equals(status));
        m.put("referer", referer);
        m.put("userAgent", userAgent);
        m.put("source", source);
        // cookie 故意不序列化：它等价于登录态，写进 tasks.json 就是明文落盘
        m.put("hasCookie", cookie != null && !cookie.isEmpty());
        m.put("segments", segsToMaps());
        return m;
    }

    private List<Object> segsToMaps() {
        List<Object> list = new ArrayList<>();
        for (Seg s : segs) {
            list.add(Json.obj("start", s.start, "end", s.end, "done", s.done));
        }
        return list;
    }

    public static Task fromMap(Map<String, Object> m) {
        Task t = new Task();
        t.id = Json.str(m, "id", t.id);
        t.url = Json.str(m, "url", "");
        t.name = Json.str(m, "name", "");
        t.dir = Json.str(m, "dir", "");
        t.kind = Json.str(m, "kind", "HTTP");
        t.status = Json.str(m, "status", "queued");
        t.error = Json.str(m, "error", "");
        t.mime = Json.str(m, "mime", "");
        t.size = Json.num(m, "size", 0);
        t.done = Json.num(m, "done", 0);
        t.createdAt = Json.num(m, "createdAt", System.currentTimeMillis());
        t.finishedAt = Json.num(m, "finishedAt", 0);
        t.connections = Json.i(m, "connections", 8);
        t.rangeSupported = Json.bool(m, "rangeSupported", true);
        t.finalPath = Json.str(m, "finalPath", "");
        t.sha256 = Json.str(m, "sha256", "");
        t.md5 = Json.str(m, "md5", "");
        t.expectedSha256 = Json.str(m, "expectedSha256", "");
        t.verify = Json.str(m, "verify", "");
        t.signer = Json.str(m, "signer", "");
        t.scheme = Json.str(m, "scheme", "");
        t.signerSubject = Json.str(m, "signerSubject", "");
        t.signature = Json.str(m, "signature", "");
        t.signatureDetail = Json.str(m, "signatureDetail", "");
        t.warning = Json.str(m, "warning", "");
        t.gid = Json.str(m, "gid", "");
        t.resumeOnStart = Json.bool(m, "resumeOnStart", false);
        t.referer = Json.str(m, "referer", "");
        t.userAgent = Json.str(m, "userAgent", "");
        t.source = Json.str(m, "source", "manual");
        for (Object o : Json.list(m.get("segments"))) {
            Map<String, Object> sm = Json.map(o);
            t.segs.add(new Seg(Json.num(sm, "start", 0), Json.num(sm, "end", 0), Json.num(sm, "done", 0)));
        }
        // 重启后不能把上次的"下载中"当成还在下，统一归到暂停，由用户或自动续传拉起
        if ("downloading".equals(t.status) || "verifying".equals(t.status) || "queued".equals(t.status)) {
            t.status = "paused";
        }
        return t;
    }
}

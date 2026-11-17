package com.dlguard.desktop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 无框架自检：`--selftest` 直接跑，退出码非 0 即失败。
 *
 * 桌面版刻意不引 JUnit：整个工程零依赖，测试也用 main 跑，
 * 这样 CI 里只需要一个 JDK，不需要联网拉 Maven 仓库。
 */
public final class SelfTest {

    private SelfTest() {}

    private static int passed;
    private static int failed;

    public static int run() {
        passed = 0;
        failed = 0;

        jsonRoundTrip();
        jsonEscapes();
        segmentPlanCoverage();
        segmentPlanNoRange();
        segmentPlanSmallFile();
        fileNameSanitizing();
        contentDisposition();
        errorMessages();
        taskRoundTrip();
        requestGuard();
        retryBudget();
        zipInspection();
        apkSignatureIfPresent();
        resumeSemantics();

        System.out.println();
        System.out.println("自检结果：" + passed + " 通过 / " + failed + " 失败");
        return failed == 0 ? 0 : 1;
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            System.out.println("  ✗ " + name + "  " + (detail == null ? "" : detail));
        }
    }

    private static void jsonRoundTrip() {
        String src = "{\"a\":1,\"b\":[true,null,\"x\"],\"c\":{\"d\":-2.5}}";
        Object parsed = Json.parse(src);
        String out = Json.write(parsed);
        check("JSON 往返", src.equals(out), out);
    }

    private static void jsonEscapes() {
        String text = "引号\" 反斜杠\\ 换行\n 中文🚀";
        String json = Json.write(Json.obj("k", text));
        Map<String, Object> back = Json.map(Json.parse(json));
        check("JSON 转义与 Unicode", text.equals(back.get("k")), String.valueOf(back.get("k")));
    }

    private static void segmentPlanCoverage() {
        long size = 100L * 1024 * 1024 + 123;
        List<Task.Seg> segs = Engine.planSegments(size, 8, true);
        long sum = 0;
        boolean contiguous = true;
        long expect = 0;
        for (Task.Seg s : segs) {
            if (s.start != expect) contiguous = false;
            sum += s.length();
            expect = s.end + 1;
        }
        check("分段不重叠且无空洞", contiguous, "段起点不连续");
        check("分段字节数守恒", sum == size, sum + " != " + size);
        check("分段数符合连接数", segs.size() == 8, "segs=" + segs.size());
    }

    private static void segmentPlanNoRange() {
        List<Task.Seg> segs = Engine.planSegments(1024, 8, false);
        check("不支持 Range 时单段", segs.size() == 1 && segs.get(0).end == 1023, "segs=" + segs.size());
    }

    private static void segmentPlanSmallFile() {
        List<Task.Seg> segs = Engine.planSegments(1024, 8, true);
        check("小文件不硬拆多段", segs.size() == 1, "segs=" + segs.size());
    }

    private static void fileNameSanitizing() {
        check("非法字符替换",
                "a_b_c_.zip".equals(Util.sanitizeName("a/b:c*.zip", "x")),
                Util.sanitizeName("a/b:c*.zip", "x"));
        check("Windows 保留名", "_CON".equals(Util.sanitizeName("CON", "x")),
                Util.sanitizeName("CON", "x"));
        check("去掉结尾的点", "name".equals(Util.sanitizeName("name...", "x")),
                Util.sanitizeName("name...", "x"));
        check("空名回退", "fallback".equals(Util.sanitizeName("   ", "fallback")),
                Util.sanitizeName("   ", "fallback"));
    }

    private static void contentDisposition() {
        String h = "attachment; filename*=UTF-8''%E4%B8%AD%E6%96%87.apk";
        check("Content-Disposition UTF-8", "中文.apk".equals(Util.parseContentDisposition(h)),
                String.valueOf(Util.parseContentDisposition(h)));
    }

    private static void errorMessages() {
        check("403 提示 Referer", DownloadErrors.forHttp(403, "https://a/b").contains("Referer"), "");
        check("404 说明地址不存在", DownloadErrors.forHttp(404, "https://a/b").contains("404"), "");
        check("429 建议降连接", DownloadErrors.forHttp(429, "https://a/b").contains("连接"), "");
    }

    private static void taskRoundTrip() {
        Task t = new Task();
        t.name = "测试.apk";
        t.status = "downloading";
        t.segs.add(new Task.Seg(0, 99, 40));
        Map<String, Object> m = t.toMap();
        Task back = Task.fromMap(m);
        check("任务序列化", "测试.apk".equals(back.name) && back.segs.size() == 1, "");
        check("下载中重启后归为暂停", "paused".equals(back.status), back.status);
        check("下载中标记为可续传", back.resumeOnStart, "resumeOnStart=false");
    }

    private static void zipInspection() throws RuntimeException {
        try {
            Path tmp = Files.createTempFile("dg-test", ".zip");
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
                out.putNextEntry(new ZipEntry("hello.txt"));
                out.write("download guard".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            Integrity.Result r = Integrity.inspectApk(tmp);
            check("ZIP 结构检查", r.zipOk, r.detail);
            Files.deleteIfExists(tmp);
        } catch (Exception e) {
            check("ZIP 结构检查", false, e.getMessage());
        }
    }

    private static void apkSignatureIfPresent() {
        Path apk = java.nio.file.Paths.get(System.getProperty("dg.apk", ""));
        if (!Files.isRegularFile(apk)) {
            System.out.println("  · 跳过 APK 签名用例（未提供 -Ddg.apk=/path/to.apk）");
            return;
        }
        Integrity.Result r = Integrity.inspectApk(apk);
        check("APK 签名方案识别", r.schemes.contains("v2") || r.schemes.contains("v3"), r.schemes);
        check("APK 证书指纹提取", r.certSha256.length() == 64, r.certSha256);
    }

    private static void resumeSemantics() {
        Task manualPause = new Task();
        manualPause.status = "paused";
        manualPause.resumeOnStart = false;
        check("手动暂停不自动续传", !Json.bool(manualPause.toMap(), "resumeOnStart", true), "");
    }

    /**
     * 本地服务的来源判定。
     *
     * 这些用例对应真实攻击面：任意网页都能向 127.0.0.1 发简单请求，
     * 不做 Host/Origin 校验就等于把「往任意目录下载任意文件」暴露给浏览器。
     */
    private static void requestGuard() {
        int port = 8765;
        check("本机 Host 放行",
                Guard.allowed("127.0.0.1:8765", null, null, port, false, null), "");
        check("localhost 放行",
                Guard.allowed("localhost:8765", null, null, port, false, null), "");
        check("IPv6 回环放行",
                Guard.allowed("[::1]:8765", null, null, port, false, null), "");
        check("陌生域名拒绝（DNS 重绑定）",
                !Guard.allowed("evil.example:8765", null, null, port, false, null), "");
        check("端口不符拒绝",
                !Guard.allowed("127.0.0.1:9999", null, null, port, false, null), "");
        check("跨站 Origin 拒绝",
                !Guard.allowed("127.0.0.1:8765", "http://evil.example", null, port, false, null), "");
        check("同源 Origin 放行",
                Guard.allowed("127.0.0.1:8765", "http://127.0.0.1:8765", null, port, false, null), "");
        check("Origin: null 拒绝",
                !Guard.allowed("127.0.0.1:8765", "null", null, port, false, null), "");
        check("Sec-Fetch-Site: cross-site 拒绝",
                !Guard.allowed("127.0.0.1:8765", null, "cross-site", port, false, null), "");
        check("显式放行的域名可用",
                Guard.allowed("dg.lan:8765", null, null, port, false, java.util.Collections.singletonList("dg.lan")), "");
        check("--lan 下要求 Host",
                !Guard.allowed(null, null, null, port, true, null), "");
        check("HTTP/1.0 无 Host 本机放行",
                Guard.allowed(null, null, null, port, false, null), "");
    }

    /** 分段重试必须有上限，否则「每次给 1 字节再断开」的服务器会让 worker 永远转。 */
    private static void retryBudget() {
        check("正常重连不触发预算",
                Engine.retryBudgetError(1, 1000, 0, 1024) == null, "");
        check("重连次数到上限放行最后一次",
                Engine.retryBudgetError(Engine.MAX_SEGMENT_RECONNECTS, 0, 0, 1024) == null, "");
        check("重连超限报错",
                Engine.retryBudgetError(Engine.MAX_SEGMENT_RECONNECTS + 1, 0, 0, 1024) != null, "");
        check("长时间无数据报错",
                Engine.retryBudgetError(1, Engine.STALL_LIMIT_MS + 1, 0, 1024) != null, "");
    }
}

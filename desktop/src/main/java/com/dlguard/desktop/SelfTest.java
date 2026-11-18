package com.dlguard.desktop;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
    private static int skipped;

    public static int run() {
        passed = 0;
        failed = 0;
        skipped = 0;

        jsonRoundTrip();
        jsonEscapes();
        segmentPlanCoverage();
        segmentPlanNoRange();
        segmentPlanSmallFile();
        segmentPlanUnknownSize();
        fileNameSanitizing();
        contentDisposition();
        errorMessages();
        taskRoundTrip();
        requestGuard();
        retryBudget();
        rangeStartParsing();
        directoryGuard();
        zipInspection();
        apkSignatureIfPresent();
        resumeSemantics();

        System.out.println();
        System.out.println("自检结果：" + passed + " 通过 / " + failed + " 失败 / " + skipped + " 跳过");
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

    private static void segmentPlanUnknownSize() {
        List<Task.Seg> segs = Engine.planSegments(0, 8, true);
        check("未知长度不生成 1 字节占位段",
                segs.size() == 1 && segs.get(0).length() == 0, "segs=" + segs.size()
                        + " len=" + (segs.isEmpty() ? -1 : segs.get(0).length()));
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
        // forCode：有明确的 4xx/5xx 就优先按状态码解释，即使最后一击是 EOF 类异常
        // （重试/短连接下根因在最后一次收到的响应码里，这样文案跨重试稳定）
        check("forCode 403 覆盖 EOF 异常",
                DownloadErrors.forCode(403, new IOException("Premature EOF"), "https://a/b")
                        .equals(DownloadErrors.forHttp(403, "https://a/b")), "");
        check("forCode 无状态码回退 humanize",
                DownloadErrors.forCode(0, new IOException("Connection reset"), "https://a/b")
                        .equals("连接中断，下载不完整，请重试"), "");
        check("forCode 2xx 不遮蔽传输中断",
                DownloadErrors.forCode(200, new IOException("Connection reset"), "https://a/b")
                        .equals("连接中断，下载不完整，请重试"), "");
        // 传输中断必须归一成同一句中文：JDK 17/21/25 对「提前关闭」的底层文案不一样
        check("JDK25 Premature EOF 是中断", DownloadErrors.isEofLike(new IOException("Premature EOF")), "");
        check("JDK17 unexpected end 是中断", DownloadErrors.isEofLike(new IOException("unexpected end of file")), "");
        check("Connection reset 是中断", DownloadErrors.isEofLike(new IOException("Connection reset")), "");
        check("EOFException 是中断", DownloadErrors.isEofLike(new java.io.EOFException()), "");
        check("超时不是中断", !DownloadErrors.isEofLike(new java.net.SocketTimeoutException("timed out")), "");
        check("Premature EOF 转成中文",
                DownloadErrors.humanize(new IOException("Premature EOF")).equals("连接中断，下载不完整，请重试"),
                DownloadErrors.humanize(new IOException("Premature EOF")));
        check("Connection reset 转成中文",
                DownloadErrors.humanize(new IOException("Connection reset")).equals("连接中断，下载不完整，请重试"),
                DownloadErrors.humanize(new IOException("Connection reset")));
        // 分段 worker 的异常经过 Future/RuntimeException 再包一层时，消息会带类名前缀
        check("带类名前缀的异常剥壳",
                DownloadErrors.humanize(new IOException("java.io.IOException: 服务器不支持断点续传，请重试"))
                        .equals("服务器不支持断点续传，请重试"),
                DownloadErrors.humanize(new IOException("java.io.IOException: 服务器不支持断点续传，请重试")));
        check("带前缀的 Premature EOF 仍映射",
                DownloadErrors.humanize(new IOException("java.io.IOException: Premature EOF"))
                        .equals("连接中断，下载不完整，请重试"),
                DownloadErrors.humanize(new IOException("java.io.IOException: Premature EOF")));
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
        if (!Files.isRegularFile(apk)) apk = java.nio.file.Paths.get("desktop/test/fixtures/signed.apk");
        if (!Files.isRegularFile(apk)) apk = java.nio.file.Paths.get("../desktop/test/fixtures/signed.apk");
        if (!Files.isRegularFile(apk)) {
            skipped++;
            System.out.println("  · 跳过 APK 验签用例（未提供 -Ddg.apk，也找不到内置样本）");
            return;
        }
        Integrity.Result r = Integrity.inspectApk(apk);
        check("APK v2 验签通过", r.verified(), r.schemes + " / " + r.signatureDetail);
        check("APK 证书指纹提取", r.certSha256.length() == 64, r.certSha256);
        check("APK 签名方案包含 v2", r.schemes.contains("v2"), r.schemes);

        try {
            byte[] src = Files.readAllBytes(apk);
            int start = signingBlockStart(src);
            check("能定位 APK 签名块", start > 0, "start=" + start);
            if (start > 0) {
                // 篡改签名块里的一个字节：ZIP 结构仍然完好，但签名必须验不过
                byte[] bad = src.clone();
                int at = Math.min(bad.length - 1, start + 8 + 8 + 4 + 40);
                bad[at] ^= 0x5a;
                Path tmp = Files.createTempFile("dg-bad", ".apk");
                Files.write(tmp, bad);
                Integrity.Result br = Integrity.inspectApk(tmp);
                check("篡改签名块不能显示通过", !br.verified(), br.signature + " " + br.signatureDetail);
                check("篡改后判定为 failed", "failed".equals(br.signature), br.signature);
                Files.deleteIfExists(tmp);

                // 删除签名块（等价于重新打包没签名）：只能 unsupported，不能 ok
                Path stripped = Files.createTempFile("dg-unsigned", ".apk");
                Files.write(stripped, stripSigningBlock(src, start));
                Integrity.Result sr = Integrity.inspectApk(stripped);
                check("删除签名块不能显示通过", !sr.verified(), sr.signature);
                check("删除签名块判定为 unsupported", "unsupported".equals(sr.signature), sr.signature);
                Files.deleteIfExists(stripped);
            }
        } catch (Exception e) {
            check("APK 篡改/去签名用例", false, e.getMessage());
        }
    }

    /** 从 APK 字节里定位签名块起点；找不到返回 -1。 */
    private static int signingBlockStart(byte[] b) {
        int eocd = -1;
        for (int i = b.length - 22; i >= Math.max(0, b.length - 128 * 1024); i--) {
            if (b[i] == 0x50 && b[i + 1] == 0x4b && b[i + 2] == 0x05 && b[i + 3] == 0x06) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) return -1;
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        long cd = bb.getInt(eocd + 16) & 0xffffffffL;
        if (cd < 32 || cd > b.length) return -1;
        long size = bb.getLong((int) cd - 24);
        long start = cd - size - 8;
        if (start < 0) return -1;
        String magic = new String(b, (int) cd - 16, 16, StandardCharsets.US_ASCII);
        return "APK Sig Block 42".equals(magic) ? (int) start : -1;
    }

    /** 去掉签名块并修正 EOCD 的 central directory offset。 */
    private static byte[] stripSigningBlock(byte[] b, int start) {
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = -1;
        for (int i = b.length - 22; i >= 0; i--) {
            if (b[i] == 0x50 && b[i + 1] == 0x4b && b[i + 2] == 0x05 && b[i + 3] == 0x06) {
                eocd = i;
                break;
            }
        }
        int cd = bb.getInt(eocd + 16);
        byte[] out = new byte[start + (b.length - cd)];
        System.arraycopy(b, 0, out, 0, start);
        System.arraycopy(b, cd, out, start, b.length - cd);
        int newEocd = eocd - (cd - start);
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(newEocd + 16, start);
        return out;
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

    private static void rangeStartParsing() {
        check("解析 Content-Range 起点", Engine.parseRangeStart("bytes 1048576-2097151/4194304") == 1048576, "");
        check("拒绝缺失的 Content-Range", Engine.parseRangeStart(null) == -1, "");
        check("拒绝非 bytes 单位", Engine.parseRangeStart("items 0-10/20") == -1, "");
    }

    /** 保存目录白名单：系统目录、..、白名单外、软链逃逸都要拒绝。 */
    private static void directoryGuard() {
        try {
            Path tmp = Files.createTempDirectory("dg-dirguard");
            Path root = Files.createDirectories(tmp.resolve("ok"));
            Path outside = Files.createDirectories(tmp.resolve("outside"));
            List<Path> roots = java.util.Collections.singletonList(DirGuard.canonical(root));

            Path inside = DirGuard.resolveWritable(root.resolve("sub").toString(), roots, roots.get(0));
            check("白名单内目录放行", inside.startsWith(roots.get(0)), inside.toString());
            check("空目录回退默认目录",
                    DirGuard.resolveWritable("", roots, roots.get(0)).equals(roots.get(0)), "");
            check("白名单外目录拒绝",
                    !accepted(() -> DirGuard.resolveWritable(outside.toString(), roots, roots.get(0))), "");
            check("系统目录拒绝",
                    !accepted(() -> DirGuard.resolveWritable("/etc", roots, roots.get(0))), "");
            check(".. 逃逸拒绝",
                    !accepted(() -> DirGuard.resolveWritable(root + "/../outside", roots, roots.get(0))), "");

            try {
                Path link = root.resolve("link");
                if (!Files.exists(link)) Files.createSymbolicLink(link, outside);
                check("符号链接逃逸拒绝",
                        !accepted(() -> DirGuard.resolveWritable(link.toString(), roots, roots.get(0))), "");
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
                // Windows 无权限建软链时不影响其他用例；CI 的 Linux 会真实执行
                System.out.println("  · 当前文件系统不支持创建符号链接，跳过软链用例");
            }
            deleteTree(tmp);
        } catch (Exception e) {
            check("目录白名单用例", false, e.getMessage());
        }
    }

    private interface Call {
        void run() throws Exception;
    }

    private static boolean accepted(Call c) {
        try {
            c.run();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteTree(Path p) {
        try (java.util.stream.Stream<Path> s = Files.walk(p)) {
            s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(x -> {
                try { Files.deleteIfExists(x); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
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

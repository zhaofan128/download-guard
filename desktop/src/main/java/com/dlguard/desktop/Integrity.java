package com.dlguard.desktop;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * APK / ZIP 结构体检 + APK Signature Scheme v2 验签。
 *
 * <p>电脑上最容易被忽略的一步：文件下完了，但可能是运营商劫持页、半截文件、
 * 或者被人重新打包过的安装包。这里做三件事：
 * <ol>
 *   <li>遍历 ZIP 条目读取全部内容，CRC 不对直接判定损坏；</li>
 *   <li>实现 v2 验签：重算 1MB 分块内容摘要、用证书公钥验签、核对证书与 signer 公钥；</li>
 *   <li>提取签名证书 SHA-256 指纹，用户可以和官方公布的值对照。</li>
 * </ol>
 *
 * <p>结果严格区分 {@code verified} / {@code failed} / {@code unsupported}：
 * 只做了「识别签名方案」而没有验签时，绝不会写成通过。
 */
public final class Integrity {

    private Integrity() {}

    /** 单个 APK 允许体检的最大体积；再大直接 unsupported，避免把内存/CPU 拖死。 */
    static final long MAX_APK_BYTES = 700L * 1024 * 1024;
    /** ZIP 解压总量上限，防 ZIP bomb。 */
    static final long MAX_UNCOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024;
    /** 单条目解压上限。 */
    static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;
    /** 体检总耗时上限。 */
    static final long MAX_INSPECT_MS = 120_000L;

    public static final class Result {
        public boolean zipOk = true;
        /** 识别到的签名方案，例如 "v2" / "v1+v2" / "v3"。 */
        public String schemes = "";
        /** verified | failed | unsupported | none */
        public String signature = "none";
        public String signatureDetail = "";
        public String certSha256 = "";
        public String subject = "";
        public String detail = "";
        /** 是否因为体积/解压总量等原因跳过了深度体检。 */
        public boolean skipped = false;

        public boolean verified() { return "verified".equals(signature); }
    }

    public static Result inspectApk(Path file) {
        Result r = new Result();
        long fileSize;
        try {
            fileSize = Files.size(file);
        } catch (Exception e) {
            r.zipOk = false;
            r.detail = "读不到文件大小：" + e.getMessage();
            return r;
        }
        if (fileSize > MAX_APK_BYTES) {
            r.skipped = true;
            r.signature = "unsupported";
            r.signatureDetail = "文件 " + Util.fmtSize(fileSize) + " 超过 700 MB 体检上限，已跳过深度校验";
            r.detail = r.signatureDetail;
            return r;
        }

        List<String> schemes = new ArrayList<>();
        long started = System.currentTimeMillis();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            // 1) 全量 CRC 校验（带解压总量与单条目上限）
            Enumeration<? extends ZipEntry> en = zip.entries();
            long total = 0;
            byte[] buf = new byte[128 * 1024];
            while (en.hasMoreElements()) {
                if (System.currentTimeMillis() - started > MAX_INSPECT_MS) {
                    r.skipped = true;
                    r.signature = "unsupported";
                    r.signatureDetail = "APK 体检超过 " + (MAX_INSPECT_MS / 1000) + " 秒，已跳过";
                    r.detail = r.signatureDetail;
                    return r;
                }
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                if (e.getSize() > MAX_ENTRY_BYTES) {
                    r.skipped = true;
                    r.signature = "unsupported";
                    r.signatureDetail = "条目 " + e.getName() + " 解压后超过 512 MB，已跳过深度校验";
                    r.detail = r.signatureDetail;
                    return r;
                }
                long entryRead = 0;
                try (InputStream in = zip.getInputStream(e)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        entryRead += n;
                        total += n;
                        if (entryRead > MAX_ENTRY_BYTES || total > MAX_UNCOMPRESSED_BYTES) {
                            r.skipped = true;
                            r.signature = "unsupported";
                            r.signatureDetail = "ZIP 解压总量超过 2 GB 或单条目超过 512 MB，已跳过深度校验";
                            r.detail = r.signatureDetail;
                            return r;
                        }
                    }
                }
            }
            // 2) v1：META-INF 下的签名文件
            en = zip.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName().toUpperCase(java.util.Locale.ROOT);
                if (n.startsWith("META-INF/") && (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))) {
                    if (!schemes.contains("v1")) schemes.add("v1");
                }
            }
        } catch (Exception e) {
            r.zipOk = false;
            r.detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return r;
        }

        try {
            SigningBlock block = readSigningBlock(file);
            if (block != null) {
                if (block.v3 != null) schemes.add(0, "v3");
                if (block.v2 != null) {
                    if (!schemes.contains("v2")) schemes.add(schemes.isEmpty() ? 0 : 1, "v2");
                    verifyV2(file, block, r);
                }
            } else if (!schemes.isEmpty()) {
                r.signature = "unsupported";
                r.signatureDetail = "只有 v1 签名（JAR 签名），当前版本不做 v1 验签";
            }
        } catch (Exception e) {
            r.signature = "failed";
            r.signatureDetail = "签名块解析失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }

        if (r.signatureDetail.isEmpty()) {
            if (blockMissingV2(r, schemes)) {
                if (schemes.contains("v3") && !schemes.contains("v2")) {
                    r.signature = "unsupported";
                    r.signatureDetail = "只有 v3 签名，当前版本尚未实现 v3 验签";
                } else if (schemes.contains("v1") && !schemes.contains("v2")) {
                    r.signature = "unsupported";
                    r.signatureDetail = "只有 v1 签名，当前版本不做 v1 验签";
                } else {
                    r.signature = "none";
                    r.signatureDetail = "没有找到 APK 签名块";
                }
            }
        }
        r.schemes = String.join("+", schemes);
        if (r.detail.isEmpty()) r.detail = r.signatureDetail;
        return r;
    }

    private static boolean blockMissingV2(Result r, List<String> schemes) {
        return !"verified".equals(r.signature) && !"failed".equals(r.signature);
    }

    // ------------------------------------------------------------------ v2 验签

    /**
     * 校验 v2 签名块。
     *
     * <p>摘要算法（与 AOSP apksig 一致）：
     * 每个 1MB 块算 {@code SHA-256(0xa5 || uint32le(块长) || 块数据)}；
     * 顶层算 {@code SHA-256(0x5a || uint32le(块数) || 所有块摘要)}。
     * EOCD 参与计算前，把 central directory offset 字段替换为签名块起点。
     */
    private static void verifyV2(Path file, SigningBlock block, Result r) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            Cursor root = new Cursor(block.v2);
            Cursor signers = root.slice();
            int signerIndex = 0;
            int verifiedSigners = 0;
            String firstCertSha = "";
            String firstSubject = "";
            String lastError = "";

            while (signers.hasRemaining()) {
                Cursor signer = signers.slice();
                signerIndex++;
                Slice signedSlice = signer.sliceWithPrefix();
                Cursor signed = new Cursor(block.v2, signedSlice.contentStart, signedSlice.end);
                Cursor digests = signed.slice();
                Cursor certs = signed.slice();
                signed.slice();   // additional attributes，不参与校验
                Cursor signatures = signer.slice();
                byte[] publicKeyBytes = signer.slice().rest();

                // 注意：v2 的 digests / signatures 都是「序列套长度前缀条目」，
                // 条目内部才是 algId + length-prefixed value。少读一层会把长度当算法 ID。
                List<Integer> sigAlgs = new ArrayList<>();
                List<byte[]> sigBytes = new ArrayList<>();
                while (signatures.hasRemaining()) {
                    Cursor item = signatures.slice();
                    sigAlgs.add(item.readInt());
                    sigBytes.add(item.slice().rest());
                }
                Map<Integer, byte[]> digestByAlg = new LinkedHashMap<>();
                while (digests.hasRemaining()) {
                    Cursor item = digests.slice();
                    int alg = item.readInt();
                    digestByAlg.put(alg, item.slice().rest());
                }

                if (!certs.hasRemaining()) {
                    lastError = "第 " + signerIndex + " 个签名者没有证书";
                    continue;
                }
                byte[] der = certs.slice().rest();
                X509Certificate cert;
                try {
                    cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                            .generateCertificate(new ByteArrayInputStream(der));
                } catch (Exception e) {
                    lastError = "第 " + signerIndex + " 个签名者的证书解析失败";
                    continue;
                }
                if (firstCertSha.isEmpty()) {
                    firstCertSha = Util.hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
                    firstSubject = cert.getSubjectX500Principal().getName();
                    r.certSha256 = firstCertSha;
                    r.subject = firstSubject;
                    r.signatureDetail = "v2 签名证书有效期至 " + cert.getNotAfter();
                }

                PublicKey certKey = cert.getPublicKey();
                if (!Arrays.equals(certKey.getEncoded(), publicKeyBytes)) {
                    lastError = "第 " + signerIndex + " 个签名者的证书公钥与签名块公钥不一致";
                    continue;
                }

                boolean signerOk = false;
                for (int i = 0; i < sigAlgs.size(); i++) {
                    int alg = sigAlgs.get(i);
                    byte[] expectedDigest = digestByAlg.get(alg);
                    if (expectedDigest == null) continue;
                    String jca = jcaSignature(alg);
                    String digestAlg = digestAlgorithm(alg);
                    if (jca == null || digestAlg == null) continue;

                    byte[] actual = contentDigest(raf, block, digestAlg);
                    if (!MessageDigest.isEqual(expectedDigest, actual)) {
                        lastError = "第 " + signerIndex + " 个签名者的内容摘要不匹配（alg 0x"
                                + Integer.toHexString(alg) + "）";
                        continue;
                    }
                    Signature sig = Signature.getInstance(jca);
                    if (alg == 0x0101 || alg == 0x0102) {
                        int salt = alg == 0x0101 ? 32 : 64;
                        String md = alg == 0x0101 ? "SHA-256" : "SHA-512";
                        PSSParameterSpec pss = new PSSParameterSpec(md, "MGF1",
                                "SHA-256".equals(md) ? MGF1ParameterSpec.SHA256 : MGF1ParameterSpec.SHA512,
                                salt, 1);
                        sig.setParameter(pss);
                    }
                    sig.initVerify(certKey);
                    sig.update(signedSlice.bytesContent());
                    if (!sig.verify(sigBytes.get(i))) {
                        lastError = "第 " + signerIndex + " 个签名者的签名值验签失败";
                        continue;
                    }
                    signerOk = true;
                    break;
                }
                if (signerOk) verifiedSigners++;
                else if (lastError.isEmpty()) lastError = "第 " + signerIndex + " 个签名者没有可用的签名算法";
            }

            if (verifiedSigners == 0) {
                r.signature = "failed";
                r.signatureDetail = lastError.isEmpty() ? "APK v2 验签失败" : lastError;
            } else if (verifiedSigners < signerIndex) {
                // 多签名者里只要有一个验不过就不能算整体通过
                r.signature = "failed";
                r.signatureDetail = "共 " + signerIndex + " 个签名者，只有 " + verifiedSigners + " 个通过；" + lastError;
            } else {
                r.signature = "verified";
                if (r.signatureDetail.isEmpty()) r.signatureDetail = "v2 验签通过";
            }
        }
    }

    /** 计算三个 section 的内容摘要。 */
    private static byte[] contentDigest(RandomAccessFile raf, SigningBlock block, String digestAlg) throws Exception {
        MessageDigest top = MessageDigest.getInstance(digestAlg);
        top.update((byte) 0x5a);
        List<byte[]> chunkDigests = new ArrayList<>();

        // section 1：ZIP 条目内容（0 .. 签名块起点）
        chunkDigests.addAll(sectionChunks(raf, 0, block.blockStart, digestAlg, null));
        // section 2：central directory
        chunkDigests.addAll(sectionChunks(raf, block.cdOffset, block.eocdOffset, digestAlg, null));
        // section 3：EOCD（把 CD offset 替换为签名块起点）
        byte[] eocd = new byte[(int) (block.eocdEnd - block.eocdOffset)];
        raf.seek(block.eocdOffset);
        raf.readFully(eocd);
        if (eocd.length >= 22) {
            writeUint32(eocd, 16, block.blockStart);
        }
        chunkDigests.addAll(sectionChunks(raf, block.eocdOffset, block.eocdEnd, digestAlg, eocd));

        top.update(uint32(chunkDigests.size()));
        for (byte[] d : chunkDigests) top.update(d);
        return top.digest();
    }

    private static List<byte[]> sectionChunks(RandomAccessFile raf, long start, long end,
                                              String digestAlg, byte[] override) throws Exception {
        List<byte[]> out = new ArrayList<>();
        long pos = start;
        while (pos < end) {
            int len = (int) Math.min(1024 * 1024L, end - pos);
            byte[] chunk = new byte[len];
            if (override != null) {
                System.arraycopy(override, 0, chunk, 0, len);
            } else {
                raf.seek(pos);
                raf.readFully(chunk);
            }
            MessageDigest md = MessageDigest.getInstance(digestAlg);
            md.update((byte) 0xa5);
            md.update(uint32(len));
            md.update(chunk);
            out.add(md.digest());
            pos += len;
        }
        return out;
    }

    private static byte[] uint32(long v) {
        return new byte[]{(byte) (v & 0xff), (byte) ((v >> 8) & 0xff),
                (byte) ((v >> 16) & 0xff), (byte) ((v >> 24) & 0xff)};
    }

    private static void writeUint32(byte[] buf, int off, long v) {
        buf[off] = (byte) (v & 0xff);
        buf[off + 1] = (byte) ((v >> 8) & 0xff);
        buf[off + 2] = (byte) ((v >> 16) & 0xff);
        buf[off + 3] = (byte) ((v >> 24) & 0xff);
    }

    private static String digestAlgorithm(int alg) {
        switch (alg) {
            case 0x0101: case 0x0103: case 0x0201: case 0x0301: return "SHA-256";
            case 0x0102: case 0x0104: case 0x0202: return "SHA-512";
            default: return null;
        }
    }

    private static String jcaSignature(int alg) {
        switch (alg) {
            case 0x0101:
            case 0x0102: return "RSASSA-PSS";
            case 0x0103: return "SHA256withRSA";
            case 0x0104: return "SHA512withRSA";
            case 0x0201: return "SHA256withECDSA";
            case 0x0202: return "SHA512withECDSA";
            case 0x0301: return "SHA256withDSA";
            default: return null;
        }
    }

    // ------------------------------------------------------------------ 签名块定位

    private static final class SigningBlock {
        byte[] v2;
        byte[] v3;
        long blockStart;
        long cdOffset;
        long eocdOffset;
        long eocdEnd;
    }

    private static SigningBlock readSigningBlock(Path file) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long len = raf.length();
            int tail = (int) Math.min(len, 128 * 1024);
            byte[] buf = new byte[tail];
            raf.seek(len - tail);
            raf.readFully(buf);
            int eocd = -1;
            for (int i = tail - 22; i >= 0; i--) {
                if ((buf[i] & 0xff) == 0x50 && (buf[i + 1] & 0xff) == 0x4b
                        && (buf[i + 2] & 0xff) == 0x05 && (buf[i + 3] & 0xff) == 0x06) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) return null;
            ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
            long cdOffset = bb.getInt(eocd + 16) & 0xffffffffL;
            if (cdOffset < 32 || cdOffset > len) return null;
            int commentLen = bb.getShort(eocd + 20) & 0xffff;
            long eocdOffset = len - tail + eocd;

            byte[] footer = new byte[24];
            raf.seek(cdOffset - 24);
            raf.readFully(footer);
            String magic = new String(footer, 8, 16, StandardCharsets.US_ASCII);
            if (!"APK Sig Block 42".equals(magic)) return null;

            ByteBuffer fb = ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN);
            long sizeInFooter = fb.getLong(0);
            long start = cdOffset - sizeInFooter - 8;
            if (start < 0) return null;
            raf.seek(start);
            byte[] head = new byte[8];
            raf.readFully(head);
            long sizeInHeader = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getLong(0);
            if (sizeInHeader != sizeInFooter) return null;

            int pairsLen = (int) (sizeInFooter - 24);
            if (pairsLen <= 0 || pairsLen > 64 * 1024 * 1024) return null;
            byte[] pairs = new byte[pairsLen];
            raf.seek(start + 8);
            raf.readFully(pairs);

            SigningBlock out = new SigningBlock();
            out.blockStart = start;
            out.cdOffset = cdOffset;
            out.eocdOffset = eocdOffset;
            out.eocdEnd = Math.min(len, eocdOffset + 22 + commentLen);
            int pos = 0;
            while (pos + 12 <= pairs.length) {
                long pairLen = ByteBuffer.wrap(pairs, pos, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
                pos += 8;
                if (pairLen < 4 || pos + pairLen > pairs.length) break;
                int id = ByteBuffer.wrap(pairs, pos, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                int valueLen = (int) pairLen - 4;
                byte[] value = new byte[valueLen];
                System.arraycopy(pairs, pos + 4, value, 0, valueLen);
                if (id == 0x7109871a) out.v2 = value;
                if (id == 0xf05368c0 || id == 0x1b93ad61) out.v3 = value;
                pos += (int) pairLen;
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ 游标

    private static final class Slice {
        final byte[] data;
        final int contentStart;
        final int end;

        Slice(byte[] data, int contentStart, int end) {
            this.data = data;
            this.contentStart = contentStart;
            this.end = end;
        }

        /** v2 的签名输入是 signed data 的内容本身，不含外层 4 字节长度前缀。 */
        byte[] bytesContent() {
            return Arrays.copyOfRange(data, contentStart, end);
        }
    }

    /** 长度前缀游标：每次 slice() 读 4 字节长度并切出子数组。 */
    private static final class Cursor {
        private final byte[] data;
        private final int start;
        private final int end;
        private int pos;

        Cursor(byte[] data) { this(data, 0, data.length); }

        Cursor(byte[] data, int start, int end) {
            this.data = data;
            this.start = start;
            this.end = end;
            this.pos = start;
        }

        boolean hasRemaining() { return pos < end; }

        int readInt() throws Exception {
            if (pos + 4 > end) throw new Exception("长度前缀越界");
            int v = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            pos += 4;
            return v;
        }

        Cursor slice() throws Exception {
            int len = readInt();
            if (len < 0 || pos + len > end) throw new Exception("子序列越界");
            Cursor c = new Cursor(data, pos, pos + len);
            pos += len;
            return c;
        }

        Slice sliceWithPrefix() throws Exception {
            int len = readInt();
            if (len < 0 || pos + len > end) throw new Exception("子序列越界");
            Slice s = new Slice(data, pos, pos + len);
            pos += len;
            return s;
        }

        byte[] rest() {
            byte[] out = new byte[end - start];
            System.arraycopy(data, start, out, 0, out.length);
            return out;
        }
    }

    /** 给 .zip/.jar 用：只做结构完整性检查。 */
    public static boolean zipIntact(Path file) {
        try {
            if (Files.size(file) > MAX_APK_BYTES) return false;
            try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            byte[] buf = new byte[128 * 1024];
            long total = 0;
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                try (InputStream in = zip.getInputStream(e)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_UNCOMPRESSED_BYTES) return false;
                    }
                }
            }
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}

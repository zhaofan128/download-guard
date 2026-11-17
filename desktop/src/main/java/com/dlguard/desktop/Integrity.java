package com.dlguard.desktop;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * APK / ZIP 结构体检。
 *
 * 电脑上最容易被忽略的一步：文件下完了，但可能是运营商劫持页、半截文件、
 * 或者被人重新打包过的安装包。这里做三件事：
 * 1. 遍历 ZIP 条目读取全部内容，CRC 不对直接判定损坏；
 * 2. 定位 APK Signing Block，识别 v2/v3 签名方案；
 * 3. 取出签名证书的 SHA-256 指纹，用户可以和官方公布的值对照。
 */
public final class Integrity {

    private Integrity() {}

    public static final class Result {
        public boolean zipOk = true;
        public String schemes = "";
        public String certSha256 = "";
        public String subject = "";
        public String detail = "";
    }

    public static Result inspectApk(Path file) {
        Result r = new Result();
        List<String> schemes = new ArrayList<>();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            // 1) 全量 CRC 校验
            Enumeration<? extends ZipEntry> en = zip.entries();
            byte[] buf = new byte[128 * 1024];
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                try (InputStream in = zip.getInputStream(e)) {
                    while (in.read(buf) > 0) { /* 读空即触发 CRC 校验 */ }
                }
            }
            // 2) v1：META-INF 下的签名文件
            en = zip.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName().toUpperCase();
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
                if (block.v3 != null) {
                    schemes.add(0, "v3");
                    parseCert(block.v3, r, "v3");
                }
                if (block.v2 != null) {
                    if (!schemes.contains("v2")) schemes.add(schemes.isEmpty() ? 0 : 1, "v2");
                    if (r.certSha256.isEmpty()) parseCert(block.v2, r, "v2");
                }
            }
        } catch (Exception e) {
            r.detail = "签名块解析失败：" + e.getMessage();
        }
        r.schemes = String.join("+", schemes);
        return r;
    }

    private static final class SigningBlock {
        byte[] v2;
        byte[] v3;
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

            byte[] footer = new byte[24];
            raf.seek(cdOffset - 24);
            raf.readFully(footer);
            String magic = new String(footer, 8, 16, java.nio.charset.StandardCharsets.US_ASCII);
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

    /** 解析 v2/v3 签名块，取第一张证书。结构是层层 length-prefix 的序列。 */
    private static void parseCert(byte[] value, Result r, String scheme) {
        try {
            Cursor c = new Cursor(value);
            Cursor signers = c.slice();          // signers 序列
            Cursor signer = signers.slice();     // 第一个 signer
            Cursor signedData = signer.slice();  // signed data
            signedData.skipSlice();              // digests
            Cursor certs = signedData.slice();   // certificates
            Cursor cert = certs.slice();         // 第一张证书 DER
            byte[] der = cert.rest();
            X509Certificate x = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            r.certSha256 = Util.hex(md.digest(x.getEncoded()));
            r.subject = x.getSubjectX500Principal().getName();
            if (r.detail.isEmpty()) r.detail = scheme + " 签名证书有效期至 " + x.getNotAfter();
        } catch (Exception e) {
            if (r.detail.isEmpty()) r.detail = scheme + " 签名块存在，证书解析失败";
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

        Cursor slice() throws Exception {
            if (pos + 4 > end) throw new Exception("长度前缀越界");
            int len = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            pos += 4;
            if (len < 0 || pos + len > end) throw new Exception("子序列越界");
            Cursor c = new Cursor(data, pos, pos + len);
            pos += len;
            return c;
        }

        void skipSlice() throws Exception {
            slice();
        }

        byte[] rest() {
            byte[] out = new byte[end - start];
            System.arraycopy(data, start, out, 0, out.length);
            return out;
        }
    }

    /** 给 .zip/.jar 用：只做结构完整性检查。 */
    public static boolean zipIntact(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            byte[] buf = new byte[128 * 1024];
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                try (InputStream in = zip.getInputStream(e)) {
                    while (in.read(buf) > 0) { /* CRC check */ }
                }
            }
            return true;
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

package com.dlguard.integrity

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 用仓库里真实签名的 debug APK 校验 v2 解析与摘要重算。
 * 该 APK 由 AGP 用 debug key 签名（v2/v3），能跑通说明解析器没有把正常包误判。
 * 在其他机器上若没有 dist 产物则自动跳过，不阻塞构建。
 */
class ApkSignatureVerifierTest {

    @Test
    fun `all shipped apks pass v2 digest verification`() {
        val distDir = listOf(File("../dist"), File("dist")).firstOrNull { it.isDirectory }
        // unsigned 产物是留给用户自签的半成品（无 APK Signing Block），不在验签范围
        val apks = distDir?.listFiles { f ->
            f.isFile && f.extension.equals("apk", true) &&
                !f.name.contains("unsigned", ignoreCase = true)
        }?.toList() ?: emptyList()
        assumeTrue("没有可用的已签名 APK，跳过", apks.isNotEmpty())
        apks.forEach { apk ->
            val scan = ZipInspector.scanEocd(apk)
            assertTrue("$apk 缺少 EOCD", scan is EocdScan.Found)
            val result = ApkSignatureVerifier.verify(apk, (scan as EocdScan.Found).info)
            assertTrue("$apk 校验结果：$result", result is ApkSignatureVerifier.Result.Verified)
        }
    }

    @Test
    fun `real debug apk passes v2 digest verification`() {
        val candidates = listOf(
            File("../dist/DownloadGuard-1.1.0-debug.apk"),
            File("dist/DownloadGuard-1.1.0-debug.apk"),
            File("../app/build/outputs/apk/debug/app-debug.apk")
        )
        val apk = candidates.firstOrNull { it.isFile }
        assumeTrue("没有可用的已签名 APK，跳过", apk != null)
        val scan = ZipInspector.scanEocd(apk!!)
        assumeTrue("APK 缺少 EOCD", scan is EocdScan.Found)
        val result = ApkSignatureVerifier.verify(apk, (scan as EocdScan.Found).info)
        assertTrue("v2 校验结果：$result", result is ApkSignatureVerifier.Result.Verified)
    }

    @Test
    fun `modifying one payload byte breaks v2 digest`() {
        val source = listOf(
            File("../dist/DownloadGuard-1.1.0-debug.apk"),
            File("dist/DownloadGuard-1.1.0-debug.apk")
        ).firstOrNull { it.isFile }
        assumeTrue("没有可用的已签名 APK，跳过", source != null)

        val copy = File.createTempFile("tampered_", ".apk")
        try {
            source!!.copyTo(copy, overwrite = true)
            // 改一个位于「签名块之前」的字节：v2 摘要必须因此不匹配
            java.io.RandomAccessFile(copy, "rw").use { raf ->
                raf.seek(2048)
                val value = raf.read()
                raf.seek(2048)
                raf.write(value xor 0xFF)
            }
            val scan = ZipInspector.scanEocd(copy)
            assumeTrue(scan is EocdScan.Found)
            val result = ApkSignatureVerifier.verify(copy, (scan as EocdScan.Found).info)
            assertTrue("篡改后应判定失败，实际：$result", result is ApkSignatureVerifier.Result.Failed)
        } finally {
            copy.delete()
        }
    }
}

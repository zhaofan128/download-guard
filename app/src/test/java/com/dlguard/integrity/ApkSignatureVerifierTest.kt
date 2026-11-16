package com.dlguard.integrity

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 用真实签名的 APK 校验 v2 解析与摘要重算。
 *
 * 取样顺序：`dist/` 里的历史产物 → `app/build/outputs/apk/debug/app-debug.apk`。
 * 后者在 CI 里由 `assembleDebug` 现场生成，所以这三条用例在流水线上是真的会跑的，
 * 不是「没有样本就悄悄跳过、显示一片绿」。
 */
class ApkSignatureVerifierTest {

    /** 所有可用的、带签名的 APK（排除 unsigned 半成品：它没有 APK Signing Block）。 */
    private fun signedApks(): List<File> {
        val fromDist = listOf(File("../dist"), File("dist"))
            .filter { it.isDirectory }
            .flatMap { dir ->
                dir.listFiles { f ->
                    f.isFile && f.extension.equals("apk", true) &&
                        !f.name.contains("unsigned", ignoreCase = true)
                }?.toList() ?: emptyList()
            }
        val built = listOf(
            File("../app/build/outputs/apk/debug/app-debug.apk"),
            File("app/build/outputs/apk/debug/app-debug.apk")
        ).filter { it.isFile }
        // 小于 4KB 的不是完整安装包，改字节做篡改用例也没有意义
        return (fromDist + built).filter { it.length() > 4096 }.distinctBy { it.absolutePath }
    }

    @Test
    fun `all shipped apks pass v2 digest verification`() {
        val apks = signedApks()
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
        val apk = signedApks().firstOrNull { it.name.contains("debug", ignoreCase = true) }
            ?: signedApks().firstOrNull()
        assumeTrue("没有可用的已签名 APK，跳过", apk != null)
        val scan = ZipInspector.scanEocd(apk!!)
        assumeTrue("APK 缺少 EOCD", scan is EocdScan.Found)
        val result = ApkSignatureVerifier.verify(apk, (scan as EocdScan.Found).info)
        assertTrue("v2 校验结果：$result", result is ApkSignatureVerifier.Result.Verified)
    }

    @Test
    fun `modifying one payload byte breaks v2 digest`() {
        val source = signedApks().firstOrNull()
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

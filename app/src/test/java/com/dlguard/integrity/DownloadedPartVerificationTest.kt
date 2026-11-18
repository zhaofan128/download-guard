package com.dlguard.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * 「下载完成 → 校验」链路的集成用例。
 *
 * 不走 Android 的 PackageManager（JVM 单测没有 PackageManager），但把最容易出错、
 * 也最要命的那一段完整跑了一遍：**磁盘上是 `xxx.apk.part`，按逻辑名判定后，
 * 真的对这份临时文件重算了 v2 分块摘要**。
 *
 * 之前缺的就是这条链路：142 个用例里没有一个覆盖「下载器产出的 .part 文件」，
 * 所以「扩展名取到 part，L4/L5 被跳过」这个缺陷一路活到了 2.2.0。
 */
class DownloadedPartVerificationTest {

    private fun signedApk(): File? {
        // 目录扫描而不是写死文件名：3.4.0 起产物路径带 flavor 子目录
        val candidates = listOf(
            File("../app/build/outputs/apk/full/debug"),
            File("app/build/outputs/apk/full/debug"),
            File("../app/build/outputs/apk/full/release"),
            File("app/build/outputs/apk/full/release"),
            File("../app/build/outputs/apk/debug"),
            File("app/build/outputs/apk/debug")
        ).filter { it.isDirectory }.flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.extension.equals("apk", true) }?.toList() ?: emptyList()
        }
        return candidates.firstOrNull { it.length() > 4096 }
    }

    @Test
    fun `下载中的 part 文件按逻辑名判定必须走验签`() {
        val physical = "微信_8.0.50.apk.part"
        // 旧实现的取法：物理名最后一个点之后
        assertEquals("part", physical.substringAfterLast('.', "").lowercase())
        // 新实现的取法：逻辑名
        assertTrue(IntegrityTarget.isSingleApk("微信_8.0.50.apk"))
        // 兜底：只拿得到物理名时也不能放行
        assertTrue(IntegrityTarget.isSingleApk(physical))
    }

    @Test
    fun `part 文件能真的通过 v2 摘要验签`() {
        val apk = signedApk()
        assumeTrue("没有可用的已签名 APK，跳过", apk != null)

        val part = File.createTempFile("download_", ".apk.part")
        try {
            apk!!.copyTo(part, overwrite = true)
            val scan = ZipInspector.scanEocd(part)
            assertTrue("临时文件缺少 EOCD", scan is EocdScan.Found)
            val result = ApkSignatureVerifier.verify(part, (scan as EocdScan.Found).info)
            assertTrue("part 文件验签结果：$result", result is ApkSignatureVerifier.Result.Verified)
        } finally {
            part.delete()
        }
    }

    @Test
    fun `part 文件被改一个字节后验签必须失败`() {
        val apk = signedApk()
        assumeTrue("没有可用的已签名 APK，跳过", apk != null)

        val part = File.createTempFile("tampered_", ".apk.part")
        try {
            apk!!.copyTo(part, overwrite = true)
            // 改数据区中段的一个字节：ZIP 仍能打开，只有签名摘要会炸。
            // 这正是「只改签名 / 改过 CRC」的包能骗过 L1-L3 的原因。
            RandomAccessFile(part, "rw").use { raf ->
                val offset = (part.length() / 2).coerceAtLeast(1024L)
                raf.seek(offset)
                val original = raf.read()
                raf.seek(offset)
                raf.write(original xor 0xFF)
            }
            val scan = ZipInspector.scanEocd(part)
            if (scan !is EocdScan.Found) return // 改到了目录区，ZIP 直接坏掉也算拦住了
            val result = ApkSignatureVerifier.verify(part, scan.info)
            assertFalse("改了字节居然验签通过：$result", result is ApkSignatureVerifier.Result.Verified)
        } finally {
            part.delete()
        }
    }
}

package com.dlguard.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * AndroidManifestProbe 的回归测试。
 *
 * 真实 APK 用仓库 dist/ 里已发布的包做样本（存在才跑），
 * 这样 AXML 的字符串池、资源映射、UTF-8/UTF-16 两种编码都被真实数据覆盖过，
 * 而不是只对着自己造的样例自说自话。
 */
class AndroidManifestProbeTest {

    private fun sampleApk(): File? {
        val candidates = listOf(
            File("../dist/DownloadGuard-3.4.0-arm64.apk"),
            File("dist/DownloadGuard-3.4.0-arm64.apk"),
            File("../dist/DownloadGuard-3.4.0-universal.apk")
        )
        return candidates.firstOrNull { it.exists() && it.length() > 0 }
    }

    @Test
    fun `真实 APK 能读出 package 与 sdk 版本`() {
        val apk = sampleApk()
        assumeTrue("dist 下没有样本 APK，跳过真实包用例", apk != null)
        val info = AndroidManifestProbe.inspect(apk!!)

        assertNull("解析不应报错：${info.error}", info.error)
        assertTrue("manifest 应可解析", info.parsed)
        assertEquals("com.dlguard", info.packageName)
        assertEquals(26, info.minSdk)
        assertEquals(34, info.targetSdk)
        assertEquals(36L, info.versionCode)
    }

    @Test
    fun `空字节不会抛异常`() {
        val info = AndroidManifestProbe.parse(ByteArray(0))
        assertFalse(info.parsed)
        assertNotNull(info.error)
    }

    @Test
    fun `随机字节不会抛异常`() {
        val random = ByteArray(4096) { (it * 37 % 251).toByte() }
        val info = AndroidManifestProbe.parse(random)
        assertFalse(info.parsed)
        assertNotNull(info.error)
    }

    @Test
    fun `不在 ZIP 里的文件返回错误而不是抛异常`() {
        val temp = File.createTempFile("probe", ".apk").apply {
            writeBytes(ByteArray(1024) { 0x41 })
            deleteOnExit()
        }
        val info = AndroidManifestProbe.inspect(temp)
        assertFalse(info.parsed)
        assertNotNull(info.error)
    }

    @Test
    fun `没有 AndroidManifest 的 ZIP 返回明确原因`() {
        val temp = File.createTempFile("probe", ".apk").apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write(ByteArray(16))
                zip.closeEntry()
            }
            deleteOnExit()
        }
        val info = AndroidManifestProbe.inspect(temp)
        assertFalse(info.parsed)
        assertTrue(info.error.orEmpty().contains("AndroidManifest"))
    }

    @Test
    fun `截断的 manifest 不会崩溃`() {
        val apk = sampleApk()
        assumeTrue(apk != null)
        // 取真实 manifest 的前 200 字节，模拟传输中断
        val truncated = java.util.zip.ZipFile(apk!!).use { zip ->
            zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { input ->
                val buffer = ByteArray(200)
                var total = 0
                while (total < buffer.size) {
                    val read = input.read(buffer, total, buffer.size - total)
                    if (read <= 0) break
                    total += read
                }
                buffer.copyOf(total)
            }
        }
        val info = AndroidManifestProbe.parse(truncated)
        // 200 字节通常还没到 manifest 起始标签，能读到的信息不完整但不能崩
        assertTrue(info.packageName == null || info.packageName == "com.dlguard")
    }
}

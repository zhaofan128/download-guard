package com.dlguard.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * AndroidManifestProbe 的回归测试。
 *
 * 样本优先用构建产物（CI 会先 assembleFullDebug），其次用 dist/ 里已发布的包。
 * 这里**不使用 assumeTrue**：CI 的「零跳过」守卫会把跳过算成失败，
 * 而且样本缺失本来就该被看见，不该静默略过。
 */
class AndroidManifestProbeTest {

    private fun sampleApk(): File {
        val candidates = listOf(
            File("../app/build/outputs/apk/full/debug/app-full-debug.apk"),
            File("app/build/outputs/apk/full/debug/app-full-debug.apk"),
            File("../dist/DownloadGuard-3.4.3-arm64.apk"),
            File("../dist/DownloadGuard-3.4.0-arm64.apk")
        )
        return candidates.firstOrNull { it.exists() && it.length() > 0 }
            ?: error("找不到可用的 APK 样本：先跑 ./gradlew :app:assembleFullDebug")
    }

    @Test
    fun `真实 APK 能读出 package 与 sdk 版本`() {
        val info = AndroidManifestProbe.inspect(sampleApk())

        assertNull("解析不应报错：${info.error}", info.error)
        assertTrue("manifest 应可解析", info.parsed)
        assertEquals("com.dlguard", info.packageName)
        // minSdk / targetSdk 由 build.gradle.kts 固定，版本号会随发版变化，不做硬断言
        assertEquals(26, info.minSdk)
        assertEquals(34, info.targetSdk)
        assertTrue("versionCode 应为正数", (info.versionCode ?: 0) > 0)
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
        // 取真实 manifest 的前 200 字节，模拟传输中断
        val truncated = java.util.zip.ZipFile(sampleApk()).use { zip ->
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

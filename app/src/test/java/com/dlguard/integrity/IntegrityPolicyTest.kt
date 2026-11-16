package com.dlguard.integrity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「普通文件被误判成坏文件」是社区反馈里最刺眼的一条：
 * 一个完全正常的 mp4 下完显示「校验失败（非 ZIP 结构）」，比不校验更糟。
 */
class IntegrityPolicyTest {

    @Test
    fun `video and audio are not treated as archives`() {
        assertFalse(IntegrityPolicy.requiresArchiveValidation("movie.mp4", false))
        assertFalse(IntegrityPolicy.requiresArchiveValidation("song.flac", false))
        assertFalse(IntegrityPolicy.requiresArchiveValidation("manual.pdf", false))
        assertFalse(IntegrityPolicy.requiresArchiveValidation("cover.jpg", false))
    }

    @Test
    fun `install packages always require archive validation`() {
        assertTrue(IntegrityPolicy.requiresArchiveValidation("app.apk", false))
        assertTrue(IntegrityPolicy.requiresArchiveValidation("bundle.apks", false))
        assertTrue(IntegrityPolicy.requiresArchiveValidation("data.zip", false))
    }

    @Test
    fun `pk header wins over a misleading extension`() {
        // download.php 实际是 APK：靠文件头认出来，仍要完整校验
        assertTrue(IntegrityPolicy.requiresArchiveValidation("download.php", true))
        // 没有后缀但确实是 ZIP
        assertTrue(IntegrityPolicy.requiresArchiveValidation("payload", true))
    }

    @Test
    fun `real non archive file is reported healthy by the policy path`() {
        val file = File.createTempFile("clip", ".mp4")
        try {
            file.writeBytes(ByteArray(4096) { (it % 251).toByte() })
            assertFalse(
                IntegrityPolicy.requiresArchiveValidation(
                    file.name,
                    com.dlguard.integrity.ZipInspector.startsWithZipHeader(file)
                )
            )
        } finally {
            file.delete()
        }
    }
}

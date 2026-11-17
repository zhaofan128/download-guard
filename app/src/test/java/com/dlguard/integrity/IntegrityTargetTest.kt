package com.dlguard.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这组用例守的是一个真实事故：
 *
 * 下载器边下边校验，磁盘上是 `app.apk.part`。旧实现按物理文件名取扩展名，
 * 得到 `part`，L4（APK 解析）与 L5（v2 签名）被整段跳过，而界面照样显示
 * 「校验通过 · 共 N 个条目」。也就是说：只改签名、重算过 ZIP CRC 的包能骗过它。
 */
class IntegrityTargetTest {

    @Test
    fun `物理名是 part 时按逻辑名识别安装包`() {
        assertTrue(IntegrityTarget.isInstallerPackage("微信_8.0.50.apk"))
        assertTrue(IntegrityTarget.isSingleApk("微信_8.0.50.apk"))
        // 物理名单独看是 part，不能因此否定逻辑名
        assertEquals("part", IntegrityTarget.extensionOf("微信_8.0.50.apk.part"))
    }

    @Test
    fun `只拿得到物理名时剥掉 part 再判`() {
        assertTrue(IntegrityTarget.isInstallerPackage("app.apk.part"))
        assertTrue(IntegrityTarget.isSingleApk("app.apk.part"))
        assertTrue(IntegrityTarget.isInstallerPackage("bundle.apks.part"))
        assertTrue(IntegrityTarget.isInstallerPackage("bundle.xapk.PART"))
    }

    @Test
    fun `分包容器不当作单包验签`() {
        assertTrue(IntegrityTarget.isInstallerPackage("bundle.apks"))
        assertTrue(IntegrityTarget.isInstallerPackage("bundle.xapk"))
        assertFalse(IntegrityTarget.isSingleApk("bundle.apks"))
        assertFalse(IntegrityTarget.isSingleApk("bundle.xapk"))
    }

    @Test
    fun `普通文件不会被误判成安装包`() {
        assertFalse(IntegrityTarget.isInstallerPackage("movie.mp4"))
        assertFalse(IntegrityTarget.isInstallerPackage("archive.zip"))
        assertFalse(IntegrityTarget.isInstallerPackage("app.apk.bak"))
        assertFalse(IntegrityTarget.isInstallerPackage(""))
        assertFalse(IntegrityTarget.isSingleApk("movie.mp4"))
    }

    @Test
    fun `大小写与空白不影响判定`() {
        assertTrue(IntegrityTarget.isInstallerPackage("APP.APK"))
        assertTrue(IntegrityTarget.isSingleApk("APP.APK"))
        assertTrue(IntegrityTarget.isInstallerPackage("  app.apk  "))
    }
}

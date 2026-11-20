package com.dlguard.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件 id 是磁盘目录名（`files/plugins/<id>/`），非法 id 等于任意路径读写。
 *
 * 这里只测纯函数，不构造 PluginManager（它需要 Android Context）。
 */
class PluginIdValidationTest {

    @Test
    fun `正常 id 通过`() {
        assertTrue(PluginManifest.isValidId("official.name-template"))
        assertTrue(PluginManifest.isValidId("community.example-sniffer"))
        assertTrue(PluginManifest.isValidId("a_b.c-d"))
        assertTrue(PluginManifest.isValidId("ab"))
    }

    @Test
    fun `路径穿越与分隔符被拒绝`() {
        assertFalse(PluginManifest.isValidId("../../databases"))
        assertFalse(PluginManifest.isValidId(".."))
        assertFalse(PluginManifest.isValidId("a/b"))
        assertFalse(PluginManifest.isValidId("/etc/passwd"))
        assertFalse(PluginManifest.isValidId("a\\b"))
    }

    @Test
    fun `空串、单字符与超长 id 被拒绝`() {
        assertFalse(PluginManifest.isValidId(""))
        assertFalse(PluginManifest.isValidId("a"))
        assertFalse(PluginManifest.isValidId("a".repeat(65)))
        assertFalse(PluginManifest.isValidId("插件"))
        assertFalse(PluginManifest.isValidId("a b"))
    }
}

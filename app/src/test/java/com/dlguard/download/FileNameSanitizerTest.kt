package com.dlguard.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件名去广告：2.2.0 曾因为一条嵌套字符类正则（`[[【(（]`）直接抛 PatternSyntaxException，
 * 所有「带推荐文件名」的下载全部失败。这里把预期行为钉死，避免再靠「碰巧没走到」蒙混。
 */
class FileNameSanitizerTest {

    @Test
    fun `方括号包裹的域名广告会被删掉`() {
        assertEquals("App_v1.2.apk", FileNameSanitizer.clean("[www.downxia.com]App_v1.2.apk"))
        assertEquals("微信.apk", FileNameSanitizer.clean("[www.52pojie.cn]微信.apk"))
    }

    @Test
    fun `中文方括号与圆括号的域名广告同样删掉`() {
        assertEquals("工具.zip", FileNameSanitizer.clean("【www.pc6.com】工具.zip"))
        assertEquals("Installer.exe", FileNameSanitizer.clean("(www.xxx.com)Installer.exe"))
        assertEquals("软件.apk", FileNameSanitizer.clean("（down.abc.net）软件.apk"))
    }

    @Test
    fun `正常括号内容不会被误删`() {
        assertEquals("[1080p]电影.mkv", FileNameSanitizer.clean("[1080p]电影.mkv"))
        assertEquals("【合集】音乐.zip", FileNameSanitizer.clean("【合集】音乐.zip"))
        assertEquals("游戏(全集).zip", FileNameSanitizer.clean("游戏(全集).zip"))
    }

    @Test
    fun `尾部域名与营销词会被删掉`() {
        assertEquals("游戏.zip", FileNameSanitizer.clean("游戏_www.abc.com.zip"))
        assertEquals("软件.apk", FileNameSanitizer.clean("软件_下载之家.apk"))
        assertEquals("软件.apk", FileNameSanitizer.clean("软件-绿色版.apk"))
    }

    @Test
    fun `普通文件名保持原样`() {
        val name = "DownloadGuard-2.2.0-bt.apk"
        assertEquals(name, FileNameSanitizer.clean(name))
    }

    @Test
    fun `清成空串时退回原名`() {
        val name = "[www.ad.com]"
        assertEquals(name, FileNameSanitizer.clean(name))
    }

    @Test
    fun `畸形输入不抛异常`() {
        val weird = listOf(
            "", "   ", "[[[]]]", "【【【", "(((", "]]]]", "[", "【", "(", "..apk",
            "[www.a.com", "【www.a.com】", "a".repeat(500) + ".apk"
        )
        for (input in weird) {
            // 只要不抛异常就算通过，返回什么由上面几条规则决定
            val out = FileNameSanitizer.clean(input)
            assertTrue("输出不应为 null: $input", out.isNotEmpty() || input.isEmpty())
        }
    }
}

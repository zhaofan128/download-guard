package com.dlguard.download

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 文件名模板的边界：占位符替换、未知占位符保留、无扩展名时不留下结尾的点。
 */
class FileNameTemplateTest {

    @Test
    fun `占位符全部替换`() {
        val out = FileNameTemplate.apply(
            "{date}_{name}-{host}.{ext}",
            "安装包.apk",
            "https://cdn.example.com/a/安装包.apk"
        )
        assertEquals("${today()}_安装包-cdn.example.com.apk", out)
    }

    @Test
    fun `未知占位符原样保留`() {
        val out = FileNameTemplate.apply("{name}_{unknown}.{ext}", "a.zip", "https://a.test/a.zip")
        assertEquals("a_{unknown}.zip", out)
    }

    @Test
    fun `没有扩展名时不留下结尾的点`() {
        val out = FileNameTemplate.apply("{date}_{name}.{ext}", "README", "https://a.test/README")
        assertEquals("${today()}_README", out)
    }

    @Test
    fun `空模板保持原名`() {
        assertEquals("a.bin", FileNameTemplate.apply("", "a.bin", "https://a.test/a.bin"))
    }

    private fun today(): String =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date())
}

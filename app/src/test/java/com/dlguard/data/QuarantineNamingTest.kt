package com.dlguard.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 隔离区命名回归测试。
 *
 * 这些用例对应一个真实投诉：下载器把 `xxx.apk.part` 原样搬进隔离区，
 * 用户在隔离列表里看到一堆陌生后缀，以为文件根本没被移进去。
 */
class QuarantineNamingTest {

    @Test
    fun `下载器的 part 后缀被剥掉`() {
        assertEquals("DirtyFragX-v2.1.0.apk", QuarantineNaming.logicalName("DirtyFragX-v2.1.0.apk.part"))
    }

    @Test
    fun `大写 PART 也能识别`() {
        assertEquals("setup.exe", QuarantineNaming.logicalName("setup.exe.PART"))
    }

    @Test
    fun `隔离名保留扩展名并加时间戳`() {
        assertEquals(
            "DirtyFragX-v2.1.0_20261119-2217.apk",
            QuarantineNaming.fileName("DirtyFragX-v2.1.0.apk.part", "20261119-2217")
        )
    }

    @Test
    fun `重名时追加序号且序号在扩展名之前`() {
        assertEquals(
            "app_20261119-2217_2.apk",
            QuarantineNaming.fileName("app.apk", "20261119-2217", index = 2)
        )
    }

    @Test
    fun `没有扩展名的文件也能命名`() {
        assertEquals(
            "download_20261119-2217",
            QuarantineNaming.fileName("download", "20261119-2217")
        )
    }

    @Test
    fun `路径分隔符与非法字符被替换`() {
        val name = QuarantineNaming.fileName("../../etc/passwd", "20261119-2217")
        assertEquals("passwd_20261119-2217", name)
    }
}

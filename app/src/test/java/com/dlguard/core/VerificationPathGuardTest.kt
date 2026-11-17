package com.dlguard.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 架构门禁：下载器对临时文件做校验时，必须显式告诉校验器「这个文件最终叫什么」。
 *
 * 只靠 `IntegrityTargetTest` 不够 —— 哪天有人在 `ResumableDownloader` 里再写一句
 * `checker.check(part, size)`，扩展名又会取到 `part`，L4/L5 又会被跳过，
 * 而所有纯函数单测依然全绿。这里直接扫源码，让这种回退在 CI 阶段就红。
 */
class VerificationPathGuardTest {

    private fun sourceFile(relative: String): File {
        val candidates = listOf(File("../$relative"), File(relative))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到源码文件：$relative（工作目录 ${File(".").absolutePath}）")
    }

    @Test
    fun `下载器校验临时文件时必须传 logicalName`() {
        val text = sourceFile("app/src/main/java/com/dlguard/download/ResumableDownloader.kt").readText()
        val call = Regex("""checker\.check\(\s*([^)]*)\)""").findAll(text).map { it.groupValues[1] }.toList()
        assertTrue("没有找到任何 checker.check 调用，门禁失效", call.isNotEmpty())

        val partCalls = call.filter { it.trimStart().startsWith("part") }
        assertTrue("没有对 part 文件的校验调用，门禁失效", partCalls.isNotEmpty())
        partCalls.forEach { args ->
            assertTrue(
                "对 .part 文件的校验没有传 logicalName，L4/L5 会被静默跳过：checker.check($args)",
                args.contains("logicalName")
            )
        }
    }

    @Test
    fun `完整性检查器不得再用物理文件名判类型`() {
        val text = sourceFile("app/src/main/java/com/dlguard/integrity/ApkIntegrityChecker.kt").readText()
        assertTrue(
            "ApkIntegrityChecker 里又出现了 file.name 取扩展名的写法，逻辑名判定被绕过",
            !text.contains("file.name.substringAfterLast")
        )
    }
}

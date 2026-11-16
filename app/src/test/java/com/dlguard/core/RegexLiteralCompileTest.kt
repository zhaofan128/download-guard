package com.dlguard.core

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.regex.PatternSyntaxException

/**
 * 正则字面量的编译门禁。
 *
 * 2.2.0 出过一次事故：`Regex("""[[【(（]...[^]】)）]...""")` 里的 `[` 在 java.util.regex 中
 * 会被当成**嵌套字符类**，整条正则编译即抛 PatternSyntaxException，而它又在下载命名路径上，
 * 结果所有「带推荐文件名」的下载全部失败。单测此前只覆盖了功能分支，没人编译过这条正则。
 *
 * 这个测试直接扫 `src/main/java` 下所有 `Regex("""…""")` 字面量并逐个编译，
 * 让「写错正则」在 CI 阶段就红，而不是等用户下到一半才炸。
 */
class RegexLiteralCompileTest {

    @Test
    fun `源码里所有正则字面量都能编译`() {
        val roots = listOf(File("src/main/java"), File("../xposed/src/main/java")).filter { it.isDirectory }
        assumeTrue("找不到源码目录，跳过", roots.isNotEmpty())

        val failures = mutableListOf<String>()
        var total = 0
        for (root in roots) {
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val text = file.readText()
                    var from = 0
                    while (true) {
                        val at = text.indexOf("Regex(", from)
                        if (at < 0) break
                        var i = at + "Regex(".length
                        while (i < text.length && text[i].isWhitespace()) i++
                        if (!text.startsWith("\"\"\"", i)) {
                            from = at + 1
                            continue
                        }
                        val bodyStart = i + 3
                        val bodyEnd = text.indexOf("\"\"\"", bodyStart)
                        if (bodyEnd < 0) {
                            failures += "${file.name}:${lineOf(text, at)} 未闭合的原始字符串"
                            break
                        }
                        val raw = stripTemplates(text.substring(bodyStart, bodyEnd))
                        val line = lineOf(text, at)
                        total++
                        try {
                            Regex(raw)
                        } catch (e: PatternSyntaxException) {
                            failures += "${file.name}:$line -> ${e.message?.lineSequence()?.firstOrNull()}"
                        } catch (e: Exception) {
                            failures += "${file.name}:$line -> ${e.message}"
                        }
                        from = bodyEnd + 3
                    }
                }
        }

        assertTrue("没扫到任何正则字面量，扫描逻辑可能失效", total > 0)
        assertTrue("有正则无法编译:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun lineOf(text: String, index: Int): Int = text.take(index).count { it == '\n' } + 1

    /** 把 Kotlin 字符串模板 `${...}` / `$name` 换成 x，避免误判成非法的量词写法。 */
    private fun stripTemplates(body: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '$' && i + 1 < body.length && body[i + 1] == '{') {
                val end = body.indexOf('}', i)
                sb.append('x')
                i = if (end < 0) body.length else end + 1
            } else if (c == '$' && i + 1 < body.length && (body[i + 1].isLetter() || body[i + 1] == '_')) {
                var j = i + 1
                while (j < body.length && (body[j].isLetterOrDigit() || body[j] == '_')) j++
                sb.append('x')
                i = j
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}

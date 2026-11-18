package com.dlguard.site

/**
 * 极简 JSON 读写（无第三方依赖）。
 *
 * 为什么不直接用 org.json：站点规则是**用户可导入的外部文件**，解析器必须
 *  - 有明确的资源上限（长度 / 深度 / 条目数），避免一个畸形文件把内存打爆；
 *  - 在 JVM 单元测试里可运行（Android 的 org.json 在本地测试中是空壳）；
 *  - 输出确定（键排序），导出的规则文件可直接 diff / 签名。
 *
 * 只实现 JSON 的一个子集：object / array / string / number / true / false / null。
 * 不支持注释、单引号、尾逗号——宁可拒绝，也不要「差不多能解析」。
 */
object JsonLite {

    class JsonException(message: String) : Exception(message)

    /** 单个文件上限 512 KB：站点规则是配置，不是数据。 */
    const val MAX_LENGTH = 512 * 1024

    /** 嵌套深度上限：正常规则只有 3 层（根 → rules → rule）。 */
    const val MAX_DEPTH = 32

    fun parse(text: String): Any? {
        if (text.length > MAX_LENGTH) throw JsonException("内容超过 ${MAX_LENGTH / 1024} KB 上限")
        val parser = Parser(text)
        val value = parser.readValue(0)
        parser.skipWhitespace()
        if (!parser.atEnd()) throw JsonException("第 ${parser.pos} 个字符后存在多余内容")
        return value
    }

    fun write(value: Any?): String = StringBuilder().also { writeTo(it, value) }.toString()

    fun writeTo(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Float -> sb.append(if (value.isFinite()) value.toString() else "0")
            is Double -> {
                if (!value.isFinite()) sb.append("0")
                else if (value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e15) {
                    sb.append(value.toLong().toString())
                } else {
                    sb.append(value.toString())
                }
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                // 键排序：导出结果稳定，方便用户 diff 或做签名校验
                val keys = value.keys.map { it?.toString().orEmpty() }.sorted()
                for (k in keys) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k)
                    sb.append(':')
                    writeTo(sb, value[k])
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeTo(sb, item)
                }
                sb.append(']')
            }
            is Array<*> -> writeTo(sb, value.toList())
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeString(sb: StringBuilder, text: String) {
        sb.append('"')
        for (ch in text) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (ch < ' ' || ch == '\u2028' || ch == '\u2029') {
                        sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
    }

    private class Parser(private val text: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length) {
                when (text[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw JsonException("嵌套层级超过 $MAX_DEPTH")
            skipWhitespace()
            if (atEnd()) throw JsonException("内容意外结束")
            return when (val ch = text[pos]) {
                '{' -> readObject(depth + 1)
                '[' -> readArray(depth + 1)
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> {
                    if (ch == '-' || ch in '0'..'9') readNumber()
                    else throw JsonException("第 $pos 个字符出现非法起始符 '$ch'")
                }
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return out
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                if (out.size >= 512) throw JsonException("单个对象的键超过 512 个")
                skipWhitespace()
                expect(':')
                out[key] = readValue(depth)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return out
                    }
                    else -> throw JsonException("第 $pos 个字符缺少 ',' 或 '}'")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return out
            }
            while (true) {
                out.add(readValue(depth))
                if (out.size > 4096) throw JsonException("单个数组元素超过 4096 个")
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return out
                    }
                    else -> throw JsonException("第 $pos 个字符缺少 ',' 或 ']'")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("字符串未闭合")
                val ch = text[pos++]
                when {
                    ch == '"' -> return sb.toString()
                    ch == '\\' -> {
                        if (atEnd()) throw JsonException("转义符未完成")
                        when (val esc = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw JsonException("\\u 转义不足 4 位")
                                val hex = text.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw JsonException("\\u 转义不是合法十六进制：$hex")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw JsonException("未知转义符 \\$esc")
                        }
                    }
                    ch < ' ' -> throw JsonException("字符串中出现未转义的控制字符")
                    else -> sb.append(ch)
                }
                if (sb.length > 64 * 1024) throw JsonException("单个字符串超过 64 KB")
            }
        }

        private fun readNumber(): Double {
            val start = pos
            if (peek() == '-') pos++
            while (!atEnd() && text[pos].isDigit()) pos++
            if (!atEnd() && text[pos] == '.') {
                pos++
                while (!atEnd() && text[pos].isDigit()) pos++
            }
            if (!atEnd() && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (!atEnd() && (text[pos] == '+' || text[pos] == '-')) pos++
                while (!atEnd() && text[pos].isDigit()) pos++
            }
            val raw = text.substring(start, pos)
            val value = raw.toDoubleOrNull() ?: throw JsonException("非法数字：$raw")
            if (!value.isFinite()) throw JsonException("数字超出范围：$raw")
            return value
        }

        private fun <T> readLiteral(literal: String, value: T): T {
            if (!text.startsWith(literal, pos)) throw JsonException("第 $pos 个字符应为 $literal")
            pos += literal.length
            return value
        }

        private fun peek(): Char {
            if (atEnd()) throw JsonException("内容意外结束")
            return text[pos]
        }

        private fun expect(ch: Char) {
            if (atEnd() || text[pos] != ch) throw JsonException("第 $pos 个字符应为 '$ch'")
            pos++
        }
    }
}

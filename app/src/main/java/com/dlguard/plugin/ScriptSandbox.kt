package com.dlguard.plugin

import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable

/**
 * 插件脚本沙箱（Rhino，纯 Java 解释器，无 JIT、无字节码生成）。
 *
 * 为什么插件需要脚本：1.8.0 的插件只有 JSON 配置，能改限速/重试/UA，但改不了 URL 本身。
 * 而「把 /small/ 换成 /large/」「把缩略图参数去掉」这类站点适配，本质就是一行字符串变换——
 * 每加一个站就得发一次 App 版本，社区根本没法参与。
 *
 * 安全边界（逐条落实，不靠「约定」）：
 *  1. **无网络**：Rhino 里没有 socket / OkHttp / URL 类，脚本拿不到任何 IO 入口；
 *  2. **无文件**：不注入任何 Java 对象，`initSafeStandardObjects(sealed=true)` 已移除 `Packages`/`getClass`；
 *  3. **无 Java 反射**：额外挂 [ClassShutter] 一律拒绝，`java.lang.Runtime` 这类后门不可达；
 *  4. **限时**：解释模式 + 指令计数回调，超过 200ms 直接中断（死循环打不垮 App）；
 *  5. **限形**：只接受字符串返回值，且必须是 http/https、长度 ≤ 8KB，否则视为脚本失败。
 *
 * 能力上它就是一个「字符串函数」：输入 url，输出 url。够用，且攻击面接近于零。
 */
object ScriptSandbox {

    private const val TIMEOUT_MS = 200L
    private const val MAX_URL_LENGTH = 8 * 1024
    private const val INSTRUCTION_THRESHOLD = 8_000

    data class Result(val url: String? = null, val error: String? = null) {
        val ok: Boolean get() = url != null
    }

    private val deadline = ThreadLocal<Long>()

    private val factory = object : ContextFactory() {
        override fun makeContext(): Context {
            val cx = super.makeContext()
            // 解释模式：Android 上没有字节码加载器，也顺带让指令计数回调生效
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            cx.instructionObserverThreshold = INSTRUCTION_THRESHOLD
            cx.setClassShutter(ClassShutter { false })
            return cx
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val limit = deadline.get() ?: return
            if (System.nanoTime() > limit) {
                throw Error("脚本执行超时（>${TIMEOUT_MS}ms）")
            }
        }
    }

    @Volatile
    private var factoryReady = false

    private fun ensureFactory() {
        if (factoryReady) return
        synchronized(this) {
            if (factoryReady) return
            // initGlobal 只能成功一次；已有全局 factory 时直接用（多进程/热重启场景）
            runCatching { ContextFactory.initGlobal(factory) }
            factoryReady = true
        }
    }

    /**
     * 执行 URL 重写脚本。脚本里可以使用 `url` 变量，返回值即新 URL；
     * 也支持显式赋值 `out = ...`（可读性更好）。
     */
    fun rewrite(script: String, url: String, timeoutMs: Long = TIMEOUT_MS): Result {
        if (script.isBlank()) return Result(url = url)
        ensureFactory()
        val escapedUrl = jsString(url)
        // 用 eval 执行脚本体：只有这样「最后一行表达式」才会成为返回值
        // （普通函数体不会自动返回最后表达式）。非严格模式让 `out = …` 能写回外层变量。
        val wrapped = """
            (function () {
              var url = $escapedUrl;
              var out = null;
              var __result = eval(${jsString(script)});
              if (out !== null && out !== undefined) return out;
              return __result;
            })()
        """.trimIndent()

        deadline.set(System.nanoTime() + timeoutMs * 1_000_000)
        var cx: Context? = null
        return try {
            cx = factory.enterContext()
            val scope: Scriptable = cx.initSafeStandardObjects(null, true)
            val value = cx.evaluateString(scope, wrapped, "plugin-script", 1, null)
            if (value == null || value == Context.getUndefinedValue()) {
                Result(url = url)
            } else {
                val text = Context.toString(value).trim()
                when {
                    text.isEmpty() -> Result(url = url)
                    text.length > MAX_URL_LENGTH -> Result(error = "脚本返回值过长（>${MAX_URL_LENGTH} 字符）")
                    !text.startsWith("http://") && !text.startsWith("https://") ->
                        Result(error = "脚本返回值不是 http/https 链接，已忽略")
                    else -> Result(url = text)
                }
            }
        } catch (e: Throwable) {
            val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            Result(error = message)
        } finally {
            deadline.remove()
            if (cx != null) runCatching { Context.exit() }
        }
    }

    /** 把任意字符串安全地嵌进 JS 字面量（含 U+2028/U+2029，这两个会破坏 JS 语法）。 */
    private fun jsString(value: String): String {
        val escaped = buildString(value.length + 16) {
            append('"')
            for (ch in value) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\u2028' -> append("\\u2028")
                    '\u2029' -> append("\\u2029")
                    '<' -> append("\\u003c")
                    '>' -> append("\\u003e")
                    else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
            append('"')
        }
        return escaped
    }
}

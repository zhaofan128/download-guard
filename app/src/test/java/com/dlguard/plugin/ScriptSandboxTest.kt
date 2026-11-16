package com.dlguard.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 沙箱不是「跑得通就行」，而是必须证明三件事：
 *  1. 真能改写 URL（能力有效）；
 *  2. 死循环会被中断（不会拖死下载线程）；
 *  3. `Packages` / `java` 不可达（没有逃逸面）。
 */
class ScriptSandboxTest {

    @Test
    fun `out assignment rewrites url`() {
        val result = ScriptSandbox.rewrite(
            "out = url.replace('/small/', '/large/');",
            "https://cdn.example.com/small/photo.jpg"
        )
        assertEquals("https://cdn.example.com/large/photo.jpg", result.url)
    }

    @Test
    fun `last expression is used as result`() {
        val result = ScriptSandbox.rewrite(
            "url.replace('thumb', 'origin')",
            "https://a.test/thumb/1.png"
        )
        assertEquals("https://a.test/origin/1.png", result.url)
    }

    @Test
    fun `builtin tracking cleaner keeps other params`() {
        val script = "out = url.replace(/([?&])(utm_[a-z_]+|spm|from|share_token)=[^&]*/gi, '\$1')" +
            ".replace(/[?&]\$/g, '').replace(/\\?&/g, '?');"
        val result = ScriptSandbox.rewrite(
            script,
            "https://x.test/f.apk?utm_source=wx&id=42&from=timeline"
        )
        assertEquals("https://x.test/f.apk?id=42", result.url)
    }

    @Test
    fun `infinite loop is interrupted`() {
        val started = System.currentTimeMillis()
        val result = ScriptSandbox.rewrite("while (true) {}", "https://a.test/f.apk")
        val elapsed = System.currentTimeMillis() - started
        assertNotNull(result.error)
        assertTrue("超时保护没有生效：$elapsed ms", elapsed < 3_000)
    }

    @Test
    fun `non-http result is rejected`() {
        val result = ScriptSandbox.rewrite("out = 'javascript:alert(1)';", "https://a.test/f.apk")
        assertNull(result.url)
        assertNotNull(result.error)
    }

    @Test
    fun `java packages are not reachable`() {
        val result = ScriptSandbox.rewrite(
            "out = (typeof Packages === 'undefined' && typeof java === 'undefined') ? url : 'http://escape.test';",
            "https://a.test/f.apk"
        )
        assertEquals("https://a.test/f.apk", result.url)
    }

    @Test
    fun `syntax error is reported instead of crashing`() {
        val result = ScriptSandbox.rewrite("out = url.replace(;", "https://a.test/f.apk")
        assertNotNull(result.error)
    }

    @Test
    fun `blank script is a no-op`() {
        val result = ScriptSandbox.rewrite("   ", "https://a.test/f.apk")
        assertEquals("https://a.test/f.apk", result.url)
    }
}

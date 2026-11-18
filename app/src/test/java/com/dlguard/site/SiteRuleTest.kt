package com.dlguard.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 站点规则是「用户可导入的外部文件」，这一组测试盯住三件事：
 *  1. 域名匹配不能误伤（把 A 站点的 Cookie 发给 B 站点就是泄露）；
 *  2. 导入必须有硬上限、坏条目跳过而不是整份报错；
 *  3. 导出绝不能带 Cookie 明文。
 */
class SiteRuleTest {

    // ------------------------------------------------------------ Cookie

    @Test
    fun `Cookie 按 name 合并，同名以直链域为准`() {
        val merged = CookieMerge.merge("sid=page; theme=dark", "sid=direct; lang=zh")
        assertEquals("sid=direct; theme=dark; lang=zh", merged)
    }

    @Test
    fun `Cookie 合并剔除 Set-Cookie 属性和空项`() {
        val merged = CookieMerge.merge(
            "a=1; Path=/; Domain=.example.com; Secure; b=; ; c=3",
            null
        )
        assertEquals("a=1; b=; c=3", merged)
    }

    @Test
    fun `Cookie 全空返回 null`() {
        assertNull(CookieMerge.merge(null, "", "   "))
    }

    @Test
    fun `Cookie 脱敏只保留 name`() {
        val text = CookieMerge.redact("sid=secret; token=abc")
        assertTrue(text.contains("sid=***"))
        assertTrue(text.contains("token=***"))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("abc"))
    }

    // ------------------------------------------------------------ 域名匹配

    @Test
    fun `精确域名只匹配自身`() {
        assertTrue(SiteRules.matchHost("pan.example.com", "pan.example.com"))
        assertFalse(SiteRules.matchHost("pan.example.com", "evil-pan.example.com"))
        assertFalse(SiteRules.matchHost("pan.example.com", "example.com"))
        assertFalse(SiteRules.matchHost("pan.example.com", "pan.example.com.evil.net"))
    }

    @Test
    fun `通配子域包含自身且不越界`() {
        assertTrue(SiteRules.matchHost("*.example.com", "example.com"))
        assertTrue(SiteRules.matchHost("*.example.com", "pan.example.com"))
        assertTrue(SiteRules.matchHost("*.example.com", "a.b.example.com"))
        assertFalse(SiteRules.matchHost("*.example.com", "notexample.com"))
        assertFalse(SiteRules.matchHost("*.example.com", "example.com.evil.net"))
    }

    @Test
    fun `匹配忽略大小写与规则里手写的端口`() {
        assertTrue(SiteRules.matchHost("Pan.Example.COM", "pan.example.com"))
        assertTrue(SiteRules.matchHost("pan.example.com:8443", "pan.example.com"))
    }

    @Test
    fun `通配星号匹配全部`() {
        assertTrue(SiteRules.matchHost("*", "anything.example"))
    }

    // ------------------------------------------------------------ 规则选择

    @Test
    fun `优先级高者胜出`() {
        val low = SiteRule(id = "low", hosts = listOf("*.example.com"), priority = 10)
        val high = SiteRule(id = "high", hosts = listOf("pan.example.com"), priority = 200)
        assertEquals("high", SiteRules.pick(listOf(low, high), "https://pan.example.com/x.zip")?.id)
    }

    @Test
    fun `同优先级时更具体的域名胜出`() {
        val broad = SiteRule(id = "broad", hosts = listOf("*.example.com"), priority = 100)
        val exact = SiteRule(id = "exact", hosts = listOf("pan.example.com"), priority = 100)
        assertEquals("exact", SiteRules.pick(listOf(broad, exact), "https://pan.example.com/x.zip")?.id)
    }

    @Test
    fun `禁用的规则不参与匹配`() {
        val rule = SiteRule(id = "off", hosts = listOf("pan.example.com"), enabled = false)
        assertNull(SiteRules.pick(listOf(rule), "https://pan.example.com/x.zip"))
    }

    @Test
    fun `没有规则时返回 null`() {
        assertNull(SiteRules.pick(emptyList(), "https://pan.example.com/x.zip"))
    }

    // ------------------------------------------------------------ JSON

    @Test
    fun `导出再导入保持关键字段`() {
        val rule = SiteRule(
            id = "pan-demo",
            name = "示例网盘",
            priority = 150,
            hosts = listOf("pan.example.com", "*.example.net"),
            refererMode = RefererMode.PAGE,
            cookieMode = CookieMode.MERGE,
            userAgent = "CustomUA/1.0",
            headers = mapOf("Accept-Language" to "zh-CN"),
            concurrency = 12,
            fileNameTemplate = "{name}-{date}.{ext}",
            sidecarHash = false,
            openWhenDone = true
        )
        val parsed = SiteRules.parse(SiteRules.toJson(listOf(rule)))
        assertNull(parsed.error)
        assertEquals(1, parsed.rules.size)
        val back = parsed.rules.first()
        assertEquals(rule.id, back.id)
        assertEquals(rule.hosts, back.hosts)
        assertEquals(rule.priority, back.priority)
        assertEquals(rule.userAgent, back.userAgent)
        assertEquals(rule.headers, back.headers)
        assertEquals(rule.concurrency, back.concurrency)
        assertEquals(rule.fileNameTemplate, back.fileNameTemplate)
        assertEquals(false, back.sidecarHash)
        assertEquals(true, back.openWhenDone)
    }

    @Test
    fun `导出默认抹掉自定义 Cookie 的值`() {
        val rule = SiteRule(
            id = "secret",
            hosts = listOf("pan.example.com"),
            cookieMode = CookieMode.CUSTOM,
            cookieValue = "sid=SUPER_SECRET"
        )
        val exported = SiteRules.toJson(listOf(rule))
        assertFalse(exported.contains("SUPER_SECRET"))
        // 本机持久化才保留
        assertTrue(SiteRules.toJson(listOf(rule), includeSecrets = true).contains("SUPER_SECRET"))
    }

    @Test
    fun `接受裸数组与简写模式`() {
        val json = """
            [
              {"id":"a","hosts":["a.com"],"referer":"direct","cookie":"none"},
              {"id":"b","hosts":["b.com"],"referer":{"mode":"custom","value":"https://b.com/"}}
            ]
        """.trimIndent()
        val parsed = SiteRules.parse(json)
        assertNull(parsed.error)
        assertEquals(2, parsed.rules.size)
        assertEquals(RefererMode.DIRECT, parsed.rules[0].refererMode)
        assertEquals(CookieMode.NONE, parsed.rules[0].cookieMode)
        assertEquals("https://b.com/", parsed.rules[1].refererValue)
    }

    @Test
    fun `坏条目跳过而不是整份失败`() {
        val json = """
            [
              {"id":"ok","hosts":["ok.com"]},
              {"id":"","hosts":["bad.com"]},
              {"id":"bad-host","hosts":["http://evil.com/x"]},
              {"id":"dup","hosts":["dup.com"]},
              {"id":"dup","hosts":["dup2.com"]}
            ]
        """.trimIndent()
        val parsed = SiteRules.parse(json)
        assertNull(parsed.error)
        assertEquals(2, parsed.rules.size)
        assertEquals(3, parsed.skipped)
    }

    @Test
    fun `非法 JSON 给出可读错误`() {
        assertNotNull(SiteRules.parse("not json").error)
        assertNotNull(SiteRules.parse("{\"rules\":{}}").error)
        assertNotNull(SiteRules.parse("[").error)
    }

    @Test
    fun `高版本 schema 明确拒绝`() {
        val error = SiteRules.parse("""{"schema":"dlguard.site-rules","version":99,"rules":[]}""").error
        assertNotNull(error)
        assertTrue(error!!.contains("99"))
    }

    @Test
    fun `未知 schema 拒绝`() {
        assertNotNull(SiteRules.parse("""{"schema":"other","rules":[]}""").error)
    }

    // ------------------------------------------------------------ 请求头清洗

    @Test
    fun `拒绝会破坏下载协议的头与 CRLF 注入`() {
        val cleaned = SiteRules.sanitizeHeaders(
            mapOf(
                "Accept-Language" to "zh-CN",
                "Range" to "bytes=0-1",
                "Host" to "evil.com",
                "X-Bad" to "a\r\nInjected: 1",
                "Bad Name" to "x",
                "Cookie" to "sid=1"
            )
        )
        assertEquals(setOf("Accept-Language", "Cookie"), cleaned.keys)
    }

    @Test
    fun `请求头文本解析容忍注释和空行`() {
        val headers = SiteRules.parseHeaderLines(
            """
            # 注释
            Accept-Language: zh-CN

            X-Token: abc:def
            """.trimIndent()
        )
        assertEquals("zh-CN", headers["Accept-Language"])
        assertEquals("abc:def", headers["X-Token"])
        assertEquals(2, headers.size)
    }

    // ------------------------------------------------------------ 文件名模板

    @Test
    fun `文件名模板占位符`() {
        val name = SiteRules.applyTemplate(
            "{name}-{date}-{host}.{ext}",
            "app.apk",
            "https://dl.example.com/app.apk",
            "示例"
        )
        assertTrue(name.startsWith("app-"))
        assertTrue(name.endsWith("-dl.example.com.apk"))
        assertFalse(name.contains("{name}"))
    }

    @Test
    fun `未知占位符原样保留`() {
        assertEquals("app-{unknown}.apk", SiteRules.applyTemplate("{name}-{unknown}.{ext}", "app.apk", "https://a.com/app.apk"))
    }

    @Test
    fun `无扩展名文件模板不产生尾点`() {
        val name = SiteRules.applyTemplate("{name}-{date}", "README", "https://a.com/README")
        assertFalse(name.endsWith("."))
        assertTrue(name.startsWith("README-"))
    }

    // ------------------------------------------------------------ JSON 解析器边界

    @Test
    fun `深层嵌套被拒绝而不是栈溢出`() {
        val deep = "[".repeat(80) + "]".repeat(80)
        assertNotNull(runCatching { JsonLite.parse(deep) }.exceptionOrNull())
    }

    @Test
    fun `超长内容被拒绝`() {
        val huge = "{\"a\":\"" + "x".repeat(JsonLite.MAX_LENGTH) + "\"}"
        assertNotNull(runCatching { JsonLite.parse(huge) }.exceptionOrNull())
    }
}

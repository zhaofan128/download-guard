package com.dlguard.site

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把「站点规则 + 页面上下文」翻译成一次请求实际要带的头和参数。
 *
 * Cookie 读取要点：
 *  - [CookieManager] 必须在有 Looper 的线程上首次初始化，所以统一切到 Main；
 *  - 任何异常都吞掉返回 null——读不到登录态是常态（用户没登录 / WebView 未初始化），
 *    不能因此让整个下载失败；
 *  - 读到的 Cookie **只放进 RequestProfile**，不写日志、不进任务摘要。
 */
class SiteRuleResolver(private val store: SiteRuleStore) {

    /**
     * @param url 最终要下载的直链
     * @param pageUrl 嗅探页 / 分享来源页；决定了「页面域 Cookie」取哪个域
     * @param pageCookie 调用方已经拿到的页面 Cookie（嗅探页 WebView 里现成的），传了就不再重复读
     * @param explicit 用户手动填写的请求参数，优先级最高
     */
    suspend fun resolve(
        url: String,
        pageUrl: String? = null,
        pageCookie: String? = null,
        explicit: RequestProfile = RequestProfile()
    ): RequestProfile {
        val rules = runCatching { store.snapshot() }.getOrDefault(emptyList())
        val rule = SiteRules.pick(rules, url)
        return resolveWith(rule, url, pageUrl, pageCookie, explicit)
    }

    /** 规则编辑页的「测试」：不落库，直接看一条规则会产出什么。 */
    suspend fun preview(
        rule: SiteRule,
        url: String,
        pageUrl: String? = null,
        pageCookie: String? = null
    ): RequestProfile = resolveWith(rule, url, pageUrl, pageCookie, RequestProfile())

    private suspend fun resolveWith(
        rule: SiteRule?,
        url: String,
        pageUrl: String?,
        pageCookie: String?,
        explicit: RequestProfile
    ): RequestProfile {
        val origin = SiteRules.originOf(url)
        val page = pageUrl?.takeIf { it.isNotBlank() }

        // 用户可能在「额外 Header」里直接写了 Referer: / Cookie: / User-Agent:，
        // 这三项有专门通道，先提升出来，最后再从 headers 里剔除，避免同一个头发两次。
        fun headerOf(name: String): String? = explicit.headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value?.trim()?.takeIf { it.isNotEmpty() }

        val referer = when {
            !explicit.referer.isNullOrBlank() -> explicit.referer.trim()
            headerOf("Referer") != null -> headerOf("Referer")
            rule == null -> null
            rule.refererMode == RefererMode.PAGE -> page ?: origin
            rule.refererMode == RefererMode.DIRECT -> url
            rule.refererMode == RefererMode.CUSTOM -> rule.refererValue.trim().takeIf { it.isNotBlank() }
            else -> null
        }

        val cookie = if (!explicit.cookie.isNullOrBlank()) {
            // 用户显式给的（手填 / 嗅探页合并好的）：原样使用，不擅自扩大凭据使用范围
            explicit.cookie.trim()
        } else if (headerOf("Cookie") != null) {
            headerOf("Cookie")
        } else when (rule?.cookieMode) {
            CookieMode.CUSTOM -> rule.cookieValue.trim().takeIf { it.isNotBlank() }
            CookieMode.NONE -> null
            CookieMode.PAGE -> pageCookie?.takeIf { it.isNotBlank() } ?: readCookie(page)
            CookieMode.DIRECT -> readCookie(url)
            CookieMode.MERGE -> CookieMerge.merge(
                pageCookie?.takeIf { it.isNotBlank() } ?: readCookie(page),
                readCookie(url)
            )
            else -> null
        }

        val userAgent = explicit.userAgent?.takeIf { it.isNotBlank() }
            ?: headerOf("User-Agent")
            ?: rule?.userAgent?.takeIf { it.isNotBlank() }

        val mergedHeaders = LinkedHashMap<String, String>()
        rule?.headers?.let { mergedHeaders.putAll(it) }
        explicit.headers.forEach { (k, v) -> mergedHeaders[k] = v }
        // 三个专用头已经从上面的逻辑生效，不能再作为普通头重复发送
        mergedHeaders.keys.removeAll {
            it.equals("Referer", true) || it.equals("Cookie", true) || it.equals("User-Agent", true)
        }

        return RequestProfile(
            ruleId = rule?.id,
            ruleName = rule?.name,
            referer = referer?.takeIf { it.isNotBlank() },
            cookie = cookie?.takeIf { it.isNotBlank() },
            userAgent = userAgent,
            headers = SiteRules.sanitizeHeaders(mergedHeaders),
            concurrency = explicit.concurrency.takeIf { it > 0 } ?: (rule?.concurrency ?: 0),
            fileNameTemplate = explicit.fileNameTemplate.takeIf { it.isNotBlank() }
                ?: rule?.fileNameTemplate.orEmpty(),
            sidecarHash = explicit.sidecarHash ?: rule?.sidecarHash,
            openWhenDone = explicit.openWhenDone ?: rule?.openWhenDone
        )
    }

    private suspend fun readCookie(target: String?): String? {
        if (target.isNullOrBlank()) return null
        return runCatching {
            withContext(Dispatchers.Main.immediate) {
                CookieManager.getInstance().getCookie(target)
            }
        }.getOrNull()
    }
}

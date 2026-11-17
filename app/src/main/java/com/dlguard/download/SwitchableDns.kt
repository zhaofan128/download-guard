package com.dlguard.download

import okhttp3.Dns
import java.net.InetAddress

/**
 * 可开关的双栈竞速 DNS。
 *
 * 「IPv4/IPv6 双栈竞速」在插件页是一个可见开关，但早前它只存在于插件清单里：
 * 没有任何代码读取 `ID_DUAL_STACK`，`RacingDns` 永远开着 —— 关掉开关毫无效果，
 * 属于「装饰性开关」。这里把开关接到真实链路上，并且用 lambda 延迟读取，
 * 避免 AppContainer 初始化时 `pluginManager` 与 `downloader` 互相依赖成环。
 */
class SwitchableDns(
    private val enabled: () -> Boolean,
    private val racing: Dns = RacingDns(),
    private val fallback: Dns = Dns.SYSTEM
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val on = runCatching { enabled() }.getOrDefault(true)
        return if (on) racing.lookup(hostname) else fallback.lookup(hostname)
    }
}

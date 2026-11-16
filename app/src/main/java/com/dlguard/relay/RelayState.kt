package com.dlguard.relay

/**
 * 接力模式的全局状态。
 *
 * 读取频率很高（每个 HTTP 请求的 ProxySelector 都会读一次），所以用 volatile
 * 而不是 Flow：这里只需要「最新值」，不需要事件流。
 */
object RelayState {

    /** 作为客户端：所有下载走 [host]:[port] 的网关。 */
    @Volatile
    var clientEnabled: Boolean = false

    @Volatile
    var host: String = ""

    @Volatile
    var port: Int = RelayServer.DEFAULT_PORT

    @Volatile
    var token: String = ""

    /** 作为网关：本机正在共享网络。 */
    @Volatile
    var gatewayRunning: Boolean = false

    @Volatile
    var gatewayPort: Int = 0

    @Volatile
    var gatewayToken: String = ""

    @Volatile
    var lastError: String? = null

    /** 客户端是否真的应该走代理。false 时 ProxySelector 返回 NO_PROXY。 */
    val active: Boolean get() = clientEnabled && host.isNotBlank()
}

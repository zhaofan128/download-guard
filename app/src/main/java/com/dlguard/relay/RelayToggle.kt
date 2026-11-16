package com.dlguard.relay

/**
 * 接力开关的状态决策。
 *
 * 这里刻意「不拦截」任何一次写入。开关是受控组件（checked 来自 DataStore），
 * 如果开启时因为网关地址为空而拒绝写入，checked 就不会变化，点击会被反复算成
 * 「开启」，用户会陷入「关不掉、一直弹先填地址」的死循环。
 *
 * 地址缺失只影响提示文案，不影响开关状态；是否真的走代理由 [RelayState.active]
 * 决定——host 为空时它返回 false，仍然是本机直连，不会因为开关显示开着就劫持下载。
 */
object RelayToggle {

    /**
     * 是否把这次点击写进 DataStore。
     *
     * 恒为 true 是关键不变量：关闭必须无条件生效，开启也必须写入，
     * 否则受控开关的 checked 与用户看到的状态会永久分叉。
     */
    fun shouldPersist(value: Boolean): Boolean = true

    /** 开关切换后展示给用户的提示。 */
    fun message(value: Boolean, host: String, port: Int): String = when {
        !value -> "接力已关闭：恢复本机直连"
        host.isBlank() -> "接力已开启，但尚未填写网关地址：当前仍走本机网络，请先填写"
        else -> "接力已开启：下载走 ${host.trim()}:$port"
    }
}

package com.dlguard.download

/**
 * 按设备内存给出合理的并发上限。
 *
 * 每个 worker 是「一条阻塞 socket + 一个 256KB 缓冲区 + 一个独立 OkHttpClient 连接池」，
 * 256 个 worker 光缓冲区就 64MB，低端机上会直接被 LMK 干掉，或者频繁 GC 掉速。
 * 之前所有设备都用同一个 256，属于纸面参数。
 *
 * 分档原则：缓冲区总量控制在可用堆的 1/4 以内，同时留出 OkHttp 连接池与解压的开销。
 */
object DeviceBudget {

    /** 每个 worker 的读缓冲（与 ResumableDownloader.BUFFER 一致）。 */
    const val BUFFER_BYTES = 256L * 1024

    fun maxConnections(maxMemoryBytes: Long): Int {
        val byMemory = when {
            maxMemoryBytes <= 128L * 1024 * 1024 -> 32
            maxMemoryBytes <= 256L * 1024 * 1024 -> 64
            maxMemoryBytes <= 512L * 1024 * 1024 -> 128
            else -> ResumableDownloader.MAX_CONNECTIONS
        }
        return byMemory.coerceIn(8, ResumableDownloader.MAX_CONNECTIONS)
    }

    /** 运行时版本，供下载中心直接调用。 */
    fun maxConnections(): Int = maxConnections(Runtime.getRuntime().maxMemory())
}

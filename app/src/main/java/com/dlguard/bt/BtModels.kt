package com.dlguard.bt

/**
 * BT 任务的纯数据模型。
 *
 * 刻意不引用任何 libtorrent4j / Android 类型：这样状态映射、限速格式化、
 * 分享率计算这些逻辑可以在 JVM 上单测，不需要加载 native 库。
 */

/** 与 libtorrent4j 的 TorrentStatus.State 解耦后的展示状态。 */
enum class BtState {
    /** 磁力刚加入，还在通过 DHT/PEX 找元数据。 */
    FETCHING_METADATA,
    /** 校验已下载分片。 */
    CHECKING,
    /** 正在下载。 */
    DOWNLOADING,
    /** 分片收齐，正在收尾/做种。 */
    SEEDING,
    /** 用户暂停。 */
    PAUSED,
    /** 出错（DHT 失败、种子损坏等），message 给出原因。 */
    ERROR,
    /** 未知/尚未上报。 */
    UNKNOWN;

    val label: String get() = when (this) {
        FETCHING_METADATA -> "获取元数据"
        CHECKING -> "校验文件"
        DOWNLOADING -> "下载中"
        SEEDING -> "做种中"
        PAUSED -> "已暂停"
        ERROR -> "出错"
        UNKNOWN -> "连接中"
    }
}

/**
 * 一个 BT 任务的快照。由引擎定时从 TorrentHandle.status() 映射而来。
 */
data class BtTask(
    /** 稳定 id：v1 info hash（40 位 hex）。 */
    val id: String,
    /** 显示名；磁力元数据未到时用 dn 参数或 hash 前缀。 */
    val name: String,
    /** 原始 magnet URI 或 .torrent 路径，用于重启后恢复。 */
    val source: String,
    val isMagnet: Boolean,
    val state: BtState = BtState.UNKNOWN,
    val progress: Float = 0f,
    val downloaded: Long = 0,
    val total: Long = 0,
    val downloadRate: Long = 0,
    val uploadRate: Long = 0,
    val totalUploaded: Long = 0,
    /** 已连接种子数（含 web seed）。 */
    val seeds: Int = 0,
    /** 已连接下载者数。 */
    val peers: Int = 0,
    /** DHT 节点数（全局，展示在每个任务上）。 */
    val dhtNodes: Int = 0,
    /** 分享率 = 上传 / 下载；下载为 0 时用 0。 */
    val shareRatio: Float = 0f,
    val message: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    /** 该任务是否已被用户暂停（用于区分 SEEDING 与手动暂停）。 */
    val paused: Boolean = false
)

/** 从 libtorrent 状态字符串映射到 UI 状态；独立出来便于单测。 */
internal fun mapState(swigName: String?, paused: Boolean, isSeeding: Boolean): BtState {
    if (paused) return BtState.PAUSED
    if (isSeeding) return BtState.SEEDING
    return when (swigName) {
        "checking_files", "checking_resume_data" -> BtState.CHECKING
        "downloading_metadata" -> BtState.FETCHING_METADATA
        "downloading" -> BtState.DOWNLOADING
        "finished", "seeding" -> BtState.SEEDING
        else -> BtState.UNKNOWN
    }
}

/** 分享率：上传字节 / 下载字节，下载为 0 时返回 0。 */
internal fun shareRatio(uploaded: Long, downloaded: Long): Float =
    if (downloaded <= 0) 0f else uploaded.toFloat() / downloaded.toFloat()

/** 把 SQLite 风格的字节数格式化成 `1.2 GB` 这种展示串。 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${value.toLong()} ${units[unit]}"
    else String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
}

/** 把字节每秒格式化成 `3.4 MB/s`。 */
fun formatRate(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0) "0 B/s" else "${formatBytes(bytesPerSecond)}/s"

package com.dlguard.integrity

/** 六类明确结论，UI 与隔离通知都直接消费该枚举。 */
enum class Verdict(val label: String, val healthy: Boolean) {
    OK("完好", true),
    TRUNCATED("截断", false),
    CRC_ERROR("CRC 错误", false),
    NOT_A_ZIP("非 ZIP 结构", false),
    SIGNATURE_ERROR("签名损坏", false),
    PARSE_ERROR("APK 解析失败", false),
    SIZE_MISMATCH("大小不符", false),
    IO_ERROR("读取失败", false),
    UNKNOWN("未知异常", false)
}

data class IntegrityResult(
    val verdict: Verdict,
    val detail: String,
    val sizeBytes: Long,
    val declaredSize: Long? = null,
    val entriesChecked: Int = 0,
    val uncompressedBytes: Long = 0,
    val apkPackageName: String? = null,
    val signatureSchemes: String? = null,
    val eocdOffset: Long = -1,
    val elapsedMs: Long = 0
) {
    val healthy: Boolean get() = verdict.healthy

    /** 一句话结论，用于通知标题副文案。 */
    fun summary(): String = if (healthy) {
        "校验通过 · 共 ${entriesChecked} 个条目"
    } else {
        "校验失败（${verdict.label}）"
    }
}

internal data class EocdInfo(
    val eocdOffset: Long,
    val centralDirectoryOffset: Long,
    val centralDirectorySize: Long,
    val entryCount: Int,
    val commentLength: Int,
    val zip64: Boolean
)

internal sealed class EocdScan {
    data class Found(val info: EocdInfo) : EocdScan()
    data class Corrupted(val detail: String) : EocdScan()
    data object NotFound : EocdScan()
}

internal data class EntryCheck(
    val entries: Int = 0,
    val uncompressedBytes: Long = 0,
    val errorVerdict: Verdict? = null,
    val detail: String = "",
    val firstBadEntry: String? = null
)

package com.dlguard.integrity

/** 结论的展示基调：绿 / 黄 / 红。健康不等于「能装」，黄色专门留给「文件没坏但系统不认」。 */
enum class Tone { OK, WARN, BAD }

/** 七类明确结论，UI 与隔离通知都直接消费该枚举。 */
enum class Verdict(val label: String, val healthy: Boolean, val tone: Tone) {
    OK("完好", true, Tone.OK),
    /** 用户明确确认信任（加壳包、企业定制包等结构校验过不了但确实可用的文件）。 */
    TRUSTED("已信任", true, Tone.OK),
    /**
     * 字节层面完好（EOCD、全部条目 CRC、AndroidManifest 均可解析），
     * 但系统 PackageManager 拒绝解析，典型原因是 Android 14 起禁止 targetSdkVersion < 23 的应用。
     *
     * 这种包不该被当成「损坏」隔离：文件是原样的，用户在老设备或别的工具里仍能安装。
     * healthy = true，只降级为提醒，不参与「损坏」统计。
     */
    BLOCKED("系统拒装", true, Tone.WARN),
    TRUNCATED("截断", false, Tone.BAD),
    CRC_ERROR("CRC 错误", false, Tone.BAD),
    NOT_A_ZIP("非 ZIP 结构", false, Tone.BAD),
    SIGNATURE_ERROR("签名损坏", false, Tone.BAD),
    PARSE_ERROR("APK 解析失败", false, Tone.BAD),
    SIZE_MISMATCH("大小不符", false, Tone.BAD),
    IO_ERROR("读取失败", false, Tone.BAD),
    UNKNOWN("未知异常", false, Tone.BAD)
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
    fun summary(): String = when {
        verdict == Verdict.BLOCKED ->
            "结构完整 · 系统拒绝解析（文件未动，未隔离）\n本机系统会拒绝安装此包（常见原因是 targetSdk 过低），文件本身没有损坏"
        healthy -> "校验通过 · 共 ${entriesChecked} 个条目"
        else -> "校验失败（${verdict.label}）"
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

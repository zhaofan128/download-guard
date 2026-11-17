package com.dlguard.integrity

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.cert.CertificateFactory
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/**
 * 多级递进式安装包完整性校验器（全部真实执行，不做任何结果伪造）：
 *  L1 大小比对（与 DownloadManager / MediaStore 声明的字节数）
 *  L2 EOCD 与中央目录结构（识别截断、偏移错误）
 *  L3 全 entry CRC32 + 解压字节数（识别数据区损坏）
 *  L4 APK 解析（getPackageArchiveInfo）
 *  L5 签名块可解析性与证书链解析
 */
class ApkIntegrityChecker(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    suspend fun check(
        file: File,
        declaredSize: Long? = null,
        /**
         * 逻辑文件名（用户最终看到的那个名字）。
         *
         * 下载器边下边校验，磁盘上是 `app.apk.part`；如果按物理文件名取扩展名，
         * 拿到的是 `part`，L4 APK 解析和 L5 签名校验会被整段跳过 —— 界面照样显示
         * 「校验通过」，但签名一次都没验过。所以类型判定必须用逻辑名，物理名只用于读字节。
         */
        logicalName: String? = null,
        onStage: ((String) -> Unit)? = null
    ): IntegrityResult = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        val displayName = logicalName?.takeIf { it.isNotBlank() } ?: file.name

        fun finish(
            verdict: Verdict,
            detail: String,
            entries: Int = 0,
            uncompressed: Long = 0,
            packageName: String? = null,
            schemes: String? = null,
            eocdOffset: Long = -1
        ) = IntegrityResult(
            verdict = verdict,
            detail = detail,
            sizeBytes = if (file.exists()) file.length() else 0,
            declaredSize = declaredSize,
            entriesChecked = entries,
            uncompressedBytes = uncompressed,
            apkPackageName = packageName,
            signatureSchemes = schemes,
            eocdOffset = eocdOffset,
            elapsedMs = SystemClock.elapsedRealtime() - startedAt
        )

        if (!file.exists() || !file.isFile) {
            return@withContext finish(Verdict.IO_ERROR, "文件不存在或不是普通文件：${file.absolutePath}")
        }
        val actualSize = file.length()
        if (actualSize <= 0L) {
            return@withContext finish(Verdict.TRUNCATED, "文件长度为 0，下载未写入任何数据")
        }

        // L1 大小校验
        var sizeNote: String? = null
        if (declaredSize != null && declaredSize > 0L) {
            when {
                actualSize < declaredSize -> return@withContext finish(
                    Verdict.TRUNCATED,
                    "实际 $actualSize 字节，小于声明 $declaredSize 字节，差 ${declaredSize - actualSize} 字节"
                )
                actualSize > declaredSize -> sizeNote =
                    "实际 $actualSize 字节，大于声明 $declaredSize 字节，多 ${actualSize - declaredSize} 字节"
            }
        }

        onStage?.invoke("检查 ZIP 中央目录")
        // 视频 / 音频 / PDF / 图片本来就不是 ZIP，走 EOCD 只会白白判成「非 ZIP 结构」。
        // 过去这里少了这道分流，一个完全正常的 mp4 会被标成「校验失败」，比不校验更糟。
        if (!IntegrityPolicy.requiresArchiveValidation(displayName, ZipInspector.startsWithZipHeader(file))) {
            return@withContext finish(
                Verdict.OK,
                "非压缩格式，已核对大小（$actualSize 字节）"
            )
        }
        // L2 EOCD
        val eocdScan = ZipInspector.scanEocd(file)
        when (eocdScan) {
            is EocdScan.Corrupted -> return@withContext finish(Verdict.TRUNCATED, eocdScan.detail)
            EocdScan.NotFound -> {
                val looksZip = ZipInspector.startsWithZipHeader(file)
                return@withContext finish(
                    if (looksZip) Verdict.TRUNCATED else Verdict.NOT_A_ZIP,
                    if (looksZip) "文件以 PK 头开始但尾部缺少 EOCD 记录，判定为下载截断" else "文件头不是 PK，非 ZIP/APK 结构"
                )
            }
            is EocdScan.Found -> Unit
        }
        val eocd = (eocdScan as EocdScan.Found).info
        coroutineContext.ensureActive()

        // L3 全条目 CRC32
        onStage?.invoke("校验 ${eocd.entryCount} 个条目 CRC")
        val entryCheck = ZipInspector.verifyEntries(file)
        if (entryCheck.errorVerdict != null) {
            return@withContext finish(
                entryCheck.errorVerdict,
                entryCheck.detail,
                entryCheck.entries,
                entryCheck.uncompressedBytes,
                eocdOffset = eocd.eocdOffset
            )
        }
        if (eocd.entryCount > 0 && entryCheck.entries < eocd.entryCount && !eocd.zip64) {
            return@withContext finish(
                Verdict.TRUNCATED,
                "中央目录声明 ${eocd.entryCount} 个条目，实际只能读取 ${entryCheck.entries} 个",
                entryCheck.entries,
                entryCheck.uncompressedBytes,
                eocdOffset = eocd.eocdOffset
            )
        }
        coroutineContext.ensureActive()

        // L4 / L5 APK 解析与签名
        if (IntegrityTarget.isInstallerPackage(displayName)) {
            onStage?.invoke("解析 APK 与签名")
            val probe = if (IntegrityTarget.isSingleApk(displayName)) {
                probeApk(file)
            } else {
                probeContainer(file)
            }
            val schemes = detectSignatureSchemes(file, eocd)
            if (probe.verdict != Verdict.OK) {
                return@withContext finish(
                    probe.verdict,
                    probe.detail,
                    entryCheck.entries,
                    entryCheck.uncompressedBytes,
                    probe.packageName,
                    schemes,
                    eocd.eocdOffset
                )
            }
            // APK 单包：追加 v2 分块摘要 + 验签（v1-only / 算法不支持时如实标注「未验证」）
            val v2 = if (IntegrityTarget.isSingleApk(displayName)) ApkSignatureVerifier.verify(file, eocd) else null
            if (v2 is ApkSignatureVerifier.Result.Failed) {
                return@withContext finish(
                    Verdict.SIGNATURE_ERROR,
                    "签名校验失败：${v2.reason}",
                    entryCheck.entries,
                    entryCheck.uncompressedBytes,
                    probe.packageName,
                    schemes,
                    eocd.eocdOffset
                )
            }
            val schemeText = describeSchemes(schemes, v2)
            if (sizeNote != null) {
                return@withContext finish(
                    Verdict.SIZE_MISMATCH,
                    "$sizeNote；ZIP 与 APK 结构本身可解析，请核对来源",
                    entryCheck.entries,
                    entryCheck.uncompressedBytes,
                    probe.packageName,
                    schemeText,
                    eocd.eocdOffset
                )
            }
            return@withContext finish(
                Verdict.OK,
                "ZIP 结构完整，${entryCheck.entries} 个条目 CRC 全部匹配，APK 可解析，签名（$schemeText）正常",
                entryCheck.entries,
                entryCheck.uncompressedBytes,
                probe.packageName,
                schemeText,
                eocd.eocdOffset
            )
        }

        if (sizeNote != null) {
            return@withContext finish(
                Verdict.SIZE_MISMATCH,
                "$sizeNote；ZIP 结构完整，请核对来源",
                entryCheck.entries,
                entryCheck.uncompressedBytes,
                eocdOffset = eocd.eocdOffset
            )
        }
        return@withContext finish(
            Verdict.OK,
            "ZIP 结构完整，${entryCheck.entries} 个条目 CRC 全部匹配",
            entryCheck.entries,
            entryCheck.uncompressedBytes,
            eocdOffset = eocd.eocdOffset
        )
    }

    private data class ApkProbe(
        val verdict: Verdict,
        val detail: String,
        val packageName: String? = null
    )

    @Suppress("DEPRECATION")
    private fun probeApk(file: File): ApkProbe {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info: PackageInfo? = try {
            pm.getPackageArchiveInfo(file.absolutePath, flags)
        } catch (e: Exception) {
            return ApkProbe(
                Verdict.PARSE_ERROR,
                "PackageManager 解析抛出异常：${e.javaClass.simpleName} ${e.message ?: ""}"
            )
        }
        if (info == null) {
            return ApkProbe(Verdict.PARSE_ERROR, "getPackageArchiveInfo() 返回 null，AndroidManifest 无法解析")
        }
        if (info.packageName.isNullOrBlank()) {
            return ApkProbe(Verdict.PARSE_ERROR, "解析结果缺少 packageName")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo
                ?: return ApkProbe(Verdict.SIGNATURE_ERROR, "APK 未签名或签名块无法解析（signingInfo = null）", info.packageName)
            val signers = signingInfo.apkContentsSigners
            if (signers.isNullOrEmpty()) {
                return ApkProbe(Verdict.SIGNATURE_ERROR, "签名信息存在但签名者列表为空", info.packageName)
            }
            val factory = runCatching { CertificateFactory.getInstance("X.509") }.getOrNull()
                ?: return ApkProbe(Verdict.SIGNATURE_ERROR, "系统缺少 X.509 CertificateFactory，无法解析证书", info.packageName)
            signers.forEachIndexed { index, signature ->
                val bytes = signature.toByteArray()
                if (bytes.isEmpty()) {
                    return ApkProbe(Verdict.SIGNATURE_ERROR, "第 ${index + 1} 个签名证书字节为空", info.packageName)
                }
                try {
                    ByteArrayInputStream(bytes).use { factory.generateCertificate(it) }
                } catch (e: Exception) {
                    return ApkProbe(Verdict.SIGNATURE_ERROR, "签名证书 X.509 解析失败：${e.message}", info.packageName)
                }
            }
        } else {
            val signatures = info.signatures
            if (signatures.isNullOrEmpty()) {
                return ApkProbe(Verdict.SIGNATURE_ERROR, "APK 未签名（老系统签名数组为空）", info.packageName)
            }
        }
        return ApkProbe(Verdict.OK, "APK 可解析", info.packageName)
    }

    /**
     * .apks / .xapk 容器：
     *  1. 解出 base.apk 做真实 APK 解析；
     *  2. 对 base.apk 跑 v2 摘要 + 验签；
     *  3. 依次解出其余 split，比对签名证书集合是否与 base 完全一致（v1 只能验单包，这一步补上 split 一致性）。
     */
    @Suppress("DEPRECATION")
    private fun probeContainer(file: File): ApkProbe {
        val temp = File(context.cacheDir, "probe_${System.currentTimeMillis()}_${file.name.hashCode()}.apk")
        return try {
            val extracted = ZipFile(file).use { zip ->
                var candidate: java.util.zip.ZipEntry? = null
                var fallback: java.util.zip.ZipEntry? = null
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val lower = entry.name.lowercase()
                    if (!lower.endsWith(".apk")) continue
                    if (lower == "base.apk" || lower.endsWith("/base.apk")) {
                        candidate = entry
                        break
                    }
                    if (fallback == null) fallback = entry
                }
                val target = candidate ?: fallback
                if (target == null) {
                    return ApkProbe(Verdict.PARSE_ERROR, "容器内没有 .apk 条目，无法作为安装包使用")
                }
                zip.getInputStream(target).use { input ->
                    temp.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
                }
                target.name
            }
            val probe = probeApk(temp)
            if (probe.verdict != Verdict.OK) {
                return probe.copy(detail = "容器内 $extracted：${probe.detail}")
            }
            val tempEocd = (ZipInspector.scanEocd(temp) as? EocdScan.Found)?.info
            val baseVerify = tempEocd?.let { ApkSignatureVerifier.verify(temp, it) }
            if (baseVerify is ApkSignatureVerifier.Result.Failed) {
                return ApkProbe(
                    Verdict.SIGNATURE_ERROR,
                    "容器内 $extracted 签名校验失败：${baseVerify.reason}",
                    probe.packageName
                )
            }
            val mismatch = ApkSignatureVerifier.containerSignerMismatch(file, pm, context.cacheDir)
            if (mismatch != null) {
                return ApkProbe(Verdict.SIGNATURE_ERROR, "容器内 split 签名不一致：$mismatch", probe.packageName)
            }
            val note = when (baseVerify) {
                is ApkSignatureVerifier.Result.Verified -> "v2 摘要+验签通过（${baseVerify.algorithm}）"
                is ApkSignatureVerifier.Result.Unsupported -> "v2 未验证（${baseVerify.reason}）"
                else -> "v2 未验证"
            }
            probe.copy(detail = "容器内 $extracted：${probe.detail}；$note；split 签名一致")
        } catch (e: Exception) {
            ApkProbe(Verdict.PARSE_ERROR, "解出容器内 APK 失败：${e.message}")
        } finally {
            temp.delete()
        }
    }

    private fun describeSchemes(schemes: String, v2: ApkSignatureVerifier.Result?): String = buildString {
        append(schemes)
        when (v2) {
            is ApkSignatureVerifier.Result.Verified -> append("；v2 摘要+验签通过（${v2.algorithm}）")
            is ApkSignatureVerifier.Result.Unsupported -> append("；v2 未验证（${v2.reason}）")
            is ApkSignatureVerifier.Result.Failed -> append("；v2 校验失败")
            null -> Unit
        }
    }

    /** 仅读中央目录，判断 v1（META-INF 签名文件）与 v2+（APK Signing Block）是否同时/单独存在。 */
    private fun detectSignatureSchemes(file: File, eocd: EocdInfo): String {
        val schemes = mutableListOf<String>()
        if (eocd.centralDirectoryOffset >= 16) {
            try {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(eocd.centralDirectoryOffset - 16)
                    val magic = ByteArray(16)
                    raf.readFully(magic)
                    if (String(magic, Charsets.US_ASCII) == "APK Sig Block 42") schemes += "v2+"
                }
            } catch (_: Exception) {
                // 忽略：签名区块缺失本身就是 v1-only 的正常情况
            }
        }
        try {
            ZipFile(file).use { zip ->
                val hasManifest = zip.getEntry("META-INF/MANIFEST.MF") != null
                if (hasManifest) {
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val name = entries.nextElement().name.uppercase()
                        if (name.startsWith("META-INF/") &&
                            (name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC"))
                        ) {
                            schemes += "v1"
                            break
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return schemes.distinct().joinToString("+").ifBlank { "未知" }
    }
}

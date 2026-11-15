package com.dlguard.shizuku

import android.os.Build

/**
 * Shizuku 特权操作集：全部等价于「adb shell 一条命令」，逐条 Result 封装。
 *
 * 设计原则：
 *  - 每条操作独立成函数，失败只返回 Result.failure，绝不抛出、绝不影响主流程；
 *  - 命令执行前显式检查 Shizuku 状态，未授权直接返回失败而不是静默吞掉；
 *  - 不使用 root（su）——Shizuku 走 shell(uid=2000) 权限，足够覆盖以下全部操作。
 *
 * 覆盖的特权场景：
 *  1. `appops set MANAGE_EXTERNAL_STORAGE allow`：免手点授权所有文件访问；
 *  2. `dumpsys deviceidle whitelist +pkg`：加入电池优化白名单；
 *  3. `settings put secure enabled_accessibility_services`：静默开启本 App 无障碍服务
 *     （需要 shell uid 的 WRITE_SECURE_SETTINGS 能力，这正是 Shizuku 相对普通 App 的优势）；
 *  4. `content query content://downloads`：以 shell 身份查询 DownloadManager 全量记录，
 *     用于拦截浏览器发起的下载并引导转交本 App 加速。
 */
object PrivilegeOps {

    private const val PKG = "com.dlguard"
    private const val ACCESSIBILITY_SERVICE = "$PKG/com.dlguard.service.DownloadAccessibilityService"

    /** 判断命令输出是否真的成功：exitCode != 0 或 stderr 出现 denied 都算失败。 */
    private fun isDenied(stderr: String): Boolean =
        stderr.contains("denied", true) || stderr.contains("not allowed", true)

    // ------------------------------------------------------------- 文件访问

    /**
     * 用 appops 直接授予「所有文件访问」，用户从此不用去系统设置里翻开关。
     * Android 11+ 生效；低版本顺带 pm grant 读写权限。
     */
    fun grantAllFilesAccess(): Result<Unit> {
        val appops = ShizukuHelper.execShell("appops set $PKG MANAGE_EXTERNAL_STORAGE allow")
        if (appops.isFailure) return Result.failure(appops.exceptionOrNull() ?: IllegalStateException("appops 执行失败"))
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            ShizukuHelper.execShell("pm grant $PKG android.permission.READ_EXTERNAL_STORAGE")
            ShizukuHelper.execShell("pm grant $PKG android.permission.WRITE_EXTERNAL_STORAGE")
        }
        return Result.success(Unit)
    }

    // ------------------------------------------------------------- 电池白名单

    /**
     * deviceidle whitelist 加入自身。dumpsys deviceidle 的 whitelist 子命令
     * 在多数 ROM 上 shell uid 可写；个别 ROM 拒绝时返回失败，UI 降级为手动跳转。
     */
    fun addToBatteryWhitelist(): Result<Unit> {
        val result = ShizukuHelper.execShell("dumpsys deviceidle whitelist +$PKG")
        if (result.isFailure) return Result.failure(result.exceptionOrNull() ?: IllegalStateException("deviceidle 执行失败"))
        return Result.success(Unit)
    }

    // ------------------------------------------------------------- 无障碍自启动与保活

    /** 当前系统已启用的无障碍服务列表（冒号分隔，每项 pkg/cls）。 */
    fun readEnabledAccessibilityServices(): Result<String> {
        val result = ShizukuHelper.execShell("settings get secure enabled_accessibility_services")
        if (result.isFailure) return Result.failure(result.exceptionOrNull() ?: IllegalStateException("读取失败"))
        val value = result.getOrNull().orEmpty().trim()
        if (value == "null" || value == "none") return Result.success("")
        if (isDenied(value)) return Result.failure(IllegalStateException(value.take(200)))
        return Result.success(value)
    }

    /**
     * 把本 App 的无障碍服务静默写进系统开关。保留其它已启用的服务，追加而不是覆盖。
     * 返回 Result：成功后系统无障碍列表立即生效（不需要重启）。
     */
    fun enableSelfAccessibility(): Result<Unit> {
        val existing = readEnabledAccessibilityServices()
        val current = existing.getOrDefault("")
        if (current.contains(ACCESSIBILITY_SERVICE)) return Result.success(Unit)
        val merged = if (current.isBlank()) ACCESSIBILITY_SERVICE else "$current:$ACCESSIBILITY_SERVICE"
        val put = ShizukuHelper.execShell(
            "settings put secure enabled_accessibility_services \"$merged\""
        )
        if (put.isFailure) return Result.failure(put.exceptionOrNull() ?: IllegalStateException("写入失败"))
        val enable = ShizukuHelper.execShell("settings put secure accessibility_enabled 1")
        if (enable.isFailure) return Result.failure(enable.exceptionOrNull() ?: IllegalStateException("开启失败"))
        return Result.success(Unit)
    }

    /** 心跳保活的反向操作：用户主动关闭时把自己从列表里摘掉。 */
    fun disableSelfAccessibility(): Result<Unit> {
        val existing = readEnabledAccessibilityServices()
        val current = existing.getOrDefault("")
        if (!current.contains(ACCESSIBILITY_SERVICE)) return Result.success(Unit)
        val removed = current.split(':')
            .filter { it.isNotBlank() && !it.equals(ACCESSIBILITY_SERVICE, true) }
            .joinToString(":")
        val put = ShizukuHelper.execShell(
            "settings put secure enabled_accessibility_services \"$removed\""
        )
        if (put.isFailure) return Result.failure(put.exceptionOrNull() ?: IllegalStateException("写入失败"))
        return Result.success(Unit)
    }

    /** 无障碍总开关是否打开（用于心跳判断是否被系统/用户关掉）。 */
    fun isAccessibilityEnabled(): Boolean {
        val result = ShizukuHelper.execShell("settings get secure accessibility_enabled")
        return result.getOrDefault("0") == "1"
    }

    // ------------------------------------------------------------- DownloadManager 特权查询

    data class SystemDownloadRow(
        val id: Long,
        val title: String,
        val targetFile: String,
        val status: Int,
        val uri: String = ""
    )

    /**
     * 以 shell uid 查询 DownloadManager 底层数据：普通 App 只能看到自己发起的下载，
     * shell uid 能看到全系统记录 —— 这是「系统下载接管」的数据来源。
     * 取 _id / title / _data / status / uri，供来源反查与「转交本 App 加速下载」。
     */
    fun querySystemDownloads(limit: Int = 20): Result<List<SystemDownloadRow>> {
        val command =
            "content query --uri content://downloads/my_downloads " +
                "--projection _id:title:_data:status:uri --sort _id\\ DESC 2>/dev/null | head -n $limit"
        val result = ShizukuHelper.execShell(command)
        if (result.isFailure) return Result.failure(result.exceptionOrNull() ?: IllegalStateException("查询失败"))
        val rows = mutableListOf<SystemDownloadRow>()
        // 输出形如：Row: 0 _id=42, title=app.apk, _data=/storage/.../app.apk, status=200, uri=https://...
        val rowRegex = Regex("Row:\\s*\\d+(.*)")
        result.getOrDefault("").lineSequence().forEach { line ->
            val match = rowRegex.find(line) ?: return@forEach
            val body = match.groupValues[1]
            fun field(name: String): String {
                val start = body.indexOf("$name=")
                if (start < 0) return ""
                val rest = body.substring(start + name.length + 1)
                val next = Regex(",\\s*\\w+=").find(rest)
                return if (next != null) rest.substring(0, next.range.first).trim() else rest.trim()
            }
            val id = field("_id").toLongOrNull() ?: return@forEach
            rows += SystemDownloadRow(
                id = id,
                title = field("title"),
                targetFile = field("_data"),
                status = field("status").toIntOrNull() ?: 0,
                uri = field("uri")
            )
        }
        return Result.success(rows)
    }
}

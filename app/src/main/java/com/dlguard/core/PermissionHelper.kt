package com.dlguard.core

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 权限状态读取与各 ROM 的跳转入口。
 *
 * 这一版重点解决「点了去授权没反应」：Android 11+ 的「所有文件访问权限」在不同 ROM 上
 * 有三个不同的落地页（本应用详情页 / 应用列表页 / 只能进应用详情再手动找），
 * 任何一级跳转失败都必须继续降级，最后返回失败让 UI 给出文字路径，不能静默吞掉。
 */
object PermissionHelper {

    /** 跳转结果，UI 据此决定提示什么。 */
    enum class JumpResult {
        /** 直接落在「浏览器下载接管」的所有文件访问开关页（最理想） */
        APP_PAGE,

        /** 落在「所有文件访问权限」应用列表，用户需自己点进来 */
        LIST_PAGE,

        /** 只落到应用详情页，用户需要再点「权限」自己找 */
        APP_DETAILS,

        /** 系统设置完全打不开，只能给文字路径 */
        FAILED
    }

    fun hasStorageAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 普通「存储空间」权限是否已给。用于区分「完全没授权」和「只给了普通权限」两种状态，
     * 因为用户在应用信息页点开「存储空间」后，往往会误以为已经满足了。
     */
    fun hasLegacyStoragePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

    fun hasNotificationPermission(context: Context): Boolean {
        val runtimeGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return runtimeGranted && runCatching {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(false)
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching { manager.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
    }

    /**
     * 打开「所有文件访问权限」。三级降级，每一级都先 `resolveActivity` 预检再启动，
     * 保证不会出现「Intent 抛异常被 runCatching 吞掉、界面毫无反应」的情况。
     */
    fun openAllFilesAccessDetailed(context: Context): JumpResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appPage = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            if (canResolve(context, appPage) && startSafely(context, appPage)) {
                return JumpResult.APP_PAGE
            }
            val listPage = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            if (canResolve(context, listPage) && startSafely(context, listPage)) {
                return JumpResult.LIST_PAGE
            }
        }
        if (openAppDetails(context)) return JumpResult.APP_DETAILS
        return JumpResult.FAILED
    }

    fun openAllFilesAccess(context: Context): Boolean =
        openAllFilesAccessDetailed(context) != JumpResult.FAILED

    /**
     * 直接打开「所有文件访问权限」应用列表。
     * 用户第一次点「去授权」如果落在详情页找不到开关，第二次就切到列表页，让他自己点进来。
     */
    fun openAllFilesAccessList(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return openAppDetails(context)
        return startSafely(context, Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }

    fun openAppDetails(context: Context): Boolean =
        startSafely(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        )

    fun openNotificationSettings(context: Context): Boolean {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        if (startSafely(context, intent)) return true
        return openAppDetails(context)
    }

    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
        if (canResolve(context, direct) && startSafely(context, direct)) return true
        return startSafely(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    fun openAppBatteryUsage(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
            if (canResolve(context, intent) && startSafely(context, intent)) return true
        }
        return startSafely(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /**
     * 打开系统无障碍设置。优先落在本应用的无障碍开关页（部分 ROM 支持 extras 定位），
     * 失败退到无障碍总列表，再失败退到应用详情。
     */
    fun openAccessibilitySettings(context: Context): Boolean {
        val appPage = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
        if (startSafely(context, appPage)) return true
        val listPage = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        if (startSafely(context, listPage)) return true
        return openAppDetails(context)
    }

    /** 厂商自启动白名单页面，返回是否成功打开。 */
    fun openAutoStartSettings(context: Context): Boolean {
        val candidates = vendorAutoStartComponents()
        for (component in candidates) {
            val intent = Intent().apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        for (action in vendorAutoStartActions()) {
            if (runCatching {
                    context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
            ) return true
        }
        return openAppDetails(context)
    }

    /**
     * 「所有文件访问权限」的本机路径。不同 ROM 把入口放在完全不同的层级，
     * 跳转失败时用户至少还能照着文字找到。
     */
    fun allFilesAccessHint(): String = when {
        isXiaomi() ->
            "小米 / Redmi / HyperOS：设置 → 应用设置 → 应用管理 → 浏览器下载接管 → 权限管理 → 所有文件访问；" +
                "若没有该项，走 设置 → 隐私保护 → 特殊权限设置 → 所有文件访问权限 → 浏览器下载接管"
        isHuawei() ->
            "华为 / 荣耀：设置 → 应用和服务 → 应用管理 → 浏览器下载接管 → 权限 → 所有文件访问；" +
                "或 设置 → 隐私 → 特殊访问权限 → 所有文件访问权限"
        isOppo() ->
            "OPPO / 一加 / realme：设置 → 应用 → 应用管理 → 浏览器下载接管 → 权限 → 所有文件访问；" +
                "或 设置 → 权限与隐私 → 特殊应用权限 → 所有文件访问权限"
        isVivo() ->
            "vivo / iQOO：设置 → 应用与权限 → 权限管理 → 浏览器下载接管 → 所有文件访问；" +
                "或 设置 → 应用与权限 → 特殊应用权限 → 所有文件访问"
        isSamsung() ->
            "三星：设置 → 应用程序 → 浏览器下载接管 → 权限 → 所有文件访问"
        isMeizu() ->
            "魅族：设置 → 应用管理 → 浏览器下载接管 → 权限 → 所有文件访问"
        else ->
            "设置 → 应用 → 特殊应用权限（或「特殊访问权限」）→ 所有文件访问权限 → 浏览器下载接管 → 打开开关"
    }

    fun autoStartGuideText(): String = when {
        isXiaomi() -> "小米 / Redmi：设置 → 应用设置 → 应用管理 → 浏览器下载接管 → 自启动，并允许「后台弹出界面」"
        isHuawei() -> "华为 / 荣耀：设置 → 应用 → 应用启动管理 → 浏览器下载接管 → 关闭自动管理，勾选「自启动 / 关联启动 / 后台活动」"
        isOppo() -> "OPPO / 一加 / realme：设置 → 电池 → 应用耗电管理 → 浏览器下载接管 → 允许后台运行 / 允许自启动"
        isVivo() -> "vivo / iQOO：设置 → 电池 → 后台耗电管理 → 浏览器下载接管 → 允许后台运行；i管家 → 自启动管理"
        isSamsung() -> "三星：设置 → 电池 → 后台使用限制 → 从「休眠应用」中移除浏览器下载接管"
        isMeizu() -> "魅族：设置 → 应用管理 → 权限管理 → 自启动管理 → 允许浏览器下载接管"
        else -> "设置 → 应用 → 浏览器下载接管 → 电池 / 后台运行 → 允许自启动与后台活动"
    }

    fun vendorLabel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    fun isXiaomi() = Build.MANUFACTURER.contains("xiaomi", true) || Build.BRAND.contains("redmi", true) ||
        Build.BRAND.contains("poco", true)

    fun isHuawei() = Build.MANUFACTURER.contains("huawei", true) || Build.BRAND.contains("honor", true)
    fun isOppo() = Build.MANUFACTURER.contains("oppo", true) || Build.MANUFACTURER.contains("realme", true) ||
        Build.MANUFACTURER.contains("oneplus", true)

    fun isVivo() = Build.MANUFACTURER.contains("vivo", true) || Build.MANUFACTURER.contains("iqoo", true)
    fun isSamsung() = Build.MANUFACTURER.contains("samsung", true)
    fun isMeizu() = Build.MANUFACTURER.contains("meizu", true)

    fun notificationRuntimePermission(): String = Manifest.permission.POST_NOTIFICATIONS

    fun storageRuntimePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    fun requestPermission(activity: Activity, permission: String, requestCode: Int) {
        runCatching { activity.requestPermissions(arrayOf(permission), requestCode) }
    }

    private fun canResolve(context: Context, intent: Intent): Boolean = runCatching {
        context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
    }.getOrDefault(false)

    private fun startSafely(context: Context, intent: Intent): Boolean {
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun vendorAutoStartComponents(): List<ComponentName> = listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")
    )

    private fun vendorAutoStartActions(): List<String> = listOf(
        "miui.intent.action.OP_AUTO_START",
        "com.huawei.systemmanager.startupmgr.action.ALLOW_AUTO_START",
        "com.coloros.safecenter.permission.startup.startupapp.startupapplist"
    )
}

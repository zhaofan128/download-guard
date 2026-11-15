package com.dlguard.shizuku

import android.content.ClipData
import android.os.Build
import android.os.IBinder

/**
 * 特权剪贴板桥：绕过 Android 10+ 「后台 App 读取剪贴板被拒」的限制。
 *
 * 原理：普通 App 调 ClipboardManager 时，系统 ClipboardService 会做 AppOps
 * 「SELF/DEFAULT」检查——后台读取直接返回 null 甚至 SecurityException。
 * 而 Shizuku 的 binder 包装把调用方身份提升为 shell(uid=2000)，
 * shell 对剪贴板的 AppOps 默认放行，于是 `IClipboard.getPrimaryClip` 就能正常返回。
 *
 * IClipboard 是 @hide 接口，编译期拿不到 SDK：这里全部走反射，
 * 并按系统版本尝试三种已知签名（API 26~28 / 29~30 / 31+）。
 * 任何一步失败都返回 Result.failure，调用方退回普通 ClipboardManager 路径。
 */
object ClipboardBridge {

    private var cachedInterface: Any? = null
    private var cachedMethod: java.lang.reflect.Method? = null
    private var signatureFailed = false

    /** 三种已知 getPrimaryClip 签名，从新到旧。 */
    private val signatureCandidates: List<(Any) -> java.lang.reflect.Method?> = listOf(
        // API 31+：getPrimaryClip(String pkg, String attributionTag, int userId)
        { service ->
            service.javaClass.methods.firstOrNull {
                it.name == "getPrimaryClip" && it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java &&
                    it.parameterTypes[2] == Int::class.javaPrimitiveType
            }
        },
        // API 29~30：getPrimaryClip(String pkg, int userId)
        { service ->
            service.javaClass.methods.firstOrNull {
                it.name == "getPrimaryClip" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
            }
        },
        // API 26~28：getPrimaryClip(String pkg)
        { service ->
            service.javaClass.methods.firstOrNull {
                it.name == "getPrimaryClip" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == String::class.java
            }
        }
    )

    /**
     * 读取剪贴板全文。成功返回文本；失败返回 Result.failure（调用方降级）。
     */
    fun readViaShizuku(): Result<String> {
        if (signatureFailed) return Result.failure(IllegalStateException("剪贴板特权签名不可用"))
        if (ShizukuHelper.status.value != ShizukuHelper.Status.READY) {
            return Result.failure(IllegalStateException("Shizuku 未授权"))
        }
        val binder = ShizukuHelper.wrappedSystemBinder("clipboard")
            ?: return Result.failure(IllegalStateException("clipboard binder 包装失败"))
        return runCatching {
            val service = obtainInterface(binder)
            val method = obtainMethod(service)
            val clip = invoke(service, method)
                ?: throw IllegalStateException("剪贴板读取返回空")
            val text = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                ?: throw IllegalStateException("剪贴板为空")
            text
        }
    }

    private fun obtainInterface(binder: IBinder): Any {
        cachedInterface?.let { return it }
        val stub = Class.forName("android.content.IClipboard\$Stub")
        val asInterface = stub.getMethod("asInterface", IBinder::class.java)
        val service = asInterface.invoke(null, binder)
            ?: throw IllegalStateException("IClipboard.asInterface 返回 null")
        cachedInterface = service
        return service
    }

    private fun obtainMethod(service: Any): java.lang.reflect.Method {
        cachedMethod?.let { return it }
        for (candidate in signatureCandidates) {
            val method = candidate(service)
            if (method != null) {
                cachedMethod = method
                return method
            }
        }
        signatureFailed = true
        throw IllegalStateException("当前系统没有可用的 getPrimaryClip 签名")
    }

    private fun invoke(service: Any, method: java.lang.reflect.Method): ClipData? {
        method.isAccessible = true
        return try {
            when (method.parameterTypes.size) {
                1 -> method.invoke(service, "com.dlguard") as? ClipData
                2 -> method.invoke(service, "com.dlguard", 0) as? ClipData
                3 -> method.invoke(service, "com.dlguard", null, 0) as? ClipData
                else -> null
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // 个别 ROM 对 shell uid 也做 AppOps 校验；如实报告失败，让调用方降级
            throw IllegalStateException(
                "特权读取被拒绝：${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}"
            )
        }
    }

    /** 仅供诊断：当前是否还能走特权路径。 */
    fun isPrivilegedPathAvailable(): Boolean =
        !signatureFailed && ShizukuHelper.status.value == ShizukuHelper.Status.READY

    /** Build 版本辅助（单元测试可模拟）。 */
    fun apiLevel(): Int = Build.VERSION.SDK_INT
}

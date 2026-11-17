package com.dlguard.core

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 主线程阻塞调用门禁。
 *
 * 2.2.0 实机事故（用户录屏）：首次安装后进入设置页，点一下「浏览器链接爬取（无障碍）」，
 * 界面直接卡住并弹出「浏览器下载接管没有响应」（ANR）。原因是开关回调跑在
 * viewModelScope（Dispatchers.Main）里，直接调了 `PrivilegeOps.enableSelfAccessibility()`：
 * 它同步走 Shizuku binder + user service 的 shell exec，Shizuku 尚未就绪时一次能等数秒，
 * 主线程被占满 5 秒就 ANR。`ShizukuHelper.refresh()` 里的 pingBinder / getVersion /
 * checkSelfPermission 也是同理，`onResume` 每次回前台都会踩。
 *
 * 规则（只约束 UI 层，服务层自己在 IO 线程里跑）：
 *  1. UI 层不得直接调 `ShizukuHelper.refresh()`，一律走 `refreshAsync()`；
 *  2. UI 层出现 `PrivilegeOps.xxx()` 时，同一行必须带 `withContext(Dispatchers.IO)`。
 */
class MainThreadBlockingGuardTest {

    private val uiRoots = listOf(
        File("src/main/java/com/dlguard/ui"),
        File("src/main/java/com/dlguard/MainActivity.kt")
    ).filter { it.exists() }

    @Test
    fun `UI 层不得直接调用阻塞的 Shizuku 接口`() {
        assumeTrue("找不到源码目录，跳过", uiRoots.isNotEmpty())

        val offenses = mutableListOf<String>()

        fun scan(file: File) {
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                    return@forEachIndexed
                }
                // 同一行已经切到 IO 的调用是安全的，直接放行
                val onIo = line.contains("withContext(Dispatchers.IO)")
                if (!onIo && line.contains("ShizukuHelper.refresh()")) {
                    offenses.add("${file.path}:${index + 1} 直接调 ShizukuHelper.refresh()，应改 refreshAsync()")
                }
                if (!onIo && line.contains("PrivilegeOps.")) {
                    offenses.add("${file.path}:${index + 1} PrivilegeOps 调用不在 Dispatchers.IO 上下文：$trimmed")
                }
            }
        }

        uiRoots.forEach { root ->
            if (root.isFile) scan(root)
            else root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach(::scan)
        }

        assertTrue(
            "以下调用会把 Shizuku binder / shell 执行压到主线程，必须在 Dispatchers.IO 里：\n" +
                offenses.joinToString("\n"),
            offenses.isEmpty()
        )
    }
}

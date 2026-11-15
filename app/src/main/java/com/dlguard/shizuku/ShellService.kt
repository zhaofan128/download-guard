package com.dlguard.shizuku

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Shizuku 用户服务实现：由 Shizuku server 以 shell(uid=2000) 身份拉起，
 * 通过 binder 调用 [exec] 执行特权命令（appops / settings / dumpsys / content query）。
 *
 * 约定（Shizuku UserService 协议，方法名不可改）：
 *  - public 无参构造；
 *  - `onCreate(context)`：Shizuku 拉起后回调；
 *  - `onDestroy()`：Shizuku 回收时回调。
 *
 * exec 返回单条文本（固定分隔符切段，避免引入 JSON 解析依赖）：
 * ```
 * <exitCode>
 * ---DLG-OUT---
 * <stdout>
 * ---DLG-ERR---
 * <stderr>
 * ```
 */
class ShellService : IShellService.Stub() {

    companion object {
        private const val OUT_MARKER = "---DLG-OUT---"
        private const val ERR_MARKER = "---DLG-ERR---"
        private const val TIMEOUT_SECONDS = 15L

        /** 解析 exec 返回的打包文本。 */
        fun parseResult(payload: String): ShellResult {
            val codeLineEnd = payload.indexOf('\n')
            val code = payload.substring(0, codeLineEnd.coerceAtLeast(0)).trim().toIntOrNull() ?: -1
            val rest = if (codeLineEnd >= 0) payload.substring(codeLineEnd + 1) else ""
            val outStart = rest.indexOf(OUT_MARKER)
            val errStart = rest.indexOf(ERR_MARKER)
            val stdout = if (outStart >= 0) {
                val end = if (errStart > outStart) errStart else rest.length
                rest.substring(outStart + OUT_MARKER.length, end).trim()
            } else ""
            val stderr = if (errStart >= 0) {
                rest.substring(errStart + ERR_MARKER.length).trim()
            } else ""
            return ShellResult(code, stdout, stderr)
        }

        fun packResult(exitCode: Int, stdout: String, stderr: String): String =
            listOf(exitCode, OUT_MARKER, stdout, ERR_MARKER, stderr).joinToString("\n")
    }

    @Volatile
    private var appContext: Context? = null

    /** Shizuku 拉起服务后回调（协议方法名）。 */
    fun onCreate(context: Context) {
        appContext = context.applicationContext
    }

    /** Shizuku 回收服务时回调（协议方法名）。 */
    fun onDestroy() {
        appContext = null
    }

    override fun exec(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .directory(File("/data/local/tmp").takeIf { it.canWrite() } ?: File("."))
                .start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outThread = Thread {
                runCatching {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    while (true) stdout.append(reader.readLine() ?: break).append('\n')
                }
            }
            val errThread = Thread {
                runCatching {
                    val reader = BufferedReader(InputStreamReader(process.errorStream))
                    while (true) stderr.append(reader.readLine() ?: break).append('\n')
                }
            }
            outThread.start()
            errThread.start()
            val exited = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                return packResult(-1, "", "命令超时（${TIMEOUT_SECONDS}s）：$command")
            }
            outThread.join(1_000)
            errThread.join(1_000)
            packResult(process.exitValue(), stdout.toString().trim(), stderr.toString().trim())
        } catch (e: Exception) {
            packResult(-1, "", "${e.javaClass.simpleName}: ${e.message ?: "未知错误"}")
        }
    }

    override fun exit() {
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

/** exec 的结构化结果。 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

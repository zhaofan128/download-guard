package com.dlguard.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Shizuku 授权中枢（API 13.1.5）。
 *
 * 职责边界（真实边界，不吹嘘）：
 *  - 管理连接状态（未安装 / 未运行 / 待授权 / 就绪），以 StateFlow 暴露给首页顶部卡片；
 *  - 提供 binder 包装入口（给 ClipboardBridge 走 shell uid）；
 *  - 通过 UserService（[ShellService]，跑在 shell uid 进程）执行特权命令，
 *    全部 Result 封装，失败绝不向上抛。
 *
 * 生命周期：DownloadGuardApp.onCreate 里 init()；Activity 侧通过 refresh() 主动核对。
 *
 * 降级：Shizuku 不存在 / 服务没起 / 用户拒绝授权，status 都会如实反映，
 * 调用方据此退回手动授权模式 —— 整个模块不存在「拿不到就崩溃」的路径。
 */
object ShizukuHelper {

    enum class Status {
        /** 设备上没有安装 Shizuku 管理器。 */
        NOT_INSTALLED,

        /** 管理器已装、binder 从未送达（覆盖安装/进程被杀错过推送）。可主动索取。 */
        BINDER_PENDING,

        /** binder 已收到但已死亡（服务停止/被杀）。 */
        NOT_RUNNING,

        /** 服务在运行，但本 App 还没有拿到授权。 */
        NEED_PERMISSION,

        /** 已授权，可以使用特权通道。 */
        READY
    }

    private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"

    private val _status = MutableStateFlow(Status.NOT_INSTALLED)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val initialized = AtomicBoolean(false)
    private var appContext: Context? = null

    // ------------------------------------------------------------ 用户服务（特权 shell 执行器）

    @Volatile
    private var shellService: IShellService? = null

    /** Application.onCreate 调用一次。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (initialized.getAndSet(true)) {
            refreshAsync()
            return
        }
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    // 回调可能落在主线程，状态评估里有多次 binder 往返
                    refreshAsync()
                }
            })
        }
        runCatching {
            Shizuku.addBinderDeadListener(object : Shizuku.OnBinderDeadListener {
                override fun onBinderDead() {
                    _status.value = Status.NOT_RUNNING
                }
            })
        }
        // 覆盖安装 / 进程被杀会错过管理器的 binder 推送：启动时主动索取一次，
        // 服务在跑的话几秒内自动就绪；服务没跑则保持 BINDER_PENDING，等用户点「连接」
        refreshAsync()
    }

    /**
     * 后台线程做一次状态评估。
     *
     * `computeStatus()` 要走 pingBinder / getVersion / checkSelfPermission 三次 binder 往返，
     * Shizuku 没在跑时每次都要等 binder 超时；而这些调用原来直接发生在
     * `Application.onCreate`、`Activity.onResume` 和设置页的开关回调里 —— 全在主线程。
     * 首次安装后 Shizuku 尚未就绪时，冷启动或点一下「无障碍链接爬取」就会卡满 5 秒触发 ANR
     * （用户录屏里的「浏览器下载接管没有响应」）。所有非 UI 线程的调用点统一走这里。
     */
    fun refreshAsync() {
        Thread({
            runCatching {
                refresh()
                if (_status.value == Status.BINDER_PENDING) requestBinder()
            }
        }, "shizuku-refresh").start()
    }

    /** 重新评估连接状态并返回。读 Shizuku 的所有调用都包了 runCatching。 */
    fun refresh(): Status {
        val next = computeStatus()
        _status.value = next
        return next
    }

    private fun computeStatus(): Status {
        val context = appContext ?: return Status.NOT_INSTALLED
        val installed = runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_MANAGER_PACKAGE, 0)
        }.isSuccess
        if (!installed) return Status.NOT_INSTALLED
        val binder = runCatching { Shizuku.getBinder() }.getOrNull()
        // binder 从未送达 ≠ 服务未运行：覆盖安装或进程被杀会错过管理器推送，
        // 此时服务可能仍在跑，给用户「主动索取」的路径而不是死等
        if (binder == null) return Status.BINDER_PENDING
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) return Status.NOT_RUNNING
        val version = runCatching { Shizuku.getVersion() }.getOrDefault(0)
        if (version < 11) return Status.NOT_RUNNING
        val granted = runCatching { Shizuku.checkSelfPermission() }
            .getOrDefault(PackageManager.PERMISSION_DENIED) == PackageManager.PERMISSION_GRANTED
        return if (granted) Status.READY else Status.NEED_PERMISSION
    }

    fun hasPermission(): Boolean = _status.value == Status.READY

    /**
     * 拉起 Shizuku 授权对话框。结果通过 Activity 注册的
     * [Shizuku.OnRequestPermissionResultListener] 回调回传。
     */
    fun requestPermission(requestCode: Int): Result<Unit> {
        val current = refresh()
        if (current == Status.NOT_INSTALLED) {
            return Result.failure(IllegalStateException("尚未安装 Shizuku 管理器"))
        }
        if (current == Status.BINDER_PENDING) {
            return Result.failure(IllegalStateException("Shizuku 连接未送达，请先点击「连接」索取"))
        }
        if (current == Status.NOT_RUNNING) {
            return Result.failure(IllegalStateException("Shizuku 服务未运行，请先在 Shizuku 管理器中启动"))
        }
        return runCatching {
            Shizuku.requestPermission(requestCode)
        }.onFailure { _status.value = Status.NEED_PERMISSION }
    }

    // ------------------------------------------------------------ 主动索取 binder（自救通道）

    private val requesting = AtomicBoolean(false)

    /**
     * 向 Shizuku 管理器主动索取 binder（REQUEST_BINDER 协议，带重试）。
     * 适用于「管理器在运行但 binder 未送达」的场景；成功后状态流自动刷新。
     * 并发去重：同一次请求周期内重复点击无效。
     */
    fun requestBinder(onFinished: (Boolean) -> Unit = {}) {
        val context = appContext ?: return onFinished(false)
        val installed = runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_MANAGER_PACKAGE, 0)
        }.isSuccess
        if (!installed) return onFinished(false)
        if (!requesting.compareAndSet(false, true)) return
        Thread {
            val ok = runCatching {
                kotlinx.coroutines.runBlocking { ShizukuBinderRequester.request(context) }
            }.getOrDefault(false)
            requesting.set(false)
            refresh()
            runCatching { onFinished(ok) }
        }.start()
    }

    /** 是否正在索取中（UI 可据此显示「正在请求连接…」）。 */
    fun isRequesting(): Boolean = requesting.get()

    /** 注册授权结果回调。 */
    fun setPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener?) {
        runCatching { if (listener != null) Shizuku.addRequestPermissionResultListener(listener) }
    }

    fun removePermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener?) {
        runCatching { if (listener != null) Shizuku.removeRequestPermissionResultListener(listener) }
    }

    /** 拿到经 Shizuku 包装的系统服务 binder；未就绪时返回 null。 */
    fun wrappedSystemBinder(service: String): IBinder? {
        if (_status.value != Status.READY) return null
        return runCatching {
            ShizukuBinderWrapper(SystemServiceHelper.getSystemService(service))
        }.getOrNull()
    }

    /** binder 版本号。 */
    fun binderVersion(): Int = runCatching { Shizuku.getVersion() }.getOrDefault(0)

    // ------------------------------------------------------------ UserService 绑定与 shell 执行

    /**
     * 绑定 ShellService 用户服务（跑在 shell uid 进程）。
     * 已绑定直接返回；绑定超时或 Shizuku 拒绝返回失败。
     */
    private fun ensureUserService(): Result<Unit> {
        val context = appContext
            ?: return Result.failure(IllegalStateException("Application 未初始化"))
        if (shellService != null) return Result.success(Unit)
        if (_status.value != Status.READY) {
            return Result.failure(IllegalStateException("Shizuku 未授权"))
        }
        return runCatching {
            val latch = CountDownLatch(1)
            val args = Shizuku.UserServiceArgs(
                ComponentName(context, ShellService::class.java)
            )
                .processNameSuffix("shell")
                .version(1)
                .daemon(false)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    shellService = IShellService.Stub.asInterface(binder)
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    shellService = null
                }

                override fun onBindingDied(name: ComponentName?) {
                    shellService = null
                }
            }
            Shizuku.bindUserService(args, connection)
            if (!latch.await(8, TimeUnit.SECONDS)) {
                throw IllegalStateException("Shizuku 用户服务绑定超时（8s）")
            }
        }
    }

    /**
     * 以 shell uid 执行一条命令（等价 adb shell）。要求 Shizuku 已授权。
     * 返回 stdout；失败时 Result.failure（消息含退出码与 stderr 摘要）。
     */
    fun execShell(command: String): Result<String> {
        ensureUserService().let { if (it.isFailure) return Result.failure(it.exceptionOrNull()!!) }
        val service = shellService
            ?: return Result.failure(IllegalStateException("用户服务未连接"))
        return runCatching {
            val result = ShellService.parseResult(service.exec(command))
            if (result.exitCode != 0) {
                throw IllegalStateException(
                    "exit=${result.exitCode} ${result.stderr.take(300)}"
                )
            }
            result.stdout
        }
    }
}

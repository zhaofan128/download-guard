package com.dlguard.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.UserManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dlguard.DownloadGuardApp
import com.dlguard.core.PermissionHelper
import com.dlguard.data.AppContainer
import com.dlguard.data.DownloadHistoryResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class MonitorService : Service() {

    companion object {
        private const val TAG = "MonitorService"
        const val ACTION_START = "com.dlguard.action.START"
        const val ACTION_STOP = "com.dlguard.action.STOP"
        const val ACTION_PAUSE = "com.dlguard.action.PAUSE"
        const val ACTION_RESUME = "com.dlguard.action.RESUME"
        const val ACTION_RESCAN = "com.dlguard.action.RESCAN"
    }

    private lateinit var container: AppContainer
    private lateinit var notifier: NotificationHelper
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var watcher: DownloadWatcher? = null
    private var pipeline: CheckPipeline? = null
    private var unlockReceiver: BroadcastReceiver? = null
    private var notificationJob: Job? = null
    private var heartbeatJob: Job? = null
    @Volatile
    private var notificationsEnabled = true
    @Volatile
    private var starting = false
    @Volatile
    private var activeDirs: List<File> = emptyList()

    override fun onCreate() {
        super.onCreate()
        container = (application as DownloadGuardApp).container
        notifier = NotificationHelper(this)
        notifier.createChannels()

        // 必须在 5 秒内进入前台
        pushForeground(GuardState.snapshot.value)

        pipeline = CheckPipeline(
            context = this,
            scope = serviceScope,
            checker = container.checker,
            repository = container.repository,
            quarantine = container.quarantine,
            trustedStore = container.trustedStore,
            resolver = DownloadHistoryResolver(this),
            notifier = notifier,
            shouldNotify = { notificationsEnabled },
            onRecordSaved = { serviceScope.launch { refreshCounts() } }
        )

        notificationJob = serviceScope.launch {
            GuardState.snapshot.collectLatest { snapshot -> pushForeground(snapshot) }
        }
        serviceScope.launch {
            container.settings.settings.collectLatest { settings ->
                notificationsEnabled = settings.notifications
            }
        }

        registerUnlockReceiver()
        // 闹钟看门狗与 WorkManager 周期任务互为兜底
        Watchdog.schedule(this)
        startHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> {
                stopGuard()
                Watchdog.cancel(this)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> pauseGuard()
            ACTION_RESCAN -> {
                if (GuardState.snapshot.value.paused) {
                    Log.i(TAG, "已暂停，忽略扫描请求")
                } else {
                    startGuard()
                    rescan()
                }
            }
            ACTION_RESUME -> {
                GuardState.update { it.copy(paused = false) }
                startGuard()
            }
            ACTION_START, null -> startGuard()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopGuard()
        heartbeatJob?.cancel()
        heartbeatJob = null
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
        unlockReceiver = null
        notificationJob?.cancel()
        serviceScope.cancel()
        GuardState.update { it.copy(running = false, paused = false) }
        super.onDestroy()
    }

    /**
     * 心跳保活（每 20 秒）：
     *  1. 无障碍自启动开关打开 + Shizuku 就绪时，检测本应用无障碍服务是否还在
     *     系统的 enabled_accessibility_services 列表里；被系统/用户关掉就写回开启。
     *     全部 Result 封装，失败只打日志，绝不让守护服务崩溃。
     *  2. 剪贴板监听开关联动：开关开着但服务没在跑 → 拉起；开关关了但服务还活着 → 停掉。
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(20_000L)
                runCatching {
                    val settings = container.settings.settings.first()
                    // 1. 无障碍心跳自修复
                    if (settings.autoRepairAccessibility &&
                        settings.accessibilityCapture &&
                        com.dlguard.shizuku.ShizukuHelper.hasPermission()
                    ) {
                        val serviceString = android.provider.Settings.Secure.getString(
                            contentResolver,
                            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                        ).orEmpty()
                        val self = "$packageName/com.dlguard.service.DownloadAccessibilityService"
                        if (!serviceString.contains(self)) {
                            com.dlguard.shizuku.PrivilegeOps.enableSelfAccessibility().fold(
                                onSuccess = { Log.i(TAG, "心跳：无障碍服务已被写回开启") },
                                onFailure = { Log.w(TAG, "心跳：无障碍写回失败 ${it.message}") }
                            )
                        }
                    }
                    // 2. 剪贴板监听联动
                    if (settings.clipboardWatch && !ClipboardWatchService.isRunning) {
                        ContextCompat.startForegroundService(
                            this@MonitorService,
                            Intent(this@MonitorService, ClipboardWatchService::class.java)
                        )
                    } else if (!settings.clipboardWatch && ClipboardWatchService.isRunning) {
                        startService(
                            Intent(this@MonitorService, ClipboardWatchService::class.java)
                                .setAction(ClipboardWatchService.ACTION_STOP)
                        )
                    }
                }.onFailure { Log.w(TAG, "心跳执行异常（忽略）: ${it.message}") }
            }
        }
    }

    @Synchronized
    private fun startGuard() {
        if (!isUserUnlocked()) {
            Log.i(TAG, "设备未解锁，等待 ACTION_USER_UNLOCKED 后再启动监控")
            return
        }
        if (watcher != null && GuardState.snapshot.value.running) {
            rescan()
            return
        }
        // onStartCommand 可能被连续调用（自启 + 看门狗 + 界面恢复），防止并发创建两个 watcher
        if (starting) {
            Log.i(TAG, "启动中，忽略重复请求")
            return
        }
        starting = true
        serviceScope.launch {
            val enabled = runCatching {
                container.settings.settings.first().monitorEnabled
            }.getOrDefault(true)
            if (!enabled) {
                // 用户点过「暂停」或「停止」：服务可以被系统拉起，但不能再自动开始监听
                starting = false
                GuardState.update { it.copy(running = false, paused = GuardState.snapshot.value.paused) }
                Log.i(TAG, "监控总开关已关闭，保持暂停，不启动 watcher")
                return@launch
            }
            Watchdog.schedule(this@MonitorService)
            if (!PermissionHelper.hasStorageAccess(this@MonitorService)) {
                GuardState.update { it.copy(running = false, paused = false, directories = 0) }
                notifier.notifyPermissionMissing("缺少「所有文件访问权限」，无法监听 Download 等公共目录。点击通知前往授权。")
                return@launch
            }
            val dirs = currentDirs()
            if (dirs.isEmpty()) {
                GuardState.update { it.copy(running = false, paused = false, directories = 0) }
                Log.w(TAG, "没有可监听的目录")
                return@launch
            }
            activeDirs = dirs
            val newWatcher = DownloadWatcher(
                context = this@MonitorService,
                scope = serviceScope,
                directoryProvider = { activeDirs },
                onCandidate = { file -> pipeline?.submit(file) }
            )
            newWatcher.start()
            watcher = newWatcher
            GuardState.update {
                it.copy(
                    running = true,
                    paused = false,
                    directories = dirs.size,
                    watchingSince = System.currentTimeMillis()
                )
            }
            refreshCounts()
            Log.i(TAG, "guard started, dirs=${dirs.joinToString { it.name }}")
        }.also {
            it.invokeOnCompletion { starting = false }
        }
    }

    private fun pauseGuard() {
        watcher?.stop()
        watcher = null
        Watchdog.cancel(this)
        GuardState.update { it.copy(running = false, paused = true) }
        Log.i(TAG, "guard paused")
    }

    private fun stopGuard() {
        starting = false
        watcher?.stop()
        watcher = null
        pipeline?.cancelAll()
        GuardState.update { it.copy(running = false, paused = false, directories = 0) }
    }

    private fun rescan() {
        if (watcher == null) return
        serviceScope.launch(Dispatchers.IO) {
            val dirs = currentDirs()
            activeDirs = dirs
            watcher?.refresh()
            val now = System.currentTimeMillis()
            dirs.forEach { root ->
                walk(root, 0) { file ->
                    val lower = file.name.lowercase()
                    val ext = lower.substringAfterLast('.', "")
                    if (ext in setOf("apk", "apks", "xapk", "zip") &&
                        file.length() > 0 &&
                        now - file.lastModified() < 30 * 60 * 1000L
                    ) {
                        pipeline?.submit(file)
                    }
                }
            }
        }
    }

    private suspend fun currentDirs(): List<File> {
        val custom = runCatching { container.repository.allCustomDirs() }.getOrDefault(emptyList())
            .map { File(it.path) }
        return (DownloadDirs.builtIn() + custom)
            .filter { it.exists() && it.isDirectory }
            .distinctBy { it.absolutePath }
    }

    private fun walk(dir: File, depth: Int, visitor: (File) -> Unit) {
        if (depth > 3) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        children.forEach { child ->
            if (child.isDirectory) walk(child, depth + 1, visitor) else visitor(child)
        }
    }

    private suspend fun refreshCounts() {
        val counts = runCatching { container.repository.counts() }.getOrNull() ?: return
        GuardState.update { it.copy(checked = counts.total, broken = counts.broken) }
    }

    private fun pushForeground(snapshot: GuardSnapshot) {
        val granted = PermissionHelper.hasStorageAccess(this)
        val notification = notifier.buildStatusNotification(
            checked = snapshot.checked,
            broken = snapshot.broken,
            dirs = snapshot.directories,
            running = snapshot.running && !snapshot.paused,
            storageGranted = granted
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NotificationHelper.STATUS_ID, notification, type)
        }.onFailure { Log.e(TAG, "startForeground 失败", it) }
    }

    private fun isUserUnlocked(): Boolean {
        val manager = getSystemService(Context.USER_SERVICE) as? UserManager ?: return true
        return runCatching { manager.isUserUnlocked }.getOrDefault(true)
    }

    private fun registerUnlockReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_UNLOCKED) {
                    Log.i(TAG, "user unlocked, start guard")
                    startGuard()
                }
            }
        }
        unlockReceiver = receiver
        runCatching {
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(Intent.ACTION_USER_UNLOCKED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }
}

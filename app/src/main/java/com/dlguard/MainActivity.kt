package com.dlguard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dlguard.core.CrashGuard
import com.dlguard.core.PermissionHelper
import com.dlguard.shizuku.ShizukuHelper
import com.dlguard.ui.BtViewModel
import com.dlguard.ui.MainViewModel
import com.dlguard.ui.PluginViewModel
import com.dlguard.ui.SettingViewModel
import com.dlguard.ui.SniffActivity
import com.dlguard.ui.components.BottomTab
import com.dlguard.ui.components.GlassBackground
import com.dlguard.ui.components.GlassBottomBar
import com.dlguard.ui.components.PermissionGuideDialog
import com.dlguard.ui.screens.BtScreen
import com.dlguard.ui.screens.HomeScreen
import com.dlguard.ui.screens.PluginScreen
import com.dlguard.ui.screens.RecordsScreen
import com.dlguard.ui.screens.SettingScreen
import com.dlguard.ui.theme.DownloadGuardTheme
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "extra_download_url"
        const val EXTRA_RECORD_ID = "record_id"
    }

    private val viewModel: MainViewModel by viewModels()
    private val settingViewModel: SettingViewModel by viewModels()
    private val pluginViewModel: PluginViewModel by viewModels()
    private val btViewModel: BtViewModel by viewModels()
    private var pendingUrl by mutableStateOf<String?>(null)
    private var pendingSharedText by mutableStateOf<String?>(null)
    private var pendingRecordId by mutableStateOf(-1L)

    /** Shizuku 授权对话框的结果回调：授权成功/失败都同步进状态流并提示。 */
    private val shizukuPermissionListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            if (requestCode != SettingViewModel.SHIZUKU_REQUEST_CODE) return
            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ShizukuHelper.refresh()
                viewModel.notifyMessage("Shizuku 已授权，特权功能可用")
            } else {
                viewModel.notifyMessage("Shizuku 授权被拒绝，相关功能将走降级路径")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ShizukuHelper.init(applicationContext)
        ShizukuHelper.setPermissionResultListener(shizukuPermissionListener)
        readIntent(intent)
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val dark = isSystemInDarkTheme()
            val view = LocalView.current
            SideEffect {
                val activity = view.context as? Activity ?: return@SideEffect
                val controller = WindowCompat.getInsetsController(activity.window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
            DownloadGuardTheme(
                darkTheme = dark,
                dynamicColor = settings.dynamicColor,
                lowRamDevice = (application as DownloadGuardApp).container.lowRamDevice
            ) {
                AppShell(
                    viewModel = viewModel,
                    settingViewModel = settingViewModel,
                    pluginViewModel = pluginViewModel,
                    btViewModel = btViewModel,
                    dark = dark
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
        consumePending()
    }

    /** 支持三种入口：应用内跳转带 URL、通知「重新下载」带记录号、系统分享文本。 */
    private fun readIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("text/") == true) {
                    pendingSharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                }
            }
            Intent.ACTION_VIEW -> pendingUrl = intent.dataString
            else -> {
                pendingUrl = intent.getStringExtra(EXTRA_URL)
                val id = intent.getLongExtra(EXTRA_RECORD_ID, -1L)
                if (id > 0L) pendingRecordId = id
            }
        }
    }

    private fun consumePending() {
        pendingUrl?.let {
            viewModel.consumeIntentUrl(it)
            pendingUrl = null
        }
        pendingSharedText?.let {
            viewModel.consumeSharedText(it)
            pendingSharedText = null
        }
        if (pendingRecordId > 0L) {
            viewModel.handleRedownload(pendingRecordId)
            pendingRecordId = -1L
        }
    }

    override fun onResume() {
        super.onResume()
        ShizukuHelper.refresh()
        // 连接仍未送达且当前没有索取任务时再补一次：覆盖「打开 Shizuku 启动服务后回到本应用」的路径
        if (ShizukuHelper.status.value == ShizukuHelper.Status.BINDER_PENDING &&
            !ShizukuHelper.isRequesting()
        ) {
            ShizukuHelper.requestBinder()
        }
        viewModel.refreshPermissions()
        viewModel.refreshQuarantine()
        viewModel.checkClipboard()
        consumePending()
        viewModel.ensureServiceRunningIfAllowed()
    }

    override fun onDestroy() {
        ShizukuHelper.removePermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }
}

@Composable
private fun AppShell(
    viewModel: MainViewModel,
    settingViewModel: SettingViewModel,
    pluginViewModel: PluginViewModel,
    btViewModel: BtViewModel,
    dark: Boolean
) {
    val context = LocalContext.current
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val guideVisible by viewModel.permissionGuide.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }
    LaunchedEffect(Unit) {
        settingViewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }
    LaunchedEffect(Unit) {
        btViewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }
    // 上次异常退出（如点开关闪退）：启动即提醒，日志在「设置 → 上次异常退出」可一键分享
    LaunchedEffect(Unit) {
        if (CrashGuard.hasLastReport()) {
            snackbarHostState.showSnackbar("检测到上次异常退出，诊断日志在「设置」页底部，可一键分享给开发者")
        }
    }
    // 智能下载判定为网页：拉起 WebView 嗅探页，由页面自己触发下载回调
    LaunchedEffect(viewModel) {
        viewModel.sniffRequest.collect { pageUrl ->
            runCatching {
                context.startActivity(
                    Intent(context, SniffActivity::class.java)
                        .putExtra(SniffActivity.EXTRA_PAGE_URL, pageUrl)
                )
            }
        }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        viewModel.navigation.collect { index -> tab = index }
    }
    val tabs = remember {
        listOf(
            BottomTab("首页", Icons.Filled.Home),
            BottomTab("记录", Icons.Filled.List),
            BottomTab("BT", Icons.Filled.ArrowDropDown),
            BottomTab("插件", Icons.Filled.Build),
            BottomTab("设置", Icons.Filled.Settings)
        )
    }
    GlassBackground(dark = dark) {
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    (
                        fadeIn(tween(300)) +
                            slideInVertically(tween(300)) { height -> height / 14 }
                        ) togetherWith (
                        fadeOut(tween(160)) +
                            slideOutVertically(tween(160)) { height -> -height / 30 }
                        )
                },
                label = "pageSwitch",
                modifier = Modifier.fillMaxSize()
            ) { index ->
                when (index) {
                    0 -> HomeScreen(
                        viewModel = viewModel,
                        settingViewModel = settingViewModel,
                        onOpenRecords = { tab = 1 },
                        onOpenSettings = { tab = 4 },
                        bottomInset = 86.dp
                    )
                    1 -> RecordsScreen(viewModel = viewModel, bottomInset = 86.dp)
                    2 -> BtScreen(viewModel = btViewModel, bottomInset = 86.dp)
                    3 -> PluginScreen(
                        viewModel = pluginViewModel,
                        onMessage = viewModel::notifyMessage,
                        bottomInset = 86.dp
                    )
                    else -> SettingScreen(
                        viewModel = viewModel,
                        settingViewModel = settingViewModel,
                        bottomInset = 86.dp
                    )
                }
            }
            GlassBottomBar(
                tabs = tabs,
                selectedIndex = tab,
                onSelect = { tab = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 10.dp)
            )
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
            )
        }
        // 挂在 AppShell：首页横幅、设置页权限行、启动失败都复用同一个向导
        if (guideVisible) {
            PermissionGuideDialog(
                legacyStorage = permissions.legacyStorage,
                granted = permissions.storage,
                onDismiss = viewModel::dismissPermissionGuide,
                onRetry = {
                    viewModel.refreshPermissions()
                    if (PermissionHelper.hasStorageAccess(context)) {
                        viewModel.dismissPermissionGuide()
                        viewModel.startGuard()
                    } else {
                        viewModel.notifyMessage("还没有检测到授权，请确认开关已打开")
                    }
                }
            )
        }
    }
}

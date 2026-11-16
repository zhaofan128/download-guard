package com.dlguard.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.guardDataStore: DataStore<Preferences> by preferencesDataStore(name = "guard_settings")

data class GuardSettings(
    val autoStart: Boolean = true,
    val notifications: Boolean = true,
    val clipboardWatch: Boolean = false,
    val haptics: Boolean = true,
    val preferInAppDownload: Boolean = true,
    val monitorEnabled: Boolean = true,
    val dynamicColor: Boolean = true,
    val wifiOnlyDownload: Boolean = false,
    /** 并发连接数：1~48，默认 16（设置页滑杆可调，支持自动测速写回）。 */
    val downloadThreads: Int = 16,

    /** 同时下载的任务数上限：多任务队列的并行度，1~5。 */
    val maxParallelTasks: Int = 2,
    /** 是否允许通过「通知使用权」从浏览器/网盘通知里抓取下载链接。 */
    val captureNotificationLinks: Boolean = false,
    /** 是否启用无障碍服务抓取浏览器安装包直链。 */
    val accessibilityCapture: Boolean = false,
    /** 无障碍被系统杀掉后，心跳检测到就自动写回开启（需要 Shizuku）。 */
    val autoRepairAccessibility: Boolean = false,
    /**
     * 跨应用链接嗅探：把无障碍抓取范围从浏览器扩展到微信/QQ 等内嵌 WebView 容器。
     * 默认关闭——这是隐私敏感能力，只抓「明确的文件直链」，不记录聊天与浏览内容。
     */
    val crossAppCapture: Boolean = false,
    /** 首页标题字体：sans（现代无衬线）/ system（跟随系统）/ serif / mono。 */
    val titleFont: String = "sans",
    /**
     * 允许点击校验完成的 APK 时直接调起安装。
     * 走 Shizuku 的 pm install，**不申请 REQUEST_INSTALL_PACKAGES**，
     * 这样安装包本身不会被 OPPO/华为等 ROM 的「安装前安全检测」拦成「已损坏」。
     */
    val allowInstaller: Boolean = true
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val AUTO_START = booleanPreferencesKey("auto_start")
        val NOTIFICATIONS = booleanPreferencesKey("notifications")
        val CLIPBOARD_WATCH = booleanPreferencesKey("clipboard_watch")
        val HAPTICS = booleanPreferencesKey("haptics")
        val PREFER_IN_APP = booleanPreferencesKey("prefer_in_app_download")
        val MONITOR_ENABLED = booleanPreferencesKey("monitor_enabled")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only_download")
        val DOWNLOAD_THREADS = intPreferencesKey("download_threads")
        val MAX_PARALLEL_TASKS = intPreferencesKey("max_parallel_tasks")
        val CAPTURE_NOTIFICATION_LINKS = booleanPreferencesKey("capture_notification_links")
        val ACCESSIBILITY_CAPTURE = booleanPreferencesKey("accessibility_capture")
        val AUTO_REPAIR_ACCESSIBILITY = booleanPreferencesKey("auto_repair_accessibility")
        val ALLOW_INSTALLER = booleanPreferencesKey("allow_installer")
        val CROSS_APP_CAPTURE = booleanPreferencesKey("cross_app_capture")
        val TITLE_FONT = stringPreferencesKey("title_font")
    }

    val settings: Flow<GuardSettings> = context.guardDataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs ->
            GuardSettings(
                autoStart = prefs[Keys.AUTO_START] ?: true,
                notifications = prefs[Keys.NOTIFICATIONS] ?: true,
                clipboardWatch = prefs[Keys.CLIPBOARD_WATCH] ?: false,
                haptics = prefs[Keys.HAPTICS] ?: true,
                preferInAppDownload = prefs[Keys.PREFER_IN_APP] ?: true,
                monitorEnabled = prefs[Keys.MONITOR_ENABLED] ?: true,
                dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
                wifiOnlyDownload = prefs[Keys.WIFI_ONLY] ?: false,
                downloadThreads = (prefs[Keys.DOWNLOAD_THREADS] ?: 16).coerceIn(1, 256),
                maxParallelTasks = (prefs[Keys.MAX_PARALLEL_TASKS] ?: 2).coerceIn(1, 5),
                captureNotificationLinks = prefs[Keys.CAPTURE_NOTIFICATION_LINKS] ?: false,
                accessibilityCapture = prefs[Keys.ACCESSIBILITY_CAPTURE] ?: false,
                autoRepairAccessibility = prefs[Keys.AUTO_REPAIR_ACCESSIBILITY] ?: false,
                crossAppCapture = prefs[Keys.CROSS_APP_CAPTURE] ?: false,
                titleFont = prefs[Keys.TITLE_FONT] ?: "sans",
                allowInstaller = prefs[Keys.ALLOW_INSTALLER] ?: true
            )
        }

    suspend fun setAutoStart(value: Boolean) = put(Keys.AUTO_START, value)
    suspend fun setNotifications(value: Boolean) = put(Keys.NOTIFICATIONS, value)
    suspend fun setClipboardWatch(value: Boolean) = put(Keys.CLIPBOARD_WATCH, value)
    suspend fun setHaptics(value: Boolean) = put(Keys.HAPTICS, value)
    suspend fun setPreferInAppDownload(value: Boolean) = put(Keys.PREFER_IN_APP, value)
    suspend fun setMonitorEnabled(value: Boolean) = put(Keys.MONITOR_ENABLED, value)
    suspend fun setDynamicColor(value: Boolean) = put(Keys.DYNAMIC_COLOR, value)
    suspend fun setWifiOnlyDownload(value: Boolean) = put(Keys.WIFI_ONLY, value)
    suspend fun setCaptureNotificationLinks(value: Boolean) = put(Keys.CAPTURE_NOTIFICATION_LINKS, value)
    suspend fun setAccessibilityCapture(value: Boolean) = put(Keys.ACCESSIBILITY_CAPTURE, value)
    suspend fun setAutoRepairAccessibility(value: Boolean) = put(Keys.AUTO_REPAIR_ACCESSIBILITY, value)
    suspend fun setCrossAppCapture(value: Boolean) = put(Keys.CROSS_APP_CAPTURE, value)

    suspend fun setTitleFont(value: String) {
        val allowed = setOf("sans", "system", "serif", "mono")
        context.guardDataStore.edit { it[Keys.TITLE_FONT] = value.takeIf { v -> v in allowed } ?: "sans" }
    }

    suspend fun setAllowInstaller(value: Boolean) = put(Keys.ALLOW_INSTALLER, value)

    suspend fun setDownloadThreads(value: Int) {
        context.guardDataStore.edit { it[Keys.DOWNLOAD_THREADS] = value.coerceIn(1, 256) }
    }

    suspend fun setMaxParallelTasks(value: Int) {
        context.guardDataStore.edit { it[Keys.MAX_PARALLEL_TASKS] = value.coerceIn(1, 5) }
    }

    private suspend fun put(key: Preferences.Key<Boolean>, value: Boolean) {
        context.guardDataStore.edit { it[key] = value }
    }
}

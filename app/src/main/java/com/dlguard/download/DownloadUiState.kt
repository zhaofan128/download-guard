package com.dlguard.download

/** 下载任务在 UI 层的状态。放在 download 包，保证 DownloadCenter 不反向依赖 ui 层。 */
sealed interface DownloadUiState {
    data object Idle : DownloadUiState

    data class Running(
        val fileName: String,
        val progress: Float,
        val downloaded: Long,
        val total: Long,
        val speed: Long,
        val attempt: Int,
        val connections: Int = 1,
        val etaSeconds: Long = -1
    ) : DownloadUiState

    data class Success(val fileName: String, val path: String, val summary: String) : DownloadUiState
    data class Failed(
        val message: String,
        /** 失败任务的原始 URL，用于「改用网页模式」重试。 */
        val sourceUrl: String = "",
        /** HTTP 状态码；403/404/410 说明链接失效或需浏览器验证。 */
        val httpCode: Int = 0
    ) : DownloadUiState
}

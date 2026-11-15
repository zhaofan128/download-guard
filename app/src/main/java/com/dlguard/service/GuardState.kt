package com.dlguard.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class GuardSnapshot(
    val running: Boolean = false,
    val paused: Boolean = false,
    val checked: Int = 0,
    val broken: Int = 0,
    val directories: Int = 0,
    val watchingSince: Long = 0L,
    val lastCheckedAt: Long = 0L,
    val lastFileName: String? = null,
    val lastVerdict: String? = null
)

/** 服务与 Compose 之间共享的运行态（进程内单例，进程被杀后随服务重建）。 */
object GuardState {
    private val _snapshot = MutableStateFlow(GuardSnapshot())
    val snapshot: StateFlow<GuardSnapshot> = _snapshot.asStateFlow()

    fun update(transform: (GuardSnapshot) -> GuardSnapshot) {
        _snapshot.update(transform)
    }

    fun reset() {
        _snapshot.value = GuardSnapshot()
    }
}

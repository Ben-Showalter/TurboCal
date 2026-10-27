package com.turbocal.app.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-process "a sync is running" flag for the status lines. */
object SyncState {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    internal fun set(running: Boolean) {
        _running.value = running
    }
}

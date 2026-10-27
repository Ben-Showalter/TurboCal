package com.turbocal.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small settings in app-private SharedPreferences. Never holds a feed URL. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("turbocal", Context.MODE_PRIVATE)

    private val _showDisabled = MutableStateFlow(sp.getBoolean(KEY_SHOW_DISABLED, false))
    val showDisabled: StateFlow<Boolean> = _showDisabled.asStateFlow()

    fun setShowDisabled(show: Boolean) {
        sp.edit().putBoolean(KEY_SHOW_DISABLED, show).apply()
        _showDisabled.value = show
    }

    /** Minutes between background syncs; 0 = manual only. */
    var syncIntervalMinutes: Int
        get() = sp.getInt(KEY_INTERVAL, DEFAULT_INTERVAL)
        set(v) = sp.edit().putInt(KEY_INTERVAL, v).apply()

    /** When every enabled feed last synced without error (for the manual-sync throttle). */
    var lastCleanSyncAt: Long?
        get() = sp.getLong(KEY_CLEAN_SYNC, -1).takeIf { it >= 0 }
        set(v) = sp.edit().putLong(KEY_CLEAN_SYNC, v ?: -1).apply()

    var lastUpdateCheckAt: Long?
        get() = sp.getLong(KEY_UPDATE_CHECK, -1).takeIf { it >= 0 }
        set(v) = sp.edit().putLong(KEY_UPDATE_CHECK, v ?: -1).apply()

    companion object {
        const val DEFAULT_INTERVAL = 30
        /** WorkManager's floor is 15 minutes. */
        val INTERVAL_CHOICES = listOf(15, 30, 60, 180, 360, 0)

        fun intervalLabel(minutes: Int): String = when {
            minutes <= 0 -> "Manual only"
            minutes < 60 -> "$minutes minutes"
            minutes == 60 -> "1 hour"
            else -> "${minutes / 60} hours"
        }

        private const val KEY_SHOW_DISABLED = "show_disabled"
        private const val KEY_INTERVAL = "sync_interval_min"
        private const val KEY_CLEAN_SYNC = "last_clean_sync"
        private const val KEY_UPDATE_CHECK = "last_update_check"
    }
}

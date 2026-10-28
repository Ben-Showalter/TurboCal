package com.turbocal.app.data

import android.content.Context
import com.turbocal.core.notify.ReminderSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime

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

    /** Light (default, owner round 6) or dark; every screen applies it in onCreate. */
    var theme: AppTheme
        get() = AppTheme.values().firstOrNull { it.name == sp.getString(KEY_THEME, null) } ?: AppTheme.LIGHT
        set(v) = sp.edit().putString(KEY_THEME, v.name).apply()

    /** The calendar view the app was last on; the launcher reopens it. */
    var lastView: CalendarView
        get() = CalendarView.values().firstOrNull { it.name == sp.getString(KEY_LAST_VIEW, null) } ?: CalendarView.MONTH
        set(v) = sp.edit().putString(KEY_LAST_VIEW, v.name).apply()

    // --- Notifications (Settings > Notifications; owner defaults: on, 5 min before) ---

    var notifyEnabled: Boolean
        get() = sp.getBoolean(KEY_NOTIFY, true)
        set(v) = sp.edit().putBoolean(KEY_NOTIFY, v).apply()

    var notifyLeadMinutes: Int
        get() = sp.getInt(KEY_NOTIFY_LEAD, 5)
        set(v) = sp.edit().putInt(KEY_NOTIFY_LEAD, v).apply()

    /** When all-day events notify, in minutes past midnight (default 8:00 AM). */
    var allDayNotifyMinutes: Int
        get() = sp.getInt(KEY_ALLDAY_TIME, 8 * 60)
        set(v) = sp.edit().putInt(KEY_ALLDAY_TIME, v).apply()

    /** Also show reminders on the Kyocera outer display. */
    var coverCards: Boolean
        get() = sp.getBoolean(KEY_COVER, true)
        set(v) = sp.edit().putBoolean(KEY_COVER, v).apply()

    /** The last reminder instant that fired, so a reschedule never repeats it. */
    var lastReminderAt: Long?
        get() = sp.getLong(KEY_LAST_REMINDER, -1).takeIf { it >= 0 }
        set(v) = sp.edit().putLong(KEY_LAST_REMINDER, v ?: -1).apply()

    var askedNotifyPermission: Boolean
        get() = sp.getBoolean(KEY_ASKED_NOTIFY, false)
        set(v) = sp.edit().putBoolean(KEY_ASKED_NOTIFY, v).apply()

    fun reminderSettings() = ReminderSettings(
        enabled = notifyEnabled,
        leadMinutes = notifyLeadMinutes,
        allDayTime = LocalTime.of(allDayNotifyMinutes / 60, allDayNotifyMinutes % 60),
    )

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

        private const val KEY_NOTIFY = "notify_enabled"
        private const val KEY_NOTIFY_LEAD = "notify_lead_min"
        private const val KEY_ALLDAY_TIME = "notify_allday_min"
        private const val KEY_COVER = "notify_cover"
        private const val KEY_LAST_REMINDER = "notify_last_at"
        private const val KEY_ASKED_NOTIFY = "notify_asked_permission"
        private const val KEY_THEME = "theme"
        private const val KEY_LAST_VIEW = "last_view"
        private const val KEY_SHOW_DISABLED = "show_disabled"
        private const val KEY_INTERVAL = "sync_interval_min"
        private const val KEY_CLEAN_SYNC = "last_clean_sync"
        private const val KEY_UPDATE_CHECK = "last_update_check"
    }
}

enum class AppTheme(val label: String) { DARK("Dark"), LIGHT("Light") }

/** The four interchangeable calendar views, in View-key order. */
enum class CalendarView(val label: String) {
    MONTH("Month"), WEEK("Week"), DAY("Day"), AGENDA("Agenda");

    fun next(): CalendarView = values()[(ordinal + 1) % values().size]
}

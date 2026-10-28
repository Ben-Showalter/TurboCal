package com.turbocal.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import android.util.Log
import com.turbocal.app.data.CalendarRepository
import com.turbocal.app.data.Prefs
import com.turbocal.core.display.Formats
import com.turbocal.core.notify.ReminderTexts
import com.turbocal.core.notify.Reminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Keeps exactly one alarm set: the next reminder group (core Reminders). The
 * guide's rule for alerts that must fire with the app closed is AlarmManager
 * (docs/platform.md). Rescheduled after every sync, phone-calendar edit,
 * settings change, boot, app start and clock/zone change.
 */
class ReminderScheduler(
    private val context: Context,
    private val prefs: Prefs,
    private val repository: CalendarRepository,
    val notifier: ReminderNotifier,
) {
    private val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun rescheduleAsync() {
        background.launch { runCatching { reschedule() }.onFailure { Log.w(TAG, "reschedule failed: ${it.javaClass.simpleName}") } }
    }

    suspend fun reschedule() = withContext(Dispatchers.IO) {
        val s = prefs.reminderSettings()
        if (!s.enabled) {
            alarms.cancel(alarmIntent(0))
            return@withContext
        }
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val after = maxOf(prefs.lastReminderAt?.let { Instant.ofEpochMilli(it) } ?: Instant.EPOCH, now.minusSeconds(60))
        val group = Reminders.nextGroup(load(zone), s, zone, after)
        if (group == null) {
            alarms.cancel(alarmIntent(0))
            return@withContext
        }
        val at = group.at.toEpochMilli()
        val pi = alarmIntent(at)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        Log.i(TAG, "next reminder at $at (${group.events.size} event(s))")
    }

    /** The alarm went off for [atMillis]: post that group (re-read now, so edits count), then set the next. */
    suspend fun fire(atMillis: Long) = withContext(Dispatchers.IO) {
        val s = prefs.reminderSettings()
        val zone = ZoneId.systemDefault()
        val at = Instant.ofEpochMilli(atMillis)
        val late = Instant.now().toEpochMilli() - atMillis
        if (s.enabled && atMillis > 0 && late < MAX_LATE_MS) {
            Reminders.groupAt(load(zone), s, zone, at)?.let { group ->
                val text = ReminderTexts.build(group, s, zone, Formats(Locale.getDefault(), DateFormat.is24HourFormat(context)))
                notifier.post(group, text, group.events.first().time.firstDay(zone))
            }
        }
        if (atMillis > 0) prefs.lastReminderAt = atMillis
        reschedule()
    }

    /** Settings > Notifications > Send a test notification. */
    fun sendTest() {
        background.launch {
            val s = prefs.reminderSettings().copy(enabled = true)
            val zone = ZoneId.systemDefault()
            val events = load(zone).filter { !it.time.endInstant(zone).isBefore(Instant.now()) }.take(3)
            val group = if (events.isEmpty()) null else com.turbocal.core.notify.ReminderGroup(Instant.now(), events)
            notifier.postTest(group, group?.let { ReminderTexts.build(it, s, zone, Formats(Locale.getDefault(), DateFormat.is24HourFormat(context))) })
        }
    }

    /** Shown calendars' events from yesterday to eight days out: enough for any lead time and the all-day time. */
    private suspend fun load(zone: ZoneId) = LocalDate.now(zone).let { repository.eventsOnce(it.minusDays(1), it.plusDays(8), zone) }

    private fun alarmIntent(atMillis: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE).putExtra(ReminderReceiver.EXTRA_AT, atMillis),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "TurboCalRemind"

        /** A group missed by more than this (phone off) is not posted late. */
        private const val MAX_LATE_MS = 10 * 60_000L
    }
}

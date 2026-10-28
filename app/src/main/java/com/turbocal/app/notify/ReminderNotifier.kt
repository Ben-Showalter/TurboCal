package com.turbocal.app.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import com.turbocal.app.R
import com.turbocal.app.data.Prefs
import com.turbocal.app.ui.base.CalendarActivity
import com.turbocal.app.ui.day.DayActivity
import com.turbocal.core.notify.ReminderGroup
import com.turbocal.core.notify.ReminderText
import java.time.LocalDate

/**
 * Posts one notification per reminder group (events starting together are
 * wrapped into one), plus the outer-display card (guide docs/platform.md
 * "Outer display and LED"): the LED is enabled on the channel and with
 * setLights, and the notification and its card share one id so cancelling
 * matches posting.
 */
class ReminderNotifier(private val context: Context, private val prefs: Prefs) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.notify_channel), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.notify_channel_desc)
            enableLights(true)
            lightColor = Color.BLUE
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun post(group: ReminderGroup, text: ReminderText, day: LocalDate) {
        val id = idFor(group.at.epochSecond)
        manager.notify(id, build(id, text, day))
        if (prefs.coverCards) CoverScreenNotifier.notify(context, id, text.cover)
    }

    /** Settings > Notifications > Send a test notification: real upcoming events, or a sample. */
    fun postTest(group: ReminderGroup?, text: ReminderText?) {
        val sample = text ?: ReminderText(
            context.getString(R.string.notify_test_title),
            context.getString(R.string.notify_test_text),
            emptyList(),
            context.getString(R.string.notify_test_title),
        )
        val id = TEST_ID
        manager.notify(id, build(id, sample, LocalDate.now()))
        if (prefs.coverCards) CoverScreenNotifier.notify(context, id, sample.cover)
    }

    fun cancelCover(id: Int) = CoverScreenNotifier.cancel(context, id)

    /** A calendar view is open: the user has seen it, so stop the outer-display cards. */
    fun clearCovers() = CoverScreenNotifier.cancelAll(context)

    @Suppress("DEPRECATION") // Builder(Context), priority, defaults and setLights for API 24–25.
    private fun build(id: Int, text: ReminderText, day: LocalDate): Notification {
        val open = PendingIntent.getActivity(
            context, id,
            CalendarActivity.intentFor(context, DayActivity::class.java, day).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismissed = PendingIntent.getBroadcast(
            context, id,
            Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_DISMISS).putExtra(ReminderReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, CHANNEL) else Notification.Builder(context)
        builder.setSmallIcon(R.drawable.ic_stat_turbocal)
            .setContentTitle(text.title)
            .setContentText(text.text)
            .setContentIntent(open)
            .setDeleteIntent(dismissed)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_EVENT)
            .setShowWhen(true)
            .setLights(Color.BLUE, 1, 0)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
        }
        if (text.lines.size > 1) {
            val style = Notification.InboxStyle().setBigContentTitle(text.title)
            text.lines.forEach { style.addLine(it) }
            builder.setStyle(style)
        }
        return builder.build()
    }

    companion object {
        const val CHANNEL = "event_starts"
        private const val TEST_ID = 1

        /** One id per reminder minute, shared with the outer-display card. */
        fun idFor(epochSecond: Long): Int = (epochSecond / 60 % Int.MAX_VALUE).toInt().coerceAtLeast(2)
    }
}

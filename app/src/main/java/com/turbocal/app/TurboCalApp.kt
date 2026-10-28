package com.turbocal.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.turbocal.app.sync.SyncScheduler
import com.turbocal.core.ics.Ical4jConfig

class TurboCalApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        Ical4jConfig.apply()
        graph = AppGraph(this)
        SyncScheduler.schedulePeriodic(this, graph.prefs.syncIntervalMinutes)
        graph.reminders.notifier.ensureChannel()
        graph.reminders.rescheduleAsync()
        // The expansion window is in local dates: re-sync after a zone change. Reminder
        // times are wall-clock times too, so a zone or clock change moves the alarm.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) SyncScheduler.syncNow(context)
                    graph.reminders.rescheduleAsync()
                }
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED, // system broadcasts still arrive; other apps' don't
        )
    }
}

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
        // The expansion window is in local dates: re-sync after a zone change.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = SyncScheduler.syncNow(context)
            },
            IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED, // a system broadcast still arrives; other apps' don't
        )
    }
}

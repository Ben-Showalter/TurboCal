package com.turbocal.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.turbocal.app.TurboCalApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The reminder alarm, and the notification being dismissed (which also clears its outer-display card). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as TurboCalApp).graph
        when (intent.action) {
            ACTION_DISMISS -> graph.reminders.notifier.cancelCover(intent.getIntExtra(EXTRA_ID, 0))
            ACTION_FIRE -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        graph.reminders.fire(intent.getLongExtra(EXTRA_AT, -1))
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.turbocal.app.REMINDER"
        const val ACTION_DISMISS = "com.turbocal.app.REMINDER_DISMISSED"
        const val EXTRA_AT = "at"
        const val EXTRA_ID = "id"
    }
}

/** Alarms don't survive a reboot or an app update: set the next one again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // TurboCalApp.onCreate already reschedules when the process starts; this keeps it explicit.
        (context.applicationContext as TurboCalApp).graph.reminders.rescheduleAsync()
    }
}

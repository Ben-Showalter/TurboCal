package com.turbocal.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.turbocal.app.TurboCalApp
import com.turbocal.app.ui.base.CalendarActivity
import java.time.LocalDate

/**
 * The launcher entry. Shows nothing: it opens the calendar view the app was
 * last on (Month the first time), on today, and finishes.
 */
class LaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = (application as TurboCalApp).graph.prefs.lastView
        // Clear the task so the view is alone on the back stack and Back exits.
        startActivity(
            CalendarActivity.intentFor(this, CalendarActivity.classFor(view), LocalDate.now())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
        overridePendingTransition(0, 0)
    }
}

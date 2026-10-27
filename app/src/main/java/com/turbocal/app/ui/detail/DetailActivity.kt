package com.turbocal.app.ui.detail

import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import com.turbocal.app.R
import com.turbocal.app.ui.base.CalendarActivity
import com.turbocal.app.ui.base.FlipBaseActivity
import com.turbocal.app.ui.day.DayActivity
import com.turbocal.core.display.DisplayEvent
import com.turbocal.core.display.Formats
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Read-only event detail. The left soft key opens that event's day. */
class DetailActivity : FlipBaseActivity() {
    private var event: DisplayEvent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        setSoftkeys(getString(R.string.key_day), "", "")
        val scroll = findViewById<ScrollView>(R.id.scroll)
        scroll.requestFocus()
        val id = intent.getLongExtra(EXTRA_ID, -1)
        scope.launch {
            val e = graph.repository.event(id)
            event = e
            render(e)
        }
    }

    private fun render(e: DisplayEvent?) {
        val title = findViewById<TextView>(R.id.title)
        if (e == null) {
            title.setText(R.string.detail_missing)
            return
        }
        val zone = ZoneId.systemDefault()
        val formats = Formats(Locale.getDefault(), DateFormat.is24HourFormat(this))
        findViewById<View>(R.id.color_bar).setBackgroundColor(e.color)
        title.text = e.title.ifBlank { "(No title)" }
        title.paint.isFakeBoldText = true // stock font has no bold (guide §4)
        findViewById<TextView>(R.id.`when`).text = formats.detailWhen(e.time, zone, LocalDate.now(zone))
        findViewById<TextView>(R.id.location).apply {
            text = getString(R.string.detail_location, e.location)
            visibility = if (e.location.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.calendar).text = getString(R.string.detail_calendar, e.feedName)
        findViewById<TextView>(R.id.description).apply {
            text = e.description
            visibility = if (e.description.isBlank()) View.GONE else View.VISIBLE
        }
    }

    override fun onPrimaryKey() {
        val e = event ?: return
        startActivity(CalendarActivity.intentFor(this, DayActivity::class.java, e.time.firstDay(ZoneId.systemDefault())))
        finish()
    }

    companion object {
        const val EXTRA_ID = "eventId"
    }
}

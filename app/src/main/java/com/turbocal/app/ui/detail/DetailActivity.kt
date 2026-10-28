package com.turbocal.app.ui.detail

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import com.turbocal.app.R
import com.turbocal.app.data.EventRef
import com.turbocal.app.data.FeedEntity
import com.turbocal.app.ui.base.CalendarActivity
import com.turbocal.app.ui.base.FlipBaseActivity
import com.turbocal.app.ui.day.DayActivity
import com.turbocal.app.ui.eventedit.EventEditActivity
import com.turbocal.core.display.DisplayEvent
import com.turbocal.core.display.Formats
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Read-only event detail. The left soft key opens that event's day. */
class DetailActivity : FlipBaseActivity() {
    private var event: DisplayEvent? = null
    private var ref: EventRef? = null
    private val isLocal get() = ref?.source == FeedEntity.SOURCE_LOCAL

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
            ref = graph.repository.eventRef(id)
            // Phone-calendar events can be changed: left = Edit, right = Options.
            if (isLocal) setSoftkeys(getString(R.string.key_edit), "", getString(R.string.key_options))
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
        findViewById<View>(R.id.color_bar).setBackgroundColor(calendarColor(e.color))
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
        if (isLocal) {
            edit()
            return
        }
        val e = event ?: return
        // Stacked, so Back returns to this event (owner, round 6).
        startActivity(
            CalendarActivity.intentFor(this, DayActivity::class.java, e.time.firstDay(ZoneId.systemDefault()))
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    override fun onOptionsKey() {
        val r = ref ?: return
        if (!isLocal) return
        val repeating = r.recurrenceKey.isNotEmpty()
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions += getString(R.string.event_menu_edit) to { edit() }
        if (repeating) actions += getString(R.string.event_menu_delete_one) to { confirm(R.string.event_delete_one_confirm) { graph.local.deleteOccurrence(r.uid, r.recurrenceKey) } }
        actions += getString(if (repeating) R.string.event_menu_delete_all else R.string.event_menu_delete) to {
            confirm(R.string.event_delete_confirm) { graph.local.delete(r.uid) }
        }
        AlertDialog.Builder(this).setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }.show()
    }

    /** The instance ids change when the calendar is re-expanded, so this screen closes behind the editor. */
    private fun edit() {
        val r = ref ?: return
        startActivity(Intent(this, EventEditActivity::class.java).putExtra(EventEditActivity.EXTRA_UID, r.uid))
        finish()
    }

    private fun confirm(message: Int, action: suspend () -> Unit) {
        val dialog = AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(R.string.delete) { _, _ ->
                scope.launch {
                    action()
                    finish()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }

    companion object {
        const val EXTRA_ID = "eventId"
    }
}

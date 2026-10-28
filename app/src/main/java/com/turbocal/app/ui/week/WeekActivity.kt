package com.turbocal.app.ui.week

import android.content.Intent
import android.os.Bundle
import android.widget.ListView
import com.turbocal.app.R
import com.turbocal.app.data.CalendarView
import com.turbocal.app.ui.base.CalendarActivity
import com.turbocal.app.ui.base.Key
import com.turbocal.app.ui.base.RowAdapter
import com.turbocal.app.ui.day.DayActivity
import com.turbocal.app.ui.detail.DetailActivity
import com.turbocal.core.display.AgendaBuilder
import com.turbocal.core.display.Row
import com.turbocal.core.display.WeekBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * One week, every day with a header (empty days say "No events"). The week
 * starts on the locale's first day, like Month. 1/3 change week; LEFT/RIGHT
 * move a day, crossing into the next or previous week at the ends.
 */
class WeekActivity : CalendarActivity() {
    private lateinit var list: ListView
    private lateinit var adapter: RowAdapter
    private var weekStart: LocalDate = LocalDate.now()
    private var focusDay: LocalDate = LocalDate.now()
    private var job: Job? = null
    private val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek

    override val view = CalendarView.WEEK

    override val currentDate: LocalDate
        get() = adapter.rows.getOrNull(list.selectedItemPosition)?.let { AgendaBuilder.dayOf(it) } ?: focusDay

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        setSoftkeys(getString(R.string.key_view), getString(R.string.key_open), getString(R.string.key_options))
        list = findViewById(R.id.list)
        adapter = RowAdapter(layoutInflater, ::calendarColor)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            when (val row = adapter.getItem(position)) {
                is Row.Event -> startActivity(Intent(this, DetailActivity::class.java).putExtra(DetailActivity.EXTRA_ID, row.eventId))
                is Row.Empty -> jump(intentFor(this, DayActivity::class.java, row.date))
                is Row.DayHeader -> Unit
            }
        }
        observeStatus()
        show(dateFrom(intent, today))
        list.requestFocus()
    }

    /** Shows the week containing [day] and selects [day]'s first row. */
    private fun show(day: LocalDate) {
        focusDay = day
        val start = WeekBuilder.weekStart(day, firstDayOfWeek)
        if (start == weekStart && job != null) {
            adapter.select(list, AgendaBuilder.initialIndex(adapter.rows, day))
            return
        }
        weekStart = start
        setTitleText(formats.weekTitle(start, today))
        job?.cancel()
        var first = true
        job = scope.launch {
            graph.repository.events(start, start.plusDays(7), zone).collect { events ->
                val rows = WeekBuilder.build(events, start, today, zone, formats)
                if (first) {
                    first = false
                    adapter.submit(list, rows) { AgendaBuilder.initialIndex(rows, focusDay) }
                    adapter.select(list, AgendaBuilder.initialIndex(rows, focusDay))
                } else {
                    adapter.submit(list, rows) { AgendaBuilder.initialIndex(rows, focusDay) }
                }
            }
        }
    }

    /** 1 / 3: previous / next week, same weekday. */
    override fun step(direction: Int) = show(currentDate.plusWeeks(direction.toLong()))

    override fun onKey(key: Key): Boolean = when (key) {
        // LEFT / RIGHT: one day, into the neighbouring week at the ends.
        Key.LEFT -> { show(currentDate.minusDays(1)); true }
        Key.RIGHT -> { show(currentDate.plusDays(1)); true }
        else -> super.onKey(key)
    }

    override fun goToday() = show(today)
}

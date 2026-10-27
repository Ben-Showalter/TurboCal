package com.turbocal.app.ui.month

import android.os.Bundle
import android.widget.TextView
import com.turbocal.app.R
import com.turbocal.app.ui.agenda.AgendaActivity
import com.turbocal.app.ui.base.CalendarActivity
import com.turbocal.app.ui.day.DayActivity
import com.turbocal.core.display.DisplayEvent
import com.turbocal.core.display.MonthGrid
import com.turbocal.core.display.MonthNav
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Month grid. The D-pad moves the selected day (and may leave the month), OK
 * opens that day, 1/3 change month. The grid is the only focusable view.
 */
class MonthActivity : CalendarActivity() {
    private lateinit var grid: MonthGridView
    private lateinit var summary: TextView
    private var selected: LocalDate = LocalDate.now()
    private var month: YearMonth? = null
    private var events: List<DisplayEvent> = emptyList()
    private var job: Job? = null
    private val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek

    // The grid takes all four arrows itself.
    override val handlesHorizontalKeys = false

    override val currentDate get() = selected

    override fun nextView() = AgendaActivity::class.java

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_month)
        setSoftkeys(getString(R.string.key_view), getString(R.string.key_open), getString(R.string.key_options))
        grid = findViewById(R.id.grid)
        summary = findViewById(R.id.summary)
        grid.listener = object : MonthGridView.Listener {
            override fun onMove(dx: Int, dy: Int) = select(MonthNav.move(selected, dx, dy))
            override fun onOpen() = jump(intentFor(this@MonthActivity, DayActivity::class.java, selected))
        }
        observeStatus()
        select(dateFrom(intent, today))
        grid.requestFocus()
    }

    private fun select(day: LocalDate) {
        selected = day
        val ym = YearMonth.from(day)
        if (ym != month) {
            month = ym
            setTitleText(formats.monthTitle(day))
            load(ym)
        }
        render()
    }

    private fun load(ym: YearMonth) {
        val (start, end) = MonthGrid.range(ym, firstDayOfWeek)
        job?.cancel()
        job = scope.launch {
            graph.repository.events(start, end, zone).collect {
                events = it
                render()
            }
        }
    }

    private fun render() {
        val ym = month ?: return
        grid.show(MonthGrid.build(ym, firstDayOfWeek, today, events, zone), selected, formats)
        val onDay = events.filter { it.time.occursOn(selected, zone) }
        summary.text = buildString {
            append(formats.dayTitle(selected, today))
            append(" · ")
            append(
                when (onDay.size) {
                    0 -> "No events"
                    1 -> "1 event"
                    else -> "${onDay.size} events"
                },
            )
            onDay.sortedBy { it.time.startInstant(zone) }.firstOrNull()?.let { append("\n").append(it.title.ifBlank { "(No title)" }) }
        }
    }

    override fun step(direction: Int) = select(MonthNav.shiftMonth(selected, direction))

    override fun goToday() = select(today)
}

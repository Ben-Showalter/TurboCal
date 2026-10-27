package com.turbocal.app.ui.day

import android.content.Intent
import android.os.Bundle
import android.widget.ListView
import com.turbocal.app.R
import com.turbocal.app.ui.base.CalendarActivity
import com.turbocal.app.ui.base.RowAdapter
import com.turbocal.app.ui.detail.DetailActivity
import com.turbocal.app.ui.month.MonthActivity
import com.turbocal.core.display.DayBuilder
import com.turbocal.core.display.Row
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate

/** One day: all-day events first, then by time. 1/3 or LEFT/RIGHT change the day. */
class DayActivity : CalendarActivity() {
    private lateinit var list: ListView
    private lateinit var adapter: RowAdapter
    private var day: LocalDate = LocalDate.now()
    private var job: Job? = null

    override val currentDate get() = day

    override fun nextView() = MonthActivity::class.java

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        setSoftkeys(getString(R.string.key_view), getString(R.string.key_open), getString(R.string.key_options))
        list = findViewById(R.id.list)
        adapter = RowAdapter(layoutInflater)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val row = adapter.getItem(position)
            if (row is Row.Event) startActivity(Intent(this, DetailActivity::class.java).putExtra(DetailActivity.EXTRA_ID, row.eventId))
        }
        observeStatus()
        show(dateFrom(intent, today))
        list.requestFocus()
    }

    private fun show(newDay: LocalDate) {
        day = newDay
        setTitleText(formats.dayHeader(day, today))
        job?.cancel()
        var first = true
        job = scope.launch {
            graph.repository.events(day, day.plusDays(1), zone).collect { events ->
                val rows = DayBuilder.build(events, day, zone, formats)
                if (first) {
                    first = false
                    adapter.submit(list, rows) { 0 }
                    adapter.select(list, 0)
                } else {
                    adapter.submit(list, rows) { 0 }
                }
            }
        }
    }

    override fun step(direction: Int) = show(day.plusDays(direction.toLong()))

    override fun goToday() = show(today)
}

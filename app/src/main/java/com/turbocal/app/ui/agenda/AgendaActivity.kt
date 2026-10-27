package com.turbocal.app.ui.agenda

import android.content.Intent
import android.os.Bundle
import android.widget.ListView
import com.turbocal.app.R
import com.turbocal.app.ui.base.CalendarActivity
import com.turbocal.app.ui.base.RowAdapter
import com.turbocal.app.ui.day.DayActivity
import com.turbocal.app.ui.detail.DetailActivity
import com.turbocal.core.display.AgendaBuilder
import com.turbocal.core.display.Row
import com.turbocal.core.ics.ExpansionWindow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The default screen: every day with events, grouped by day, opening on today (AGENTS.md §8). */
class AgendaActivity : CalendarActivity() {
    private lateinit var list: ListView
    private lateinit var adapter: RowAdapter
    private var target: LocalDate = LocalDate.now()
    private var firstLoad = true

    override val currentDate: LocalDate
        get() = adapter.rows.getOrNull(list.selectedItemPosition)?.let { AgendaBuilder.dayOf(it) } ?: target

    override fun nextView() = DayActivity::class.java

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        setTitleText(getString(R.string.title_agenda))
        setSoftkeys(getString(R.string.key_view), getString(R.string.key_open), getString(R.string.key_options))
        target = dateFrom(intent, today)

        list = findViewById(R.id.list)
        adapter = RowAdapter(layoutInflater)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            when (val row = adapter.getItem(position)) {
                is Row.Event -> startActivity(Intent(this, DetailActivity::class.java).putExtra(DetailActivity.EXTRA_ID, row.eventId))
                is Row.Empty -> jump(intentFor(this, DayActivity::class.java, row.date))
                is Row.DayHeader -> Unit
            }
        }
        observeStatus()

        val day0 = today
        val window = ExpansionWindow.around(day0, zone)
        scope.launch {
            graph.repository.events(window.from, window.toExclusive, zone).collect { events ->
                val rows = AgendaBuilder.build(events, window.from, window.toExclusive, day0, zone, formats)
                if (firstLoad) {
                    firstLoad = false
                    adapter.submit(list, rows) { AgendaBuilder.initialIndex(rows, target) }
                    list.requestFocus()
                } else {
                    adapter.submit(list, rows) { AgendaBuilder.initialIndex(rows, currentDateOr(target)) }
                }
            }
        }
        graph.let { g -> scope.launch { g.updates.checkForUpdate(force = false) } }
    }

    private fun currentDateOr(fallback: LocalDate) = runCatching { currentDate }.getOrDefault(fallback)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        target = dateFrom(intent, today)
        if (!firstLoad) adapter.select(list, AgendaBuilder.initialIndex(adapter.rows, target))
    }

    override fun step(direction: Int) {
        val from = list.selectedItemPosition.takeIf { it >= 0 } ?: return
        AgendaBuilder.dayJump(adapter.rows, from, direction)?.let { adapter.select(list, it) }
    }

    override fun goToday() {
        target = today
        adapter.select(list, AgendaBuilder.initialIndex(adapter.rows, target))
    }
}

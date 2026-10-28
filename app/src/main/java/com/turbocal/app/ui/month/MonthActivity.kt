package com.turbocal.app.ui.month

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.turbocal.app.R
import com.turbocal.app.data.CalendarView
import com.turbocal.app.ui.base.CalendarActivity
import com.turbocal.app.ui.day.DayActivity
import com.turbocal.core.display.DayBuilder
import com.turbocal.core.display.DisplayEvent
import com.turbocal.core.display.MonthGrid
import com.turbocal.core.display.MonthNav
import com.turbocal.core.display.Row
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
    private lateinit var dayInfo: LinearLayout
    private var selected: LocalDate = LocalDate.now()
    private var month: YearMonth? = null
    private var events: List<DisplayEvent> = emptyList()
    private var job: Job? = null
    private val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek

    // The grid takes all four arrows itself.
    override val handlesHorizontalKeys = false

    override val currentDate get() = selected

    override val view = CalendarView.MONTH

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_month)
        setSoftkeys(getString(R.string.key_view), getString(R.string.key_open), getString(R.string.key_options))
        grid = findViewById(R.id.grid)
        dayInfo = findViewById(R.id.day_info)
        grid.listener = object : MonthGridView.Listener {
            // LEFT/RIGHT move a day (wrapping into the next/previous week), UP/DOWN a week.
            override fun onMove(dx: Int, dy: Int) = select(MonthNav.move(selected, dx, dy))
            override fun onOpen() = openView(DayActivity::class.java, selected)
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
        renderDayInfo()
    }

    /**
     * The selected day's events under the grid, one line each in Day-view
     * order: up to [MAX_LINES], or the first four and "+N more – OK opens the
     * day". The area is always at least 3 lines tall (day_info_min_height), so
     * the grid only changes between the 3- and 5-line sizes. Not focusable;
     * focus stays on the grid.
     */
    private fun renderDayInfo() {
        val rows = DayBuilder.build(events, selected, zone, formats).filterIsInstance<Row.Event>()
        dayInfo.removeAllViews()
        val lineH = resources.getDimensionPixelSize(R.dimen.day_info_line)
        dayInfo.addView(infoText(formats.dayInfoHeader(selected, today, rows.size), header = true), ViewGroup.LayoutParams.MATCH_PARENT, lineH)
        // Owner, round 8: at most 5 event lines; past that 4 events and "+N more".
        val fit = if (rows.size <= MAX_LINES) rows.size else MAX_LINES - 1
        for (r in rows.take(fit)) {
            val v = layoutInflater.inflate(R.layout.row_day_info, dayInfo, false)
            v.findViewById<View>(R.id.color_bar).setBackgroundColor(calendarColor(r.color))
            v.findViewById<TextView>(R.id.time).text = r.timeLabel
            v.findViewById<TextView>(R.id.title).text = r.title
            dayInfo.addView(v)
        }
        val hidden = rows.size - fit
        if (hidden > 0) dayInfo.addView(infoText(getString(R.string.month_more, hidden), header = false), ViewGroup.LayoutParams.MATCH_PARENT, lineH)
    }

    private fun infoText(text: String, header: Boolean) = TextView(this).apply {
        this.text = text
        isFocusable = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(if (header) R.dimen.text_label else R.dimen.text_meta))
        setTextColor(themeColor(if (header) R.attr.tcAccentText else R.attr.tcTextSecondary))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        select(dateFrom(intent, today))
    }

    override fun step(direction: Int) = select(MonthNav.shiftMonth(selected, direction))

    override fun goToday() = select(today)

    companion object {
        private const val MAX_LINES = 5
    }
}

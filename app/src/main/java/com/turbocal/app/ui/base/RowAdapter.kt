package com.turbocal.app.ui.base

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import com.turbocal.app.R
import com.turbocal.core.display.Row

/**
 * Agenda/Day rows for a ListView. Day headers are disabled items, so the
 * D-pad skips them; events and "No events" rows are selectable. Stable ids
 * let [submit] keep the selection on the same row when data changes.
 */
class RowAdapter(private val inflater: LayoutInflater) : BaseAdapter() {
    var rows: List<Row> = emptyList()
        private set

    /** Replace the rows, keeping the selected row (by id) selected, else the nearest one. */
    fun submit(list: ListView, newRows: List<Row>, fallbackIndex: () -> Int) {
        val selectedId = list.selectedItemId.takeIf { list.selectedItemPosition != ListView.INVALID_POSITION }
        rows = newRows
        notifyDataSetChanged()
        val keep = selectedId?.let { id -> newRows.indexOfFirst { it.stableId == id }.takeIf { it >= 0 } }
        select(list, keep ?: fallbackIndex())
    }

    /** Selects [index] (moved off a header if needed) and scrolls it into view. */
    fun select(list: ListView, index: Int) {
        if (rows.isEmpty()) return
        var i = index.coerceIn(0, rows.size - 1)
        if (!isEnabled(i)) i = (i until rows.size).firstOrNull { isEnabled(it) } ?: (i downTo 0).firstOrNull { isEnabled(it) } ?: return
        list.setSelectionFromTop(i, list.height / 4)
    }

    override fun getCount() = rows.size
    override fun getItem(position: Int): Row = rows[position]
    override fun getItemId(position: Int) = rows[position].stableId
    override fun hasStableIds() = true
    override fun areAllItemsEnabled() = false
    override fun isEnabled(position: Int) = rows[position] !is Row.DayHeader
    override fun getViewTypeCount() = 3
    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.DayHeader -> 0
        is Row.Event -> 1
        is Row.Empty -> 2
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = when (val row = rows[position]) {
        is Row.DayHeader -> (convertView ?: inflater.inflate(R.layout.row_header, parent, false)).also {
            (it as TextView).text = row.label
        }
        is Row.Empty -> (convertView ?: inflater.inflate(R.layout.row_empty, parent, false)).also {
            (it as TextView).text = row.label
        }
        is Row.Event -> (convertView ?: inflater.inflate(R.layout.row_event, parent, false)).also {
            it.findViewById<View>(R.id.color_bar).setBackgroundColor(row.color)
            it.findViewById<TextView>(R.id.time).text = row.timeLabel
            it.findViewById<TextView>(R.id.title).text = row.title
            it.findViewById<TextView>(R.id.subtitle).apply {
                text = row.subtitle
                visibility = if (row.subtitle.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }
}

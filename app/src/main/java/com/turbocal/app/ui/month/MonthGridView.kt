package com.turbocal.app.ui.month

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import com.turbocal.app.R
import com.turbocal.core.display.Formats
import com.turbocal.core.display.MonthGrid
import com.turbocal.core.feeds.FeedColors
import java.time.LocalDate

/**
 * The six-week grid. Every cell is a bordered block: the day number (16sp) at
 * the top-left, then one thin full-width line per event in its calendar's colour,
 * stacked in Day-view order, with "+N" in the top corner for the rest. The selected
 * cell gets a thick accent border over the focus fill; today's number sits
 * on an accent circle. It owns the D-pad while focused. Colours come from
 * the theme, so it follows Dark/Light.
 */
class MonthGridView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Listener {
        fun onMove(dx: Int, dy: Int)
        fun onOpen()
    }

    var listener: Listener? = null

    private var grid: MonthGrid? = null
    private var selected: LocalDate? = null
    private var letters: List<String> = emptyList()
    private var centerDown = false

    private fun attr(id: Int): Int {
        val a = context.obtainStyledAttributes(intArrayOf(id))
        try {
            return a.getColor(0, 0)
        } finally {
            a.recycle()
        }
    }

    private val accent = attr(R.attr.tcAccent)
    private val onAccent = attr(R.attr.tcOnAccent)
    private val primary = attr(R.attr.tcTextPrimary)
    private val secondary = attr(R.attr.tcTextSecondary)
    private val gridLine = attr(R.attr.tcGridLine)
    private val outOfMonth = attr(R.attr.tcOutOfMonth)
    private val focusFill = attr(R.attr.tcFocusFill)
    private val headerBg = attr(R.attr.tcSurfaceHeader)
    private val light = context.obtainStyledAttributes(intArrayOf(R.attr.tcIsLight)).let { a ->
        try {
            a.getBoolean(0, false)
        } finally {
            a.recycle()
        }
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT; textSize = sp(16f) }
    private val more = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT; textSize = sp(11f) }
    private val header = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = sp(11f); color = secondary }
    private val fill = Paint()
    private val line = Paint().apply { color = gridLine }
    private val border = Paint().apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); color = accent }
    private val rect = RectF()
    private val corner = dp(5f)

    init {
        isFocusable = true
        isFocusableInTouchMode = false
    }

    fun show(grid: MonthGrid, selected: LocalDate, formats: Formats) {
        this.grid = grid
        this.selected = selected
        letters = grid.cells.take(7).map { formats.weekdayLetter(it.date) }
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> listener?.onMove(-1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> listener?.onMove(1, 0)
            KeyEvent.KEYCODE_DPAD_UP -> listener?.onMove(0, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> listener?.onMove(0, 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> centerDown = event.repeatCount == 0 || centerDown
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            // Act on UP only for a press this view saw go down (guide §1).
            if (centerDown && !event.isCanceled) listener?.onOpen()
            centerDown = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) centerDown = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val g = grid ?: return
        // Every edge is snapped to a whole pixel: at the E4810's ~0.75 density a
        // 1dp line on a fractional position can vanish (owner, round 6).
        val headerH = Math.round(dp(16f))
        val colX = IntArray(8) { Math.round(width * it / 7f) }
        val rowY = IntArray(MonthGrid.WEEKS + 1) { headerH + Math.round((height - headerH) * it / MonthGrid.WEEKS.toFloat()) }
        val lineW = maxOf(1, Math.round(dp(1f)))

        // Weekday letters on a header band.
        fill.color = headerBg
        canvas.drawRect(0f, 0f, width.toFloat(), headerH.toFloat(), fill)
        for (c in 0 until 7) {
            canvas.drawText(letters.getOrElse(c) { "" }, (colX[c] + colX[c + 1]) / 2f, headerH - dp(4f), header)
        }

        val pad = maxOf(2f, Math.round(dp(2f)).toFloat())
        // Thin event lines with a little space between them (owner, round 6).
        val lineH = maxOf(2f, Math.round(dp(2f)).toFloat())
        val gap = maxOf(2f, Math.round(dp(2f)).toFloat())

        g.cells.forEachIndexed { i, cell ->
            val col = i % 7
            val row = i / 7
            val left = colX[col].toFloat()
            val right = colX[col + 1].toFloat()
            val top = rowY[row].toFloat()
            val bottom = rowY[row + 1].toFloat()
            rect.set(left, top, right, bottom)
            val isSelected = cell.date == selected

            if (!cell.inMonth) {
                fill.color = outOfMonth
                canvas.drawRect(rect, fill)
            }
            if (isSelected) {
                fill.color = focusFill
                val inset = dp(1.5f)
                canvas.drawRoundRect(left + inset, top + inset, right - inset, bottom - inset, corner, corner, fill)
            }

            // Day number, on an accent circle for today.
            // Inset past the rounded selection border so today's circle isn't clipped.
            val nx = left + dp(6f)
            val ny = top + dp(3f) + number.textSize
            number.isFakeBoldText = isSelected || cell.isToday // stock font has no bold (guide §4)
            if (cell.isToday) {
                fill.color = accent
                val label = cell.date.dayOfMonth.toString()
                val w = number.measureText(label)
                canvas.drawCircle(nx + w / 2, ny - number.textSize / 3, number.textSize * 0.72f, fill)
                number.color = onAccent
            } else {
                number.color = if (cell.inMonth) primary else secondary
            }
            canvas.drawText(cell.date.dayOfMonth.toString(), nx, ny, number)

            // One full-width line per event, stacked; "+N" in the corner for the rest.
            val linesTop = ny + dp(4f)
            val room = ((bottom - pad - linesTop + gap) / (lineH + gap)).toInt().coerceAtLeast(0)
            val events = cell.events
            val shown = minOf(events.size, room)
            for (k in 0 until shown) {
                val y = Math.round(linesTop + k * (lineH + gap)).toFloat()
                fill.color = FeedColors.forTheme(events[k].color, light)
                canvas.drawRect(left + pad + lineW, y, right - pad, y + lineH, fill)
            }
            val hidden = events.size - shown
            if (hidden > 0) {
                more.color = if (cell.inMonth) primary else secondary
                more.isFakeBoldText = true
                canvas.drawText("+$hidden", right - pad, top + pad + more.textSize, more)
            }
        }

        // Grid lines between all blocks, as whole-pixel rects.
        for (c in 1 until 7) canvas.drawRect(colX[c].toFloat(), headerH.toFloat(), (colX[c] + lineW).toFloat(), height.toFloat(), line)
        for (r in 0..MonthGrid.WEEKS) {
            val y = minOf(rowY[r], height - lineW).toFloat()
            canvas.drawRect(0f, y, width.toFloat(), y + lineW, line)
        }

        // Selection border last, over the lines, with rounded corners.
        val si = g.cells.indexOfFirst { it.date == selected }
        if (si >= 0) {
            val inset = border.strokeWidth / 2 + dp(1.5f)
            val left = colX[si % 7].toFloat()
            val right = colX[si % 7 + 1].toFloat()
            val top = rowY[si / 7].toFloat()
            val bottom = rowY[si / 7 + 1].toFloat()
            canvas.drawRoundRect(left + inset, top + inset, right - inset, bottom - inset, corner, corner, border)
        }
    }
}

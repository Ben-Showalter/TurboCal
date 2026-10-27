package com.turbocal.app.ui.month

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import com.turbocal.app.R
import com.turbocal.core.display.Formats
import com.turbocal.core.display.MonthGrid
import java.time.LocalDate

/**
 * Draws the six-week grid: weekday letters, day numbers, up to three
 * calendar-colour dots per day, a teal ring on today and a filled teal
 * highlight on the selected day. It owns the D-pad while focused.
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

    private val accent = context.getColor(R.color.accent)
    private val primary = context.getColor(R.color.text_primary)
    private val secondary = context.getColor(R.color.text_secondary)
    private val dim = Color.argb(255, 110, 110, 110)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = sp(15f) }
    private val header = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = sp(11f); color = secondary }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); color = accent }
    private val rect = RectF()

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
        val headerH = dp(16f)
        val cellW = width / 7f
        val cellH = (height - headerH) / MonthGrid.WEEKS
        for (c in 0 until 7) {
            canvas.drawText(letters.getOrElse(c) { "" }, cellW * c + cellW / 2, headerH - dp(4f), header)
        }
        val dotR = dp(2.5f)
        g.cells.forEachIndexed { i, cell ->
            val col = i % 7
            val row = i / 7
            rect.set(cellW * col + dp(1f), headerH + cellH * row + dp(1f), cellW * (col + 1) - dp(1f), headerH + cellH * (row + 1) - dp(1f))
            val isSelected = cell.date == selected
            if (isSelected) {
                fill.color = accent
                canvas.drawRoundRect(rect, dp(4f), dp(4f), fill)
            } else if (cell.isToday) {
                canvas.drawRoundRect(rect, dp(4f), dp(4f), ring)
            }
            text.color = when {
                isSelected -> Color.BLACK
                !cell.inMonth -> dim
                else -> primary
            }
            text.isFakeBoldText = isSelected || cell.isToday // stock font has no bold (guide §4)
            val baseline = rect.centerY() + text.textSize / 3 - if (cell.colors.isEmpty()) 0f else dp(3f)
            canvas.drawText(cell.date.dayOfMonth.toString(), rect.centerX(), baseline, text)
            if (cell.colors.isNotEmpty()) {
                val y = rect.bottom - dotR * 2
                val spacing = dotR * 3
                var x = rect.centerX() - spacing * (cell.colors.size - 1) / 2
                for (color in cell.colors) {
                    fill.color = if (isSelected) Color.BLACK else color
                    canvas.drawCircle(x, y, dotR, fill)
                    x += spacing
                }
            }
        }
    }
}

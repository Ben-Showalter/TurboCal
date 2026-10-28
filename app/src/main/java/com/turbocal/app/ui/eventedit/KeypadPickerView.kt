package com.turbocal.app.ui.eventedit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import com.turbocal.app.R
import com.turbocal.core.input.KeypadEntry

/**
 * The date/time picker body: the segments in large type with the focused
 * one filled in the accent colour. All keys are its own while it has focus:
 * digits type (core KeypadEntry), LEFT/RIGHT move, UP/DOWN change, Clear
 * deletes a digit (or cancels when there is none), OK sets.
 */
class KeypadPickerView(
    context: Context,
    private val entry: KeypadEntry,
    private val separator: String,
    private val onSet: () -> Unit,
    private val onCancel: () -> Unit,
    private val onChange: () -> Unit,
) : View(context) {

    private var centerDown = false

    private fun attr(id: Int): Int {
        val a = context.obtainStyledAttributes(intArrayOf(id))
        try {
            return a.getColor(0, 0)
        } finally {
            a.recycle()
        }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(24f); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = attr(R.attr.tcAccent) }
    private val primary = attr(R.attr.tcTextPrimary)
    private val onAccent = attr(R.attr.tcOnAccent)
    private val rect = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = false
        minimumHeight = dp(48f).toInt()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> if (event.repeatCount == 0) entry.type(keyCode - KeyEvent.KEYCODE_0)
            KeyEvent.KEYCODE_NUMPAD_0, KeyEvent.KEYCODE_NUMPAD_1, KeyEvent.KEYCODE_NUMPAD_2, KeyEvent.KEYCODE_NUMPAD_3,
            KeyEvent.KEYCODE_NUMPAD_4, KeyEvent.KEYCODE_NUMPAD_5, KeyEvent.KEYCODE_NUMPAD_6, KeyEvent.KEYCODE_NUMPAD_7,
            KeyEvent.KEYCODE_NUMPAD_8, KeyEvent.KEYCODE_NUMPAD_9,
            -> if (event.repeatCount == 0) entry.type(keyCode - KeyEvent.KEYCODE_NUMPAD_0)
            KeyEvent.KEYCODE_DPAD_LEFT -> entry.move(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> entry.move(1)
            KeyEvent.KEYCODE_DPAD_UP -> entry.step(1)
            KeyEvent.KEYCODE_DPAD_DOWN -> entry.step(-1)
            // Clear sends BACK on Kyocera: delete a digit while typing, otherwise cancel.
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_BACK -> if (!entry.backspace() && event.repeatCount == 0) onCancel()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.repeatCount == 0) centerDown = true
                return true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        onChange()
        invalidate()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // Only for a press this view saw go down (guide §1).
                if (centerDown && !event.isCanceled) onSet()
                centerDown = false
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DEL,
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            -> return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDraw(canvas: Canvas) {
        val n = entry.size
        val texts = (0 until n).map { entry.text(it) }
        val widths = (0 until n).map { i -> maxOf(text.measureText(if (entry.kind(i) == KeypadEntry.Kind.YEAR) "0000" else "00"), text.measureText(texts[i])) + dp(10f) }
        val sepW = text.measureText(separator) + dp(4f)
        val total = widths.sum() + sepW * (n - 1)
        var x = (width - total) / 2
        val baseline = height / 2f + text.textSize / 3
        for (i in 0 until n) {
            val w = widths[i]
            if (i == entry.focus) {
                rect.set(x, height / 2f - text.textSize * 0.75f, x + w, height / 2f + text.textSize * 0.65f)
                canvas.drawRoundRect(rect, dp(4f), dp(4f), fill)
            }
            text.color = if (i == entry.focus) onAccent else primary
            canvas.drawText(texts[i], x + w / 2, baseline, text)
            x += w
            if (i < n - 1) {
                // AM/PM sits after a space, not a separator.
                val sep = if (entry.kind(i + 1) == KeypadEntry.Kind.AMPM) " " else separator
                text.color = primary
                canvas.drawText(sep, x + sepW / 2, baseline, text)
                x += sepW
            }
        }
    }
}

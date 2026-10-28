package com.turbocal.app.ui.base

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.turbocal.app.R
import com.turbocal.app.TurboCalApp
import com.turbocal.app.data.AppTheme
import com.turbocal.core.feeds.FeedColors
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Every TurboCal screen extends this (guide §1, from its FlipBaseActivity
 * template). Screens override the hooks, never onKeyDown/dispatchKeyEvent.
 *
 *  - Left soft key / 7  → [onPrimaryKey]       (the screen's main action)
 *  - Right soft key / 9 / MENU → [onOptionsKey]
 *  - 1 3 5 0 * #, D-pad LEFT/RIGHT → [onKey]
 *  - OK (CENTER or ENTER) and D-pad UP/DOWN go to the focused view.
 *  - Back keeps the normal back stack.
 *
 * One-shot actions fire on key UP, only for a press whose DOWN this window
 * saw, so the UP of the key that opened a screen never fires here (guide §1).
 * While a text field has focus, digits and soft keys go to the system T9, and
 * Clear (which sends BACK on Kyocera) deletes a character until the field's
 * cursor reaches the start.
 *
 * This is a framework Activity, not AppCompat: no AppCompat options panel can
 * open on MENU, and the app needs nothing else from AppCompat.
 */
abstract class FlipBaseActivity : Activity() {

    protected val graph get() = (application as TurboCalApp).graph

    /** Cancelled in onDestroy. Screens collect their flows here. */
    protected val scope = MainScope()

    /** Keycodes whose DOWN reached this window and is still waiting for its UP. */
    private val pendingDowns = HashSet<Int>()

    /** True on screens that use D-pad LEFT/RIGHT themselves (day stepping). */
    protected open val handlesHorizontalKeys: Boolean = false

    open fun onPrimaryKey() {}
    open fun onOptionsKey() {}

    /**
     * True when the focused view takes digit keys itself (the editor's date and
     * time rows open a picker with the digit typed). Then 0–9 skip the shortcuts.
     */
    protected open fun wantsDigits(): Boolean = false

    /** Return true if handled. LEFT/RIGHT arrive here only when [handlesHorizontalKeys]. */
    open fun onKey(key: Key): Boolean = false

    private val barFocusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        if (newFocus is EditText) SystemBars.showNavigation(this) else SystemBars.hideNavigation(this)
    }

    private var appliedTheme: AppTheme? = null

    /** True once onResume has asked for a recreate (theme change); subclasses stop there. */
    protected var recreating = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        val theme = graph.prefs.theme
        appliedTheme = theme
        setTheme(if (theme == AppTheme.LIGHT) R.style.AppTheme_Light else R.style.AppTheme_Dark)
        super.onCreate(savedInstanceState)
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(barFocusListener)
    }

    /** A calendar's colour as drawn in this theme (darkened on Light, see FeedColors.forTheme). */
    fun calendarColor(color: Int): Int {
        val a = obtainStyledAttributes(intArrayOf(R.attr.tcIsLight))
        val light = try {
            a.getBoolean(0, false)
        } finally {
            a.recycle()
        }
        return FeedColors.forTheme(color, light)
    }

    /** A colour role (R.attr.tc…) from the current theme. */
    fun themeColor(attr: Int): Int {
        val a = obtainStyledAttributes(intArrayOf(attr))
        try {
            return a.getColor(0, 0)
        } finally {
            a.recycle()
        }
    }

    override fun onDestroy() {
        window.decorView.viewTreeObserver.removeOnGlobalFocusChangeListener(barFocusListener)
        scope.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // The theme changed in Settings while this screen was underneath: repaint.
        if (appliedTheme != null && appliedTheme != graph.prefs.theme) {
            recreating = true
            recreate()
            return
        }
        SystemBars.hideNavigation(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) SystemBars.hideNavigation(this) else pendingDowns.clear()
    }

    override fun onPause() {
        pendingDowns.clear()
        super.onPause()
    }

    /** Sets the three soft-key labels; an empty label leaves that key unlabelled. */
    protected fun setSoftkeys(left: CharSequence, center: CharSequence, right: CharSequence) {
        findViewById<TextView>(R.id.softkey_left)?.text = left
        findViewById<TextView>(R.id.softkey_center)?.text = center
        findViewById<TextView>(R.id.softkey_right)?.text = right
    }

    protected fun setTitleText(title: CharSequence) {
        findViewById<TextView>(R.id.title)?.text = title
    }

    protected fun setStatusText(status: CharSequence) {
        findViewById<TextView>(R.id.status)?.text = status
    }

    /** Launches on this screen's scope (for helpers outside the subclass). */
    fun launchOnScreen(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    protected fun toast(text: CharSequence) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    protected fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    /** Sideways navigation: replace this screen rather than stack on it (guide §1). */
    protected fun jump(intent: Intent) {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        finish()
        overridePendingTransition(0, 0)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.repeatCount == 0) {
            Log.d(KEY_TAG, "${if (down) "DOWN" else "UP"} ${KeyEvent.keyCodeToString(code)} scan=${event.scanCode}")
        }

        val field = currentFocus as? EditText
        if (field != null) return dispatchWhileTyping(event, field)

        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && wantsDigits()) return super.dispatchKeyEvent(event)

        val key = KeyMap.map(code)
        if (key == null) {
            if (down && event.repeatCount == 0 && !KeyMap.isKnownUnmapped(code)) {
                // Rugged phones remap buttons: show unknown keys (guide §7), even in release builds.
                toast(getString(R.string.unhandled_key, code))
            }
            return super.dispatchKeyEvent(event)
        }

        if (KeyMap.isMovement(key)) {
            if (!handlesHorizontalKeys) return super.dispatchKeyEvent(event)
            if (down) onKey(key)
            return true
        }

        // One-shot: consume the whole press (DOWN, repeats, UP), act on UP.
        if (down) {
            if (event.repeatCount == 0) pendingDowns += code
        } else if (event.action == KeyEvent.ACTION_UP && pendingDowns.remove(code) && !event.isCanceled) {
            when (key) {
                Key.PRIMARY -> onPrimaryKey()
                Key.OPTIONS -> onOptionsKey()
                else -> onKey(key)
            }
        }
        return true
    }

    /** Clear-as-backspace; everything else belongs to the field and the system T9 (guide §3). */
    private fun dispatchWhileTyping(event: KeyEvent, field: EditText): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_MENU) return true // never let MENU open anything over the T9
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val start = field.selectionStart
                val end = field.selectionEnd
                if (start >= 0 && (start > 0 || end > start)) {
                    if (end > start) field.text.delete(start, end) else field.text.delete(start - 1, start)
                    pendingDowns += code
                    return true
                }
            } else if (event.action == KeyEvent.ACTION_UP && pendingDowns.remove(code)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val KEY_TAG = "TurboCalKeys"
    }
}

package com.turbocal.app.ui.base

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText

/**
 * Hides the phone's white soft-key label bar at the bottom of the screen.
 * On keypad phones that bar is the system *navigation bar*. Your app draws
 * its own labelled soft-key bar, so hide this one on every screen and don't
 * offer a setting to show it.
 *
 * Call hideNavigation() from onResume() and from onWindowFocusChanged(true)
 * (see FlipBaseActivity.kt).
 *
 * Except while a text field has focus: the system T9 keyboard shows its word
 * and mode labels in that bar. hideNavigation() does nothing then, and its
 * re-hide listener leaves the bar alone. FlipBaseActivity calls
 * showNavigation() when focus moves into a text field and hideNavigation()
 * when it moves out (a global focus-change listener).
 *
 * Two layers, always both:
 *  - Android 11+: the insets controller.
 *  - Every API level: the old sticky-immersive flags, on the decor view AND
 *    in the window attributes, because vendor keypad ROMs often honor only
 *    these. The listener puts them back if the system clears them.
 * The window is not extended under the bar, so content just grows into the
 * freed space. If a ROM refuses to hide the bar, your own bar sits above it
 * rather than behind it.
 */
object SystemBars {

    @Suppress("DEPRECATION")
    private const val LEGACY_FLAGS =
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

    /** True while a text field has focus: the keyboard needs the bar then. */
    private fun typing(activity: Activity) = activity.currentFocus is EditText

    fun hideNavigation(activity: Activity) {
        val window = activity.window ?: return
        val decor = window.decorView
        val hide = hide@{
            if (typing(activity)) return@hide
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let {
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    it.hide(WindowInsets.Type.navigationBars())
                }
            }
            @Suppress("DEPRECATION")
            run {
                decor.systemUiVisibility = decor.systemUiVisibility or LEGACY_FLAGS
                // The window params survive system-initiated clears better than the view flag alone.
                val lp = window.attributes
                if (lp.systemUiVisibility and LEGACY_FLAGS != LEGACY_FLAGS) {
                    lp.systemUiVisibility = lp.systemUiVisibility or LEGACY_FLAGS
                    window.attributes = lp
                }
                // If the system brings the bar back (after a dialog, say), hide it again.
                decor.setOnSystemUiVisibilityChangeListener { visibility ->
                    val shown = visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0
                    if (shown && !typing(activity)) {
                        decor.systemUiVisibility = decor.systemUiVisibility or LEGACY_FLAGS
                    }
                }
            }
        }
        hide()
        // Early in a launch the decor isn't attached to the window yet, so apply again once it is.
        decor.post { hide() }
    }

    /** Brings the bar back, for a text field's keyboard labels. */
    fun showNavigation(activity: Activity) {
        val window = activity.window ?: return
        val decor = window.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.navigationBars())
        }
        @Suppress("DEPRECATION")
        run {
            decor.systemUiVisibility = decor.systemUiVisibility and LEGACY_FLAGS.inv()
            val lp = window.attributes
            if (lp.systemUiVisibility and LEGACY_FLAGS != 0) {
                lp.systemUiVisibility = lp.systemUiVisibility and LEGACY_FLAGS.inv()
                window.attributes = lp
            }
        }
    }
}

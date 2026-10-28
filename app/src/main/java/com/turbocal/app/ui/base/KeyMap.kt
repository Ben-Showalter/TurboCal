package com.turbocal.app.ui.base

import android.view.KeyEvent

/** What a key means in TurboCal (AGENTS.md §8). The only place that reads keycodes. */
enum class Key { PRIMARY, OPTIONS, LEFT, RIGHT, PREV, NEXT, TODAY, SYNC, SETTINGS }

object KeyMap {

    fun map(keyCode: Int): Key? = when (keyCode) {
        // Left soft key = the screen's primary action; 7 is its alias for other D-pad phones.
        KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_7 -> Key.PRIMARY
        // Right soft key = Options; MENU too (some phones send it for a soft key); 9 is the alias.
        KeyEvent.KEYCODE_SOFT_RIGHT, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_9 -> Key.OPTIONS
        KeyEvent.KEYCODE_DPAD_LEFT -> Key.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Key.RIGHT
        KeyEvent.KEYCODE_1 -> Key.PREV
        KeyEvent.KEYCODE_3 -> Key.NEXT
        KeyEvent.KEYCODE_5 -> Key.TODAY
        KeyEvent.KEYCODE_0 -> Key.SYNC
        KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> Key.SETTINGS
        else -> null
    }

    /** Keys the system or the focused view handles; never reported as "unknown". */
    fun isKnownUnmapped(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_ENDCALL, KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        // # had "show hidden calendars" until round 8; the Calendars screen does that now.
        KeyEvent.KEYCODE_POUND,
        KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_8,
        KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_FOCUS, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        -> true
        else -> false
    }

    /** One-shot keys fire on UP; movement keys act on DOWN and may repeat. */
    fun isMovement(key: Key): Boolean = key == Key.LEFT || key == Key.RIGHT
}

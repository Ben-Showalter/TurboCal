package com.turbocal.app.ui.base

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The README key map and AGENTS.md §8 must match this. */
class KeyMapTest {
    @Test fun `soft keys follow the guide and 7 and 9 alias them`() {
        assertEquals(Key.PRIMARY, KeyMap.map(KeyEvent.KEYCODE_SOFT_LEFT))
        assertEquals(Key.PRIMARY, KeyMap.map(KeyEvent.KEYCODE_7))
        assertEquals(Key.OPTIONS, KeyMap.map(KeyEvent.KEYCODE_SOFT_RIGHT))
        assertEquals(Key.OPTIONS, KeyMap.map(KeyEvent.KEYCODE_MENU))
        assertEquals(Key.OPTIONS, KeyMap.map(KeyEvent.KEYCODE_9))
    }

    @Test fun `number shortcuts`() {
        assertEquals(Key.PREV, KeyMap.map(KeyEvent.KEYCODE_1))
        assertEquals(Key.NEXT, KeyMap.map(KeyEvent.KEYCODE_3))
        assertEquals(Key.TODAY, KeyMap.map(KeyEvent.KEYCODE_5))
        assertEquals(Key.SYNC, KeyMap.map(KeyEvent.KEYCODE_0))
        assertEquals(Key.SETTINGS, KeyMap.map(KeyEvent.KEYCODE_STAR))
        assertEquals(Key.SETTINGS, KeyMap.map(KeyEvent.KEYCODE_NUMPAD_MULTIPLY))
        assertNull(KeyMap.map(KeyEvent.KEYCODE_POUND))
        assertTrue(KeyMap.isKnownUnmapped(KeyEvent.KEYCODE_POUND)) // no "KEY" toast
    }

    @Test fun `OK, Back and vertical D-pad stay with the system and focused view`() {
        for (k in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
            assertNull(KeyMap.map(k))
            assertTrue(KeyMap.isKnownUnmapped(k))
        }
        assertFalse(KeyMap.isKnownUnmapped(287)) // the Kyocera mic key: reported as unknown
        assertTrue(KeyMap.isMovement(Key.LEFT))
        assertFalse(KeyMap.isMovement(Key.SYNC))
    }
}

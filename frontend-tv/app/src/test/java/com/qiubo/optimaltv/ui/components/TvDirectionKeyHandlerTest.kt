package com.qiubo.optimaltv.ui.components

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvDirectionKeyHandlerTest {
    @Test
    fun unhandledDirectionReachesNativeFocus() {
        val handler = TvDirectionKeyHandler()
        assertFalse(handler.handle(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN) { false })
        assertFalse(handler.handle(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP) { true })
    }

    @Test
    fun explicitActionRunsOnDownAndConsumesMatchingRelease() {
        val handler = TvDirectionKeyHandler()
        var clicks = 0
        val action: (Int) -> Boolean = { clicks++; true }
        assertTrue(handler.handle(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, onDirection = action))
        assertTrue(handler.handle(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP, onDirection = action))
        assertEquals(1, clicks)
        assertFalse(handler.handle(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_UP, onDirection = action))
    }

    @Test
    fun disabledAndSystemKeysNeverInvokePageAction() {
        val handler = TvDirectionKeyHandler()
        var calls = 0
        val action: (Int) -> Boolean = { calls++; true }
        assertFalse(handler.handle(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, false, action))
        assertFalse(handler.handle(KeyEvent.KEYCODE_HOME, KeyEvent.ACTION_DOWN, onDirection = action))
        assertFalse(handler.handle(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, onDirection = action))
        assertEquals(0, calls)
    }
}

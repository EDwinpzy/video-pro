package com.qiubo.optimaltv.ui.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteDirectionHoldTest {
    @Test
    fun nativeRepeatAcceleratesAndReleaseThenNewPressResets() {
        val hold = RemoteDirectionHold()
        hold.update(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, 0, 100, 100)
        assertEquals(10_000L, RemoteSeekPolicy.acceleratedDelta(10_000, hold.durationMs))
        hold.update(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, 12, 100, 4_100)
        assertEquals(40_000L, RemoteSeekPolicy.acceleratedDelta(10_000, hold.durationMs))
        hold.update(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP, 0, 100, 4_200)
        assertEquals(0L, hold.durationMs)
        hold.update(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN, 0, 5_000, 5_000)
        assertEquals(-10_000L, RemoteSeekPolicy.acceleratedDelta(-10_000, hold.durationMs))
    }

    @Test
    fun directionChangeAndInvalidClockCannotReusePreviousHold() {
        val hold = RemoteDirectionHold()
        hold.update(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN, 1, 100, 8_100)
        assertEquals(8_000L, hold.durationMs)
        hold.update(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN, 0, 9_000, 9_000)
        assertEquals(0L, hold.durationMs)
        hold.update(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, 1, 9_000, 8_000)
        assertEquals(0L, hold.durationMs)
    }
}

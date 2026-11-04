package com.qiubo.optimaltv.ui.components

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectionKeyHoldStateTest {
    @Test
    fun normalLongPressWaitsForRelease() {
        var now = 0L
        val state = DirectionKeyHoldState { now }

        state.onAction(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN)
        now = 300_000_000L
        assertFalse(state.isIdle(staleAfterNanos = 900_000_000L))

        state.onAction(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP)
        assertTrue(state.isIdle(staleAfterNanos = 900_000_000L))
    }

    @Test
    fun repeatRefreshesHoldAndOtherKeysRemainTracked() {
        var now = 0L
        val state = DirectionKeyHoldState { now }

        state.onAction(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN)
        state.onAction(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN)
        now = 800_000_000L
        state.onAction(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN)
        now = 1_000_000_000L
        assertFalse(state.isIdle(staleAfterNanos = 900_000_000L))

        state.onAction(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP)
        assertFalse(state.isIdle(staleAfterNanos = 900_000_000L))
        state.onAction(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP)
        assertTrue(state.isIdle(staleAfterNanos = 900_000_000L))
    }

    @Test
    fun lostReleaseCannotKeepAutoSelectWaitingForever() {
        var now = 0L
        val state = DirectionKeyHoldState { now }

        state.onAction(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN)
        now = 900_000_000L
        assertTrue(state.isIdle(staleAfterNanos = 900_000_000L))
    }
}

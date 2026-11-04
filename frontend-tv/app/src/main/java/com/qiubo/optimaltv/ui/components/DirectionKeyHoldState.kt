package com.qiubo.optimaltv.ui.components

import android.view.KeyEvent

/**
 * Tracks which direction keys are physically held.
 *
 * A stale-event fallback exists only for the rare case where the system loses
 * the matching ACTION_UP; it must not turn a normal long press into an early
 * auto-select.
 */
internal class DirectionKeyHoldState(
    private val clockNanos: () -> Long = { System.nanoTime() },
) {
    private val heldKeys = mutableSetOf<Int>()
    private var lastEventAtNanos = Long.MIN_VALUE

    @Synchronized
    fun onAction(
        keyCode: Int,
        action: Int,
    ) {
        when (action) {
            KeyEvent.ACTION_DOWN -> {
                heldKeys += keyCode
                lastEventAtNanos = clockNanos()
            }
            KeyEvent.ACTION_UP -> {
                heldKeys -= keyCode
                lastEventAtNanos = clockNanos()
            }
        }
    }

    @Synchronized
    fun isIdle(staleAfterNanos: Long): Boolean {
        if (heldKeys.isEmpty()) return true
        return clockNanos() - lastEventAtNanos >= staleAfterNanos
    }

    @Synchronized
    fun reset() {
        heldKeys.clear()
        lastEventAtNanos = Long.MIN_VALUE
    }
}

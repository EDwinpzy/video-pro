package com.qiubo.optimaltv.ui.player

import android.view.KeyEvent

/** Native event timing, independent of the retired geometric navigation dispatcher. */
internal class RemoteDirectionHold {
    var durationMs: Long = 0L
        private set

    fun update(keyCode: Int, action: Int, repeatCount: Int, downTime: Long, eventTime: Long) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> Unit
            else -> return
        }
        durationMs = if (action == KeyEvent.ACTION_DOWN && repeatCount > 0) {
            (eventTime - downTime).coerceAtLeast(0L)
        } else {
            0L
        }
    }
}

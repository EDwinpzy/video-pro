package com.qiubo.optimaltv.ui.components

import android.view.KeyEvent

/** Only explicit page actions consume directions; ordinary navigation reaches Compose. */
internal class TvDirectionKeyHandler {
    private val consumed = mutableSetOf<Int>()

    fun handle(
        keyCode: Int,
        action: Int,
        enabled: Boolean = true,
        onDirection: ((Int) -> Boolean)?,
    ): Boolean {
        val direction = navDirOf(keyCode) ?: return false
        OtvNav.onDirectionKeyAction(keyCode, action)
        if (action == KeyEvent.ACTION_UP) {
            return consumed.remove(keyCode)
        }
        if (action != KeyEvent.ACTION_DOWN || !enabled) return false
        val handled = onDirection?.invoke(direction) == true
        if (handled) consumed.add(keyCode) else consumed.remove(keyCode)
        return handled
    }
}

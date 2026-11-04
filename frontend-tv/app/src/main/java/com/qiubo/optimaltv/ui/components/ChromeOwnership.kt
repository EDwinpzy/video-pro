package com.qiubo.optimaltv.ui.components

/**
 * Prevents a leaving page from clearing chrome state acquired by the page that
 * replaced it during a navigation transition.
 */
internal class ChromeOwnership {
    private var activeOwner: Any? = null

    fun acquire(owner: Any) {
        activeOwner = owner
    }

    fun release(owner: Any): Boolean {
        if (activeOwner !== owner) return false
        activeOwner = null
        return true
    }

    fun reset() {
        activeOwner = null
    }
}

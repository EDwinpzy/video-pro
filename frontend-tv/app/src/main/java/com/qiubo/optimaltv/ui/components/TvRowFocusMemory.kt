package com.qiubo.optimaltv.ui.components

/** A row remembers business identity, never a disposed focus target or item index. */
class TvRowFocusMemory {
    var lastFocusedKey: String? = null
        private set

    fun focused(key: String) { lastFocusedKey = key }

    fun entryKey(keys: List<String>, visibleKeys: Set<String>): String? {
        val last = lastFocusedKey
        if (last in keys && last in visibleKeys) return last
        return keys.firstOrNull { it in visibleKeys }
    }
}

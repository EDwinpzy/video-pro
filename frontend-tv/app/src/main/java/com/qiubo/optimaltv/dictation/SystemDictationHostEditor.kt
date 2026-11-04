package com.qiubo.optimaltv.dictation

/**
 * Bridges system dictation to an app-owned editor that does not expose an Android input
 * connection, such as the TV search screen's custom keyboard.
 */
internal class SystemDictationHostEditorHandle internal constructor(
    val token: Long,
) {
    @Volatile
    private var closed = false

    fun updateCommit(commit: (String) -> Boolean) {
        if (closed) return
        SystemDictationHostEditorRegistry.updateCommit(token, commit)
    }

    fun setActive(active: Boolean) {
        if (closed) return
        if (SystemDictationHostEditorRegistry.setActive(token, active)) {
            SystemDictationService.notifyHostEditorStateChanged()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        if (SystemDictationHostEditorRegistry.close(token)) {
            SystemDictationService.notifyHostEditorStateChanged()
        }
    }
}

internal object SystemDictationHostEditorRegistry {
    private data class Entry(
        val token: Long,
        var commit: (String) -> Boolean,
        var active: Boolean,
    )

    private var nextToken = 1L
    private var current: Entry? = null

    @Synchronized
    fun register(commit: (String) -> Boolean): SystemDictationHostEditorHandle {
        val token = nextToken++
        current = Entry(token = token, commit = commit, active = false)
        return SystemDictationHostEditorHandle(token)
    }

    @Synchronized
    fun updateCommit(
        token: Long,
        commit: (String) -> Boolean,
    ) {
        current?.takeIf { it.token == token }?.commit = commit
    }

    @Synchronized
    fun setActive(
        token: Long,
        active: Boolean,
    ): Boolean {
        val entry = current?.takeIf { it.token == token } ?: return false
        if (entry.active == active) return false
        entry.active = active
        return true
    }

    @Synchronized
    fun close(token: Long): Boolean {
        val entry = current?.takeIf { it.token == token } ?: return false
        current = null
        return entry.active
    }

    @Synchronized
    fun activeToken(): Long? = current?.takeIf { it.active }?.token

    fun hasActiveEditor(): Boolean = activeToken() != null

    fun commit(
        token: Long,
        text: String,
    ): Boolean {
        val handler =
            synchronized(this) {
                current
                    ?.takeIf { it.token == token && it.active }
                    ?.commit
            } ?: return false
        return runCatching { handler(text) }.getOrDefault(false)
    }

    @Synchronized
    internal fun clearForTest() {
        current = null
    }
}

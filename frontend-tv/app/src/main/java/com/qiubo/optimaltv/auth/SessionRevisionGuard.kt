package com.qiubo.optimaltv.auth

/** Commits an asynchronous auth result only while its captured session is current. */
internal class SessionRevisionGuard {
    private var revision = 0L
    private var pendingRevision: Long? = null

    @Synchronized
    fun <T> snapshot(read: () -> T): Pair<Long, T> = revision to read()

    /**
     * Invalidates every request bound to the previous session before an interactive
     * login starts. The old stored session remains available until the login either
     * succeeds or fails, but requests started during this window are rejected.
     */
    @Synchronized
    fun beginTransition(): Long {
        revision += 1
        pendingRevision = revision
        return revision
    }

    @Synchronized
    fun finishTransition(expectedRevision: Long) {
        if (pendingRevision == expectedRevision) pendingRevision = null
    }

    @Synchronized
    fun isTransitioning(expectedRevision: Long): Boolean =
        revision == expectedRevision && pendingRevision == expectedRevision

    @Synchronized
    fun change(block: () -> Unit = {}): Long {
        revision += 1
        pendingRevision = null
        block()
        return revision
    }

    @Synchronized
    fun commit(expectedRevision: Long, block: () -> Unit): Boolean {
        if (revision != expectedRevision) return false
        if (pendingRevision != null) return false
        block()
        return true
    }

    /** Commits the login that owns the current transition and makes it the active session. */
    @Synchronized
    fun commitTransition(expectedRevision: Long, block: () -> Unit): Boolean {
        if (revision != expectedRevision || pendingRevision != expectedRevision) return false
        pendingRevision = null
        block()
        return true
    }
}

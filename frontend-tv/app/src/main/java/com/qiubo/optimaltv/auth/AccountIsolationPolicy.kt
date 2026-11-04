package com.qiubo.optimaltv.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps account-scoped content hidden until the current session has finished
 * refreshing its server-owned library state.
 */
internal fun accountContentIsPrepared(
    sessionUserId: String?,
    preparedUserId: String?,
): Boolean = sessionUserId != null && sessionUserId == preparedUserId

internal fun accountContentIsPrepared(
    sessionRevision: Long?,
    preparedRevision: Long?,
): Boolean = sessionRevision != null && sessionRevision == preparedRevision

internal data class AccountSessionKey(
    val revision: Long,
    val userId: String,
)

internal class StaleAccountSessionException : Exception()

/** Serializes account-scoped Room replacement and verifies the exact logical session. */
internal class AccountSessionWriteGate(
    private val currentSession: () -> AccountSessionKey?,
) {
    private val mutex = Mutex()

    suspend fun <T> forCurrentSession(
        expected: AccountSessionKey,
        block: suspend () -> T,
    ): T? = mutex.withLock {
        if (currentSession() != expected) return@withLock null
        block().also { requireCurrent(expected) }
    }

    fun requireCurrent(expected: AccountSessionKey) {
        if (currentSession() != expected) throw StaleAccountSessionException()
    }

    suspend fun <T> exclusive(block: suspend () -> T): T =
        mutex.withLock { block() }
}

/**
 * Serializes writes to the shared Room library tables. A write captured for one
 * owner is skipped after logout or a switch to another owner.
 */
internal class AccountWriteGate(
    private val currentUserId: () -> String?,
) {
    private val mutex = Mutex()

    suspend fun <T> forCurrentOwner(
        expectedUserId: String,
        block: suspend () -> T,
    ): T? = mutex.withLock {
        if (currentUserId() != expectedUserId) return@withLock null
        block()
    }

    suspend fun <T> exclusive(block: suspend () -> T): T =
        mutex.withLock { block() }
}

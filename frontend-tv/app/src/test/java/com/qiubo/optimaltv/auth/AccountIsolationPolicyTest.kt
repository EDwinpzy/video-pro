package com.qiubo.optimaltv.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountIsolationPolicyTest {
    @Test
    fun contentStaysHiddenUntilTheCurrentSessionIsPrepared() {
        assertFalse(accountContentIsPrepared("owner-a", null))
        assertFalse(accountContentIsPrepared("owner-b", "owner-a"))
        assertTrue(accountContentIsPrepared("owner-a", "owner-a"))
    }

    @Test
    fun writeCapturedForAnOldOwnerIsSkippedAfterLogout() = runBlocking {
        var currentOwner: String? = "owner-a"
        val gate = AccountWriteGate { currentOwner }

        currentOwner = null

        val result = gate.forCurrentOwner("owner-a") { 1 }

        assertNull(result)
    }

    @Test
    fun accountClearWaitsForAnInFlightWrite() = runBlocking {
        var currentOwner: String? = "owner-a"
        var rows = "owner-a"
        val gate = AccountWriteGate { currentOwner }
        val writeEntered = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()

        val write = async {
            gate.forCurrentOwner("owner-a") {
                writeEntered.complete(Unit)
                releaseWrite.await()
                rows = "owner-a-final"
            }
        }
        writeEntered.await()
        currentOwner = "owner-b"
        val clear = async {
            gate.exclusive { rows = "" }
        }

        assertFalse(clear.isCompleted)
        releaseWrite.complete(Unit)
        write.await()
        clear.await()
        assertEquals("", rows)
    }

    @Test
    fun accountSessionChangeDuringWriteIsDetectedEvenWhenOwnerReturnsToSameUser() = runBlocking {
        var current = AccountSessionKey(1L, "owner-a")
        val gate = AccountSessionWriteGate { current }
        val captured = current

        val failure =
            runCatching {
                gate.forCurrentSession(captured) {
                    current = AccountSessionKey(3L, "owner-a")
                }
            }.exceptionOrNull()

        assertTrue(failure is StaleAccountSessionException)
    }
}

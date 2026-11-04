package com.qiubo.optimaltv.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountStartupTest {
    @Test fun slowSyncDoesNotHoldSafeLocalContentAndFailureKeepsItVisible() = runTest {
        val local = CompletableDeferred<Unit>()
        val remote = CompletableDeferred<Unit>()
        var visible = false
        var syncFailed = false
        val job = launch {
            prepareAccountStartup(
                prepareLocal = { local.await() },
                onPrepared = { visible = true },
                syncRemote = { remote.await(); error("fictional offline server") },
                onSyncFailure = { syncFailed = true },
            )
        }
        runCurrent()
        assertFalse(visible)
        local.complete(Unit)
        runCurrent()
        assertTrue(visible)
        assertTrue(job.isActive)
        remote.complete(Unit)
        job.join()
        assertTrue(visible)
        assertTrue(syncFailed)
    }

    @Test fun failedLocalOwnershipNeverExposesContentOrStartsNetwork() = runTest {
        val events = mutableListOf<String>()
        try {
            prepareAccountStartup(
                prepareLocal = { throw StaleAccountSessionException() },
                onPrepared = { events += "visible" },
                syncRemote = { events += "remote" },
                onSyncFailure = { events += "failed" },
            )
        } catch (_: StaleAccountSessionException) {
            events += "rejected"
        }
        assertEquals(listOf("rejected"), events)
    }

    @Test fun switchingSessionCancelsOldPreparationBeforeItCanExposeRows() = runTest {
        val oldLocal = CompletableDeferred<Unit>()
        var exposed = false
        val job = launch {
            prepareAccountStartup(
                prepareLocal = { oldLocal.await() },
                onPrepared = { exposed = true },
                syncRemote = {},
                onSyncFailure = { error("Cancellation must propagate") },
            )
        }
        runCurrent()
        job.cancel(CancellationException("new session"))
        oldLocal.complete(Unit)
        job.join()
        assertFalse(exposed)
    }
}

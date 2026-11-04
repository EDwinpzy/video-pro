package com.qiubo.optimaltv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeStartupPreloadTest {
    @Test fun waitsForLastTabAndEveryArtworkBeforeEntry() = runTest {
        val slowTab = CompletableDeferred<Unit>()
        val artwork = CompletableDeferred<Unit>()
        var entered = false
        var images: List<String>? = null
        val job = launch {
            preloadHomeTabs(listOf("movie", "tv"), {
                if (it == "tv") slowTab.await()
                listOf("shared", it)
            }, {
                images = it
                artwork.await()
            }, { _, _ -> })
            entered = true
        }
        runCurrent()
        assertFalse(entered)
        assertEquals(null, images)
        slowTab.complete(Unit)
        runCurrent()
        assertEquals(listOf("shared", "movie", "tv"), images)
        assertFalse(entered)
        artwork.complete(Unit)
        job.join()
        assertTrue(entered)
    }

    @Test fun failureNeverEntersAndRetryRunsMissingWork() = runTest {
        var attempts = 0
        var entered = false
        var failure: Exception? = null
        suspend fun attempt() {
            preloadHomeTabs(listOf("movie"), { listOf(it) }, {
                if (++attempts == 1) error("Fictional image failure")
            }, { _, _ -> })
            entered = true
        }
        try { attempt() } catch (caught: IllegalStateException) { failure = caught }
        assertTrue(failure != null)
        assertFalse(entered)
        attempt()
        assertTrue(entered)
    }

    @Test fun cancellationOnAccountChangeNeverEntersOldContent() = runTest {
        val pending = CompletableDeferred<Unit>()
        var entered = false
        val job = launch {
            preloadHomeTabs(listOf("movie"), { pending.await(); listOf(it) }, {}, { _, _ -> })
            entered = true
        }
        runCurrent()
        job.cancel()
        pending.complete(Unit)
        job.join()
        assertFalse(entered)
    }

    @Test fun transientTabFailureIsRetriedBeforeShowingTheErrorScreen() = runTest {
        var calls = 0
        var reportedFailures = 0

        val result = retryStartupLoad(
            retryDelayMs = 0L,
            onFailure = { _, _ -> reportedFailures++ },
        ) {
            calls++
            if (calls == 1) error("Fictional socket timeout")
            "loaded"
        }

        assertEquals("loaded", result)
        assertEquals(2, calls)
        assertEquals(1, reportedFailures)
    }
}

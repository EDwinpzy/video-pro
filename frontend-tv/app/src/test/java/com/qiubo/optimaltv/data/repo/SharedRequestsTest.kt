package com.qiubo.optimaltv.data.repo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SharedRequestsTest {
    private fun TestScope.repositoryScope(): CoroutineScope = CoroutineScope(
        backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]),
    )

    @Test
    fun prefetchAndScreenShareOneRequestAndCancelingTheScreenKeepsTheResult() = runTest {
        val requests = SharedRequests<String, String>(repositoryScope())
        val response = CompletableDeferred<String>()
        var loads = 0
        val screen = async { requests.await("film") { loads++; response.await() } }
        val prefetch = async { requests.await("film") { error("must join the screen request") } }
        runCurrent()
        assertEquals(1, loads)
        screen.cancel()
        runCurrent()
        assertFalse(response.isCancelled)
        response.complete("detail and episodes")
        assertEquals("detail and episodes", prefetch.await())
    }

    @Test
    fun differentFilmsDoNotWaitForOneAnother() = runTest {
        val requests = SharedRequests<String, String>(repositoryScope())
        val slowResponse = CompletableDeferred<String>()
        val slow = async { requests.await("slow-film") { slowResponse.await() } }
        runCurrent()
        assertEquals("ready", requests.await("other-film") { "ready" })
        assertFalse(slow.isCompleted)
        slowResponse.complete("ready later")
        assertEquals("ready later", slow.await())
    }

    @Test
    fun failedRequestIsRemovedSoTheNextVisitCanRetry() = runTest {
        val requests = SharedRequests<String, String>(repositoryScope())
        var failed = false
        try {
            requests.await("film") { error("temporary failure") }
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertEquals(true, failed)
        assertEquals("retried", requests.await("film") { "retried" })
    }

    @Test
    fun finishedRequestsAreNotASecondUnboundedResultCache() = runTest {
        val requests = SharedRequests<String, Int>(repositoryScope())
        var loads = 0
        assertEquals(1, requests.await("film") { ++loads })
        assertEquals(2, requests.await("film") { ++loads })
    }
}

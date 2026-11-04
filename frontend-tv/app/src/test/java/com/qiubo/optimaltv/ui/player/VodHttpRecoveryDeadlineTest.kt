package com.qiubo.optimaltv.ui.player

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VodHttpRecoveryDeadlineTest {
    @Test fun hangingResolutionExpiresButExplicitPauseDoesNotConsumeBudget() = runTest {
        var waiting = true
        var expired = 0
        val job = launch {
            awaitVodHttpRecoveryDeadline({ testScheduler.currentTime }, { true }, { waiting }, { false }, { expired++ })
        }
        advanceTimeBy(10_000)
        waiting = false
        advanceTimeBy(60_000)
        assertEquals(0, expired)
        waiting = true
        advanceTimeBy(20_250)
        runCurrent()
        assertEquals(1, expired)
        assertTrue(job.isCompleted)
    }

    @Test fun firstFrameOrNewSelectionDisarmsOldDeadline() = runTest {
        var current = true
        var rendered = false
        var expired = false
        val job = launch {
            awaitVodHttpRecoveryDeadline({ testScheduler.currentTime }, { current }, { true }, { rendered }, { expired = true })
        }
        advanceTimeBy(1_000)
        rendered = true
        advanceTimeBy(30_000)
        assertTrue(job.isCompleted)
        assertFalse(expired)
        rendered = false
        val next = launch {
            awaitVodHttpRecoveryDeadline({ testScheduler.currentTime }, { current }, { true }, { rendered }, { expired = true })
        }
        advanceTimeBy(1_000)
        current = false
        advanceTimeBy(30_000)
        assertTrue(next.isCompleted)
        assertFalse(expired)
    }
}

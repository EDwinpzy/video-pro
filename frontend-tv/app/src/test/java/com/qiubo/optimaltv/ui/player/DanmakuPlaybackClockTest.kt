package com.qiubo.optimaltv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuPlaybackClockTest {
    @Test
    fun followsCoarsePositionUpdatesWithoutReanchoring() {
        val clock = DanmakuPlaybackClock()

        assertFalse(clock.update(positionMs = 10_000L, realtimeMs = 0L, isPlaying = true, playbackRate = 1f))
        assertEquals(11_000L, clock.positionAt(realtimeMs = 1_000L, playbackRate = 1f))

        assertFalse(clock.update(positionMs = 11_000L, realtimeMs = 1_000L, isPlaying = true, playbackRate = 1f))
        assertEquals(11_500L, clock.positionAt(realtimeMs = 1_500L, playbackRate = 1f))
    }

    @Test
    fun capsExtrapolationWhenUiStopsUpdating() {
        val clock = DanmakuPlaybackClock()

        clock.update(positionMs = 10_000L, realtimeMs = 0L, isPlaying = true, playbackRate = 1f)

        assertEquals(14_000L, clock.positionAt(realtimeMs = 60_000L, playbackRate = 1f))
    }

    @Test
    fun reanchorsAfterForwardSeekAndRequestsWindowReset() {
        val clock = DanmakuPlaybackClock()
        clock.update(positionMs = 10_000L, realtimeMs = 0L, isPlaying = true, playbackRate = 1f)

        assertTrue(clock.update(positionMs = 40_000L, realtimeMs = 1_000L, isPlaying = true, playbackRate = 1f))
        assertEquals(40_500L, clock.positionAt(realtimeMs = 1_500L, playbackRate = 1f))
    }

    @Test
    fun reanchorsAfterBackwardSeek() {
        val clock = DanmakuPlaybackClock()
        clock.update(positionMs = 40_000L, realtimeMs = 0L, isPlaying = true, playbackRate = 1f)

        assertTrue(clock.update(positionMs = 10_000L, realtimeMs = 1_000L, isPlaying = true, playbackRate = 1f))
        assertEquals(10_000L, clock.positionAt(realtimeMs = 1_000L, playbackRate = 1f))
    }

    @Test
    fun pausedClockDoesNotAdvance() {
        val clock = DanmakuPlaybackClock()

        clock.update(positionMs = 10_000L, realtimeMs = 0L, isPlaying = false, playbackRate = 1f)

        assertEquals(10_000L, clock.positionAt(realtimeMs = 5_000L, playbackRate = 1f))
    }

    @Test
    fun honoursPlaybackRate() {
        val clock = DanmakuPlaybackClock()

        clock.update(positionMs = 10_000L, realtimeMs = 0L, isPlaying = true, playbackRate = 2f)

        assertEquals(12_000L, clock.positionAt(realtimeMs = 1_000L, playbackRate = 2f))
    }
}

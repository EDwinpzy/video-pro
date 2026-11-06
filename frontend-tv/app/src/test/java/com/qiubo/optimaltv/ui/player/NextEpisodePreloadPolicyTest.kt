package com.qiubo.optimaltv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodePreloadPolicyTest {

    @Test
    fun shortDramaStartsPreloadingAtSixtyPercent() {
        assertFalse(
            NextEpisodePreloadPolicy.shouldStart(
                positionMs = 107_999L,
                durationMs = 180_000L,
                hasNextEpisode = true,
            ),
        )
        assertTrue(
            NextEpisodePreloadPolicy.shouldStart(
                positionMs = 108_000L,
                durationMs = 180_000L,
                hasNextEpisode = true,
            ),
        )
    }

    @Test
    fun customIntroAndOutroShiftTheShortDramaWindow() {
        assertEquals(
            102_000L,
            NextEpisodePreloadPolicy.preloadStartMs(
                durationMs = 180_000L,
                introSkipSeconds = 30,
                outroSkipSeconds = 30,
            ),
        )
    }

    @Test
    fun longEpisodeStartsPreloadingFifteenMinutesBeforeTheEnd() {
        assertEquals(900_000L, NextEpisodePreloadPolicy.preloadStartMs(durationMs = 1_800_000L))
        assertFalse(
            NextEpisodePreloadPolicy.shouldStart(
                positionMs = 899_999L,
                durationMs = 1_800_000L,
                hasNextEpisode = true,
            ),
        )
        assertTrue(
            NextEpisodePreloadPolicy.shouldStart(
                positionMs = 900_000L,
                durationMs = 1_800_000L,
                hasNextEpisode = true,
            ),
        )
    }

    @Test
    fun customOutroMovesThePreloadPointToTheEffectiveEnd() {
        assertEquals(
            1_200_000L,
            NextEpisodePreloadPolicy.preloadStartMs(
                durationMs = 2_400_000L,
                outroSkipSeconds = 300,
            ),
        )
    }

    @Test
    fun leadNeverExceedsHalfOfPlayableContent() {
        assertEquals(300_000L, NextEpisodePreloadPolicy.preloadStartMs(durationMs = 600_000L))
    }

    @Test
    fun lastEpisodeAndVeryShortItemsAreNotPreloaded() {
        assertFalse(
            NextEpisodePreloadPolicy.shouldStart(
                positionMs = 170_000L,
                durationMs = 180_000L,
                hasNextEpisode = false,
            ),
        )
        assertFalse(
            NextEpisodePreloadPolicy.shouldStart(
                positionMs = 40_000L,
                durationMs = 40_000L,
                hasNextEpisode = true,
            ),
        )
    }
}

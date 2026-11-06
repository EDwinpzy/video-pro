package com.qiubo.optimaltv.ui.player

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
    fun longEpisodeStartsPreloadingTwoMinutesBeforeTheEnd() {
        assertFalse(
            NextEpisodePreloadPolicy.shouldStart(
                positionMs = 1_679_999L,
                durationMs = 1_800_000L,
                hasNextEpisode = true,
            ),
        )
        assertTrue(
            NextEpisodePreloadPolicy.shouldStart(
                positionMs = 1_680_000L,
                durationMs = 1_800_000L,
                hasNextEpisode = true,
            ),
        )
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

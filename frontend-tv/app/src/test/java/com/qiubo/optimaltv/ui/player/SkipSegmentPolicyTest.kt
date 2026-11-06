package com.qiubo.optimaltv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipSegmentPolicyTest {

    @Test
    fun zeroSecondsOrTooShortContentIsNeverSkipped() {
        assertEquals(0L, SkipSegmentPolicy.introSkipMs(0, 600_000L))
        assertEquals(0L, SkipSegmentPolicy.outroSkipMs(0, 600_000L))
        assertEquals(0L, SkipSegmentPolicy.introSkipMs(30, 10_000L))
        assertEquals(0L, SkipSegmentPolicy.outroSkipMs(30, 10_000L))
    }

    @Test
    fun customSecondsAreUsedDirectly() {
        assertEquals(30_000L, SkipSegmentPolicy.introSkipMs(30, 600_000L))
        assertEquals(45_000L, SkipSegmentPolicy.outroSkipMs(45, 600_000L))
        assertEquals(90_000L, SkipSegmentPolicy.introSkipMs(90, 3_600_000L))
    }

    @Test
    fun overlongCustomValueLeavesTenSecondsOfContent() {
        assertEquals(50_000L, SkipSegmentPolicy.introSkipMs(600, 60_000L))
        assertEquals(50_000L, SkipSegmentPolicy.outroSkipMs(600, 60_000L))
        assertFalse(
            SkipSegmentPolicy.shouldSkipOutro(
                skipOutroSeconds = 600,
                positionMs = 9_999L,
                durationMs = 60_000L,
                hasNextEpisode = true,
            ),
        )
        assertTrue(
            SkipSegmentPolicy.shouldSkipOutro(
                skipOutroSeconds = 600,
                positionMs = 10_000L,
                durationMs = 60_000L,
                hasNextEpisode = true,
            ),
        )
    }

    @Test
    fun introOnlySkipsAFreshStartOnce() {
        assertTrue(
            SkipSegmentPolicy.shouldApplyIntro(
                skipIntroSeconds = 30,
                startPositionMs = 0L,
                durationMs = 600_000L,
                alreadyApplied = false,
            ),
        )
        assertFalse(
            SkipSegmentPolicy.shouldApplyIntro(
                skipIntroSeconds = 30,
                startPositionMs = 120_000L,
                durationMs = 600_000L,
                alreadyApplied = false,
            ),
        )
        assertFalse(
            SkipSegmentPolicy.shouldApplyIntro(
                skipIntroSeconds = 30,
                startPositionMs = 0L,
                durationMs = 600_000L,
                alreadyApplied = true,
            ),
        )
        assertFalse(
            SkipSegmentPolicy.shouldApplyIntro(
                skipIntroSeconds = 0,
                startPositionMs = 0L,
                durationMs = 600_000L,
                alreadyApplied = false,
            ),
        )
    }

    @Test
    fun outroRequiresAValueAndANextEpisode() {
        assertTrue(
            SkipSegmentPolicy.shouldSkipOutro(
                skipOutroSeconds = 30,
                positionMs = 570_000L,
                durationMs = 600_000L,
                hasNextEpisode = true,
            ),
        )
        assertFalse(
            SkipSegmentPolicy.shouldSkipOutro(
                skipOutroSeconds = 30,
                positionMs = 569_999L,
                durationMs = 600_000L,
                hasNextEpisode = true,
            ),
        )
        assertFalse(
            SkipSegmentPolicy.shouldSkipOutro(
                skipOutroSeconds = 30,
                positionMs = 590_000L,
                durationMs = 600_000L,
                hasNextEpisode = false,
            ),
        )
        assertFalse(
            SkipSegmentPolicy.shouldSkipOutro(
                skipOutroSeconds = 0,
                positionMs = 590_000L,
                durationMs = 600_000L,
                hasNextEpisode = true,
            ),
        )
    }
}

package com.qiubo.optimaltv.ui.player

import com.qiubo.optimaltv.data.prefs.SkipSegmentsMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipSegmentPolicyTest {

    @Test
    fun veryShortEpisodesAreNeverSkipped() {
        assertEquals(0L, SkipSegmentPolicy.introSkipMs(40_000L))
        assertEquals(0L, SkipSegmentPolicy.outroSkipMs(40_000L))
    }

    @Test
    fun shortDramaSkipsUseClampedMinimums() {
        assertEquals(4_000L, SkipSegmentPolicy.introSkipMs(45_000L))
        assertEquals(3_000L, SkipSegmentPolicy.outroSkipMs(45_000L))
    }

    @Test
    fun longEpisodesScaleWithDurationAndStayWithinCaps() {
        assertEquals(48_000L, SkipSegmentPolicy.introSkipMs(600_000L))
        assertEquals(36_000L, SkipSegmentPolicy.outroSkipMs(600_000L))
        assertEquals(90_000L, SkipSegmentPolicy.introSkipMs(3_600_000L))
        assertEquals(60_000L, SkipSegmentPolicy.outroSkipMs(3_600_000L))
    }

    @Test
    fun introOnlySkipsAFreshStartOnce() {
        assertTrue(
            SkipSegmentPolicy.shouldApplyIntro(
                mode = SkipSegmentsMode.INTRO,
                startPositionMs = 0L,
                durationMs = 600_000L,
                alreadyApplied = false,
            ),
        )
        assertFalse(
            SkipSegmentPolicy.shouldApplyIntro(
                mode = SkipSegmentsMode.INTRO,
                startPositionMs = 120_000L,
                durationMs = 600_000L,
                alreadyApplied = false,
            ),
        )
        assertFalse(
            SkipSegmentPolicy.shouldApplyIntro(
                mode = SkipSegmentsMode.INTRO,
                startPositionMs = 0L,
                durationMs = 600_000L,
                alreadyApplied = true,
            ),
        )
        assertFalse(
            SkipSegmentPolicy.shouldApplyIntro(
                mode = SkipSegmentsMode.OUTRO,
                startPositionMs = 0L,
                durationMs = 600_000L,
                alreadyApplied = false,
            ),
        )
    }

    @Test
    fun outroRequiresModeAndANextEpisode() {
        assertTrue(
            SkipSegmentPolicy.shouldSkipOutro(
                mode = SkipSegmentsMode.OUTRO,
                positionMs = 564_000L,
                durationMs = 600_000L,
                hasNextEpisode = true,
            ),
        )
        assertFalse(
            SkipSegmentPolicy.shouldSkipOutro(
                mode = SkipSegmentsMode.OUTRO,
                positionMs = 563_999L,
                durationMs = 600_000L,
                hasNextEpisode = true,
            ),
        )
        assertFalse(
            SkipSegmentPolicy.shouldSkipOutro(
                mode = SkipSegmentsMode.OUTRO,
                positionMs = 590_000L,
                durationMs = 600_000L,
                hasNextEpisode = false,
            ),
        )
        assertFalse(
            SkipSegmentPolicy.shouldSkipOutro(
                mode = SkipSegmentsMode.INTRO,
                positionMs = 590_000L,
                durationMs = 600_000L,
                hasNextEpisode = true,
            ),
        )
    }
}

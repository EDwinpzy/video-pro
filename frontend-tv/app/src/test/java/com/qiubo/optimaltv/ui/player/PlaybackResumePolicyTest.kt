package com.qiubo.optimaltv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackResumePolicyTest {
    @Test fun explicitRoutePositionWinsOverSavedProgress() {
        assertEquals(
            2_972_000L,
            PlaybackResumePolicy.startPositionMs(
                routePositionMs = 2_972_000L,
                savedPositionMs = 600_000L,
                savedDurationMs = 2_984_000L,
            ),
        )
    }

    @Test fun savedProgressIsUsedWhenRouteHasNoValidPosition() {
        assertEquals(
            600_000L,
            PlaybackResumePolicy.startPositionMs(
                routePositionMs = 1_000L,
                savedPositionMs = 600_000L,
                savedDurationMs = 2_984_000L,
            ),
        )
    }

    @Test fun savedProgressNearEndIsRetainedWhileUnfinished() {
        assertEquals(
            2_972_000L,
            PlaybackResumePolicy.startPositionMs(
                routePositionMs = 0L,
                savedPositionMs = 2_972_000L,
                savedDurationMs = 2_984_000L,
            ),
        )
    }

    @Test fun finishedProgressStartsFromBeginning() {
        val durationMs = 2_984_000L
        assertEquals(
            0L,
            PlaybackResumePolicy.startPositionMs(0L, durationMs, durationMs),
        )
        assertEquals(
            0L,
            PlaybackResumePolicy.startPositionMs(0L, durationMs + 1_000L, durationMs),
        )
    }

    @Test fun explicitRouteRemainsAuthoritativeWhenSavedProgressIsFinished() {
        val durationMs = 2_984_000L
        assertEquals(
            2_972_000L,
            PlaybackResumePolicy.startPositionMs(2_972_000L, durationMs, durationMs),
        )
    }

    @Test fun unknownDurationStillAllowsValidSavedProgress() {
        assertEquals(
            600_000L,
            PlaybackResumePolicy.startPositionMs(0L, 600_000L, null),
        )
        assertEquals(
            600_000L,
            PlaybackResumePolicy.startPositionMs(0L, 600_000L, 0L),
        )
        assertEquals(
            600_000L,
            PlaybackResumePolicy.startPositionMs(0L, 600_000L, -1L),
        )
    }

    @Test fun nonPositiveAndOneSecondPositionsAreInvalid() {
        for (positionMs in listOf(-1L, 0L, 1L, 1_000L)) {
            assertEquals(
                0L,
                PlaybackResumePolicy.startPositionMs(positionMs, positionMs, null),
            )
        }
    }
}

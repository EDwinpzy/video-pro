package com.qiubo.optimaltv.ui.player

import com.qiubo.optimaltv.playback.EnginePlayState
import org.junit.Assert.*
import org.junit.Test

class PlayerStartupStatePolicyTest {
    @Test fun footballStartsInLiveModeBeforeAnyNetworkRequest() {
        val state = PlayerStartupStatePolicy.initial("live:129478", 0)
        assertTrue(state.isLive)
        assertTrue(state.sourceResolving)
        assertFalse(state.controlsVisible)
        assertTrue(state.lines.isEmpty())
        assertNull(state.fatalMsg)
    }

    @Test fun vodKeepsItsEpisodeAndPointPlaybackControls() {
        val state = PlayerStartupStatePolicy.initial("douban:37457082", 2)
        assertFalse(state.isLive)
        assertTrue(state.sourceResolving)
        assertEquals(2, state.epIndex)
    }

    @Test fun aTitleContainingLiveDoesNotSelectFootballMode() {
        assertFalse(PlayerStartupStatePolicy.initial("hhkan:live", 0).isLive)
    }

    @Test fun officialDetailRouteSkipsLegacyBootstrapOnly() {
        assertTrue(PlayerStartupStatePolicy.skipsLegacyBootstrap("dongpian:123"))
        assertFalse(PlayerStartupStatePolicy.skipsLegacyBootstrap("douban:123"))
        assertFalse(PlayerStartupStatePolicy.skipsLegacyBootstrap("live:123"))
    }

    @Test fun autoplaySessionWaitsForARealFirstFrameButUserPauseCanStartWithoutOne() {
        assertFalse(PlayerStartupStatePolicy.sessionCanStart(EnginePlayState.READY, false, true))
        assertTrue(PlayerStartupStatePolicy.sessionCanStart(EnginePlayState.READY, true, true))
        assertTrue(PlayerStartupStatePolicy.sessionCanStart(EnginePlayState.READY, false, false))
    }

    @Test fun engineFallbackKeepsTheRequestedResumeBeforeFirstFrame() {
        assertEquals(
            120_000L,
            EngineFallbackStartPolicy.positionMs(
                isLive = false,
                firstFrameDisplayed = false,
                requestedStartMs = 120_000L,
                currentPositionMs = 0L,
                pendingSeekMs = null,
            ),
        )
    }

    @Test fun engineFallbackUsesCurrentOrPendingSeekAfterPlaybackStarts() {
        assertEquals(
            45_000L,
            EngineFallbackStartPolicy.positionMs(
                isLive = false,
                firstFrameDisplayed = true,
                requestedStartMs = 120_000L,
                currentPositionMs = 45_000L,
                pendingSeekMs = null,
            ),
        )
        assertEquals(
            180_000L,
            EngineFallbackStartPolicy.positionMs(
                isLive = false,
                firstFrameDisplayed = true,
                requestedStartMs = 120_000L,
                currentPositionMs = 45_000L,
                pendingSeekMs = 180_000L,
            ),
        )
        assertEquals(
            0L,
            EngineFallbackStartPolicy.positionMs(
                isLive = true,
                firstFrameDisplayed = true,
                requestedStartMs = 120_000L,
                currentPositionMs = 45_000L,
                pendingSeekMs = null,
            ),
        )
    }
}

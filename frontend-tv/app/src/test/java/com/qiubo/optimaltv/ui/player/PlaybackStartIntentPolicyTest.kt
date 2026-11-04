package com.qiubo.optimaltv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStartIntentPolicyTest {
    @Test
    fun automaticRecoveryPreservesAnExplicitPause() {
        val intent = PlaybackStartIntent()
        intent.pause()
        assertFalse(intent.shouldAutoplay(PlaybackStartTrigger.AUTOMATIC_RECOVERY))
        intent.resume()
        assertTrue(intent.shouldAutoplay(PlaybackStartTrigger.AUTOMATIC_RECOVERY))
    }

    @Test
    fun pauseDuringManualSourceResolutionIsNotOverwrittenByUserActionAutoplay() {
        val intent = PlaybackStartIntent()
        intent.pause()

        intent.beginUserSelection()
        assertTrue(intent.shouldAutoplay(PlaybackStartTrigger.USER_ACTION))

        val pauseRequested = PlaybackStartIntentPolicy.shouldPauseOnToggle(
            isPlaying = false,
            awaitingPrepare = false,
            sourceResolving = true,
            firstFrameRendered = true,
            playbackRequested = true,
        )
        assertTrue(pauseRequested)
        if (pauseRequested) intent.pause()

        assertFalse(intent.shouldAutoplay(PlaybackStartTrigger.USER_ACTION))
    }

    @Test
    fun toggleCanPauseWhileBufferingBeforeTheFirstFrame() {
        assertTrue(
            PlaybackStartIntentPolicy.shouldPauseOnToggle(
                isPlaying = false,
                awaitingPrepare = true,
                sourceResolving = false,
                firstFrameRendered = false,
                playbackRequested = true,
            ),
        )
        assertFalse(
            PlaybackStartIntentPolicy.shouldPauseOnToggle(
                isPlaying = false,
                awaitingPrepare = true,
                sourceResolving = false,
                firstFrameRendered = false,
                playbackRequested = false,
            ),
        )
        assertTrue(
            PlaybackStartIntentPolicy.shouldPauseOnToggle(
                isPlaying = true,
                awaitingPrepare = false,
                sourceResolving = false,
                firstFrameRendered = true,
                playbackRequested = false,
            ),
        )
    }

    @Test
    fun startupBudgetStopsForUserPauseAndHostBackground() {
        assertTrue(PlaybackStartIntentPolicy.shouldWaitForOutput(true, hostBackgrounded = false))
        assertFalse(PlaybackStartIntentPolicy.shouldWaitForOutput(false, hostBackgrounded = false))
        assertFalse(PlaybackStartIntentPolicy.shouldWaitForOutput(true, hostBackgrounded = true))
    }

    @Test
    fun explicitPauseClearsFirstFrameBudgetImmediately() {
        val window = FirstFrameWaitWindow()

        assertEquals(0L, window.elapsedMs(nowMs = 1_000L, waiting = true))
        assertEquals(4_000L, window.elapsedMs(nowMs = 5_000L, waiting = true))

        window.clear()

        assertEquals(0L, window.elapsedMs(nowMs = 6_000L, waiting = true))
    }
}

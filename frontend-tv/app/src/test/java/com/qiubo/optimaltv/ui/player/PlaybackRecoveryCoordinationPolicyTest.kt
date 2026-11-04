package com.qiubo.optimaltv.ui.player

import com.qiubo.optimaltv.playback.EnginePlayState
import com.qiubo.optimaltv.playback.PlaybackKind
import com.qiubo.optimaltv.playback.PlaybackRecoveryPolicy
import com.qiubo.optimaltv.playback.RecoveryAction
import com.qiubo.optimaltv.playback.ResolvedSessionUrlReusePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRecoveryCoordinationPolicyTest {
    @Test
    fun `recovery waits for active prepare and any in flight recovery`() {
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.canStartRecovery(
                startedThisSession = false,
                awaitingPrepare = false,
                sourceResolving = false,
                recoveryInFlight = false,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.canStartRecovery(
                startedThisSession = true,
                awaitingPrepare = true,
                sourceResolving = false,
                recoveryInFlight = false,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.canStartRecovery(
                startedThisSession = true,
                awaitingPrepare = false,
                sourceResolving = true,
                recoveryInFlight = false,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.canStartRecovery(
                startedThisSession = true,
                awaitingPrepare = false,
                sourceResolving = false,
                recoveryInFlight = true,
            ),
        )
        assertTrue(
            PlaybackRecoveryCoordinationPolicy.canStartRecovery(
                startedThisSession = true,
                awaitingPrepare = false,
                sourceResolving = false,
                recoveryInFlight = false,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.canStartRecovery(
                startedThisSession = true,
                awaitingPrepare = false,
                sourceResolving = false,
                recoveryInFlight = false,
                firstFrameRecoveryInFlight = true,
            ),
        )
    }

    @Test
    fun `first frame ladder yields to a resolving source or another recovery`() {
        assertTrue(
            PlaybackRecoveryCoordinationPolicy.canAdvanceFirstFrame(
                sourceResolving = false,
                recoveryInFlight = false,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.canAdvanceFirstFrame(
                sourceResolving = true,
                recoveryInFlight = false,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.canAdvanceFirstFrame(
                sourceResolving = false,
                recoveryInFlight = true,
            ),
        )
    }

    @Test
    fun `stall recovery cannot start a second pre-ready VOD retry`() {
        assertTrue(
            PlaybackRecoveryCoordinationPolicy.canStartPreStartVodRetry(
                sourceResolving = false,
                firstFrameLadderInFlight = false,
                vodRetryInFlight = false,
                recoveryInFlight = false,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.canStartPreStartVodRetry(
                sourceResolving = false,
                firstFrameLadderInFlight = true,
                vodRetryInFlight = false,
                recoveryInFlight = false,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.canStartPreStartVodRetry(
                sourceResolving = false,
                firstFrameLadderInFlight = false,
                vodRetryInFlight = true,
                recoveryInFlight = false,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.canStartPreStartVodRetry(
                sourceResolving = true,
                firstFrameLadderInFlight = false,
                vodRetryInFlight = false,
                recoveryInFlight = false,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.canStartPreStartVodRetry(
                sourceResolving = false,
                firstFrameLadderInFlight = false,
                vodRetryInFlight = false,
                recoveryInFlight = true,
            ),
        )
    }

    @Test
    fun `a long buffering gap after READY lets stall recovery own the session`() {
        assertTrue(
            PlaybackRecoveryCoordinationPolicy.firstFrameLadderOwnsStartup(
                ladderInFlight = true,
                reachedReady = false,
                playState = EnginePlayState.BUFFERING,
            ),
        )
        assertTrue(
            PlaybackRecoveryCoordinationPolicy.firstFrameLadderOwnsStartup(
                ladderInFlight = true,
                reachedReady = true,
                playState = EnginePlayState.READY,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.firstFrameLadderOwnsStartup(
                ladderInFlight = true,
                reachedReady = true,
                playState = EnginePlayState.BUFFERING,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.firstFrameLadderOwnsStartup(
                ladderInFlight = false,
                reachedReady = false,
                playState = EnginePlayState.BUFFERING,
            ),
        )
    }

    @Test
    fun `a session that becomes READY during retry delay is not restarted`() {
        assertTrue(
            PlaybackRecoveryCoordinationPolicy.shouldSkipPendingVodRetry(
                startedThisSession = true,
                awaitingPrepare = false,
                playState = EnginePlayState.READY,
                engineFirstFrameRendered = false,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.shouldSkipPendingVodRetry(
                startedThisSession = false,
                awaitingPrepare = false,
                playState = EnginePlayState.READY,
                engineFirstFrameRendered = true,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.shouldSkipPendingVodRetry(
                startedThisSession = true,
                awaitingPrepare = false,
                playState = EnginePlayState.BUFFERING,
                engineFirstFrameRendered = false,
            ),
        )
        assertTrue(
            PlaybackRecoveryCoordinationPolicy.shouldSkipPendingVodRetry(
                startedThisSession = false,
                awaitingPrepare = true,
                playState = EnginePlayState.BUFFERING,
                engineFirstFrameRendered = true,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.shouldSkipPendingVodRetry(
                startedThisSession = true,
                awaitingPrepare = false,
                playState = EnginePlayState.BUFFERING,
                engineFirstFrameRendered = true,
            ),
        )
    }

    @Test
    fun `a confirmed READY freeze may restart the already started VOD session`() {
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.shouldSkipPendingVodRetry(
                startedThisSession = true,
                awaitingPrepare = false,
                playState = EnginePlayState.READY,
                engineFirstFrameRendered = true,
                confirmedStall = true,
            ),
        )
    }

    @Test
    fun `only the active prepare attempt can satisfy the pending retry`() {
        assertTrue(
            PlaybackRecoveryCoordinationPolicy.shouldSkipPendingVodRetry(
                startedThisSession = false,
                awaitingPrepare = true,
                playState = EnginePlayState.BUFFERING,
                engineFirstFrameRendered = true,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.shouldSkipPendingVodRetry(
                startedThisSession = false,
                awaitingPrepare = false,
                playState = EnginePlayState.BUFFERING,
                engineFirstFrameRendered = true,
            ),
        )
    }

    @Test
    fun `fresh retry is healthy only after its own frame with a usable state`() {
        assertTrue(
            PlaybackRecoveryCoordinationPolicy.retryAttemptHealthy(
                startedThisSession = true,
                firstFrameRendered = true,
                playState = EnginePlayState.READY,
            ),
        )
        assertTrue(
            PlaybackRecoveryCoordinationPolicy.retryAttemptHealthy(
                startedThisSession = true,
                firstFrameRendered = true,
                playState = EnginePlayState.BUFFERING,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.retryAttemptHealthy(
                startedThisSession = false,
                firstFrameRendered = true,
                playState = EnginePlayState.READY,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.retryAttemptHealthy(
                startedThisSession = true,
                firstFrameRendered = false,
                playState = EnginePlayState.READY,
            ),
        )
    }

    @Test
    fun `resuming before the first frame rearms the VOD startup wait`() {
        assertTrue(
            PlaybackRecoveryCoordinationPolicy.shouldRearmVodFirstFrameWait(
                isLive = false,
                firstFrameRendered = false,
                playbackRequested = true,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.shouldRearmVodFirstFrameWait(
                isLive = false,
                firstFrameRendered = true,
                playbackRequested = true,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.shouldRearmVodFirstFrameWait(
                isLive = true,
                firstFrameRendered = false,
                playbackRequested = true,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.shouldRearmVodFirstFrameWait(
                isLive = false,
                firstFrameRendered = false,
                playbackRequested = false,
            ),
        )
    }

    @Test
    fun `live recovery never uses the VOD same session reload action`() {
        assertEquals(RecoveryAction.RELOAD_CURRENT, PlaybackRecoveryPolicy.action(PlaybackKind.VOD, 0))
        for (attempt in 0..5) {
            assertFalse(
                PlaybackRecoveryPolicy.action(PlaybackKind.LIVE, attempt) == RecoveryAction.RELOAD_CURRENT,
            )
        }
    }

    @Test
    fun `same selected VOD line can reuse resolved URL before first READY`() {
        val startedThisSession = false

        assertFalse(startedThisSession)
        assertEquals(
            "https://cdn.example/video.m3u8?token=current",
            ResolvedSessionUrlReusePolicy.vodUrl(
                reuseRequested = true,
                requestedLine = 2,
                activeLine = 2,
                sessionUrl = "https://cdn.example/video.m3u8?token=current",
            ),
        )
    }

    @Test
    fun `VOD URL reuse rejects a different line or an empty URL`() {
        assertNull(
            ResolvedSessionUrlReusePolicy.vodUrl(
                reuseRequested = true,
                requestedLine = 1,
                activeLine = 2,
                sessionUrl = "https://cdn.example/video.m3u8?token=current",
            ),
        )
        assertNull(
            ResolvedSessionUrlReusePolicy.vodUrl(
                reuseRequested = true,
                requestedLine = 2,
                activeLine = 2,
                sessionUrl = " \t",
            ),
        )
        assertNull(
            ResolvedSessionUrlReusePolicy.vodUrl(
                reuseRequested = false,
                requestedLine = 2,
                activeLine = 2,
                sessionUrl = "https://cdn.example/video.m3u8?token=current",
            ),
        )
    }

    @Test
    fun `advancing video frames prevent clock only false stall`() {
        var ticks = 0
        repeat(20) { frame ->
            ticks = PlaybackRecoveryCoordinationPolicy.nextReadyStallTicks(
                ticks = ticks,
                previousPositionMs = 0L,
                positionMs = 0L,
                previousRenderedFrames = frame,
                renderedFrames = frame + 1,
            )
        }
        assertEquals(0, ticks)
    }

    @Test
    fun `missing VLC frame stats keep clock based stall fallback`() {
        var ticks = 0
        repeat(8) {
            ticks = PlaybackRecoveryCoordinationPolicy.nextReadyStallTicks(
                ticks = ticks,
                previousPositionMs = 0L,
                positionMs = 0L,
                previousRenderedFrames = -1,
                renderedFrames = -1,
            )
        }
        assertEquals(8, ticks)
    }

    @Test
    fun `either advancing clock or rendered frames is stable playback progress`() {
        assertTrue(PlaybackRecoveryCoordinationPolicy.hasPlaybackProgress(true, false))
        assertTrue(PlaybackRecoveryCoordinationPolicy.hasPlaybackProgress(false, true))
        assertFalse(PlaybackRecoveryCoordinationPolicy.hasPlaybackProgress(false, false))
        assertTrue(PlaybackRecoveryCoordinationPolicy.renderedFramesAdvanced(4, 5))
        assertFalse(PlaybackRecoveryCoordinationPolicy.renderedFramesAdvanced(-1, 5))
        assertFalse(PlaybackRecoveryCoordinationPolicy.renderedFramesAdvanced(5, 5))
    }

    @Test
    fun `negative live timeline still detects a stalled decoder`() {
        assertEquals(4, PlaybackRecoveryCoordinationPolicy.nextReadyStallTicks(3, -3000L, -3000L, 10, 10))
        assertEquals(0, PlaybackRecoveryCoordinationPolicy.nextReadyStallTicks(3, -3000L, -3000L, 10, 11))
    }

    @Test
    fun `first frame grace counts from READY instead of from prepare`() {
        // 真机实测：慢上游 prepare 后 16.5s 才 READY。旧实现从 prepare 起算，
        // READY 后 0.6s 就判死；此时 VLC 还要 1.7s 才 vout（出画面）。
        assertFalse(PlaybackRecoveryCoordinationPolicy.firstFrameWatchdogExpired(16_500L, 17_100L, false))
        assertFalse(PlaybackRecoveryCoordinationPolicy.firstFrameWatchdogExpired(16_500L, 18_300L, false))
        // 满 15s 才判死
        assertFalse(PlaybackRecoveryCoordinationPolicy.firstFrameWatchdogExpired(16_500L, 31_499L, false))
        assertTrue(PlaybackRecoveryCoordinationPolicy.firstFrameWatchdogExpired(16_500L, 31_500L, false))
        // 已出画面永不判死
        assertFalse(PlaybackRecoveryCoordinationPolicy.firstFrameWatchdogExpired(16_500L, 99_000L, true))
        // 还没 READY：归 20s 起播看门狗管，这里不能动手
        assertFalse(PlaybackRecoveryCoordinationPolicy.firstFrameWatchdogExpired(-1L, 99_000L, false))
    }

    @Test
    fun `pausing clears first frame wait budget and resuming starts a fresh window`() {
        val waitWindow = FirstFrameWaitWindow()

        assertEquals(0L, waitWindow.elapsedMs(10_000L, waiting = true) ?: -1L)
        assertEquals(6_999L, waitWindow.elapsedMs(16_999L, waiting = true) ?: -1L)
        assertNull(waitWindow.elapsedMs(20_000L, waiting = false))
        assertEquals(0L, waitWindow.elapsedMs(30_000L, waiting = true) ?: -1L)
        assertEquals(7_000L, waitWindow.elapsedMs(37_000L, waiting = true) ?: -1L)
    }

    @Test
    fun `recovery prepare budget excludes paused and background time`() {
        val waitWindow = FirstFrameWaitWindow()

        assertEquals(0L, waitWindow.elapsedMs(10_000L, waiting = true) ?: -1L)
        assertEquals(9_000L, waitWindow.elapsedMs(19_000L, waiting = true) ?: -1L)
        assertNull(waitWindow.elapsedMs(30_000L, waiting = false))
        assertEquals(0L, waitWindow.elapsedMs(40_000L, waiting = true) ?: -1L)
        assertEquals(19_999L, waitWindow.elapsedMs(59_999L, waiting = true) ?: -1L)
        assertEquals(
            FirstFrameRecoveryPolicy.Action.WAIT,
            FirstFrameRecoveryPolicy.beforeReadyAction(19_999L),
        )
        assertEquals(
            FirstFrameRecoveryPolicy.Action.RETRY_SOURCE,
            FirstFrameRecoveryPolicy.beforeReadyAction(20_000L),
        )
    }

    @Test
    fun `leaving READY pauses instead of resetting the first frame budget`() {
        val waitWindow = FirstFrameWaitWindow()

        assertEquals(0L, waitWindow.elapsedMs(10_000L, waiting = true) ?: -1L)
        assertEquals(4_000L, waitWindow.elapsedMs(14_000L, waiting = true) ?: -1L)
        waitWindow.pause(15_000L)
        waitWindow.pause(25_000L)
        assertEquals(5_000L, waitWindow.elapsedMs(30_000L, waiting = true) ?: -1L)
        assertEquals(12_000L, waitWindow.elapsedMs(37_000L, waiting = true) ?: -1L)
    }

    @Test
    fun `ended before the first VOD ready keeps an automatic recovery owner`() {
        assertTrue(
            PlaybackRecoveryCoordinationPolicy.endedBeforeVodStarted(
                isLive = false,
                awaitingPrepare = true,
                startedThisSession = false,
                playState = EnginePlayState.ENDED,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.endedBeforeVodStarted(
                isLive = false,
                awaitingPrepare = true,
                startedThisSession = true,
                playState = EnginePlayState.ENDED,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.endedBeforeVodStarted(
                isLive = true,
                awaitingPrepare = true,
                startedThisSession = false,
                playState = EnginePlayState.ENDED,
            ),
        )
    }

    @Test
    fun `fresh retry refreshes immediately after ERROR or pre-ready ENDED`() {
        assertTrue(
            PlaybackRecoveryCoordinationPolicy.retryAttemptNeedsRefresh(
                playState = EnginePlayState.ENDED,
                awaitingPrepare = false,
            ),
        )
        assertTrue(
            PlaybackRecoveryCoordinationPolicy.retryAttemptNeedsRefresh(
                playState = EnginePlayState.ERROR,
                awaitingPrepare = false,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.retryAttemptNeedsRefresh(
                playState = EnginePlayState.ENDED,
                awaitingPrepare = true,
            ),
        )
        assertFalse(
            PlaybackRecoveryCoordinationPolicy.retryAttemptNeedsRefresh(
                playState = EnginePlayState.BUFFERING,
                awaitingPrepare = false,
            ),
        )
    }
}

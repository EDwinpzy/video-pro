package com.qiubo.optimaltv.ui.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortDramaEpisodeNavigationPolicyTest {

    @Test
    fun onlyAuthoritativeShortDramaCategoriesEnableTheShortcut() {
        listOf(
            "6",
            "douban:6",
            "hhkan:6",
            "douban:short",
            "hhkan:SHORT",
            "douban:short_drama",
            "short_drama",
            "douban:短剧",
        )
            .forEach { assertTrue(it, ShortDramaEpisodeNavigationPolicy.isShortDrama(it)) }
        listOf("", "douban:movie", "douban:tv", "douban:anime", "douban:variety", "hhkan:1")
            .forEach { assertFalse(it, ShortDramaEpisodeNavigationPolicy.isShortDrama(it)) }
    }

    @Test
    fun shortFormMetadataEnablesTheShortcutWhenTheCategoryIsNotAuthoritative() {
        assertTrue(
            shortDrama(
                categoryIds = listOf("douban:anime"),
                metadata = "2026 / 中国大陆 / 短剧 / 现代都市",
                episodeCount = 46,
                durationMs = 126_000,
            ),
        )
        assertTrue(
            shortDrama(
                categoryIds = listOf("douban:anime"),
                metadata = "2026 / 中国大陆 / 漫剧 / AI漫剧",
                episodeCount = 5,
                durationMs = 124_000,
            ),
        )
        assertFalse(
            shortDrama(
                categoryIds = listOf("douban:anime"),
                metadata = "2026 / 日本 / 漫剧 / 动画",
                episodeCount = 12,
                durationMs = 24 * 60 * 1000L,
            ),
        )
        assertFalse(
            shortDrama(
                categoryIds = listOf("douban:anime"),
                metadata = "2026 / 中国大陆 / 短剧",
                episodeCount = 1,
                durationMs = 126_000,
            ),
        )
    }

    @Test
    fun overlaysAndLiveOrErrorStatesKeepTheirOwnDpadFocus() {
        assertTrue(canIntercept())
        assertFalse(canIntercept(panelOpen = true))
        assertFalse(canIntercept(dialogOpen = true))
        assertFalse(canIntercept(isLive = true))
        assertFalse(canIntercept(hasFatalError = true))
        assertFalse(canIntercept(shortDrama = false))
    }

    @Test
    fun previousAndNextClampAtEpisodeBoundaries() {
        assertEquals(
            1,
            ShortDramaEpisodeNavigationPolicy.targetEpisode(KeyEvent.KEYCODE_DPAD_UP, 2, 4),
        )
        assertEquals(
            3,
            ShortDramaEpisodeNavigationPolicy.targetEpisode(KeyEvent.KEYCODE_DPAD_DOWN, 2, 4),
        )
        assertNull(
            ShortDramaEpisodeNavigationPolicy.targetEpisode(KeyEvent.KEYCODE_DPAD_UP, 0, 4),
        )
        assertNull(
            ShortDramaEpisodeNavigationPolicy.targetEpisode(KeyEvent.KEYCODE_DPAD_DOWN, 3, 4),
        )
        assertNull(
            ShortDramaEpisodeNavigationPolicy.targetEpisode(KeyEvent.KEYCODE_DPAD_DOWN, 0, 1),
        )
    }

    @Test
    fun repeatAndKeyUpNeverTriggerASecondEpisodeSwitch() {
        val handler = ShortDramaEpisodeKeyHandler()

        assertEquals(1, keyDown(handler, currentEpisode = 0).targetEpisode)
        val repeat = keyDown(handler, currentEpisode = 1, repeatCount = 3)
        assertTrue(repeat.consumed)
        assertNull(repeat.targetEpisode)
        val release = keyUp(handler)
        assertTrue(release.consumed)
        assertNull(release.targetEpisode)
        assertEquals(2, keyDown(handler, currentEpisode = 1, downTimeMs = 200).targetEpisode)
    }

    @Test
    fun aFreshPressStillWorksWhenThePreviousKeyUpWasLost() {
        val handler = ShortDramaEpisodeKeyHandler()

        assertEquals(1, keyDown(handler, currentEpisode = 0, downTimeMs = 100).targetEpisode)
        assertEquals(2, keyDown(handler, currentEpisode = 1, downTimeMs = 200).targetEpisode)
    }

    @Test
    fun switchingAndBoundaryPressesAreConsumedWithoutStartingAnotherSwitch() {
        val handler = ShortDramaEpisodeKeyHandler()

        val whileSwitching = keyDown(handler, currentEpisode = 1, switchingEpisode = true)
        assertTrue(whileSwitching.consumed)
        assertNull(whileSwitching.targetEpisode)
        keyUp(handler)

        val boundary = keyDown(handler, currentEpisode = 2, downTimeMs = 200)
        assertTrue(boundary.consumed)
        assertNull(boundary.targetEpisode)
    }

    @Test
    fun anAdjacentRequestWaitsForTheInFlightSwitchToPaint() {
        assertTrue(
            ShortDramaEpisodeNavigationPolicy.shouldDeferUntilFirstFrame(
                seamlessSwitchInFlight = true,
                firstFrameRendered = false,
                requestedEpisode = 4,
                currentEpisode = 5,
            ),
        )
        assertTrue(
            ShortDramaEpisodeNavigationPolicy.shouldDeferUntilFirstFrame(
                seamlessSwitchInFlight = true,
                firstFrameRendered = false,
                requestedEpisode = 6,
                currentEpisode = 5,
            ),
        )
        assertFalse(
            ShortDramaEpisodeNavigationPolicy.shouldDeferUntilFirstFrame(
                seamlessSwitchInFlight = true,
                firstFrameRendered = true,
                requestedEpisode = 6,
                currentEpisode = 5,
            ),
        )
        assertFalse(
            ShortDramaEpisodeNavigationPolicy.shouldDeferUntilFirstFrame(
                seamlessSwitchInFlight = false,
                firstFrameRendered = false,
                requestedEpisode = 6,
                currentEpisode = 5,
            ),
        )
        assertFalse(
            ShortDramaEpisodeNavigationPolicy.shouldDeferUntilFirstFrame(
                seamlessSwitchInFlight = true,
                firstFrameRendered = false,
                requestedEpisode = 8,
                currentEpisode = 5,
            ),
        )
    }

    @Test
    fun listDialogAndOtherMediaEventsAreLeftUntouched() {
        val handler = ShortDramaEpisodeKeyHandler()

        assertFalse(keyDown(handler, panelOpen = true).consumed)
        assertFalse(keyDown(handler, dialogOpen = true).consumed)
        assertFalse(keyDown(handler, shortDrama = false).consumed)
        assertFalse(
            handler.handle(
                shortDrama = true,
                keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                downTimeMs = 100,
                currentEpisode = 0,
                episodeCount = 3,
                panelOpen = false,
                dialogOpen = false,
                isLive = false,
                hasFatalError = false,
                switchingEpisode = false,
            ).consumed,
        )
    }

    private fun canIntercept(
        shortDrama: Boolean = true,
        panelOpen: Boolean = false,
        dialogOpen: Boolean = false,
        isLive: Boolean = false,
        hasFatalError: Boolean = false,
    ): Boolean = ShortDramaEpisodeNavigationPolicy.canIntercept(
        shortDrama = shortDrama,
        panelOpen = panelOpen,
        dialogOpen = dialogOpen,
        isLive = isLive,
        hasFatalError = hasFatalError,
    )

    private fun keyDown(
        handler: ShortDramaEpisodeKeyHandler,
        currentEpisode: Int = 0,
        shortDrama: Boolean = true,
        repeatCount: Int = 0,
        downTimeMs: Long = 100,
        panelOpen: Boolean = false,
        dialogOpen: Boolean = false,
        switchingEpisode: Boolean = false,
    ): ShortDramaEpisodeKeyResult = handler.handle(
        shortDrama = shortDrama,
        keyCode = KeyEvent.KEYCODE_DPAD_DOWN,
        action = KeyEvent.ACTION_DOWN,
        repeatCount = repeatCount,
        downTimeMs = downTimeMs,
        currentEpisode = currentEpisode,
        episodeCount = 3,
        panelOpen = panelOpen,
        dialogOpen = dialogOpen,
        isLive = false,
        hasFatalError = false,
        switchingEpisode = switchingEpisode,
    )

    private fun keyUp(
        handler: ShortDramaEpisodeKeyHandler,
    ): ShortDramaEpisodeKeyResult = handler.handle(
        shortDrama = true,
        keyCode = KeyEvent.KEYCODE_DPAD_DOWN,
        action = KeyEvent.ACTION_UP,
        repeatCount = 0,
        downTimeMs = 100,
        currentEpisode = 1,
        episodeCount = 3,
        panelOpen = false,
        dialogOpen = false,
        isLive = false,
        hasFatalError = false,
        switchingEpisode = false,
    )

    private fun shortDrama(
        categoryIds: List<String>,
        metadata: String,
        episodeCount: Int,
        durationMs: Long,
    ): Boolean = ShortDramaEpisodeNavigationPolicy.isShortDrama(
        categoryIds = categoryIds,
        metadata = metadata,
        episodeCount = episodeCount,
        durationMs = durationMs,
    )
}

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
        listOf("6", "douban:6", "hhkan:6", "douban:short", "hhkan:SHORT")
            .forEach { assertTrue(it, ShortDramaEpisodeNavigationPolicy.isShortDrama(it)) }
        listOf("", "douban:movie", "douban:tv", "douban:anime", "douban:variety", "hhkan:1")
            .forEach { assertFalse(it, ShortDramaEpisodeNavigationPolicy.isShortDrama(it)) }
    }

    @Test
    fun overlaysAndLiveOrErrorStatesKeepTheirOwnDpadFocus() {
        assertTrue(canIntercept())
        assertFalse(canIntercept(controlsVisible = true))
        assertFalse(canIntercept(panelOpen = true))
        assertFalse(canIntercept(dialogOpen = true))
        assertFalse(canIntercept(isLive = true))
        assertFalse(canIntercept(hasFatalError = true))
        assertFalse(canIntercept(categoryId = "douban:tv"))
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
        assertEquals(2, keyDown(handler, currentEpisode = 1, downTimeMs = 200)?.targetEpisode)
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
    fun listDialogAndOtherMediaEventsAreLeftUntouched() {
        val handler = ShortDramaEpisodeKeyHandler()

        assertFalse(keyDown(handler, panelOpen = true).consumed)
        assertFalse(keyDown(handler, dialogOpen = true).consumed)
        assertFalse(keyDown(handler, categoryId = "douban:tv").consumed)
        assertFalse(
            handler.handle(
                categoryId = "douban:short",
                keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                downTimeMs = 100,
                currentEpisode = 0,
                episodeCount = 3,
                controlsVisible = false,
                panelOpen = false,
                dialogOpen = false,
                isLive = false,
                hasFatalError = false,
                switchingEpisode = false,
            ).consumed,
        )
    }

    private fun canIntercept(
        categoryId: String = "douban:short",
        controlsVisible: Boolean = false,
        panelOpen: Boolean = false,
        dialogOpen: Boolean = false,
        isLive: Boolean = false,
        hasFatalError: Boolean = false,
    ): Boolean = ShortDramaEpisodeNavigationPolicy.canIntercept(
        categoryId = categoryId,
        controlsVisible = controlsVisible,
        panelOpen = panelOpen,
        dialogOpen = dialogOpen,
        isLive = isLive,
        hasFatalError = hasFatalError,
    )

    private fun keyDown(
        handler: ShortDramaEpisodeKeyHandler,
        currentEpisode: Int = 0,
        categoryId: String = "douban:short",
        repeatCount: Int = 0,
        downTimeMs: Long = 100,
        controlsVisible: Boolean = false,
        panelOpen: Boolean = false,
        dialogOpen: Boolean = false,
        switchingEpisode: Boolean = false,
    ): ShortDramaEpisodeKeyResult = handler.handle(
        categoryId = categoryId,
        keyCode = KeyEvent.KEYCODE_DPAD_DOWN,
        action = KeyEvent.ACTION_DOWN,
        repeatCount = repeatCount,
        downTimeMs = downTimeMs,
        currentEpisode = currentEpisode,
        episodeCount = 3,
        controlsVisible = controlsVisible,
        panelOpen = panelOpen,
        dialogOpen = dialogOpen,
        isLive = false,
        hasFatalError = false,
        switchingEpisode = switchingEpisode,
    )

    private fun keyUp(
        handler: ShortDramaEpisodeKeyHandler,
    ): ShortDramaEpisodeKeyResult = handler.handle(
        categoryId = "douban:short",
        keyCode = KeyEvent.KEYCODE_DPAD_DOWN,
        action = KeyEvent.ACTION_UP,
        repeatCount = 0,
        downTimeMs = 100,
        currentEpisode = 1,
        episodeCount = 3,
        controlsVisible = false,
        panelOpen = false,
        dialogOpen = false,
        isLive = false,
        hasFatalError = false,
        switchingEpisode = false,
    )
}

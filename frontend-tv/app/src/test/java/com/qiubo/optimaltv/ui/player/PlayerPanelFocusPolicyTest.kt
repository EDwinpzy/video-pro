package com.qiubo.optimaltv.ui.player

import com.qiubo.optimaltv.ui.components.NAV_DOWN
import com.qiubo.optimaltv.ui.components.NAV_LEFT
import com.qiubo.optimaltv.ui.components.NAV_RIGHT
import com.qiubo.optimaltv.ui.components.NAV_UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerPanelFocusPolicyTest {

    @Test
    fun verticalMoveClampsIntoShorterEpisodeRow() {
        assertEquals(
            PlayerPanelFocusPosition(2, 2),
            PlayerPanelFocusPolicy.next(listOf(5, 5, 3), PlayerPanelFocusPosition(1, 4), NAV_DOWN),
        )
        assertEquals(
            PlayerPanelFocusPosition(1, 1),
            PlayerPanelFocusPolicy.next(listOf(3, 2), PlayerPanelFocusPosition(0, 2), NAV_DOWN),
        )
    }

    @Test
    fun verticalMoveMapsColumnWhenReturningToWiderRow() {
        assertEquals(
            PlayerPanelFocusPosition(0, 2),
            PlayerPanelFocusPolicy.next(listOf(5, 3), PlayerPanelFocusPosition(1, 1), NAV_UP),
        )
    }

    @Test
    fun horizontalMoveStopsAtLogicalRowEdges() {
        val rows = listOf(2, 4)
        assertNull(PlayerPanelFocusPolicy.next(rows, PlayerPanelFocusPosition(1, 3), NAV_RIGHT))
        assertNull(PlayerPanelFocusPolicy.next(rows, PlayerPanelFocusPosition(0, 0), NAV_LEFT))
        assertEquals(
            PlayerPanelFocusPosition(0, 1),
            PlayerPanelFocusPolicy.next(rows, PlayerPanelFocusPosition(0, 0), NAV_RIGHT),
        )
    }

    @Test
    fun initialFocusUsesRequestedActivePage() {
        assertEquals(
            PlayerPanelFocusPosition(5, 2),
            PlayerPanelFocusPolicy.normalized(listOf(1, 1, 1, 1, 1, 4), PlayerPanelFocusPosition(5, 2)),
        )
    }

    @Test
    fun staleFocusRowsAreClampedInsteadOfCrashingAfterATabChange() {
        val rows = listOf(6, 6, 2)

        assertEquals(
            PlayerPanelFocusPosition(2, 1),
            PlayerPanelFocusPolicy.normalized(rows, PlayerPanelFocusPosition(9, 99)),
        )
        assertEquals(
            PlayerPanelFocusPosition(1, 3),
            PlayerPanelFocusPolicy.next(rows, PlayerPanelFocusPosition(9, 4), NAV_UP),
        )
        assertEquals(
            PlayerPanelFocusPosition(2, 0),
            PlayerPanelFocusPolicy.next(rows, PlayerPanelFocusPosition(9, 4), NAV_LEFT),
        )
    }

    @Test
    fun emptyRowsAndRowsWithoutVisibleItemsDoNotProduceInvalidTargets() {
        assertEquals(
            PlayerPanelFocusPosition(0, 0),
            PlayerPanelFocusPolicy.normalized(emptyList(), PlayerPanelFocusPosition(3, 2)),
        )
        assertNull(PlayerPanelFocusPolicy.next(emptyList(), PlayerPanelFocusPosition(0, 0), NAV_DOWN))
        assertNull(PlayerPanelFocusPolicy.next(listOf(0, 3), PlayerPanelFocusPosition(1, 1), NAV_UP))
        assertEquals(
            PlayerPanelFocusPosition(0, 0),
            PlayerPanelFocusPolicy.normalized(listOf(0), PlayerPanelFocusPosition(0, 7)),
        )
    }
}

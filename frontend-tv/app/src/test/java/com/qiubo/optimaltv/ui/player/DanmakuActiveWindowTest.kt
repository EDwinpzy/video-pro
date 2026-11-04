package com.qiubo.optimaltv.ui.player

import com.qiubo.optimaltv.data.source.DanmakuItem
import org.junit.Assert.assertEquals
import org.junit.Test

class DanmakuActiveWindowTest {
    @Test
    fun steadyClockOnlyKeepsIntervalsThatAreStillVisible() {
        val window =
            DanmakuActiveWindow(
                listOf(
                    placement(startMs = 0L, endMs = 100L),
                    placement(startMs = 50L, endMs = 200L),
                    placement(startMs = 150L, endMs = 250L),
                ),
            )

        assertEquals(listOf(0, 1), window.activeAt(60L).toList())
        assertEquals(listOf(1), window.activeAt(120L).toList())
        assertEquals(listOf(1, 2), window.activeAt(150L).toList())
        assertEquals(listOf(2), window.activeAt(210L).toList())
    }

    @Test
    fun backwardClockRebuildsTheActiveWindow() {
        val window =
            DanmakuActiveWindow(
                listOf(
                    placement(startMs = 0L, endMs = 100L),
                    placement(startMs = 50L, endMs = 200L),
                ),
            )

        assertEquals(listOf(1), window.activeAt(150L).toList())
        assertEquals(listOf(0, 1), window.activeAt(60L).toList())
    }

    @Test
    fun lateFirstFrameSkipsExpiredIntervals() {
        val window =
            DanmakuActiveWindow(
                listOf(
                    placement(startMs = 0L, endMs = 100L),
                    placement(startMs = 50L, endMs = 200L),
                ),
            )

        assertEquals(listOf(1), window.activeAt(120L).toList())
    }

    private fun placement(
        startMs: Long,
        endMs: Long,
    ) = DanmakuPlacement(
        item =
            DanmakuItem(
                timeMs = startMs,
                sourceMode = 1,
                color = 0xFFFFFF,
                fontSize = 24,
                content = "fixture-$startMs",
                sourceId = "fixture-$startMs",
            ),
        lane = 0,
        endMs = endMs,
    )
}

package com.qiubo.optimaltv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchHistoryLabelTest {

    @Test
    fun formatsEpisodeAndClockPositionWithSeparators() {
        assertEquals(
            "上次看到 · 第3集 · 23:30",
            formatWatchHistoryLabel(episodeNumber = 3, positionMs = 23 * 60_000L + 30_000L),
        )
    }

    @Test
    fun omitsEpisodeForMovies() {
        assertEquals(
            "上次看到 · 23:30",
            formatWatchHistoryLabel(episodeNumber = null, positionMs = 23 * 60_000L + 30_000L),
        )
    }
}

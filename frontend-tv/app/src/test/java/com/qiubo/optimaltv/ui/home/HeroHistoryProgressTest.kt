package com.qiubo.optimaltv.ui.home

import com.qiubo.optimaltv.data.db.HistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class HeroHistoryProgressTest {

    @Test
    fun clampsProgressAndSuppressesPercentWithoutDuration() {
        assertEquals(0.5f, presentation(positionMs = 50_000L, durationMs = 100_000L).progressFraction)
        assertEquals(50, presentation(positionMs = 50_000L, durationMs = 100_000L).percent)
        assertEquals(1f, presentation(positionMs = 120_000L, durationMs = 100_000L).progressFraction)
        assertEquals(null, presentation(positionMs = 50_000L, durationMs = 0L).percent)
    }

    @Test
    fun historyEpisodeIsOneBasedOnlyWhenRequested() {
        assertEquals(3, presentation(epIndex = 2, showEpisode = true).episodeNumber)
        assertEquals(null, presentation(epIndex = 2, showEpisode = false).episodeNumber)
    }

    private fun presentation(
        positionMs: Long = 36_000L,
        durationMs: Long = 72_000L,
        epIndex: Int = 0,
        showEpisode: Boolean = true,
    ): HeroHistoryPresentation =
        heroHistoryPresentation(
            history =
                HistoryEntity(
                    vodId = "mytv:1",
                    title = "Title",
                    posterUrl = "",
                    epIndex = epIndex,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    updatedAt = 1L,
                ),
            showEpisode = showEpisode,
        )
}

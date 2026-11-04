package com.qiubo.optimaltv.ui.detail

import com.qiubo.optimaltv.data.db.ProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetailResumeUpdatesTest {
    private fun progress(vodId: String, episode: Int, position: Long, updated: Long) =
        ProgressEntity("$vodId-e$episode", vodId, episode, position, 60_000, updated)

    @Test
    fun returningToAnEarlierEpisodeUsesItsNewerProgressAcrossCandidateSources() {
        val candidates = mapOf("source-a" to 20, "source-b" to 20)
        val older = progress("source-a", 15, 30_000, 100)
        val recent = progress("source-b", 1, 8_000, 200)
        val unrelated = progress("other-title", 3, 40_000, 300)
        assertEquals(recent, latestDetailProgress(candidates, listOf(older, recent, unrelated)))
    }

    @Test
    fun outOfRangeAndInvalidMediaSamplesCannotReplaceValidResume() {
        val valid = progress("source-a", 1, 8_000, 100)
        val rows = listOf(
            valid,
            progress("source-a", 20, 9_000, 200),
            progress("source-a", -1, 9_000, 300),
            progress("source-a", 2, -1, 400),
            progress("source-a", 2, 0, 500).copy(durationMs = 0),
        )
        assertEquals(valid, latestDetailProgress(mapOf("source-a" to 20), rows))
        assertNull(latestDetailProgress(emptyMap(), rows))
    }

    @Test
    fun anExplicitlySelectedEntryPausedAtItsStartRemainsTheResumeTarget() {
        val older = progress("source-a", 1, 8_000, 100)
        val pausedAtStart = progress("source-a", 2, 0, 200)
        assertEquals(pausedAtStart, latestDetailProgress(mapOf("source-a" to 20), listOf(older, pausedAtStart)))
    }
}

package com.qiubo.optimaltv.ui.player

import com.qiubo.optimaltv.data.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeSelectionIdentityTest {
    @Test
    fun insertingANewEpisodeBeforeExtrasKeepsTheSelectedExtra() {
        val first = Episode(0, "第1集", "episode-one")
        val extra = Episode(1, "陪看1", "extra-one")
        val added = Episode(1, "第2集", "episode-two")
        val refreshed = listOf(first, added, extra.copy(index = 2))
        assertEquals(2, refreshedEpisodeIndex(extra, refreshed, stableTokenIdentity = true))
    }

    @Test
    fun stableTokensDistinguishDuplicateNamesAndSurviveRenaming() {
        val selected = Episode(0, "预告", "preview-b")
        val refreshed = listOf(Episode(0, "预告", "preview-a"), Episode(1, "新版预告", "preview-b"))
        assertEquals(1, refreshedEpisodeIndex(selected, refreshed, stableTokenIdentity = true))
    }

    @Test
    fun removedOrAmbiguousOfficialIdentityCannotFallBackToTheOldPosition() {
        val selected = Episode(0, "预告", "removed-preview")
        assertNull(refreshedEpisodeIndex(selected, listOf(Episode(0, "预告", "different-preview")), true))
        assertNull(refreshedEpisodeIndex(selected, listOf(selected, selected.copy(index = 1)), true))
    }

    @Test
    fun nonOfficialExpiringUrlsUseOnlyAnUnambiguousSourceName() {
        val selected = Episode(0, "第2集", "https://example.invalid/expired")
        val refreshed = listOf(Episode(0, "第1集", ""), Episode(1, "第2集", "https://example.invalid/fresh"))
        assertEquals(1, refreshedEpisodeIndex(selected, refreshed, false))
        assertNull(refreshedEpisodeIndex(selected, refreshed + refreshed.last(), false))
    }
}

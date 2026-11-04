package com.qiubo.optimaltv.ui.player

import com.qiubo.optimaltv.ui.components.episodeEntryCustomName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeEntryNamesTest {
    @Test
    fun canonicalOrdinalAliasesUseTheNumberedFallback() {
        listOf(null, "", "  ", "01", "1", "第 01 集", "01集").forEach { name ->
            assertNull(episodeEntryCustomName(0, name))
        }
    }

    @Test
    fun supplementalNamesSurviveTheirPositionAfterNumberedEpisodes() {
        listOf("陪看1", "预告片", "拍摄花絮", "第3集预告").forEach { name ->
            assertEquals(name, episodeEntryCustomName(26, " $name "))
        }
    }

    @Test
    fun aSourceEpisodeNumberThatDiffersFromItsPositionIsNeverRelabelled() {
        val sourceName = "第42集"
        assertEquals(sourceName, episodeEntryCustomName(26, sourceName))
    }
}

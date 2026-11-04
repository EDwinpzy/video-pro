package com.qiubo.optimaltv.ui.player

import com.qiubo.optimaltv.data.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeFocusAnchorTest {
    @Test
    fun focusFollowsTheSelectedContentAcrossAnInsertedEpisodeAndRowBoundary() {
        val original = (1..6).map { Episode(it - 1, "第${it}集", "episode-$it") } +
            Episode(6, "陪看", "extra")
        val anchor = EpisodeFocusAnchor()
        anchor.focused(episodeIdentityKeys(original)[6])
        val refreshed = original.take(6) + Episode(6, "新剧集", "new-episode") + original.last()
        assertEquals(7, anchor.indexAfterRefresh(episodeIdentityKeys(refreshed), 0))
    }

    @Test
    fun leavingForTheTabsCancelsLateRestoration() {
        val anchor = EpisodeFocusAnchor()
        anchor.focused("extra")
        anchor.clear()
        assertNull(anchor.indexAfterRefresh(listOf("episode", "extra"), 0))
    }

    @Test
    fun removedContentFallsBackToAValidEntryWithoutTargetingAnEmptyList() {
        val anchor = EpisodeFocusAnchor()
        anchor.focused("removed")
        assertEquals(1, anchor.indexAfterRefresh(listOf("one", "two"), 9))
        assertNull(anchor.indexAfterRefresh(emptyList(), 0))
    }

    @Test
    fun transientNativeFallbackCannotRewriteTheCapturedBusinessTarget() {
        val anchor = EpisodeFocusAnchor()
        anchor.focused("extra")
        val request = checkNotNull(anchor.captureRefresh(listOf("new-episode", "extra"), 0))
        anchor.focused("new-episode")
        org.junit.Assert.assertTrue(anchor.isCurrent(request))
        assertEquals(1, request.index)
    }

    @Test
    fun userMovementOrLeavingTheGridInvalidatesAnOlderRestoreRequest() {
        val anchor = EpisodeFocusAnchor()
        anchor.focused("extra")
        val first = checkNotNull(anchor.captureRefresh(listOf("extra"), 0))
        anchor.movedByUser()
        org.junit.Assert.assertFalse(anchor.isCurrent(first))
        val second = checkNotNull(anchor.captureRefresh(listOf("extra"), 0))
        anchor.clear()
        org.junit.Assert.assertFalse(anchor.isCurrent(second))
    }
}

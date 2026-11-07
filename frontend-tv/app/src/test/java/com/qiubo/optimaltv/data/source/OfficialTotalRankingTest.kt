package com.qiubo.optimaltv.data.source

import android.app.Application
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [30])
class OfficialTotalRankingTest {
    @Test fun seriesAndShortTabsUseOfficialRequestKinds() = runTest {
        val paths = mutableListOf<String>()
        val source = DongpianOfficialVodSource(RuntimeEnvironment.getApplication()) { _, path, _, _ ->
            paths += path
            response(emptyList(), false, 1)
        }
        source.totalRanking("tv")
        source.totalRanking("short")
        assertTrue("kind=series" in paths[0])
        assertTrue("kind=short_drama" in paths[1])
        assertTrue(paths.all { "window=total" in it })
    }

    @Test fun latestShortDramaDoesNotDownloadTheEntireCatalog() = runTest {
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val source = DongpianOfficialVodSource(RuntimeEnvironment.getApplication()) { _, path, _, _ ->
            paths += path
            response(listOf("a"), true, 2)
        }
        source.shortDramaRecommendations()
        assertEquals(1, paths.count { "intent=latest_catalog" in it })
        assertTrue(paths.all { "kind=short_drama" in it })
    }

    @Test fun animeSectionsUseOfficialCatalogueAndKeepEachSectionIndependent() = runTest {
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val source = DongpianOfficialVodSource(RuntimeEnvironment.getApplication()) { _, path, _, _ ->
            paths += path
            response(listOf("a"), false, 1)
        }
        val sections = source.animeRecommendationSections()
        assertEquals(listOf("最近热门", "最近更新", "豆瓣高分"), sections.map { it.title })
        assertEquals(3, paths.size)
        assertTrue(paths.all { "kind=anime" in it })
        assertTrue(paths.any { "intent=latest_catalog" in it })
        assertTrue(paths.any { "sort=rating" in it })
    }

    @Test fun usesTotalWindowAndFollowsHasMoreInsteadOfReportedTotal() = runTest {
        val paths = mutableListOf<String>()
        val source = DongpianOfficialVodSource(RuntimeEnvironment.getApplication()) { _, path, _, _ ->
            paths += path
            if (paths.size == 1) response(listOf("a", "b"), true, 2)
            else response(listOf("b", "c"), false, 3)
        }
        assertEquals(listOf("dongpian:a", "dongpian:b", "dongpian:c"), source.totalRanking("movie").map { it.id })
        assertEquals(2, paths.size)
        assertTrue(paths.all { "window=total" in it })
        assertTrue(paths.all { "kind=movie" in it })
        assertTrue("page=2" in paths.last())
    }

    @Test fun repeatedPageCannotKeepPaginationRunning() = runTest {
        var calls = 0
        val source = DongpianOfficialVodSource(RuntimeEnvironment.getApplication()) { _, _, _, _ ->
            calls += 1
            response(listOf("a"), true, calls + 1)
        }
        assertEquals(1, source.totalRanking().size)
        assertEquals(2, calls)
    }

    private fun response(ids: List<String>, more: Boolean, next: Int): JSONObject = JSONObject()
        .put("cards", JSONArray(ids.map { JSONObject().put("id", it).put("title", "Fictional $it").put("content_kind", "movie") }))
        .put("pagination", JSONObject().put("total", 200).put("has_more", more).put("next_page", next))
}

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

    @Test fun animeRankingUsesBothOfficial3dGenres() = runTest {
        val paths = mutableListOf<String>()
        val source = DongpianOfficialVodSource(RuntimeEnvironment.getApplication()) { _, path, _, _ ->
            paths += path
            animeResponse("ranking", "2026-11-11T09:00:00Z")
        }

        assertEquals(listOf("dongpian:ranking"), source.totalRanking("anime").map { it.id })
        assertEquals(2, paths.size)
        assertTrue(paths.all { "kind=anime" in it })
        assertTrue(paths.all { "genre=3D" in it })
        assertTrue(paths.all { "sort=trending" !in it })
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
        assertEquals(listOf("最近热门", "最近更新"), sections.map { it.title })
        assertEquals(4, paths.size)
        assertTrue(paths.all { "kind=anime" in it })
        assertTrue(paths.all { "genre=3D" in it })
        assertEquals(2, paths.count { "intent=latest_catalog" in it })
        assertTrue(paths.all { "sort=rating" !in it })
    }

    @Test fun animeSectionsMergeBothOfficial3dGenresByLatestUpdate() = runTest {
        val source = DongpianOfficialVodSource(RuntimeEnvironment.getApplication()) { _, path, _, _ ->
            when {
                "intent=latest_catalog" !in path -> response(emptyList(), false, 2)
                "genre=3D%E5%8A%A8%E7%94%BB" in path ->
                    animeResponse("newer", "2026-11-11T09:00:00Z")
                else -> animeResponse("older", "2026-11-10T09:00:00Z")
            }
        }

        val latest = source.animeRecommendationSections().first { it.title == "最近更新" }

        assertEquals(listOf("dongpian:newer", "dongpian:older"), latest.items.map { it.id })
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

    private fun animeResponse(id: String, updatedAt: String): JSONObject =
        JSONObject()
            .put(
                "cards",
                JSONArray(
                    listOf(
                        JSONObject()
                            .put("id", id)
                            .put("title", "Fictional $id")
                            .put("content_kind", "anime")
                            .put("area", "中国大陆")
                            .put("genres", JSONArray(listOf("国产动漫", "3D")))
                            .put("updated_at", updatedAt),
                    ),
                ),
            )
            .put("pagination", JSONObject().put("has_more", false))
}

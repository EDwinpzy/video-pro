package com.qiubo.optimaltv.data.source

import com.qiubo.optimaltv.data.model.Episode
import com.qiubo.optimaltv.data.model.VodItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DongpianOfficialLibraryPayloadTest {
    @Test
    fun libraryPagePathKeepsOfficialFiltersAndEncodesCursor() {
        assertEquals(
            "/v1/users/me/history?limit=100&update_view=text&cursor=next%2Fpage%3D2",
            officialLibraryPagePath(
                basePath = "/v1/users/me/history",
                limit = 500,
                cursor = "next/page=2",
                extraParams = mapOf("update_view" to "text"),
            ),
        )
    }

    @Test
    fun historyPayloadUsesOfficialKindAndEpisodeToken() {
        val item =
            VodItem(
                id = "dongpian:variant-1",
                sourceId = "dongpian",
                title = "测试剧",
                categoryId = "douban:tv",
                posterUrl = "https://example.com/poster.webp",
                detailRef = "dongpian:variant-1",
                episodes = listOf(Episode(0, "第1集", "episode-token-1")),
            )

        val payload =
            officialHistoryPayload(
                item = item,
                episodeIndex = 0,
                positionMs = 95_000,
                durationMs = 1_800_000,
                watchedAt = "2026-11-01T00:00:00Z",
            )

        assertEquals("series", payload.getString("content_kind"))
        assertEquals("episode-token-1", payload.getString("episode_token"))
        assertEquals(95, payload.getInt("position_seconds"))
        assertEquals(1_800, payload.getInt("duration_seconds"))
    }

    @Test
    fun favoritePayloadContainsCardAndMapsShortDramaKind() {
        val item =
            VodItem(
                id = "dongpian:variant-2",
                sourceId = "dongpian",
                title = "测试短剧",
                categoryId = "douban:short",
                year = "2026",
                posterUrl = "https://example.com/short.webp",
                detailRef = "dongpian:variant-2",
            )

        val payload = officialFavoritePayload(item, "variant-2")
        val card = payload.getJSONObject("card")

        assertEquals("short_drama", payload.getString("content_kind"))
        assertEquals("short_drama", card.getString("content_kind"))
        assertEquals("variant-2", card.getString("variant_id"))
        assertEquals(2026, payload.getInt("year"))
        assertFalse(payload.has("episode_token"))
    }
}

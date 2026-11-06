package com.qiubo.optimaltv.ui.home

import com.qiubo.optimaltv.data.db.HistoryEntity
import com.qiubo.optimaltv.data.model.VodItem
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentWatchPolicyTest {

    @Test
    fun sortsByLatestProgressAndDeduplicatesEachTitle() {
        val older = history("movie", updatedAt = 10L, epIndex = 0)
        val newer = older.copy(updatedAt = 30L, epIndex = 2, positionMs = 300_000L)
        val other = history("tv", updatedAt = 20L, epIndex = 1)

        val result =
            RecentWatchPolicy.build(
                history = listOf(older, other, newer),
                catalogById = emptyMap(),
                resolvedItems = emptyMap(),
            )

        assertEquals(listOf("movie", "tv"), result.map { it.history.vodId })
        assertEquals(2, result.first().history.epIndex)
    }

    @Test
    fun reportsProgressAndEpisodeForEpisodicHistory() {
        val saved = history("series", updatedAt = 100L, epIndex = 2, positionMs = 300_000L, durationMs = 600_000L)
        val item = VodItem(saved.vodId, "test", "Series title", "douban:tv", posterUrl = saved.posterUrl)

        val entry =
            RecentWatchPolicy.build(
                history = listOf(saved),
                catalogById = mapOf(saved.vodId to item),
                resolvedItems = emptyMap(),
            ).single()

        assertEquals("Series title", entry.title)
        assertEquals(0.5f, entry.progress)
        assertEquals("上次看到 · 第3集 · 05:00", entry.progressLabel)
    }

    @Test
    fun omitsEpisodeNumberForMoviesAndUsesSavedPosterFallback() {
        val saved = history("movie", updatedAt = 100L, epIndex = 7)

        val entry =
            RecentWatchPolicy.build(
                history = listOf(saved),
                catalogById = emptyMap(),
                resolvedItems = emptyMap(),
            ).single()

        assertEquals(saved.posterUrl, entry.posterUrl)
        assertEquals("上次看到 · 00:42", entry.progressLabel)
    }

    @Test
    fun resolvedDetailSuppliesTitleAndPosterWithoutChangingResumeState() {
        val saved = history("series", updatedAt = 100L, epIndex = 4)
        val detail = VodItem(
            id = saved.vodId,
            sourceId = "test",
            title = "Resolved title",
            categoryId = "douban:tv",
            posterUrl = "https://img.example/detail-poster.webp",
        )

        val entry =
            RecentWatchPolicy.build(
                history = listOf(saved),
                catalogById = emptyMap(),
                resolvedItems = mapOf(saved.vodId to detail),
            ).single()

        assertEquals("Resolved title", entry.title)
        assertEquals(detail.posterUrl, entry.posterUrl)
        assertEquals(saved, entry.history)
        assertEquals("上次看到 · 第5集 · 00:42", entry.progressLabel)
    }

    @Test
    fun appliesTheRequestedLimitAfterDeduplication() {
        val history =
            listOf(
                history("a", updatedAt = 30L),
                history("b", updatedAt = 20L),
                history("c", updatedAt = 10L),
            )

        val result =
            RecentWatchPolicy.build(
                history = history,
                catalogById = emptyMap(),
                resolvedItems = emptyMap(),
                limit = 2,
            )

        assertEquals(listOf("a", "b"), result.map { it.history.vodId })
    }

    private fun history(
        id: String,
        updatedAt: Long,
        epIndex: Int = 1,
        positionMs: Long = 42_000L,
        durationMs: Long = 1_800_000L,
    ) = HistoryEntity(
        vodId = id,
        title = "History $id",
        posterUrl = "https://img.example/poster/$id.webp",
        epIndex = epIndex,
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAt = updatedAt,
    )
}

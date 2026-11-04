package com.qiubo.optimaltv.ui.home

import com.qiubo.optimaltv.data.db.HistoryEntity
import com.qiubo.optimaltv.data.model.VodItem
import com.qiubo.optimaltv.data.repo.Aggregated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RecentHistoryHeroesTest {

    @Test
    fun filtersEachCategoryBeforeTakingNewestFive() {
        val records = (1..12).map { history("mytv:$it", "Title $it", "", it.toLong()) }
        val types = listOf("movie", "tv", "anime", "short")
        val metadata = records.mapIndexed { index, record ->
            record.vodId to VodItem(record.vodId, "mytv", record.title, "douban:${types[index % 4]}", area = "中国大陆")
        }.toMap()
        listOf("movie", "series", "anime", "short").forEachIndexed { index, tab ->
            val expected = records.filterIndexed { position, _ -> position % 4 == index }.reversed().map { it.vodId }
            assertEquals(expected, recentHistoryHeroes(records, null, tab, metadata).map { it.id })
        }
        val movies = records.associate { record ->
            record.vodId to VodItem(record.vodId, "mytv", record.title, "douban:movie")
        }
        assertEquals(records.reversed().take(5).map { it.vodId }, recentHistoryHeroes(records, null, "movie", movies).map { it.id })
    }

    @Test
    fun missingMetadataAndForeignAnimationDoNotLeakIntoOtherCategories() {
        val saved = history("mytv:1", "Title", "", 1)
        assertEquals(emptyList<HeroEntry>(), recentHistoryHeroes(listOf(saved), null, "movie"))
        val foreign = VodItem(saved.vodId, "mytv", saved.title, "douban:anime", area = "日本")
        assertEquals(emptyList<HeroEntry>(), recentHistoryHeroes(listOf(saved), null, "anime", mapOf(saved.vodId to foreign)))
    }

    @Test
    fun classifiesCloudHistoryFromDetailMetadataWithoutCatalogCategory() {
        fun item(meta: String) = VodItem("dongpian:fictional", "dongpian", "Title", "", meta = meta)
        assertEquals("movie", recentHistoryCategory(item("2026 / 美国 / 爱情片")))
        assertEquals("series", recentHistoryCategory(item("2026 / 中国大陆 / 国产剧 / 连续剧")))
        assertEquals("anime", recentHistoryCategory(item("2026 / 中国大陆 / 动画")))
        assertEquals("short", recentHistoryCategory(item("2026 / 中国大陆 / 短剧")))
        assertEquals("variety", recentHistoryCategory(item("2026 / 中国大陆 / 综艺")))
        assertEquals(null, recentHistoryCategory(item("2026 / 日本 / 动画")))
    }

    @Test
    fun `keeps newest five histories in room order`() {
        val history = (1..6).map { index ->
            history(
                id = "mytv:$index",
                title = "影片$index",
                poster = "https://example.com/poster-$index.jpg",
                updatedAt = 100L - index,
            )
        }

        val result = recentHistoryHeroes(history, catalog = null)

        assertEquals(listOf("mytv:1", "mytv:2", "mytv:3", "mytv:4", "mytv:5"), result.map(HeroEntry::id))
        assertEquals("影片1", result.first().title)
        assertEquals("上次使用历史海报兜底", "https://example.com/poster-1.jpg", result.first().bg)
        assertSame(history.first(), result.first().history)
    }

    @Test
    fun `uses catalog backdrop and title while keeping history resume data`() {
        val saved = history(
            id = "mytv:42",
            title = "",
            poster = "https://example.com/history-poster.jpg",
            updatedAt = 500L,
        )
        val catalogItem = VodItem(
            id = saved.vodId,
            sourceId = "mytv",
            title = "目录片名",
            categoryId = "douban:movie",
            posterUrl = "https://example.com/catalog-poster.jpg",
            heroUrl = "https://example.com/catalog-hero.jpg",
            remark = "正片",
        )
        val catalog = Aggregated(
            categories = emptyList(),
            dedupedItems = listOf(catalogItem),
            groups = mapOf(catalogItem.normTitle to listOf(catalogItem)),
        )

        val result = recentHistoryHeroes(listOf(saved), catalog).single()

        assertEquals("目录片名", result.title)
        assertEquals("https://example.com/catalog-hero.jpg", result.bg)
        assertEquals("正片", result.tag)
        assertSame(saved, result.history)
    }

    @Test
    fun `shows episode number only for episodic history heroes`() {
        val saved = history(
            id = "mytv:series",
            title = "剧集",
            poster = "https://example.com/poster.jpg",
            updatedAt = 100L,
        )
        val series = VodItem(saved.vodId, "mytv", saved.title, "douban:tv")
        val movie = series.copy(id = "mytv:movie", categoryId = "douban:movie")

        val seriesHero = recentHistoryHeroes(
            listOf(saved),
            catalog = null,
            tabKey = "series",
            resolvedItems = mapOf(saved.vodId to series),
        ).single()
        val movieHero = recentHistoryHeroes(
            listOf(saved.copy(vodId = movie.id)),
            catalog = null,
            tabKey = "movie",
            resolvedItems = mapOf(movie.id to movie),
        ).single()

        assertEquals(2, seriesHero.episodeNumber)
        assertEquals(null, movieHero.episodeNumber)
    }

    private fun history(
        id: String,
        title: String,
        poster: String,
        updatedAt: Long,
    ) = HistoryEntity(
        vodId = id,
        title = title,
        posterUrl = poster,
        epIndex = 1,
        positionMs = 36_000L,
        durationMs = 1_800_000L,
        updatedAt = updatedAt,
    )
}

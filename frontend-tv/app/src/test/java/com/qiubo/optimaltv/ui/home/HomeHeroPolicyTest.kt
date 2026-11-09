package com.qiubo.optimaltv.ui.home

import com.qiubo.optimaltv.data.model.VodItem
import com.qiubo.optimaltv.data.model.ChannelSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeHeroPolicyTest {

    @Test
    fun homeInterleavesMovieTvAndAnimeWhilePreferringWideArtwork() {
        val items =
            mapOf(
                "movie" to listOf(item("movie:1", "movie"), item("movie:poster", "movie", hero = false)),
                "tv" to listOf(item("tv:1", "tv")),
                "anime" to listOf(item("anime:1", "anime")),
            )

        val result = HomeHeroPolicy.forHome(items)

        assertEquals(listOf("movie:1", "tv:1", "anime:1"), result.map(HeroEntry::id))
        assertTrue(result.all { it.bg.contains("/hero/") })
    }

    @Test
    fun homeCapsTheMixedCarouselAtEightDistinctTitles() {
        val items =
            HomeHeroPolicy.HOME_CATEGORIES.associateWith { category ->
                (1..4).map { index -> item("$category:$index", category) }
            }

        val result = HomeHeroPolicy.forHome(items)

        assertEquals(HomeHeroPolicy.MAX_HEROES, result.size)
        assertEquals(HomeHeroPolicy.MAX_HEROES, result.map(HeroEntry::id).distinct().size)
        assertEquals(
            listOf("movie:1", "tv:1", "anime:1", "movie:2", "tv:2", "anime:2", "movie:3", "tv:3"),
            result.map(HeroEntry::id),
        )
    }

    @Test
    fun categoryUsesPopularRowBeforeOtherSections() {
        val sections =
            listOf(
                ChannelSection("最近更新", listOf(item("other", "movie"))),
                ChannelSection("大家都在看", listOf(item("popular", "movie"))),
            )

        assertEquals(listOf("popular"), HomeHeroPolicy.forCategory(sections).map(HeroEntry::id))
    }

    @Test
    fun categoryFallsBackToAvailableRowsWhenPopularRowIsMissing() {
        val sections = listOf(ChannelSection("最近更新", listOf(item("fallback", "movie"))))

        assertEquals(listOf("fallback"), HomeHeroPolicy.forCategory(sections).map(HeroEntry::id))
    }

    @Test
    fun posterOnlyTitleRemainsVisibleButKeepsItsPortraitAsFallback() {
        val posterOnly = item("movie:poster", "movie", hero = false)

        val result = HomeHeroPolicy.fromItems(listOf(posterOnly)).single()

        assertEquals(posterOnly.posterUrl, result.bg)
        assertEquals("", result.fallbackBg)
    }

    @Test
    fun dedicatedArtworkCarriesPosterForLoadFailureFallback() {
        val title = item("movie:1", "movie")

        val result = HomeHeroPolicy.fromItems(listOf(title)).single()

        assertEquals(title.heroUrl, result.bg)
        assertEquals(title.posterUrl, result.fallbackBg)
    }

    private fun item(
        id: String,
        category: String,
        hero: Boolean = true,
    ) = VodItem(
        id = id,
        sourceId = "test",
        title = "Title $id",
        categoryId = "douban:$category",
        posterUrl = "https://img.example/poster/$id.webp",
        heroUrl = if (hero) "https://img.example/hero/$id.webp" else "",
    )
}

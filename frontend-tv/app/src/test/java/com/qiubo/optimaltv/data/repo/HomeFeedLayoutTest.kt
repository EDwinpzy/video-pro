package com.qiubo.optimaltv.data.repo

import com.qiubo.optimaltv.data.model.VodItem
import com.qiubo.optimaltv.data.model.ChannelSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFeedLayoutTest {
    private fun item(id: String) = VodItem(id, "fixture", id, "movie")

    @Test fun categoryRankingsCannotLeakContentFromOtherTabs() {
        val ranking = listOf("movie", "tv", "anime", "short", "variety").map { item(it).copy(categoryId = "douban:$it", area = "中国大陆") }
        listOf("movie", "tv", "anime", "short", "variety").forEach { category ->
            assertEquals(listOf(category), HomeFeedLayout.sections(category, ranking, emptyList(), emptyList()).first().items.map { it.id })
        }
    }

    @Test fun officialMovieAndTvSectionsKeepTheirOwnFeedContent() {
        val official = listOf(ChannelSection("高分电影", listOf(item("rated"))), ChannelSection("国产新剧", listOf(item("series"))))
        val unrelated = listOf(ChannelSection("高分电影", listOf(item("wrong-source"))))
        val movie = HomeFeedLayout.sections("movie", emptyList(), official, unrelated)
        assertEquals(listOf("rated"), movie[1].items.map { it.id })
        assertTrue(movie[2].items.isEmpty())
        assertEquals(listOf("series"), HomeFeedLayout.sections("tv", emptyList(), official, unrelated)[1].items.map { it.id })
    }

    @Test fun varietyDoesNotIncludeSupplementaryDoubanFeeds() {
        fun variety(id: String) = item(id).copy(categoryId = "douban:variety")
        val douban = listOf(ChannelSection("最新上映", listOf(variety("new"))), ChannelSection("豆瓣高分", listOf(variety("rated-first"), variety("rated-second"))))
        val result = HomeFeedLayout.sections("variety", emptyList(), emptyList(), douban)
        assertTrue(result.flatMap { it.items }.isEmpty())
    }

    @Test fun animeOmitsTheDoubanRatingSection() {
        val titles = HomeFeedLayout.titles("anime")

        assertFalse("豆瓣高分" in titles)
        assertEquals(listOf("最近热门", "最近更新"), titles.drop(1))
        assertTrue("豆瓣高分" in HomeFeedLayout.titles("documentary"))

        fun anime(id: String) = item(id).copy(categoryId = "douban:anime", area = "中国大陆")
        val douban =
            listOf(
                ChannelSection("最近热门", listOf(anime("hot"))),
                ChannelSection("最近更新", listOf(anime("new"))),
                ChannelSection("豆瓣高分", listOf(anime("rated"))),
            )
        val sections = HomeFeedLayout.sections("anime", emptyList(), emptyList(), douban)
        assertFalse(sections.any { it.name == "豆瓣高分" })
    }
}

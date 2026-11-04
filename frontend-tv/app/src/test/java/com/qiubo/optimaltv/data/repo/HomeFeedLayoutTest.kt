package com.qiubo.optimaltv.data.repo

import com.qiubo.optimaltv.data.model.VodItem
import com.qiubo.optimaltv.data.source.HhkanSource.ChannelSection
import org.junit.Assert.assertEquals
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
}

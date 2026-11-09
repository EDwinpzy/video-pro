package com.qiubo.optimaltv.data.repo

import com.qiubo.optimaltv.data.model.CarouselItem
import com.qiubo.optimaltv.data.model.VodItem
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeHeroRoutingTest {
    @Test
    fun officialCarouselKeepsTheIdentityUsedByDetailAndPlayback() {
        val ids = List(3) { "dongpian:fictional-variant-$it" }
        val home = VodRepository.HomeState(
            carousel = ids.map { CarouselItem(it, "Fictional film", "", emptyList()) },
        )
        assertEquals(ids, heroRefs(null, home).map(HeroRef::id))
    }

    @Test
    fun homeSectionsFallBackToOfficialItemIds() {
        val ids = List(3) { "dongpian:fictional-$it" }
        val home = VodRepository.HomeState(
            sections =
                listOf(
                    com.qiubo.optimaltv.data.model.ChannelSection(
                        "热门",
                        ids.map {
                            VodItem(
                                id = it,
                                sourceId = "dongpian",
                                title = "Fictional film",
                                categoryId = "douban:movie",
                            )
                        },
                    ),
                ),
        )
        assertEquals(ids, heroRefs(null, home).map(HeroRef::id))
    }
}

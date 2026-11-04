package com.qiubo.optimaltv.data.repo

import com.qiubo.optimaltv.data.source.HhkanSource
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeHeroRoutingTest {
    @Test
    fun officialCarouselKeepsTheIdentityUsedByDetailAndPlayback() {
        val ids = List(3) { "dongpian:fictional-variant-$it" }
        val home = VodRepository.HomeState(
            carousel = ids.map { HhkanSource.CarouselItem(it, "Fictional film", "", emptyList()) },
        )
        assertEquals(ids, heroRefs(null, home).map(HeroRef::id))
    }

    @Test
    fun legacyCarouselStillUsesTheLegacyResolver() {
        val ids = List(3) { "fictional-vid-$it" }
        val home = VodRepository.HomeState(
            carousel = ids.map { HhkanSource.CarouselItem(it, "Fictional film", "", emptyList()) },
        )
        assertEquals(ids.map { "hhkan:$it" }, heroRefs(null, home).map(HeroRef::id))
    }
}

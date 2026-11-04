package com.qiubo.optimaltv.data.repo

import com.qiubo.optimaltv.data.model.SourceAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubanVodPolicyTest {
    @Test fun `douban ids are accepted as catalog origin`() {
        assertTrue(VodIdPolicy.isCatalogId("douban:1292052"))
        assertFalse(VodIdPolicy.isCatalogId("mcms:1:20"))
    }

    @Test fun `source state distinguishes matching and unavailable`() {
        assertEquals(SourceAvailability.MATCHING, SourceAvailability.fromWire("matching"))
        assertEquals(SourceAvailability.UNAVAILABLE, SourceAvailability.fromWire("unavailable"))
    }

    @Test fun `failed active line advances to next ranked line`() {
        val next = PlaybackLineSelector.nextAfterFailure(listOf("a", "b", "c"), "a", setOf("a"))
        assertEquals("b", next)
    }
}

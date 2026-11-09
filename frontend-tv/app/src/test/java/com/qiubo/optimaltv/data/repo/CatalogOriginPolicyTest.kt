package com.qiubo.optimaltv.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogOriginPolicyTest {
    @Test
    fun `目录快照只接受懂片帝卡片`() {
        assertTrue(CatalogOriginPolicy.allows("dongpian", "dongpian:1292052"))
        assertFalse(CatalogOriginPolicy.allows("mcms", "mcms:12345"))
        assertFalse(CatalogOriginPolicy.allows("hhkan", "hhkan:12345"))
        assertFalse(CatalogOriginPolicy.allows("", "dongpian:1292052"))
        assertFalse(CatalogOriginPolicy.allows("douban", "douban:1292052"))
    }

    @Test
    fun `持久化快照只接受当前 v4`() {
        assertEquals(setOf(CatalogOriginPolicy.SNAPSHOT_VERSION), CatalogOriginPolicy.PERSISTED_ACCEPTED_VERSIONS)
        assertFalse(3 in CatalogOriginPolicy.PERSISTED_ACCEPTED_VERSIONS)
        assertTrue(4 in CatalogOriginPolicy.PERSISTED_ACCEPTED_VERSIONS)
    }
}

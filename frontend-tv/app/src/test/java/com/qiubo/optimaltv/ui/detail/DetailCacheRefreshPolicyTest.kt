package com.qiubo.optimaltv.ui.detail

import com.qiubo.optimaltv.data.model.VodItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailCacheRefreshPolicyTest {
    @Test
    fun refreshOnlyWhenExistingCacheHasExpired() {
        val freshForMs = 60_000L

        assertFalse(shouldRefreshDetailAfterLoad(null, freshForMs))
        assertFalse(shouldRefreshDetailAfterLoad(59_999L, freshForMs))
        assertTrue(shouldRefreshDetailAfterLoad(60_000L, freshForMs))
        assertTrue(shouldRefreshDetailAfterLoad(Long.MAX_VALUE, freshForMs))
    }

    @Test
    fun resolvedDetailSurvivesBriefCatalogMetadataChanges() {
        val incoming = VodItem(
            id = "dongpian:42",
            sourceId = "dongpian",
            title = "",
            categoryId = "",
            detailRef = "dongpian:42",
        )
        val resolved = incoming.copy(
            title = "示例影片",
            categoryId = "movie",
            detailRef = "resolved-token",
            desc = "完整简介",
            episodes = listOf(
                com.qiubo.optimaltv.data.model.Episode(
                    index = 1,
                    name = "正片",
                    url = "token",
                ),
            ),
        )

        assertTrue(isSameCatalogDetail(resolved, incoming))
        assertFalse(isSameCatalogDetail(resolved, incoming.copy(id = "dongpian:43")))
        assertFalse(isSameCatalogDetail(resolved, incoming.copy(sourceId = "hhkan")))
        assertFalse(isSameCatalogDetail(null, incoming))
    }
}

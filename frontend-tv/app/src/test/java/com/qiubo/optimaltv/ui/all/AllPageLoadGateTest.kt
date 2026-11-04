package com.qiubo.optimaltv.ui.all

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AllPageLoadGateTest {
    @Test
    fun returningSoonKeepsPaginationButAnExpiredPageRefreshesItsCatalog() {
        var now = 0L
        val gate = AllPageLoadGate(nowMillis = { now }, cacheLifetimeMillis = 100L)
        val filters = query()
        gate.markLoaded(begin(gate, filters))
        val previousPagination = requireNotNull(gate.beginRequest(filters))
        now = 99L
        assertFalse(gate.shouldLoad(filters, active = true, embedded = true))
        now = 100L
        assertTrue(gate.shouldLoad(filters, active = true, embedded = true))
        assertFalse(gate.isCurrent(previousPagination))
    }

    @Test
    fun reactivatingLoadedEmbeddedPageKeepsTheExistingListAndPage() {
        val gate = AllPageLoadGate()
        val filters = query()

        gate.markLoaded(begin(gate, filters))

        assertFalse(gate.shouldLoad(filters, active = false, embedded = true))
        assertFalse(gate.shouldLoad(filters, active = true, embedded = true))
    }

    @Test
    fun changedFiltersLoadOnlyWhenTheEmbeddedPageBecomesVisible() {
        val gate = AllPageLoadGate()
        gate.markLoaded(begin(gate, query(type = "movie")))
        val changed = query(type = "tv")

        assertFalse(gate.shouldLoad(changed, active = false, embedded = true))
        assertTrue(gate.shouldLoad(changed, active = true, embedded = true))
    }

    @Test
    fun standalonePageRetainsItsExistingActiveFlagBehavior() {
        val gate = AllPageLoadGate()
        val filters = query()

        assertTrue(gate.shouldLoad(filters, active = false, embedded = false))
        gate.markLoaded(requireNotNull(gate.beginRequest(filters)))
        assertFalse(gate.shouldLoad(filters, active = false, embedded = false))
    }

    @Test
    fun failedChangedFiltersDoNotReuseThePreviousLoadedPage() {
        val gate = AllPageLoadGate()
        val first = query(type = "movie")
        val changed = query(type = "tv")

        gate.markLoaded(begin(gate, first))
        val failed = begin(gate, changed)
        gate.invalidate(failed)

        assertTrue(gate.shouldLoad(first, active = true, embedded = true))
    }

    @Test
    fun canceledChangedFiltersCannotMarkTheirOldRequestLoaded() {
        val gate = AllPageLoadGate()
        val first = query(type = "movie")
        val canceled = begin(gate, query(type = "tv"))

        assertTrue(gate.shouldLoad(first, active = true, embedded = true))
        gate.markLoaded(canceled)

        assertTrue(gate.shouldLoad(first, active = true, embedded = true))
    }

    @Test
    fun staleLoadMoreResponseCannotCrossAFilterGeneration() {
        val gate = AllPageLoadGate()
        val first = query(type = "movie")
        val changed = query(type = "tv")

        gate.markLoaded(begin(gate, first))
        val oldLoadMore = requireNotNull(gate.beginRequest(first))
        gate.markLoaded(begin(gate, changed))

        assertFalse(gate.isCurrent(oldLoadMore))
        gate.markLoaded(oldLoadMore)
        assertTrue(gate.shouldLoad(first, active = true, embedded = true))
    }

    private fun query(type: String = "") = AllPageQuery(
        category = "1",
        type = type,
        area = "",
        year = "",
        rating = "",
        sort = "hot",
    )

    private fun begin(gate: AllPageLoadGate, filters: AllPageQuery): AllPageRequest {
        assertTrue(gate.shouldLoad(filters, active = true, embedded = true))
        return requireNotNull(gate.beginRequest(filters))
    }
}

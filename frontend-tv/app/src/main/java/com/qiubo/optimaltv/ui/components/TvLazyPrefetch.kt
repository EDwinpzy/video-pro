@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.qiubo.optimaltv.ui.components

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.runtime.Composable
import com.qiubo.optimaltv.TvPerformanceProfile

/**
 * Prefetching the next off-screen row is a good default on phones and fast TV
 * boxes, but on the S621 it makes a tab switch compose and decode an entire
 * second poster row before the first one can present. The visible row already
 * has its artwork warmed by the startup gate, so constrained devices can
 * compose the next row only when the D-pad actually reaches it.
 */
internal object TvNoLazyPrefetch : LazyListPrefetchStrategy {
    override fun LazyListPrefetchScope.onScroll(
        delta: Float,
        layoutInfo: LazyListLayoutInfo,
    ) = Unit

    override fun LazyListPrefetchScope.onVisibleItemsUpdated(
        layoutInfo: LazyListLayoutInfo,
    ) = Unit

    override fun NestedPrefetchScope.onNestedPrefetch(
        index: Int,
    ) = Unit
}

internal fun createTvLazyListState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
): LazyListState =
    if (TvPerformanceProfile.isConstrained) {
        LazyListState(
            firstVisibleItemIndex = initialFirstVisibleItemIndex,
            firstVisibleItemScrollOffset = initialFirstVisibleItemScrollOffset,
            prefetchStrategy = TvNoLazyPrefetch,
        )
    } else {
        LazyListState(
            firstVisibleItemIndex = initialFirstVisibleItemIndex,
            firstVisibleItemScrollOffset = initialFirstVisibleItemScrollOffset,
        )
    }

@Composable
internal fun rememberTvLazyListState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
): LazyListState =
    if (TvPerformanceProfile.isConstrained) {
        rememberLazyListState(
            initialFirstVisibleItemIndex = initialFirstVisibleItemIndex,
            initialFirstVisibleItemScrollOffset = initialFirstVisibleItemScrollOffset,
            prefetchStrategy = TvNoLazyPrefetch,
        )
    } else {
        rememberLazyListState(
            initialFirstVisibleItemIndex = initialFirstVisibleItemIndex,
            initialFirstVisibleItemScrollOffset = initialFirstVisibleItemScrollOffset,
        )
    }

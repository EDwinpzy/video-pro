package com.qiubo.optimaltv.ui.home

import com.qiubo.optimaltv.data.db.HistoryEntity
import com.qiubo.optimaltv.data.model.VodItem
import com.qiubo.optimaltv.data.model.isUsablePosterUrl
import com.qiubo.optimaltv.ui.components.formatWatchHistoryLabel

internal data class RecentWatchEntry(
    val history: HistoryEntity,
    val title: String,
    val posterUrl: String,
    val progress: Float,
    val progressLabel: String,
)

internal object RecentWatchPolicy {
    const val MAX_ITEMS = 12

    private val episodicCategories = setOf("series", "anime", "short", "variety")

    fun build(
        history: List<HistoryEntity>,
        catalogById: Map<String, VodItem>,
        resolvedItems: Map<String, VodItem>,
        limit: Int = MAX_ITEMS,
    ): List<RecentWatchEntry> =
        history.asSequence()
            .sortedByDescending(HistoryEntity::updatedAt)
            .distinctBy(HistoryEntity::vodId)
            .take(limit.coerceAtLeast(0))
            .map { saved ->
                val item = resolvedItems[saved.vodId] ?: catalogById[saved.vodId]
                val episodeNumber =
                    (saved.epIndex + 1).takeIf {
                        recentHistoryCategory(item) in episodicCategories
                    }
                RecentWatchEntry(
                    history = saved,
                    title = item?.title?.takeIf(String::isNotBlank)
                        ?: saved.title.ifBlank { "未命名影片" },
                    posterUrl =
                        item?.posterUrl?.takeIf(::isUsablePosterUrl)
                            ?: saved.posterUrl,
                    progress =
                        if (saved.durationMs > 0L) {
                            (saved.positionMs.toFloat() / saved.durationMs)
                                .coerceIn(0f, 1f)
                        } else {
                            0f
                        },
                    progressLabel =
                        formatWatchHistoryLabel(
                            episodeNumber = episodeNumber,
                            positionMs = saved.positionMs,
                        ),
                )
            }
            .toList()
}

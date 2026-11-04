package com.qiubo.optimaltv.ui.detail

import com.qiubo.optimaltv.data.db.ProgressEntity

internal fun detailResumeCandidates(state: DetailUiState): Map<String, Int> =
    (listOfNotNull(state.item) + state.lines)
        .distinctBy { it.id }
        .filter { it.episodes.isNotEmpty() }
        .associate { it.id to it.episodes.size }

/** Resume the most recently visited valid entry, including a user-paused start position. */
internal fun latestDetailProgress(
    candidates: Map<String, Int>,
    progress: List<ProgressEntity>,
): ProgressEntity? =
    progress.filter {
        it.epIndex in 0 until (candidates[it.vodId] ?: 0) &&
            it.positionMs >= 0L && it.durationMs > 0L
    }.maxByOrNull { it.updatedAt }

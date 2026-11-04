package com.qiubo.optimaltv.ui.components

import com.qiubo.optimaltv.data.model.formatTime

internal fun formatWatchHistoryLabel(
    episodeNumber: Int?,
    positionMs: Long,
): String =
    buildList {
        add("上次看到")
        episodeNumber?.let { add("第${it}集") }
        add(formatTime(positionMs))
    }.joinToString(" · ")

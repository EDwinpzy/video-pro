package com.qiubo.optimaltv.data.repo

import com.qiubo.optimaltv.data.model.LineInfo

/**
 * Resolved official HLS links have a direct URL and no playRef. They still benefit
 * from the local relay's manifest rewrite and segment prefetch, just like lazy lines.
 */
internal object PlaybackRelayPolicy {
    fun shouldRelayDirectLine(
        line: LineInfo,
        preferRelay: Boolean,
    ): Boolean =
        preferRelay &&
            line.official == null &&
            line.playRef.isBlank() &&
            line.url.startsWith("https://") &&
            line.vodId.substringAfterLast('|').startsWith("official:")
}

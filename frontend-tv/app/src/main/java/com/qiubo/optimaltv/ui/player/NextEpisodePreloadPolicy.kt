package com.qiubo.optimaltv.ui.player

internal object NextEpisodePreloadPolicy {
    const val MIN_EPISODE_MS = 45_000L
    /** Never start a second download while the current item is still shallow-buffered. */
    const val MIN_CURRENT_BUFFER_MS = 12_000L
    private const val SHORT_EPISODE_MS = 5 * 60_000L
    private const val LONG_CONTENT_LEAD_MS = 15 * 60_000L

    fun preloadStartMs(
        durationMs: Long,
        introSkipSeconds: Int = 0,
        outroSkipSeconds: Int = 0,
    ): Long {
        if (durationMs < MIN_EPISODE_MS) return Long.MAX_VALUE
        val contentStartMs = SkipSegmentPolicy.introSkipMs(introSkipSeconds, durationMs)
        val contentEndMs =
            (durationMs - SkipSegmentPolicy.outroSkipMs(outroSkipSeconds, durationMs))
                .coerceAtLeast(contentStartMs)
        val playableMs = contentEndMs - contentStartMs
        if (playableMs <= 0L) return Long.MAX_VALUE
        return if (playableMs <= SHORT_EPISODE_MS) {
            contentStartMs + playableMs * 60L / 100L
        } else {
            val leadMs = LONG_CONTENT_LEAD_MS.coerceAtMost(playableMs / 2)
            (contentEndMs - leadMs).coerceAtLeast(contentStartMs)
        }
    }

    fun shouldStart(
        positionMs: Long,
        durationMs: Long,
        introSkipSeconds: Int = 0,
        outroSkipSeconds: Int = 0,
        hasNextEpisode: Boolean,
    ): Boolean =
        hasNextEpisode &&
            durationMs >= MIN_EPISODE_MS &&
            positionMs >= preloadStartMs(durationMs, introSkipSeconds, outroSkipSeconds)

    fun currentItemCanSpareBandwidth(bufferedAheadMs: Long): Boolean =
        bufferedAheadMs >= MIN_CURRENT_BUFFER_MS
}

package com.qiubo.optimaltv.ui.player

internal object NextEpisodePreloadPolicy {
    const val MIN_EPISODE_MS = 45_000L
    private const val SHORT_EPISODE_MS = 5 * 60_000L
    private const val LONG_EPISODE_LEAD_MS = 120_000L

    fun preloadStartMs(durationMs: Long): Long =
        when {
            durationMs < MIN_EPISODE_MS -> Long.MAX_VALUE
            durationMs <= SHORT_EPISODE_MS -> durationMs * 60L / 100L
            else -> (durationMs - LONG_EPISODE_LEAD_MS).coerceAtLeast(0L)
        }

    fun shouldStart(
        positionMs: Long,
        durationMs: Long,
        hasNextEpisode: Boolean,
    ): Boolean =
        hasNextEpisode &&
            durationMs >= MIN_EPISODE_MS &&
            positionMs >= preloadStartMs(durationMs)
}

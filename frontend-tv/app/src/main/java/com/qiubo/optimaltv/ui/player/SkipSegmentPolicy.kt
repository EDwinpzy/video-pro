package com.qiubo.optimaltv.ui.player

import com.qiubo.optimaltv.data.prefs.SkipSegmentsMode

internal object SkipSegmentPolicy {
    const val MIN_EPISODE_MS = 45_000L
    private const val MAX_INTRO_MS = 90_000L
    private const val MAX_OUTRO_MS = 60_000L
    private const val MIN_INTRO_MS = 4_000L
    private const val MIN_OUTRO_MS = 3_000L

    fun introSkipMs(durationMs: Long): Long =
        if (durationMs < MIN_EPISODE_MS) {
            0L
        } else {
            (durationMs * 8L / 100L).coerceIn(MIN_INTRO_MS, MAX_INTRO_MS)
        }

    fun outroSkipMs(durationMs: Long): Long =
        if (durationMs < MIN_EPISODE_MS) {
            0L
        } else {
            (durationMs * 6L / 100L).coerceIn(MIN_OUTRO_MS, MAX_OUTRO_MS)
        }

    fun shouldApplyIntro(
        mode: SkipSegmentsMode,
        startPositionMs: Long,
        durationMs: Long,
        alreadyApplied: Boolean,
    ): Boolean =
        mode.skipIntro &&
            !alreadyApplied &&
            startPositionMs < 1_000L &&
            durationMs >= MIN_EPISODE_MS &&
            introSkipMs(durationMs) > 0L

    fun shouldSkipOutro(
        mode: SkipSegmentsMode,
        positionMs: Long,
        durationMs: Long,
        hasNextEpisode: Boolean,
    ): Boolean {
        if (!mode.skipOutro || !hasNextEpisode || durationMs < MIN_EPISODE_MS) return false
        val skipMs = outroSkipMs(durationMs)
        return skipMs > 0L && positionMs >= durationMs - skipMs
    }
}

package com.qiubo.optimaltv.ui.player

internal object SkipSegmentPolicy {
    /** Leave this much content so an over-long custom value cannot skip a whole item. */
    const val MIN_REMAINING_MS = 10_000L

    fun introSkipMs(seconds: Int, durationMs: Long): Long = effectiveSkipMs(seconds, durationMs)

    fun outroSkipMs(seconds: Int, durationMs: Long): Long = effectiveSkipMs(seconds, durationMs)

    fun shouldApplyIntro(
        skipIntroSeconds: Int,
        startPositionMs: Long,
        durationMs: Long,
        alreadyApplied: Boolean,
    ): Boolean =
        !alreadyApplied &&
            startPositionMs < 1_000L &&
            introSkipMs(skipIntroSeconds, durationMs) > 0L

    fun shouldSkipOutro(
        skipOutroSeconds: Int,
        positionMs: Long,
        durationMs: Long,
        hasNextEpisode: Boolean,
    ): Boolean {
        if (!hasNextEpisode) return false
        val skipMs = outroSkipMs(skipOutroSeconds, durationMs)
        return skipMs > 0L && positionMs >= durationMs - skipMs
    }

    private fun effectiveSkipMs(seconds: Int, durationMs: Long): Long {
        if (seconds <= 0 || durationMs <= MIN_REMAINING_MS) return 0L
        return (seconds * 1_000L).coerceAtMost(durationMs - MIN_REMAINING_MS)
    }
}

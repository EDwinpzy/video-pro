package com.qiubo.optimaltv.ui.player

internal object PlaybackResumePolicy {
    private const val MIN_VALID_POSITION_MS = 1_000L

    fun startPositionMs(
        routePositionMs: Long,
        savedPositionMs: Long?,
        savedDurationMs: Long?,
    ): Long {
        if (isValid(routePositionMs)) return routePositionMs

        val saved = savedPositionMs?.takeIf(::isValid) ?: return 0L
        if (savedDurationMs != null && savedDurationMs > 0L && saved >= savedDurationMs) return 0L
        return saved
    }

    private fun isValid(positionMs: Long): Boolean = positionMs > MIN_VALID_POSITION_MS
}

package com.qiubo.optimaltv.ui.player

/** Rules for the ordinary movie/series retry loop; live and football replays keep their own flows. */
internal object VodRetryPolicy {
    const val RETRY_DELAY_MS = 1_000L
    const val SEEK_POSITION_TOLERANCE_MS = 3_000L
    const val HARD_LINE_FAILURE_ATTEMPTS = 2

    fun shouldRetry(isLive: Boolean): Boolean = !isLive

    fun shouldRetryBootAfterAttempt(
        isLive: Boolean,
        hasFatalMessage: Boolean,
        attemptFailed: Boolean,
        fatalReason: String? = null,
    ): Boolean = shouldRetry(isLive) &&
        !isPermanentVodFailure(fatalReason) &&
        (hasFatalMessage || attemptFailed)

    private fun isPermanentVodFailure(reason: String?): Boolean =
        reason != null && PERMANENT_VOD_FAILURES.any(reason::contains)

    fun shouldUseStartupWatchdog(isLive: Boolean): Boolean = isLive

    /**
     * A provider that explicitly asks the user to switch lines cannot recover by
     * refreshing the same ticket forever. Stalls and slow starts stay on the
     * selected line; only unavailable-line responses advance to another source.
     */
    fun isUnavailableLineFailure(reason: String): Boolean =
        reason.contains("切换线路") || reason.contains("线路解析失败")

    fun shouldSwitchLineAfterFailure(reason: String, attemptsOnCurrentLine: Int): Boolean =
        attemptsOnCurrentLine >= HARD_LINE_FAILURE_ATTEMPTS &&
            isUnavailableLineFailure(reason)

    /** A not-yet-observed seek target wins over a stale engine position. */
    fun resumePositionMs(pendingSeekMs: Long?, currentPositionMs: Long): Long =
        (pendingSeekMs ?: currentPositionMs).coerceAtLeast(0L)

    fun reachedSeekTarget(actualPositionMs: Long, targetPositionMs: Long): Boolean =
        kotlin.math.abs(actualPositionMs - targetPositionMs) <= SEEK_POSITION_TOLERANCE_MS

    /** Locate the selected source in a refreshed episode list; never substitute another source. */
    fun matchingSourceIndex(sourceIdentity: String?, candidates: List<String?>): Int? =
        sourceIdentity?.takeIf(String::isNotBlank)
            ?.let { identity -> candidates.indexOf(identity).takeIf { it >= 0 } }

    private val PERMANENT_VOD_FAILURES = listOf("没有可用线路", "换集失败")
}

/** A cancelled selection invalidates every delayed retry from the previous selection. */
internal class VodRetryGeneration {
    private var current = 0L

    fun begin(): Long = ++current

    fun invalidate(): Long = ++current

    fun isCurrent(token: Long): Boolean = token == current
}

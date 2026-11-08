package com.qiubo.optimaltv.ui.player

import com.qiubo.optimaltv.data.model.LineInfo
import kotlinx.coroutines.delay

/** One refresh for denial and two for transient HTTP failures, always on the selected source. */
internal class VodHttpRecovery {
    sealed interface Action {
        data object Refresh : Action
        data object Stop : Action
    }

    private val refreshed = mutableMapOf<String, Int>()

    fun reset() {
        refreshed.clear()
    }

    fun recover(status: Int, line: LineInfo?): Action? {
        if (status !in 400..599) return null
        line ?: return Action.Stop
        val key = identity(line)
        val attempts = refreshed[key] ?: 0
        val refreshLimit = if (status == 408 || status == 429 || status >= 500) 2 else 1
        if (attempts < refreshLimit) {
            refreshed[key] = attempts + 1
            return Action.Refresh
        }
        return Action.Stop
    }

    private fun identity(line: LineInfo): String =
        VodSourceSelectionPolicy.identity(line) ?: line.playRef.ifBlank { line.url }
}

/** Covers the whole resolve/prepare/retry chain, including a resolver that never returns. */
internal suspend fun awaitVodHttpRecoveryDeadline(
    nowMs: () -> Long,
    isCurrent: () -> Boolean,
    waiting: () -> Boolean,
    rendered: () -> Boolean,
    onExpired: () -> Unit,
) {
    val budget = FirstFrameWaitWindow()
    while (isCurrent() && !rendered()) {
        val now = nowMs()
        if (waiting()) {
            if ((budget.elapsedMs(now, waiting = true) ?: 0L) >= 30_000L) {
                onExpired()
                return
            }
        } else budget.pause(now)
        delay(250)
    }
}

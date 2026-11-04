package com.qiubo.optimaltv.ui.player

import com.qiubo.optimaltv.data.model.LineInfo
import kotlinx.coroutines.delay

/** One refresh for denial, two for transient HTTP failures, and at most two source changes. */
internal class VodHttpRecovery {
    sealed interface Action {
        data object Refresh : Action
        data class Fallback(val index: Int) : Action
        data object Stop : Action
    }

    private val refreshed = mutableMapOf<String, Int>()
    private val denied = mutableSetOf<String>()
    private var switches = 0

    fun reset() {
        refreshed.clear()
        denied.clear()
        switches = 0
    }

    fun recover(status: Int, lines: List<LineInfo>, active: Int, triedIndices: Set<Int> = emptySet()): Action? {
        if (status !in 400..599) return null
        val current = lines.getOrNull(active) ?: return Action.Stop
        val key = identity(current)
        val attempts = refreshed[key] ?: 0
        val refreshLimit = if (status == 408 || status == 429 || status >= 500) 2 else 1
        if (attempts < refreshLimit) {
            refreshed[key] = attempts + 1
            return Action.Refresh
        }
        denied += key
        if (switches >= 2 || current.official == null) return Action.Stop
        val candidates = lines.indices.filter {
            it != active && it !in triedIndices && lines[it].official != null && identity(lines[it]) !in denied &&
                VodSourceSelectionPolicy.isAvailable(lines[it])
        }
        // A denied provider often lists multiple qualities of the same unavailable stream.
        val provider = VodSourceSelectionPolicy.providerGroupKey(current)
        val next = candidates.firstOrNull { VodSourceSelectionPolicy.providerGroupKey(lines[it]) != provider }
            ?: candidates.firstOrNull()
            ?: return Action.Stop
        switches++
        return Action.Fallback(next)
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

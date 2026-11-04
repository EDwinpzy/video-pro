package com.qiubo.optimaltv.ui.player

internal data class EpisodeFocusRestoreRequest(val generation: Long, val index: Int)

/** Only a grid that still owns the user's focus may restore after its data changes. */
internal class EpisodeFocusAnchor {
    private var key: String? = null
    private var generation = 0L

    fun focused(identity: String) {
        key = identity
    }

    fun clear() {
        key = null
        generation++
    }

    fun movedByUser() {
        generation++
    }

    fun captureRefresh(keys: List<String>, fallbackIndex: Int): EpisodeFocusRestoreRequest? =
        indexAfterRefresh(keys, fallbackIndex)?.let { EpisodeFocusRestoreRequest(generation, it) }

    fun isCurrent(request: EpisodeFocusRestoreRequest): Boolean =
        key != null && request.generation == generation

    fun indexAfterRefresh(keys: List<String>, fallbackIndex: Int): Int? {
        val remembered = key ?: return null
        if (keys.isEmpty()) return null
        return keys.indexOf(remembered).takeIf { it >= 0 }
            ?: fallbackIndex.coerceIn(keys.indices)
    }
}

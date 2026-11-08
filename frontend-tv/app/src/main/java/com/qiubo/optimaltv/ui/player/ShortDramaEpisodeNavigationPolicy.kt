package com.qiubo.optimaltv.ui.player

import android.view.KeyEvent

/** Short drama episode shortcuts own UP/DOWN during ordinary playback, including while the HUD is visible. */
internal object ShortDramaEpisodeNavigationPolicy {
    fun isShortDrama(categoryId: String): Boolean =
        isShortDrama(
            categoryIds = listOf(categoryId),
            metadata = "",
            episodeCount = 0,
            durationMs = 0L,
        )

    fun isShortDrama(
        categoryIds: List<String>,
        metadata: String,
        episodeCount: Int,
        durationMs: Long,
    ): Boolean {
        if (categoryIds.any(::isShortDramaCategory)) return true
        if (episodeCount <= 1) return false
        val text = metadata.lowercase()
        if (SHORT_FORM_MARKERS.none(text::contains)) return false
        return durationMs <= 0L || durationMs <= MAX_SHORT_EPISODE_MS
    }

    fun canIntercept(
        shortDrama: Boolean,
        panelOpen: Boolean,
        dialogOpen: Boolean,
        isLive: Boolean,
        hasFatalError: Boolean,
    ): Boolean = shortDrama &&
        !panelOpen &&
        !dialogOpen &&
        !isLive &&
        !hasFatalError

    fun targetEpisode(
        keyCode: Int,
        currentEpisode: Int,
        episodeCount: Int,
    ): Int? {
        if (currentEpisode !in 0 until episodeCount) return null
        val target = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> currentEpisode - 1
            KeyEvent.KEYCODE_DPAD_DOWN -> currentEpisode + 1
            else -> return null
        }
        return target.takeIf { it in 0 until episodeCount }
    }

    private fun isShortDramaCategory(categoryId: String): Boolean {
        val category = categoryId.trim().substringAfterLast(':').lowercase()
        return category == "short" ||
            category == "short_drama" ||
            category == "短剧" ||
            category.toIntOrNull() == SHORT_DRAMA_CATEGORY
    }

    private val SHORT_FORM_MARKERS = listOf("短剧", "竖屏剧", "迷你剧", "漫剧")
    private const val MAX_SHORT_EPISODE_MS = 10 * 60 * 1000L
    private const val SHORT_DRAMA_CATEGORY = 6
}

internal data class ShortDramaEpisodeKeyResult(
    val consumed: Boolean,
    val targetEpisode: Int? = null,
) {
    companion object {
        val Ignored = ShortDramaEpisodeKeyResult(consumed = false)
    }
}

/**
 * One physical UP/DOWN press selects at most one adjacent episode. Repeats and the
 * matching release are consumed so the player HUD cannot also handle the same press.
 */
internal class ShortDramaEpisodeKeyHandler {
    private val activePresses = mutableMapOf<Int, Long>()

    fun handle(
        shortDrama: Boolean,
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        downTimeMs: Long,
        currentEpisode: Int,
        episodeCount: Int,
        panelOpen: Boolean,
        dialogOpen: Boolean,
        isLive: Boolean,
        hasFatalError: Boolean,
        switchingEpisode: Boolean,
    ): ShortDramaEpisodeKeyResult {
        if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) {
            return ShortDramaEpisodeKeyResult.Ignored
        }

        val canIntercept = ShortDramaEpisodeNavigationPolicy.canIntercept(
            shortDrama = shortDrama,
            panelOpen = panelOpen,
            dialogOpen = dialogOpen,
            isLive = isLive,
            hasFatalError = hasFatalError,
        )
        if (!canIntercept) {
            activePresses.clear()
            return ShortDramaEpisodeKeyResult.Ignored
        }

        when (action) {
            KeyEvent.ACTION_UP -> {
                if (activePresses[keyCode] != downTimeMs) {
                    return ShortDramaEpisodeKeyResult.Ignored
                }
                activePresses.remove(keyCode)
                return ShortDramaEpisodeKeyResult(consumed = true)
            }
            KeyEvent.ACTION_DOWN -> Unit
            else -> return ShortDramaEpisodeKeyResult.Ignored
        }

        val previousDownTime = activePresses[keyCode]
        if (previousDownTime == downTimeMs) {
            return ShortDramaEpisodeKeyResult(consumed = true)
        }
        val anotherPressIsActive = activePresses.any { (activeKey, _) -> activeKey != keyCode }
        activePresses[keyCode] = downTimeMs
        if (repeatCount > 0 || anotherPressIsActive || switchingEpisode) {
            return ShortDramaEpisodeKeyResult(consumed = true)
        }

        return ShortDramaEpisodeKeyResult(
            consumed = true,
            targetEpisode = ShortDramaEpisodeNavigationPolicy.targetEpisode(
                keyCode = keyCode,
                currentEpisode = currentEpisode,
                episodeCount = episodeCount,
            ),
        )
    }
}

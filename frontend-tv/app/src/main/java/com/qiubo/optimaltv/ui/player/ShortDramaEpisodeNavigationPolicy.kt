package com.qiubo.optimaltv.ui.player

import android.view.KeyEvent

/** Short drama episode shortcuts are active only while the player HUD is hidden. */
internal object ShortDramaEpisodeNavigationPolicy {
    fun isShortDrama(categoryId: String): Boolean {
        val category = categoryId.trim().substringAfterLast(':').lowercase()
        return category == "short" || category.toIntOrNull() == SHORT_DRAMA_CATEGORY
    }

    fun canIntercept(
        categoryId: String,
        controlsVisible: Boolean,
        panelOpen: Boolean,
        dialogOpen: Boolean,
        isLive: Boolean,
        hasFatalError: Boolean,
    ): Boolean = isShortDrama(categoryId) &&
        !controlsVisible &&
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
 * matching release are consumed so the hidden-control sink cannot act a second time.
 */
internal class ShortDramaEpisodeKeyHandler {
    private val activePresses = mutableMapOf<Int, Long>()

    fun handle(
        categoryId: String,
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        downTimeMs: Long,
        currentEpisode: Int,
        episodeCount: Int,
        controlsVisible: Boolean,
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
            categoryId = categoryId,
            controlsVisible = controlsVisible,
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

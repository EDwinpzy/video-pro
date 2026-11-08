package com.qiubo.optimaltv.ui.player

import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.qiubo.optimaltv.ui.components.NAV_DOWN
import com.qiubo.optimaltv.ui.components.NAV_LEFT
import com.qiubo.optimaltv.ui.components.NAV_RIGHT
import com.qiubo.optimaltv.ui.components.NAV_UP
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class PlayerPanelFocusPosition(val row: Int, val column: Int)

internal object PlayerPanelFocusPolicy {
    fun normalized(rows: List<Int>, position: PlayerPanelFocusPosition): PlayerPanelFocusPosition {
        if (rows.isEmpty()) return PlayerPanelFocusPosition(0, 0)
        val row = position.row.coerceIn(rows.indices)
        val width = rows[row]
        return PlayerPanelFocusPosition(
            row = row,
            column = if (width <= 0) 0 else position.column.coerceIn(0, width - 1),
        )
    }

    fun next(rows: List<Int>, current: PlayerPanelFocusPosition, direction: Int): PlayerPanelFocusPosition? {
        if (rows.isEmpty()) return null
        val normalizedCurrent = normalized(rows, current)
        val row = when (direction) {
            NAV_UP -> normalizedCurrent.row - 1
            NAV_DOWN -> normalizedCurrent.row + 1
            else -> normalizedCurrent.row
        }
        if (row !in rows.indices) return null
        val targetWidth = rows[row]
        if (targetWidth <= 0) return null
        val targetColumn = when (direction) {
            NAV_LEFT -> normalizedCurrent.column - 1
            NAV_RIGHT -> normalizedCurrent.column + 1
            else -> {
                val sourceWidth = rows[normalizedCurrent.row].coerceAtLeast(1)
                if (targetWidth == sourceWidth) {
                    normalizedCurrent.column
                } else {
                    ((normalizedCurrent.column * targetWidth + sourceWidth / 2) / sourceWidth)
                        .coerceIn(0, targetWidth - 1)
                }
            }
        }
        return targetColumn.takeIf { it in 0 until rows[row] }?.let { PlayerPanelFocusPosition(row, it) }
    }
}

internal fun Modifier.consumePlayerPanelKeysWhileMoving(focus: PlayerPanelFocus): Modifier =
    onPreviewKeyEvent { event ->
        if (!focus.isMoving()) return@onPreviewKeyEvent false
        when (event.type) {
            KeyEventType.KeyDown, KeyEventType.KeyUp -> when (event.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                -> true
                else -> false
            }
            else -> false
        }
    }

@Composable
internal fun rememberPlayerPanelFocus(rows: List<Int>): PlayerPanelFocus {
    val scope = rememberCoroutineScope()
    val focus = remember(rows) { PlayerPanelFocus(rows, scope) }
    DisposableEffect(focus) { onDispose { focus.cancelMoves() } }
    return focus
}

/** Logical rows stay adjacent even while the sidebar scroll clips their bounds. */
@OptIn(ExperimentalFoundationApi::class)
internal class PlayerPanelFocus(private val rows: List<Int>, private val scope: CoroutineScope) {
    private val requesters = rows.map { count -> List(count) { FocusRequester() } }
    private val revealers = rows.map { count -> List(count) { BringIntoViewRequester() } }
    private val detachedRequester = FocusRequester()
    private var requestedPosition: PlayerPanelFocusPosition? = null
    private var confirmedPosition: PlayerPanelFocusPosition? = null
    private var moveJob: Job? = null
    private var moving = false
    private var moveGeneration = 0L

    suspend fun focusInitial(row: Int, column: Int) {
        if (rows.isEmpty()) return
        val target =
            PlayerPanelFocusPolicy.normalized(
                rows,
                PlayerPanelFocusPosition(row, column),
            )
        if (!isValid(target)) return
        requestedPosition = target
        moveJob?.cancel()
        val generation = ++moveGeneration
        moving = true
        try {
            withFrameNanos { }
            revealAndFocus(target, generation)
        } finally {
            if (generation == moveGeneration) moving = false
        }
    }

    fun requester(row: Int, column: Int): FocusRequester =
        requesters.getOrNull(row)?.getOrNull(column) ?: detachedRequester

    fun cancelMoves() {
        moveGeneration++
        moveJob?.cancel()
        moveJob = null
        moving = false
        requestedPosition = null
        confirmedPosition = null
    }

    fun modifier(row: Int, column: Int): Modifier =
        if (!isValid(PlayerPanelFocusPosition(row, column))) {
            Modifier
        } else {
            Modifier.bringIntoViewRequester(revealers[row][column]).onFocusChanged {
                val position = PlayerPanelFocusPosition(row, column)
                if (it.isFocused) confirmedPosition = position
                else if (confirmedPosition == position) confirmedPosition = null
            }
        }

    /**
     * Keeps focus on a chip when selecting it causes its active state to recompose.
     * Compose can briefly drop focus while the chip visuals change; without this
     * restore, OtvNav self-heals to an unrelated row before the next D-pad press.
     */
    fun activate(row: Int, column: Int, onClick: () -> Unit) {
        val target = PlayerPanelFocusPosition(row, column)
        if (!isValid(target)) return
        requestedPosition = target
        moving = true
        val generation = ++moveGeneration
        moveJob?.cancel()
        onClick()
        moveJob =
            scope.launch {
                try {
                    revealAndFocus(target, generation)
                } finally {
                    if (generation == moveGeneration) moving = false
                }
            }
    }

    fun navigate(row: Int, column: Int, direction: Int): Boolean {
        if (rows.isEmpty()) return false
        val current =
            if (moving) {
                requestedPosition
                    ?.takeIf(::isValid)
                    ?: PlayerPanelFocusPosition(row, column).takeIf(::isValid)
                    ?: return false
            } else {
                PlayerPanelFocusPosition(row, column).takeIf(::isValid) ?: return false
            }
        val target = PlayerPanelFocusPolicy.next(rows, current, direction)
            ?: return direction == NAV_LEFT || direction == NAV_RIGHT
        requestedPosition = target
        moving = true
        val generation = ++moveGeneration
        moveJob?.cancel()
        moveJob =
            scope.launch {
                try {
                    revealAndFocus(target, generation)
                } finally {
                    if (generation == moveGeneration) moving = false
                }
            }
        return true
    }

    private suspend fun revealAndFocus(target: PlayerPanelFocusPosition, generation: Long) {
        if (generation != moveGeneration || !isValid(target)) return
        // Claim the native focus owner before a reveal suspends. The move stays
        // pending until reveal and confirmed focus finish, so OK cannot activate
        // an item before its scroll has completed.
        try {
            requester(target.row, target.column).requestFocus()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalStateException) {
            // A newly mounted item is retried after its first layout below.
        } catch (_: IllegalArgumentException) {
            // The row model may have changed while an older layout callback was queued.
        }
        try {
            revealers[target.row][target.column].bringIntoView()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Reveal is best-effort; focus retry below still establishes the target.
        }
        // bringIntoView can return before the scroll container has placed the target.
        // requestFocus then returns false and moving would end while the old chip still
        // owns focus; OK would activate that chip (for example episode 6 instead of 1).
        // Keep the move pending until the target is actually focusable, while allowing
        // newer direction presses to cancel this generation.
        repeat(FOCUS_RETRY_FRAMES) {
            // Publish the revealed layout before OtvNav validates the new focus.
            withFrameNanos { }
            if (generation != moveGeneration) return
            try {
                requester(target.row, target.column).requestFocus()
                withFrameNanos { }
                if (confirmedPosition == target) return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalStateException) {
                // Target is still detached; retry after the next layout frame.
            } catch (_: IllegalArgumentException) {
                // A stale row callback must not be able to terminate the player.
            }
        }
    }

    /** True while a reveal is pending; callers use it to consume OK as well as D-pad keys. */
    fun isMoving(): Boolean = moving

    private fun isValid(position: PlayerPanelFocusPosition): Boolean =
        position.row in rows.indices &&
            position.column >= 0 &&
            position.column < rows[position.row]

    private companion object {
        const val FOCUS_RETRY_FRAMES = 60
    }
}

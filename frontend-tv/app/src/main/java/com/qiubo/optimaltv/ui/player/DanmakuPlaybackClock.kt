package com.qiubo.optimaltv.ui.player

import kotlin.math.abs

/**
 * Keeps the danmaku clock tied to the player clock while still allowing smooth frame-by-frame
 * motion between the player's relatively coarse position updates.
 */
internal class DanmakuPlaybackClock(
    private val correctionThresholdMs: Long = 1_500L,
    private val maxExtrapolationMs: Long = 4_000L,
) {
    private var anchorPositionMs = 0L
    private var anchorRealtimeMs = 0L
    private var playing = false
    private var initialized = false

    /**
     * Feeds an authoritative player position. Returns true when the clock had to re-anchor far
     * enough that the active danmaku window must be rebuilt.
     */
    @Synchronized
    fun update(
        positionMs: Long,
        realtimeMs: Long,
        isPlaying: Boolean,
        playbackRate: Float,
    ): Boolean {
        val safePositionMs = positionMs.coerceAtLeast(0L)
        val jumped =
            initialized &&
                abs(safePositionMs - positionAt(realtimeMs, playbackRate)) >
                correctionThresholdMs
        if (!initialized || jumped || !isPlaying) {
            anchorPositionMs = safePositionMs
            anchorRealtimeMs = realtimeMs
            initialized = true
        }
        playing = isPlaying
        return jumped
    }

    @Synchronized
    fun positionAt(
        realtimeMs: Long,
        playbackRate: Float,
    ): Long {
        if (!initialized) return 0L
        if (!playing) return anchorPositionMs
        val elapsedMs =
            (realtimeMs - anchorRealtimeMs)
                .coerceIn(0L, maxExtrapolationMs)
        return anchorPositionMs + (elapsedMs * playbackRate.coerceAtLeast(0.1f)).toLong()
    }
}
